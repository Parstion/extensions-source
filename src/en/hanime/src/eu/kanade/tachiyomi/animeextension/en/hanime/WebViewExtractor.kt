package eu.kanade.tachiyomi.animeextension.en.hanime

import android.annotation.SuppressLint
import android.app.Application
import android.os.Handler
import android.os.Looper
import android.util.Log
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

    private const val TAG = "HanimeWV"
    private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
        "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    // Injected into the page to capture the HLS URL.
    // Deliberately does NOT check for "hanime.tv" in the URL — the player may use
    // relative URLs ("/hls/id/token") which would fail a domain check.
    private val CAPTURE_SCRIPT = """
        (function() {
            if (window.__hls_installed) { return; }
            window.__hls_installed = true;
            window.__hls_captured = '';
            function capture(u) {
                if (!u) return;
                var s = (typeof u === 'string') ? u : (u.url || '');
                if (s.indexOf('/hls/') === -1) return;
                window.__hls_captured = s;
                try { AndroidHls.onHlsUrl(s); } catch(e) {}
            }
            var origFetch = window.fetch;
            window.fetch = function() {
                capture(arguments[0]);
                return origFetch.apply(this, arguments);
            };
            var origOpen = XMLHttpRequest.prototype.open;
            XMLHttpRequest.prototype.open = function(method, url) {
                capture(url);
                return origOpen.apply(this, arguments);
            };
            setTimeout(function() {
                var els = document.querySelectorAll('video, button, [class*="play"], [aria-label*="lay"]');
                for (var i = 0; i < els.length; i++) {
                    try { els[i].click(); } catch(e) {}
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

        Log.d(TAG, "Starting extraction for: $videoPageUrl")

        Handler(Looper.getMainLooper()).post {
            val webView = WebView(context)
            webView.settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                mediaPlaybackRequiresUserGesture = false
                userAgentString = USER_AGENT
            }

            webView.addJavascriptInterface(
                object {
                    @JavascriptInterface
                    fun onHlsUrl(url: String) {
                        Log.d(TAG, "JS callback: HLS URL = $url")
                        if (hlsUrl.isNotEmpty()) return
                        hlsUrl = if (url.startsWith("/")) "https://hanime.tv$url" else url
                        cookies = CookieManager.getInstance().getCookie("https://hanime.tv") ?: ""
                        Handler(Looper.getMainLooper()).post { webView.destroy() }
                        latch.countDown()
                    }
                },
                "AndroidHls",
            )

            webView.webViewClient = object : WebViewClient() {
                override fun onReceivedSslError(
                    view: WebView,
                    handler: SslErrorHandler,
                    error: android.net.http.SslError,
                ) {
                    Log.d(TAG, "SSL error (proceeding): ${error.primaryError}")
                    handler.proceed()
                }

                override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                    Log.d(TAG, "onPageStarted: $url")
                    // Inject early — before the page's own scripts execute
                    view.evaluateJavascript(CAPTURE_SCRIPT, null)
                }

                override fun onPageFinished(view: WebView, url: String) {
                    Log.d(TAG, "onPageFinished: $url")
                    // Re-inject in case the page reset our overrides during hydration
                    view.evaluateJavascript(CAPTURE_SCRIPT, null)
                    pollForHlsUrl(view, attempt = 0, maxAttempts = 45) { capturedUrl, jar ->
                        Log.d(TAG, "Poll captured: $capturedUrl")
                        hlsUrl = capturedUrl
                        cookies = jar
                        webView.destroy()
                        latch.countDown()
                    }
                }

                override fun shouldInterceptRequest(
                    view: WebView,
                    request: WebResourceRequest,
                ): WebResourceResponse? {
                    val url = request.url.toString()
                    // Log all URLs for diagnostics — filter by /hls/ to avoid spam
                    if (url.contains("/hls/") || url.contains("m3u8")) {
                        Log.d(TAG, "shouldInterceptRequest: $url")
                    }
                    if (hlsUrl.isEmpty() && url.contains("/hls/")) {
                        val full = if (url.startsWith("/")) "https://hanime.tv$url" else url
                        Log.d(TAG, "shouldInterceptRequest captured HLS: $full")
                        hlsUrl = full
                        cookies = CookieManager.getInstance().getCookie("https://hanime.tv") ?: ""
                        Handler(Looper.getMainLooper()).post { webView.destroy() }
                        latch.countDown()
                    }
                    return null
                }
            }

            Log.d(TAG, "Loading WebView URL")
            webView.loadUrl(videoPageUrl)
        }

        val completed = latch.await(60, TimeUnit.SECONDS)
        Log.d(TAG, "Latch done — completed=$completed hlsUrl=$hlsUrl")
        return if (hlsUrl.isNotEmpty()) HlsResult(hlsUrl, cookies) else null
    }

    private fun pollForHlsUrl(
        view: WebView,
        attempt: Int,
        maxAttempts: Int,
        onResult: (String, String) -> Unit,
    ) {
        if (attempt >= maxAttempts) {
            Log.d(TAG, "Poll exhausted after $attempt attempts")
            return
        }

        view.evaluateJavascript("window.__hls_captured || ''") { value ->
            val url = value?.trim('"') ?: ""
            Log.d(TAG, "Poll attempt $attempt: captured='$url'")
            if (url.isNotEmpty() && url != "null" && url != "undefined") {
                val full = if (url.startsWith("/")) "https://hanime.tv$url" else url
                val jar = CookieManager.getInstance().getCookie("https://hanime.tv") ?: ""
                onResult(full, jar)
            } else {
                // Re-inject on every poll in case the Astro hydration reset our overrides
                view.evaluateJavascript(CAPTURE_SCRIPT, null)
                Handler(Looper.getMainLooper()).postDelayed(
                    { pollForHlsUrl(view, attempt + 1, maxAttempts, onResult) },
                    1000,
                )
            }
        }
    }
}
