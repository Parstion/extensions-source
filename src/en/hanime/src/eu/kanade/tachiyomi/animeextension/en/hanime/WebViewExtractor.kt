package eu.kanade.tachiyomi.animeextension.en.hanime

import android.annotation.SuppressLint
import android.app.Application
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.SslErrorHandler
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

data class HlsResult(
    val url: String,
    val cookies: String,
)

object WebViewExtractor {

    private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
        "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    // JS injected into the page to capture the HLS URL.
    // Stores the URL in window.__hls_captured so we can poll for it from Kotlin,
    // and also calls the Android interface as a fast path if timing allows.
    private val CAPTURE_SCRIPT = """
        (function() {
            if (window.__hls_installed) return;
            window.__hls_installed = true;
            window.__hls_captured = '';
            function capture(u) {
                if (!u || u.indexOf('/hls/') === -1 || u.indexOf('hanime.tv') === -1) return;
                window.__hls_captured = u;
                try { AndroidHls.onHlsUrl(u); } catch(e) {}
            }
            var origFetch = window.fetch;
            window.fetch = function() {
                var u = typeof arguments[0] === 'string'
                    ? arguments[0]
                    : (arguments[0] && arguments[0].url) || '';
                capture(u);
                return origFetch.apply(this, arguments);
            };
            var origOpen = XMLHttpRequest.prototype.open;
            XMLHttpRequest.prototype.open = function(method, url) {
                capture(url);
                return origOpen.apply(this, arguments);
            };
            setTimeout(function() {
                var candidates = [
                    document.querySelector('button[aria-label="Play"]'),
                    document.querySelector('[class*="play-btn"]'),
                    document.querySelector('[class*="PlayButton"]'),
                    document.querySelector('video')
                ];
                for (var i = 0; i < candidates.length; i++) {
                    if (candidates[i]) { candidates[i].click(); break; }
                }
            }, 2000);
        })();
    """.trimIndent()

    @SuppressLint("SetJavaScriptEnabled", "AddJavascriptInterface")
    fun extractHlsUrl(videoPageUrl: String): HlsResult? {
        val latch = CountDownLatch(1)
        var hlsUrl = ""
        var cookies = ""
        val context = Injekt.get<Application>()

        Handler(Looper.getMainLooper()).post {
            val webView = WebView(context)
            webView.settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                mediaPlaybackRequiresUserGesture = false
                userAgentString = USER_AGENT
            }

            // Fast-path callback — called from JS if timing allows
            webView.addJavascriptInterface(
                object {
                    @JavascriptInterface
                    fun onHlsUrl(url: String) {
                        if (hlsUrl.isNotEmpty()) return
                        hlsUrl = url
                        cookies = CookieManager.getInstance()
                            .getCookie("https://hanime.tv") ?: ""
                        Handler(Looper.getMainLooper()).post { webView.destroy() }
                        latch.countDown()
                    }
                },
                "AndroidHls",
            )

            webView.webViewClient = object : WebViewClient() {
                // Accept SSL certificates — hanime.tv uses a cert Android doesn't
                // always trust, causing the page to silently fail to load.
                override fun onReceivedSslError(
                    view: WebView,
                    handler: SslErrorHandler,
                    error: android.net.http.SslError,
                ) {
                    handler.proceed()
                }

                override fun onPageFinished(view: WebView, url: String) {
                    // Inject capture script and start polling
                    view.evaluateJavascript(CAPTURE_SCRIPT, null)
                    pollForHlsUrl(view, attempt = 0, maxAttempts = 30) { url, jar ->
                        hlsUrl = url
                        cookies = jar
                        webView.destroy()
                        latch.countDown()
                    }
                }

                // Fallback for native media element requests
                override fun shouldInterceptRequest(
                    view: WebView,
                    request: WebResourceRequest,
                ): WebResourceResponse? {
                    val url = request.url.toString()
                    if (hlsUrl.isEmpty() &&
                        url.contains("/hls/") &&
                        url.contains("hanime.tv")
                    ) {
                        hlsUrl = url
                        cookies = CookieManager.getInstance()
                            .getCookie("https://hanime.tv") ?: ""
                        Handler(Looper.getMainLooper()).post { webView.destroy() }
                        latch.countDown()
                    }
                    return null
                }
            }

            webView.loadUrl(videoPageUrl)
        }

        latch.await(60, TimeUnit.SECONDS)
        return if (hlsUrl.isNotEmpty()) HlsResult(hlsUrl, cookies) else null
    }

    // Poll window.__hls_captured every second — mirrors the ssignature polling
    // approach that works reliably even when JS callback timing is uncertain.
    private fun pollForHlsUrl(
        view: WebView,
        attempt: Int,
        maxAttempts: Int,
        onResult: (String, String) -> Unit,
    ) {
        if (attempt >= maxAttempts) return // let the latch timeout handle it

        view.evaluateJavascript("window.__hls_captured || ''") { value ->
            val url = value?.trim('"') ?: ""
            if (url.isNotEmpty() && url != "null" && url != "undefined") {
                val jar = CookieManager.getInstance()
                    .getCookie("https://hanime.tv") ?: ""
                onResult(url, jar)
            } else {
                // Re-inject on every poll in case the page JS reset window.fetch
                view.evaluateJavascript(CAPTURE_SCRIPT, null)
                Handler(Looper.getMainLooper()).postDelayed(
                    { pollForHlsUrl(view, attempt + 1, maxAttempts, onResult) },
                    1000,
                )
            }
        }
    }
}
