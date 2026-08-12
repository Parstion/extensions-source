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
import okhttp3.OkHttpClient
import okhttp3.Request
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

data class HlsResult(
    val url: String,
    val cookies: String,
)

object WebViewExtractor {

    private const val TAG = "HanimeWV"
    private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
        "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    // Trust-all OkHttp client used to fetch the video page HTML so we can
    // inject our monitoring script before any of the page's own JS runs.
    private val HTTP_CLIENT: OkHttpClient by lazy {
        val trustAll = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        }
        val ssl = SSLContext.getInstance("TLS").also {
            it.init(null, arrayOf(trustAll), SecureRandom())
        }
        OkHttpClient.Builder()
            .sslSocketFactory(ssl.socketFactory, trustAll)
            .hostnameVerifier { _, _ -> true }
            .build()
    }

    // Injected into <head> BEFORE any page scripts.
    // Since Astro bundles capture `window.fetch` as a local const at module parse
    // time, overriding `window.fetch` in onPageFinished is too late — the bundle
    // has already captured the original reference. By prepending this script into
    // the raw HTML, our override is in place before any module executes.
    private val CAPTURE_SCRIPT = """
        (function() {
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
            var OrigXHR = window.XMLHttpRequest;
            window.XMLHttpRequest = function() {
                var xhr = new OrigXHR();
                var origOpen = xhr.open.bind(xhr);
                xhr.open = function(m, url) {
                    capture(url);
                    return origOpen.apply(xhr, arguments);
                };
                return xhr;
            };
            window.XMLHttpRequest.prototype = OrigXHR.prototype;
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

                override fun shouldInterceptRequest(
                    view: WebView,
                    request: WebResourceRequest,
                ): WebResourceResponse? {
                    val url = request.url.toString()

                    // Catch HLS URL via shouldInterceptRequest (native <video> element)
                    if (hlsUrl.isEmpty() && url.contains("/hls/")) {
                        val full = if (url.startsWith("/")) "https://hanime.tv$url" else url
                        Log.d(TAG, "shouldInterceptRequest caught HLS: $full")
                        hlsUrl = full
                        cookies = CookieManager.getInstance().getCookie("https://hanime.tv") ?: ""
                        Handler(Looper.getMainLooper()).post { webView.destroy() }
                        latch.countDown()
                        return null
                    }

                    // Intercept the main video page HTML and inject our script before
                    // any of the page's own JS bundles can capture window.fetch.
                    if (url.contains("/videos/hentai/") && url.contains("hanime.tv")) {
                        Log.d(TAG, "Intercepting main page HTML for injection: $url")
                        val jar = CookieManager.getInstance().getCookie("https://hanime.tv") ?: ""
                        return try {
                            val response = HTTP_CLIENT.newCall(
                                Request.Builder()
                                    .url(url)
                                    .addHeader("User-Agent", USER_AGENT)
                                    .addHeader("Referer", "https://hanime.tv/")
                                    .apply {
                                        if (jar.isNotEmpty()) addHeader("Cookie", jar)
                                    }
                                    .build(),
                            ).execute()

                            val contentType = response.header("Content-Type") ?: "text/html"
                            val html = response.body.string()
                            Log.d(TAG, "Fetched HTML (${html.length} chars), injecting script")

                            // Prepend our script as the very first thing inside <head>
                            val injected = html.replaceFirst(
                                Regex("(?i)<head[^>]*>"),
                                { match -> match.value + "\n<script>\n$CAPTURE_SCRIPT\n</script>" },
                            )

                            WebResourceResponse(
                                "text/html",
                                "UTF-8",
                                injected.byteInputStream(Charsets.UTF_8),
                            )
                        } catch (e: Exception) {
                            Log.e(TAG, "HTML injection failed: ${e.message} — falling back")
                            null
                        }
                    }

                    return null
                }

                override fun onPageFinished(view: WebView, url: String) {
                    Log.d(TAG, "onPageFinished: $url")
                    // Re-inject as fallback (if HTML injection was bypassed or failed)
                    view.evaluateJavascript(CAPTURE_SCRIPT, null)
                    // Attempt to trigger player start
                    view.evaluateJavascript(
                        """
                        setTimeout(function() {
                            var els = document.querySelectorAll('video, button, [class*="play"], [aria-label]');
                            for (var i = 0; i < els.length; i++) {
                                try { els[i].click(); } catch(e) {}
                            }
                        }, 2000);
                        """.trimIndent(),
                        null,
                    )
                    pollForHlsUrl(view, attempt = 0, maxAttempts = 45) { capturedUrl, jar ->
                        Log.d(TAG, "Poll captured: $capturedUrl")
                        hlsUrl = capturedUrl
                        cookies = jar
                        webView.destroy()
                        latch.countDown()
                    }
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

        // Multi-source check: our capture var, video element src, and window.store
        val pollScript = """
            (function() {
                if (window.__hls_captured) return window.__hls_captured;
                var v = document.querySelector('video');
                if (v) {
                    if (v.src && v.src.indexOf('/hls/') !== -1) return v.src;
                    if (v.currentSrc && v.currentSrc.indexOf('/hls/') !== -1) return v.currentSrc;
                }
                return '';
            })()
        """.trimIndent()

        view.evaluateJavascript(pollScript) { value ->
            val url = value?.trim('"') ?: ""
            if (attempt % 5 == 0) Log.d(TAG, "Poll attempt $attempt: '$url'")
            if (url.isNotEmpty() && url != "null" && url != "undefined") {
                val full = if (url.startsWith("/")) "https://hanime.tv$url" else url
                val jar = CookieManager.getInstance().getCookie("https://hanime.tv") ?: ""
                onResult(full, jar)
            } else {
                Handler(Looper.getMainLooper()).postDelayed(
                    { pollForHlsUrl(view, attempt + 1, maxAttempts, onResult) },
                    1000,
                )
            }
        }
    }
}
