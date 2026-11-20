package com.music.zenith.playback

import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSchemeDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener

/**
 * Sends `data:` URIs to Media3's [DataSchemeDataSource] and everything else to
 * [http].
 *
 * Needed below the resolver, where [DefaultDataSource][androidx.media3.datasource.DefaultDataSource]
 * has already dispatched on the `zenith://` scheme: a source that answers with
 * an inline manifest (Tidal's DASH, see
 * [TidalManifest][com.music.zenith.data.tidal.TidalManifest]) would otherwise
 * reach OkHttp, which refuses it as a malformed URL.
 */
@UnstableApi
class DataSchemeRouting(private val http: DataSource.Factory) : DataSource.Factory {

    override fun createDataSource(): DataSource = Routed(http.createDataSource())

    private class Routed(private val http: DataSource) : DataSource {
        private val inline = DataSchemeDataSource()
        private var current: DataSource? = null

        override fun addTransferListener(transferListener: TransferListener) {
            http.addTransferListener(transferListener)
            inline.addTransferListener(transferListener)
        }

        override fun open(dataSpec: DataSpec): Long {
            val target = if (isDataScheme(dataSpec.uri.toString())) inline else http
            current = target
            return target.open(dataSpec)
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
            checkNotNull(current) { "read before open" }.read(buffer, offset, length)

        override fun getUri(): Uri? = current?.uri

        override fun getResponseHeaders(): Map<String, List<String>> = current?.responseHeaders ?: emptyMap()

        override fun close() {
            try {
                current?.close()
            } finally {
                current = null
            }
        }
    }

    companion object {
        fun isDataScheme(uri: String): Boolean = uri.regionMatches(0, "data:", 0, 5, ignoreCase = true)
    }
}
