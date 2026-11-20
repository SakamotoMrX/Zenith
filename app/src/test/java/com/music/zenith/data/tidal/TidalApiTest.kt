package com.music.zenith.data.tidal

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
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

class TidalApiTest {

    private lateinit var server: MockWebServer
    private val seen = mutableListOf<RecordedRequest>()
    /** Answers per path, consumed in order; the last one repeats. */
    private val routes = mutableMapOf<String, ArrayDeque<MockResponse>>()
    private var stored: TidalTokens? = null

    @Before fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                synchronized(seen) { seen += request }
                val q = routes[request.requestUrl!!.encodedPath] ?: return MockResponse().setResponseCode(404)
                return if (q.size > 1) q.removeFirst() else q.first()
            }
        }
        server.start()
        stored = TidalTokens("A1", "R1", Long.MAX_VALUE, 7L, "ID")
    }

    @After fun tearDown() { server.shutdown() }

    private fun route(path: String, vararg answers: MockResponse) { routes[path] = ArrayDeque(answers.toList()) }
    private fun ok(body: String) = MockResponse().setBody(body)
    private fun expired() = MockResponse().setResponseCode(401)
        .setBody("""{"status":401,"subStatus":11003,"userMessage":"The token has expired. (Expired on time)"}""")
    private fun api() = TidalApi(
        baseUrl = server.url("/v1/").toString(),
        tokenUrl = server.url("/token").toString(),
        load = { stored },
        replace = { old, new -> (stored?.refresh == old.refresh).also { if (it) stored = new } },
    )
    private fun apiCalls() = seen.count { it.requestUrl!!.encodedPath.startsWith("/v1/") }

    @Test fun searchParsesTracks() = runBlocking {
        route(
            "/v1/search/tracks",
            ok(
                """{"items":[{"id":11,"title":"Song","version":"Live","duration":201,"explicit":true,
                "audioQuality":"HI_RES_LOSSLESS","isrc":"X1","artists":[{"name":"A"},{"name":"B"}],
                "album":{"title":"Alb","cover":"aa-bb-cc"}}]}""",
            ),
        )
        val r = api().searchTracks("song a", 10) as TidalResult.Ok
        val t = r.value.single()
        assertEquals(11L, t.id)
        assertEquals("Song (Live)", t.title)
        assertEquals("A, B", t.artist)
        assertEquals("Alb", t.album)
        assertEquals("https://resources.tidal.com/images/aa/bb/cc/1280x1280.jpg", t.coverUrl)
        assertEquals(201, t.durationSec)
        assertTrue(t.explicit)
        assertEquals("HI_RES_LOSSLESS", t.audioQuality)
        val req = seen.single()
        assertEquals("song a", req.requestUrl!!.queryParameter("query"))
        assertEquals("Bearer A1", req.getHeader("Authorization"))
    }

    @Test fun countryCodeSent() = runBlocking {
        route("/v1/sessions", ok("""{"userId":7,"countryCode":"ID"}"""))
        assertEquals(TidalResult.Ok(7L to "ID"), api().session())
        assertEquals("ID", seen.single().requestUrl!!.queryParameter("countryCode"))
    }

    @Test fun refreshesOnceOn401ThenGivesUp() = runBlocking {
        route("/token", ok("""{"access_token":"A2","expires_in":600}"""))
        route("/v1/sessions", expired(), ok("""{"userId":7,"countryCode":"ID"}"""))
        assertEquals(TidalResult.Ok(7L to "ID"), api().session())
        assertEquals("A2", stored!!.access)
        assertEquals("R1", stored!!.refresh)

        seen.clear()
        route("/v1/sessions", expired())
        assertEquals(TidalResult.Unauthorized, api().session())
        assertEquals(2, apiCalls())
    }

    @Test fun refreshRefusedIsUnauthorized() = runBlocking {
        route("/token", MockResponse().setResponseCode(400))
        route("/v1/sessions", expired())
        assertEquals(TidalResult.Unauthorized, api().session())
        assertEquals(1, apiCalls())
    }

    @Test fun expiredTokenRefreshedBeforeCalling() = runBlocking {
        stored = stored!!.copy(expiresAtMs = 0)
        route("/token", ok("""{"access_token":"A3","refresh_token":"R3","expires_in":600}"""))
        route("/v1/sessions", ok("""{"userId":7,"countryCode":"ID"}"""))
        api().session()
        assertEquals("Bearer A3", seen.last().getHeader("Authorization"))
        assertEquals("ID", stored!!.countryCode)
    }

    @Test fun rateLimitIsUnreachable() = runBlocking {
        route("/v1/sessions", MockResponse().setResponseCode(429))
        assertTrue(api().session() is TidalResult.Failed)
    }

    @Test fun notSignedInIsUnauthorized() = runBlocking {
        stored = null
        assertEquals(TidalResult.Unauthorized, api().session())
        assertEquals(0, seen.size)
    }

    @Test fun playbackParsesManifest() = runBlocking {
        route(
            "/v1/tracks/11/playbackinfopostpaywall",
            ok(
                """{"trackId":11,"audioQuality":"HI_RES_LOSSLESS","manifestMimeType":"application/dash+xml",
                "manifest":"PE1QRD4=","bitDepth":24,"sampleRate":96000}""",
            ),
        )
        val p = (api().playback(11, "HI_RES_LOSSLESS") as TidalResult.Ok).value
        assertEquals("application/dash+xml", p.manifestMimeType)
        assertEquals(24, p.bitDepth)
        assertEquals(96000, p.sampleRate)
        val url = seen.single().requestUrl!!
        assertEquals("HI_RES_LOSSLESS", url.queryParameter("audioquality"))
        assertEquals("STREAM", url.queryParameter("playbackmode"))
        assertEquals("FULL", url.queryParameter("assetpresentation"))
    }

    @Test fun missingTrackIsUnavailable() = runBlocking {
        route("/v1/tracks/12/playbackinfopostpaywall", MockResponse().setResponseCode(404))
        assertEquals(TidalResult.Unavailable(404), api().playback(12, "LOSSLESS"))
    }

    @Test fun unplayable401IsUnavailableWithoutRefresh() = runBlocking {
        route(
            "/v1/tracks/13/playbackinfopostpaywall",
            MockResponse().setResponseCode(401).setBody("""{"status":401,"subStatus":4005,"userMessage":"Asset is not ready for playback"}"""),
        )
        assertEquals(TidalResult.Unavailable(401), api().playback(13, "LOSSLESS"))
        assertEquals(0, seen.count { it.requestUrl!!.encodedPath == "/token" })
    }

    @Test fun concurrentExpiriesRefreshOnce() = runBlocking {
        stored = stored!!.copy(expiresAtMs = 0)
        route("/token", ok("""{"access_token":"A9","refresh_token":"R9","expires_in":600}""").setBodyDelay(300, java.util.concurrent.TimeUnit.MILLISECONDS))
        route("/v1/sessions", ok("""{"userId":7,"countryCode":"ID"}"""))
        val a = api()
        val b = api()
        val results = listOf(
            async(Dispatchers.IO) { a.session() },
            async(Dispatchers.IO) { b.session() },
        ).map { it.await() }
        results.forEach { assertTrue(it.toString(), it is TidalResult.Ok) }
        assertEquals(1, seen.count { it.requestUrl!!.encodedPath == "/token" })
        assertEquals("R9", stored!!.refresh)
    }

    @Test fun signOutDuringRefreshStaysSignedOut() = runBlocking {
        stored = stored!!.copy(expiresAtMs = 0)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                synchronized(seen) { seen += request }
                if (request.requestUrl!!.encodedPath == "/token") {
                    stored = null // the user signed out while this refresh was in flight
                    return ok("""{"access_token":"A9","refresh_token":"R9","expires_in":600}""")
                }
                return ok("""{"userId":7,"countryCode":"ID"}""")
            }
        }
        assertEquals(TidalResult.Unauthorized, api().session())
        assertNull(stored)
    }
}
