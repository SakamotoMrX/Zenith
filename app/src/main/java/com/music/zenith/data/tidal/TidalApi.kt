package com.music.zenith.data.tidal

import com.music.zenith.data.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request

data class TidalTrack(
    val id: Long,
    val title: String,
    val artist: String,
    val album: String?,
    val coverId: String?,
    val durationSec: Int,
    val explicit: Boolean,
    val audioQuality: String,
    val isrc: String?,
) {
    val coverUrl: String?
        get() = coverId?.let { "https://resources.tidal.com/images/${it.replace('-', '/')}/1280x1280.jpg" }
}

data class TidalPlayback(
    val trackId: Long,
    val audioQuality: String,
    val manifestMimeType: String,
    val manifestBase64: String,
    val bitDepth: Int?,
    val sampleRate: Int?,
)

sealed interface TidalResult<out T> {
    data class Ok<T>(val value: T) : TidalResult<T>
    /** No tokens, or tokens Tidal refused even after one refresh. */
    data object Unauthorized : TidalResult<Nothing>
    /** Tidal answered, but has nothing to give for this item (region, removed, not streamable). */
    data class Unavailable(val status: Int) : TidalResult<Nothing>
    /** Network, rate limit, or a server error: worth trying again later. */
    data class Failed(val reason: String) : TidalResult<Nothing>
}

/**
 * The few Tidal v1 calls a source needs. One request at a time, no retries
 * beyond the single refresh-and-repeat on a 401: the account is the owner's own
 * and is not to be hammered.
 */
