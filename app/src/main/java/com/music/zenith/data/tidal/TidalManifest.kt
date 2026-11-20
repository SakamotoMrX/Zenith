package com.music.zenith.data.tidal

import com.music.zenith.data.sources.SourceStream
import com.music.zenith.data.sources.StreamFormat
import com.music.zenith.playback.StreamContainer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.Base64

/**
 * Turns Tidal's playback answer into something the player and the downloader
 * can open.
 *
 * Tidal hands back the manifest itself rather than a link to one. A 16-bit
 * track comes as a small JSON ("BTS") naming one plain FLAC URL; a 24-bit
 * track comes as a DASH MPD of FLAC-in-MP4 segments. The MPD is passed on as a
 * `data:` URI, which the player's upstream and the downloader both read.
 *
 * Anything protected (Atmos, encrypted renditions) is a miss, never decrypted.
 */
object TidalManifest {
    const val DASH_DATA_PREFIX = "data:application/dash+xml;base64,"

    private const val BTS = "application/vnd.tidal.bts"
    private const val DASH = "application/dash+xml"
    private val json = Json { ignoreUnknownKeys = true }

    fun toStream(p: TidalPlayback, durationSec: Int? = null): SourceStream? = when (p.manifestMimeType) {
        BTS -> bts(p, durationSec)
        DASH -> dash(p, durationSec)
        else -> null
    }

    private fun bts(p: TidalPlayback, durationSec: Int?): SourceStream? {
        val obj = runCatching {
            json.parseToJsonElement(String(Base64.getDecoder().decode(p.manifestBase64))).jsonObject
        }.getOrNull() ?: return null
        val encryption = obj["encryptionType"]?.jsonPrimitive?.content ?: "NONE"
        if (encryption != "NONE") return null
        val url = (obj["urls"] as? JsonArray)?.firstOrNull()?.jsonPrimitive?.content ?: return null
        val codec = obj["codecs"]?.jsonPrimitive?.content?.lowercase()
        if (codec != "flac") return null // lossy renditions are never asked for; refuse rather than mislabel
        return SourceStream(
            url = url,
            format = StreamFormat(codec = "flac", sampleRateHz = p.sampleRate, bitDepth = p.bitDepth ?: 16),
            durationSec = durationSec,
        )
    }

    private fun dash(p: TidalPlayback, durationSec: Int?): SourceStream? {
        val mpd = runCatching { String(Base64.getDecoder().decode(p.manifestBase64)) }.getOrNull() ?: return null
        if ("ContentProtection" in mpd) return null
        if (!Regex("""codecs\s*=\s*"flac"""", RegexOption.IGNORE_CASE).containsMatchIn(mpd)) return null
        val url = DASH_DATA_PREFIX + Base64.getEncoder().encodeToString(mpd.toByteArray())
        StreamContainer.declare(url, "dash")
        return SourceStream(
            url = url,
            format = StreamFormat(codec = "flac", sampleRateHz = p.sampleRate, bitDepth = p.bitDepth ?: 24),
            durationSec = durationSec,
        )
    }

    /** The MPD inside a [DASH_DATA_PREFIX] URI, or null for anything else. */
    fun mpdOf(url: String): String? =
        if (url.startsWith(DASH_DATA_PREFIX)) {
            runCatching { String(Base64.getDecoder().decode(url.removePrefix(DASH_DATA_PREFIX))) }.getOrNull()
        } else {
            null
        }
}
