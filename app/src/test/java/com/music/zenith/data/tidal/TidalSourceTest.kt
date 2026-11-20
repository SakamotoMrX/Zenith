package com.music.zenith.data.tidal

import com.music.zenith.data.sources.SourceConfig
import com.music.zenith.data.sources.SourceHealth
import com.music.zenith.data.sources.SourceKind
import com.music.zenith.data.sources.SourceRegistry
import com.music.zenith.data.sources.StreamRequest
import com.music.zenith.data.sources.TidalSource
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Base64

class TidalSourceTest {

    private lateinit var server: MockWebServer
    private val routes = mutableMapOf<String, MockResponse>()
    private val seen = mutableListOf<RecordedRequest>()
    private var tokens: TidalTokens? = TidalTokens("A", "R", Long.MAX_VALUE, 1L, "ID")

    @Before fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                synchronized(seen) { seen += request }
                return routes[request.requestUrl!!.encodedPath] ?: MockResponse().setResponseCode(404)
            }
        }
        server.start()
    }

    @After fun tearDown() { server.shutdown() }

    private fun source(picked: String? = null) = TidalSource(
        SourceConfig(kind = SourceKind.TIDAL, enabled = true, qualityOptions = TidalSource.QUALITIES, pickedQuality = picked),
        TidalApi(server.url("/v1/").toString(), server.url("/token").toString(), { tokens }, { tokens = it }),
    )

    private fun bts() = Base64.getEncoder().encodeToString(
        """{"codecs":"flac","encryptionType":"NONE","urls":["https://lgf.audio.tidal.com/x.flac"]}""".toByteArray(),
    )

    @Test fun searchMapsToSongsWithTrackKeys() = runBlocking {
        routes["/v1/search/tracks"] = MockResponse().setBody(
            """{"items":[{"id":5,"title":"T","duration":125,"audioQuality":"HI_RES_LOSSLESS",
               "artists":[{"name":"A"}],"album":{"title":"Al","cover":"a-b"}}]}""",
        )
        val src = source()
        val song = src.search("t a").single()
        assertEquals(SourceRegistry.trackKey(src.configId, "5"), song.videoId)
        assertEquals("2:05", song.durationText)
        assertEquals("LOSSLESS", song.sourceQuality)
        assertTrue(song.sourceHiRes)
        assertEquals("Al", song.albumName)
    }

    @Test fun unavailableTrackIsAMiss() = runBlocking {
        routes["/v1/tracks/9/playbackinfopostpaywall"] = MockResponse().setResponseCode(403)
        assertNull(source().stream("9", StreamRequest.Lossless))
    }

    @Test fun nonLosslessRequestIsAMiss() = runBlocking {
        assertNull(source().stream("9", StreamRequest.Best))
        assertNull(source().stream("9", StreamRequest.Capped(320)))
        assertEquals(0, seen.size)
    }

    @Test fun pickedQualityIsAsked() = runBlocking {
        routes["/v1/tracks/9/playbackinfopostpaywall"] = MockResponse().setBody(
            """{"trackId":9,"audioQuality":"LOSSLESS","manifestMimeType":"application/vnd.tidal.bts",
               "manifest":"${bts()}","bitDepth":16,"sampleRate":44100}""",
        )
        val s = source(picked = TidalSource.LOSSLESS).stream("9", StreamRequest.Lossless)!!
        assertEquals("https://lgf.audio.tidal.com/x.flac", s.url)
        assertEquals("LOSSLESS", seen.single().requestUrl!!.queryParameter("audioquality"))

        seen.clear()
        source().stream("9", StreamRequest.Lossless)
        assertEquals("HI_RES_LOSSLESS", seen.single().requestUrl!!.queryParameter("audioquality"))
    }

    @Test fun healthWithoutTokensIsRejected() = runBlocking {
        tokens = null
        assertTrue(source().health() is SourceHealth.Rejected)
    }

    @Test fun tidalIsSeededOffWithItsQualities() {
        val tidal = SourceRegistry.sourcesForInit(emptyList(), forceJioSaavnOff = false)
            .single { it.kind == SourceKind.TIDAL }
        assertEquals(false, tidal.enabled)
        assertEquals(TidalSource.QUALITIES, tidal.qualityOptions)
        assertEquals(TidalSource.HI_RES, tidal.activeQuality?.value)
    }
}
