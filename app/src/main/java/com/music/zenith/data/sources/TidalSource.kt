package com.music.zenith.data.sources

import com.music.zenith.data.TrackLog
import com.music.zenith.data.model.Song
import com.music.zenith.data.sources.addon.AddonQualityOption
import com.music.zenith.data.tidal.TidalApi
import com.music.zenith.data.tidal.TidalManifest
import com.music.zenith.data.tidal.TidalResult
import java.util.Locale

private const val TAG = "Zenith"

/**
 * The owner's Tidal account as a source: searched to match the track being
 * played, and asked for its FLAC — 24-bit over DASH where the album has it,
 * 16-bit otherwise. Signing in and out lives on the Sources screen; this only
 * reads the tokens it left behind.
 */
class TidalSource(
    override val config: SourceConfig,
    private val api: TidalApi = TidalApi(),
) : MusicSource, SourceRegistry.ConfigBacked {

    override val configId: String get() = config.id
    override val kind: SourceKind get() = SourceKind.TIDAL
    override val displayName: String get() = config.label.ifBlank { SourceKind.TIDAL.label }

    override suspend fun health(): SourceHealth = when (val r = api.session()) {
        is TidalResult.Ok -> SourceHealth.Ok(r.value.second)
        TidalResult.Unauthorized -> SourceHealth.Rejected("Not signed in to Tidal")
        is TidalResult.Unavailable -> SourceHealth.Rejected("Tidal refused this account (HTTP ${r.status})")
        is TidalResult.Failed -> SourceHealth.Unreachable(r.reason)
    }

    /** [waitForAll] and [request] are moot: one catalogue, and every row is asked at the pick. */
    override suspend fun search(
        query: String,
        limit: Int,
        waitForAll: Boolean,
        request: StreamRequest?,
    ): List<Song> {
        val r = api.searchTracks(query, limit.coerceAtMost(25))
        if (r !is TidalResult.Ok) {
            TrackLog.w(TAG, "  ✗ Tidal search failed: $r")
            return emptyList()
        }
        return r.value.map { t ->
            Song(
                videoId = SourceRegistry.trackKey(config.id, t.id.toString()),
                title = t.title,
                artist = t.artist,
                albumName = t.album,
                thumbnailUrl = t.coverUrl,
                durationText = t.durationSec.takeIf { it > 0 }
                    ?.let { String.format(Locale.ROOT, "%d:%02d", it / 60, it % 60) },
                // The resolver ranks on the tier, where 16- and 24-bit are one; hi-res is display only.
                sourceQuality = if (t.audioQuality in setOf(HI_RES, LOSSLESS)) LOSSLESS else t.audioQuality,
                sourceHiRes = t.audioQuality == HI_RES,
                isExplicit = t.explicit,
            )
        }
    }

    override suspend fun stream(trackId: String, request: StreamRequest): SourceStream? {
        // Only a lossless ceiling ever reaches a lossless kind (AudioQuality.permits); anything else is a miss.
        if (request != StreamRequest.Lossless) return null
        val id = trackId.toLongOrNull() ?: return null
        val quality = config.activeQuality?.value ?: HI_RES
        return when (val r = api.playback(id, quality)) {
            is TidalResult.Ok -> TidalManifest.toStream(r.value).also {
                if (it == null) TrackLog.w(TAG, "  ✗ Tidal offered no playable FLAC for $id (${r.value.manifestMimeType})")
            }
            else -> {
                TrackLog.w(TAG, "  ✗ Tidal had no stream for $id: $r")
                null
            }
        }
    }

    companion object {
        const val HI_RES = "HI_RES_LOSSLESS"
        const val LOSSLESS = "LOSSLESS"

        /** The two tiers worth asking for; the labels are replaced by string resources on screen. */
        val QUALITIES = listOf(
            AddonQualityOption(HI_RES, "Hi-Res (up to 24-bit)"),
            AddonQualityOption(LOSSLESS, "Lossless (16-bit)"),
        )
    }
}