class TidalApi(
    baseUrl: String = "https://api.tidal.com/v1/",
    private val tokenUrl: String = TidalAuth.TOKEN_URL,
    private val load: () -> TidalTokens? = TidalTokenStore::load,
    /** Stores [new] only while [old] is still what is stored: a sign-out mid-refresh must stay a sign-out. */
    private val replace: (old: TidalTokens, new: TidalTokens) -> Boolean = TidalTokenStore::replaceIf,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val base: HttpUrl = baseUrl.toHttpUrl()
    private val json = Json { ignoreUnknownKeys = true }

    /** The signed-in account's user id and country. */
    suspend fun session(): TidalResult<Pair<Long, String>> = get("sessions") { obj ->
        val user = obj["userId"]?.jsonPrimitive?.longOrNull ?: return@get null
        val country = obj["countryCode"]?.jsonPrimitive?.content ?: return@get null
        user to country
    }

    suspend fun searchTracks(q: String, limit: Int): TidalResult<List<TidalTrack>> =
        get("search/tracks", mapOf("query" to q, "limit" to limit.coerceIn(1, 25).toString())) { obj ->
            (obj["items"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.let(::parseTrack) }
        }

    suspend fun playback(id: Long, quality: String): TidalResult<TidalPlayback> =
        get(
            "tracks/$id/playbackinfopostpaywall",
            mapOf("audioquality" to quality, "playbackmode" to "STREAM", "assetpresentation" to "FULL"),
        ) { obj ->
            TidalPlayback(
                trackId = obj["trackId"]?.jsonPrimitive?.longOrNull ?: id,
                audioQuality = obj["audioQuality"]?.jsonPrimitive?.content ?: return@get null,
                manifestMimeType = obj["manifestMimeType"]?.jsonPrimitive?.content ?: return@get null,
                manifestBase64 = obj["manifest"]?.jsonPrimitive?.content ?: return@get null,
                bitDepth = obj["bitDepth"]?.jsonPrimitive?.intOrNull,
                sampleRate = obj["sampleRate"]?.jsonPrimitive?.intOrNull,
            )
        }

    private suspend fun <T> get(
        path: String,
        query: Map<String, String> = emptyMap(),
        parse: (JsonObject) -> T?,
    ): TidalResult<T> = withContext(Dispatchers.IO) {
        var tokens = load() ?: return@withContext TidalResult.Unauthorized
        if (tokens.expiresAtMs - 60_000 <= now()) {
            tokens = refreshed(tokens) ?: return@withContext TidalResult.Unauthorized
        }
        var answer = call(path, query, tokens)
        // Only an expired token is worth a refresh; Tidal also says 401 for a track it won't play.
        if (answer.first == 401 && isExpiredToken(answer.second)) {
            tokens = refreshed(tokens) ?: return@withContext TidalResult.Unauthorized
            answer = call(path, query, tokens)
        }
        val (code, body) = answer
        when {
            code == 401 && isExpiredToken(body) -> TidalResult.Unauthorized
            code == 429 -> TidalResult.Failed("Rate limited by Tidal")
            code < 0 -> TidalResult.Failed(body)
            code in 400..499 -> TidalResult.Unavailable(code)
            code !in 200..299 -> TidalResult.Failed("Tidal answered HTTP $code")
            else -> runCatching { parse(json.parseToJsonElement(body).jsonObject) }.getOrNull()
                ?.let { TidalResult.Ok(it) }
                ?: TidalResult.Failed("Unreadable answer from Tidal")
        }
    }

    /** HTTP status and body; a negative status for a request that never got an answer. */
    private fun call(path: String, query: Map<String, String>, tokens: TidalTokens): Pair<Int, String> {
        val url = base.newBuilder().addPathSegments(path).apply {
            query.forEach { (k, v) -> addQueryParameter(k, v) }
            addQueryParameter("countryCode", tokens.countryCode ?: "US")
        }.build()
        val request = Request.Builder().url(url).header("Authorization", "Bearer ${tokens.access}").build()
        return try {
            Http.client.newCall(request).execute().use { it.code to it.body?.string().orEmpty() }
        } catch (e: java.io.IOException) {
            -1 to (e.message ?: "Network error")
        }
    }

    /**
     * One refresh for every caller that found [stale] expired: Tidal rotates
     * refresh tokens, so a second POST of the same one would be refused (and
     * may revoke the session). Whoever gets the lock second reuses the result.
     */
    private suspend fun refreshed(stale: TidalTokens): TidalTokens? = refreshLock.withLock {
        val current = load() ?: return@withLock null // signed out meanwhile
        if (current.access != stale.access) return@withLock current
        val fresh = runCatching { TidalAuth.refresh(current.refresh, tokenUrl) }.getOrNull()
            ?.let { it.copy(userId = it.userId ?: current.userId, countryCode = it.countryCode ?: current.countryCode) }
            ?: return@withLock null
        fresh.takeIf { replace(current, it) }
    }

    private fun isExpiredToken(body: String): Boolean =
        runCatching { json.parseToJsonElement(body).jsonObject["userMessage"]?.jsonPrimitive?.content }
            .getOrNull()?.startsWith("The token has expired") == true

    private companion object {
        /** Process-wide: every TidalApi (each source, each probe) shares the one stored token. */
        val refreshLock = Mutex()
    }

    private fun parseTrack(o: JsonObject): TidalTrack? {
        fun s(obj: JsonObject?, k: String) = obj?.get(k)?.takeIf { it is kotlinx.serialization.json.JsonPrimitive }
            ?.jsonPrimitive?.takeIf { it.isString }?.content
        val id = o["id"]?.jsonPrimitive?.longOrNull ?: return null
        val title = s(o, "title") ?: return null
        // Tidal keeps "Live", "Remastered", "Radio Edit" apart from the title; the matcher needs it back.
        val version = s(o, "version")?.takeIf { it.isNotBlank() }
        val artists = (o["artists"] as? JsonArray).orEmpty().mapNotNull { s(it as? JsonObject, "name") }
        val album = o["album"] as? JsonObject
        return TidalTrack(
            id = id,
            title = if (version != null && !title.contains(version, ignoreCase = true)) "$title ($version)" else title,
            artist = artists.ifEmpty { listOfNotNull(s(o["artist"] as? JsonObject, "name")) }.joinToString(", ")
                .ifBlank { "Unknown Artist" },
            album = s(album, "title"),
            coverId = s(album, "cover"),
            durationSec = o["duration"]?.jsonPrimitive?.intOrNull ?: 0,
            explicit = o["explicit"]?.jsonPrimitive?.booleanOrNull ?: false,
            audioQuality = s(o, "audioQuality") ?: "LOSSLESS",
            isrc = s(o, "isrc"),
        )
    }
}
