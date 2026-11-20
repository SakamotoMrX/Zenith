package com.music.zenith.data.tidal

import com.music.zenith.data.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/** What a signed-in Tidal account needs to make requests. */
data class TidalTokens(
    val access: String,
    val refresh: String,
    val expiresAtMs: Long,
    val userId: Long?,
    val countryCode: String?,
)

/** One login attempt's PKCE secrets; a fresh one per attempt. */
class TidalPkce(val verifier: String, val challenge: String, val uniqueKey: String) {
    companion object {
        fun create(random: SecureRandom = SecureRandom()): TidalPkce {
            val bytes = ByteArray(32).also(random::nextBytes)
            val verifier = base64Url(bytes)
            val challenge = base64Url(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()))
            return TidalPkce(verifier, challenge, java.lang.Long.toHexString(random.nextLong()))
        }

        internal fun base64Url(bytes: ByteArray): String =
            Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }
}

class TidalAuthException(message: String) : Exception(message)

/**
 * Tidal's browser (PKCE) login, the flow the Android app itself uses and the
 * only one whose client is entitled to HI_RES_LOSSLESS. The device-code flow
 * is not an alternative: Tidal refuses this client on that endpoint, and the
 * client it does accept there is capped at 16-bit.
 *
 * Endpoints and client follow python-tidal (EbbLabs/python-tidal), which
 * reads them from the official app.
 */
object TidalAuth {
    const val AUTH_URL = "https://login.tidal.com/authorize"
    const val TOKEN_URL = "https://auth.tidal.com/v1/oauth2/token"
    const val REDIRECT_URI = "https://tidal.com/android/login/auth"
    const val SCOPE = "r_usr+w_usr+w_sub"

    // shortcut: the public client of Tidal's Android app; Tidal can revoke it, then swap these two.
    val CLIENT_ID: String = decode("TmtKRVUxSmtjRXM=", "NWFIRkZRbFJuVlE9PQ==")
    val CLIENT_SECRET: String =
        decode("ZUdWMVVHMVpOMjVpY0ZvNVNVbGlURUZqVVQ=", "a3pjMmhyWVRGV1RtaGxWVUZ4VGpaSlkzTjZhbFJIT0QwPQ==")

    private val json = Json { ignoreUnknownKeys = true }

    private fun decode(a: String, b: String): String {
        val d = Base64.getDecoder()
        return String(d.decode(String(d.decode(a)) + String(d.decode(b))))
    }

    fun loginUrl(pkce: TidalPkce): String = AUTH_URL.toHttpUrl().newBuilder()
        .addQueryParameter("response_type", "code")
        .addQueryParameter("redirect_uri", REDIRECT_URI)
        .addQueryParameter("client_id", CLIENT_ID)
        .addQueryParameter("lang", "EN")
        .addQueryParameter("appMode", "android")
        .addQueryParameter("client_unique_key", pkce.uniqueKey)
        .addQueryParameter("code_challenge", pkce.challenge)
        .addQueryParameter("code_challenge_method", "S256")
        .addQueryParameter("restrict_signup", "true")
        .build().toString()

    /** The `code` of the page Tidal redirects to after login, or null if [redirectUrl] isn't that page. */
    fun codeFrom(redirectUrl: String): String? {
        val url = redirectUrl.trim().toHttpUrlOrNull() ?: return null
        if (!url.isHttps) return null
        return url.queryParameter("code")?.takeIf { it.isNotBlank() }
    }

    suspend fun exchange(code: String, pkce: TidalPkce, tokenUrl: String = TOKEN_URL): TidalTokens =
        post(
            tokenUrl,
            FormBody.Builder()
                .add("code", code)
                .add("client_id", CLIENT_ID)
                .add("grant_type", "authorization_code")
                .add("redirect_uri", REDIRECT_URI)
                .add("scope", SCOPE)
                .add("code_verifier", pkce.verifier)
                .add("client_unique_key", pkce.uniqueKey)
                .build(),
            previousRefresh = null,
        )

    suspend fun refresh(refresh: String, tokenUrl: String = TOKEN_URL): TidalTokens =
        post(
            tokenUrl,
            FormBody.Builder()
                .add("grant_type", "refresh_token")
                .add("refresh_token", refresh)
                .add("client_id", CLIENT_ID)
                .add("client_secret", CLIENT_SECRET)
                .build(),
            previousRefresh = refresh,
        )

    private suspend fun post(url: String, body: FormBody, previousRefresh: String?): TidalTokens =
        withContext(Dispatchers.IO) {
            val request = Request.Builder().url(url).post(body).build()
            Http.client.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                // The body is not echoed: on success it holds the tokens, and an error body may too.
                if (!response.isSuccessful) throw TidalAuthException("Tidal token request failed (HTTP ${response.code})")
                parseTokens(json.parseToJsonElement(text).jsonObject, previousRefresh, System.currentTimeMillis())
            }
        }

    internal fun parseTokens(obj: JsonObject, previousRefresh: String?, nowMs: Long): TidalTokens {
        fun str(o: JsonObject?, key: String) = o?.get(key)?.jsonPrimitive?.takeIf { it.isString }?.content
        val access = str(obj, "access_token") ?: throw TidalAuthException("No access token in Tidal's answer")
        val refresh = str(obj, "refresh_token") ?: previousRefresh
            ?: throw TidalAuthException("No refresh token in Tidal's answer")
        val expiresIn = obj["expires_in"]?.jsonPrimitive?.longOrNull ?: 3600L
        val user = obj["user"] as? JsonObject
        return TidalTokens(
            access = access,
            refresh = refresh,
            expiresAtMs = nowMs + expiresIn * 1000,
            userId = (user?.get("userId") ?: obj["user_id"])?.jsonPrimitive?.longOrNull,
            countryCode = str(user, "countryCode"),
        )
    }
}
