package com.music.zenith.auth

import android.annotation.SuppressLint
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.music.zenith.R
import com.music.zenith.data.sources.SourceKind
import com.music.zenith.data.sources.SourceRegistry
import com.music.zenith.data.tidal.TidalAuth
import com.music.zenith.data.tidal.TidalPkce
import com.music.zenith.data.tidal.TidalTokenStore
import com.music.zenith.ui.components.PillTextField
import kotlinx.coroutines.launch

/** Saves a fresh sign-in and switches the Tidal source on. */
private suspend fun signIn(code: String, pkce: TidalPkce) {
    TidalTokenStore.save(TidalAuth.exchange(code, pkce))
    SourceRegistry.configs.value.firstOrNull { it.kind == SourceKind.TIDAL }
        ?.let { SourceRegistry.setEnabled(it.id, true) }
}

/** Forgets the account and switches the Tidal source off. */
fun signOutOfTidal() {
    TidalTokenStore.clear()
    SourceRegistry.configs.value.firstOrNull { it.kind == SourceKind.TIDAL }
        ?.let { SourceRegistry.setEnabled(it.id, false) }
}

/**
 * Tidal's own login page in a WebView, finished by catching its redirect to
 * [TidalAuth.REDIRECT_URI] — the PKCE flow, which is the one that gets
 * hi-res. Shown full screen from the activity, like [SpotifyLoginScreen], for
 * the same reason (a WebView inside the blurred content tree flickers).
 *
 * The redirect page itself is not one Tidal serves usefully, so it is caught
 * before it loads. Where the page can't be finished in the WebView (an identity
 * provider that refuses embedded browsers), the address can be pasted from a
 * real browser instead — the same fallback Waves and python-tidal use.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun TidalLoginScreen(
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val pkce = remember { TidalPkce.create() }
    val scope = rememberCoroutineScope()
    val uriHandler = LocalUriHandler.current
    var webView by remember { mutableStateOf<WebView?>(null) }
    var canGoBack by remember { mutableStateOf(false) }
    var pasted by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun finish(code: String) {
        if (busy) return
        busy = true
        error = null
        scope.launch {
            runCatching { signIn(code, pkce) }
                .onSuccess { onDone() }
                .onFailure { error = it.message ?: it.javaClass.simpleName }
            busy = false
        }
    }

    Column(modifier.fillMaxSize()) {
        error?.let {
            Text(
                stringResource(R.string.tidal_sign_in_failed, it),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Same PKCE attempt as the WebView, so the address it lands on can be pasted back here.
            TextButton(enabled = !busy, onClick = { uriHandler.openUri(TidalAuth.loginUrl(pkce)) }) {
                Text(stringResource(R.string.tidal_open_browser))
            }
            PillTextField(
                value = pasted,
                onValueChange = { pasted = it },
                placeholder = stringResource(R.string.tidal_paste_hint),
                enabled = !busy,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            TextButton(
                enabled = !busy && TidalAuth.codeFrom(pasted) != null,
                onClick = { TidalAuth.codeFrom(pasted)?.let(::finish) },
            ) { Text(stringResource(R.string.tidal_paste_use)) }
        }
        AndroidView(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            factory = { context ->
                WebView(context).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.userAgentString = browserUserAgent(settings.userAgentString)
                    CookieManager.getInstance().setAcceptCookie(true)
                    CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                            val url = request.url.toString()
                            if (!url.startsWith(TidalAuth.REDIRECT_URI)) return false
                            TidalAuth.codeFrom(url)?.let {
                                view.visibility = View.GONE
                                finish(it)
                            }
                            return true
                        }

                        override fun onPageFinished(view: WebView, url: String?) {
                            canGoBack = view.canGoBack()
                        }
                    }
                    webView = this
                    loadUrl(TidalAuth.loginUrl(pkce))
                }
            },
            onRelease = { it.destroy() },
        )
    }

    BackHandler(enabled = canGoBack) { webView?.goBack() }
}
