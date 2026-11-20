package com.music.zenith.data.tidal

import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.URLDecoder
import java.security.MessageDigest
import java.util.Base64

class TidalAuthTest {

    private lateinit var server: MockWebServer

    @Before fun setUp() { server = MockWebServer().also { it.start() } }
    @After fun tearDown() { server.shutdown() }

    private fun form(body: String): Map<String, String> = body.split('&').associate {
        val (k, v) = it.split('=', limit = 2)
        URLDecoder.decode(k, "UTF-8") to URLDecoder.decode(v, "UTF-8")
    }

    @Test fun challengeIsS256OfVerifier() {
        val pkce = TidalPkce.create()
        val expected = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(MessageDigest.getInstance("SHA-256").digest(pkce.verifier.toByteArray()))
        assertEquals(expected, pkce.challenge)
        assertTrue(pkce.verifier.length >= 43)
    }

    @Test fun loginUrlCarriesPkceParams() {
        val pkce = TidalPkce("v", "c", "k")
        val url = TidalAuth.loginUrl(pkce).toHttpUrl()
        assertEquals("login.tidal.com", url.host)
        assertEquals("code", url.queryParameter("response_type"))
        assertEquals("S256", url.queryParameter("code_challenge_method"))
        assertEquals("c", url.queryParameter("code_challenge"))
        assertEquals("k", url.queryParameter("client_unique_key"))
        assertEquals("https://tidal.com/android/login/auth", url.queryParameter("redirect_uri"))
        assertEquals("android", url.queryParameter("appMode"))
        assertEquals("true", url.queryParameter("restrict_signup"))
        assertEquals(TidalAuth.CLIENT_ID, url.queryParameter("client_id"))
    }

    @Test fun codeFromRedirect() {
        assertEquals("abc", TidalAuth.codeFrom("https://tidal.com/android/login/auth?code=abc&state=x"))
        assertEquals("abc", TidalAuth.codeFrom("  https://tidal.com/android/login/auth?code=abc \n"))
        assertNull(TidalAuth.codeFrom("http://tidal.com/android/login/auth?code=abc"))
        assertNull(TidalAuth.codeFrom("https://tidal.com/android/login/auth"))
        assertNull(TidalAuth.codeFrom("not a url"))
    }

    @Test fun exchangePostsAuthorizationCode() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """{"access_token":"A","refresh_token":"R","expires_in":600,
                   "user":{"userId":42,"countryCode":"ID"}}""",
            ),
        )
        val tokens = TidalAuth.exchange("CODE", TidalPkce("ver", "chal", "key"), server.url("/token").toString())
        val sent = form(server.takeRequest().body.readUtf8())
        assertEquals("authorization_code", sent["grant_type"])
        assertEquals("CODE", sent["code"])
        assertEquals("ver", sent["code_verifier"])
        assertEquals("key", sent["client_unique_key"])
        assertEquals("r_usr+w_usr+w_sub", sent["scope"])
        assertEquals("A", tokens.access)
        assertEquals("R", tokens.refresh)
        assertEquals(42L, tokens.userId)
        assertEquals("ID", tokens.countryCode)
        assertTrue(tokens.expiresAtMs > System.currentTimeMillis())
    }

    @Test fun refreshSendsClientSecret() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"access_token":"A2","expires_in":600}"""))
        val tokens = TidalAuth.refresh("R1", server.url("/token").toString())
        val sent = form(server.takeRequest().body.readUtf8())
        assertEquals("refresh_token", sent["grant_type"])
        assertEquals("R1", sent["refresh_token"])
        assertEquals(TidalAuth.CLIENT_ID, sent["client_id"])
        assertEquals(TidalAuth.CLIENT_SECRET, sent["client_secret"])
        assertEquals("A2", tokens.access)
        assertEquals("R1", tokens.refresh)
    }

    @Test(expected = TidalAuthException::class)
    fun rejectedRefreshThrows(): Unit = runBlocking {
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"error":"invalid_grant"}"""))
        TidalAuth.refresh("R1", server.url("/token").toString())
        Unit
    }
}
