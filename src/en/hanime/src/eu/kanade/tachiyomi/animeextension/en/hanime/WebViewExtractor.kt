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

    // Trust-all OkHttp client used only to fetch the video page HTML for injection.
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

    // Injected before any page scripts to capture the fetch/XHR reference early.
    // The HLS URL is triggered by a quality change, not on page load — so this
    // just sets up the interception; the actual trigger comes from our click chain.
    private val CAPTURE_SCRIPT = """
        (function() {
            if (window.__hls_installed) return;
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
            window.fetch = function() { capture(arguments[0]); return origFetch.apply(this, arguments); };
            var OrigXHR = window.XMLHttpRequest;
            window.XMLHttpRequest = function() {
                var xhr = new OrigXHR();
                var origOpen = xhr.open.bind(xhr);
                xhr.open = function(m, url) { capture(url); return origOpen.apply(xhr, arguments); };
                return xhr;
            };
            window.XMLHttpRequest.prototype = OrigXHR.prototype;
        })();
    """.trimIndent()

    // The 4-step click chain needed to trigger the HLS URL:
    // 1. "Continue to Video" (dismiss ad interstitial)
    // 2. Play button (player does not autoplay)
    // 3. Quality selector button (opens quality popover)
    // 4. Quality option (triggers HLS URL request)
    // Delays are tuned to allow each step to complete before the next.
    private val CLICK_CHAIN_SCRIPT = """
        (function() {
            function clickIf(selector) {
                var el = document.querySelector(selector);
                if (el) { el.click(); return true; }
                return false;
            }
            function clickByText(text) {
                var all = document.querySelectorAll('button, a, div, span');
                for (var i = 0; i < all.length; i++) {
                    if (all[i].textContent.trim().indexOf(text) !== -1) {
                        all[i].click(); return true;
                    }
                }
                return false;
            }
            // Step 1: Dismiss ad interstitial (T+4s)
            setTimeout(function() {
                var r1 = clickByText('Continue to Video') || clickByText('Continue') || clickByText('Skip');
                clickIf('[class*="continue"]') || clickIf('[class*="skip"]');
                AndroidHls.log('Step1 continue clicked=' + r1);
            }, 4000);
            // Step 2: Click play button (T+8s)
            setTimeout(function() {
                var r2 = clickIf('button[aria-label*="Play"]') ||
                clickIf('button[aria-label*="play"]') ||
                clickIf('[class*="play-btn"]') ||
                clickIf('[class*="PlayButton"]') ||
                clickIf('video');
                AndroidHls.log('Step2 play clicked=' + r2);
            }, 8000);
            // Step 3: Open quality selector (T+12s)
            setTimeout(function() {
                var r3 = clickIf('button[aria-controls="HTVPlayerQualityPopover"]') ||
                clickIf('[aria-label="Stream quality"]') ||
                clickIf('[class*="quality"]');
                AndroidHls.log('Step3 quality btn clicked=' + r3);
            }, 12000);
            // Step 4: Click quality option (T+13.5s)
            setTimeout(function() {
                var popover = document.getElementById('HTVPlayerQualityPopover');
                var clicked = false;
                AndroidHls.log('Step4 popover=' + !!popover);
                if (popover) {
                    var opts = popover.querySelectorAll('button, li, [role="option"], [class*="quality"]');
                    for (var i = 0; i < opts.length; i++) {
                        var t = opts[i].textContent.trim();
                        if (t.indexOf('720') !== -1 || t.indexOf('480') !== -1) {
                            opts[i].click(); clicked = true; break;
                        }
                    }
                    if (!clicked && opts.length > 0) { opts[0].click(); clicked = true; }
                } else {
                    clicked = clickByText('720p') || clickByText('480p') || clickByText('360p');
                }
                AndroidHls.log('Step4 quality option clicked=' + clicked);
            }, 13500);
        })();
    """.trimIndent()

    @SuppressLint("SetJavaScriptEnabled", "AddJavascriptInterface")
    fun extractHlsUrl(videoPageUrl: String): HlsResult? {
        val latch = CountDownLatch(1)
        var hlsUrl = ""
        var cookies = ""
        val context = Injekt.get<Application>()

        Log.d(TAG, "Starting extraction: $videoPageUrl")

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
                        Log.d(TAG, "JS callback: $url")
                        if (hlsUrl.isNotEmpty()) return
                        hlsUrl = if (url.startsWith("/")) "https://hanime.tv$url" else url
                        cookies = CookieManager.getInstance().getCookie("https://hanime.tv") ?: ""
                        Handler(Looper.getMainLooper()).post { webView.destroy() }
                        latch.countDown()
                    }

                    @JavascriptInterface
                    fun log(msg: String) {
                        Log.d(TAG, "JS: $msg")
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
                    handler.proceed()
                }

                // Intercept the main page HTML to inject CAPTURE_SCRIPT before any
                // page JS captures window.fetch as a local module reference.
                override fun shouldInterceptRequest(
                    view: WebView,
                    request: WebResourceRequest,
                ): WebResourceResponse? {
                    val url = request.url.toString()

                    // Log ALL CDN requests to see what's being requested vs proxied
                    if (url.contains("hanime-cdn.com")) {
                        val isProxied = url.endsWith(".js") || url.endsWith(".mjs") ||
                            url.endsWith(".wasm") || url.contains("/vhtv")
                        Log.d(TAG, "CDN: $url [proxied=$isProxied]")
                    }

                    // Proxy hanime-cdn.com JS/WASM requests with proper Referer so the
                    // Astro islands (including HTVPlayer) can hydrate. Without this,
                    // the JS bundles fail to load and the player never mounts.
                    if (url.contains("hanime-cdn.com") &&
                        (url.endsWith(".js") || url.endsWith(".mjs") || url.endsWith(".wasm") || url.contains("/vhtv"))
                    ) {
                        Log.d(TAG, "Proxying CDN resource: $url")
                        return try {
                            val cdnResp = HTTP_CLIENT.newCall(
                                Request.Builder()
                                    .url(url)
                                    .addHeader("User-Agent", USER_AGENT)
                                    .addHeader("Referer", "https://hanime.tv/")
                                    .addHeader("Origin", "https://hanime.tv")
                                    .build(),
                            ).execute()
                            val contentType = cdnResp.header("Content-Type")
                                ?: if (url.endsWith(".wasm")) "application/wasm" else "application/javascript"
                            val bytes = cdnResp.body.bytes()
                            Log.d(TAG, "CDN resource fetched: ${bytes.size} bytes")
                            WebResourceResponse(
                                contentType.split(";")[0].trim(),
                                "UTF-8",
                                bytes.inputStream(),
                            )
                        } catch (e: Exception) {
                            Log.e(TAG, "CDN proxy failed: ${e.message}")
                            null
                        }
                    }

                    // Catch HLS URL if it comes through as a native <video> src request
                    if (hlsUrl.isEmpty() && url.contains("/hls/") && url.contains("hanime")) {
                        Log.d(TAG, "shouldInterceptRequest HLS: $url")
                        hlsUrl = if (url.startsWith("/")) "https://hanime.tv$url" else url
                        cookies = CookieManager.getInstance().getCookie("https://hanime.tv") ?: ""
                        Handler(Looper.getMainLooper()).post { webView.destroy() }
                        latch.countDown()
                        return null
                    }

                    // Inject into main video page HTML
                    if (url.contains("hanime.tv/videos/hentai/")) {
                        Log.d(TAG, "Injecting into main page HTML: $url")
                        val jar = CookieManager.getInstance().getCookie("https://hanime.tv") ?: ""
                        return try {
                            val resp = HTTP_CLIENT.newCall(
                                Request.Builder()
                                    .url(url)
                                    .addHeader("User-Agent", USER_AGENT)
                                    .addHeader("Referer", "https://hanime.tv/")
                                    .apply { if (jar.isNotEmpty()) addHeader("Cookie", jar) }
                                    .build(),
                            ).execute()
                            val html = resp.body.string()
                            Log.d(TAG, "Got HTML: ${html.length} chars")
                            Log.d(TAG, "HTML head: ${html.substring(0, minOf(2000, html.length))}")
                            val headTag = Regex("(?i)<head[^>]*>").find(html)
                            val injected = if (headTag != null) {
                                buildString {
                                    append(html.substring(0, headTag.range.last + 1))
                                    append("\n<script>\n")
                                    append(CAPTURE_SCRIPT)
                                    append("\n</script>")
                                    append(html.substring(headTag.range.last + 1))
                                }
                            } else {
                                html
                            }
                            WebResourceResponse("text/html", "UTF-8", injected.byteInputStream(Charsets.UTF_8))
                        } catch (e: Exception) {
                            Log.e(TAG, "HTML injection failed: ${e.message}")
                            null
                        }
                    }

                    return null
                }

                override fun onPageFinished(view: WebView, url: String) {
                    Log.d(TAG, "onPageFinished: $url")
                    // Dump DOM state so we can see what the page looks like
                    view.evaluateJavascript(
                        """
                        (function() {
                            var btns = Array.from(document.querySelectorAll('button'))
                                .map(function(b) { return (b.textContent.trim().substring(0,30) + '|' + b.className.substring(0,40) + '|' + (b.getAttribute('aria-controls')||'')); });
                            return JSON.stringify({
                                title: document.title,
                                url: location.href,
                                bodyLen: document.body ? document.body.innerHTML.length : 0,
                                hasVideo: !!document.querySelector('video'),
                                hasQualityBtn: !!document.querySelector('button[aria-controls="HTVPlayerQualityPopover"]'),
                                hlsInstalled: !!window.__hls_installed,
                                buttons: btns.slice(0, 20)
                            });
                        })()
                        """.trimIndent(),
                    ) { result -> Log.d(TAG, "DOM state: $result") }

                    // Re-inject capture script + start click chain with generous delays
                    view.evaluateJavascript(CAPTURE_SCRIPT, null)
                    view.evaluateJavascript(CLICK_CHAIN_SCRIPT, null)
                    pollForHlsUrl(view, attempt = 0, maxAttempts = 50)
                }
            }

            Log.d(TAG, "Loading WebView")
            webView.loadUrl(videoPageUrl)
        }

        val completed = latch.await(70, TimeUnit.SECONDS)
        Log.d(TAG, "Done — completed=$completed url=$hlsUrl")
        return if (hlsUrl.isNotEmpty()) HlsResult(hlsUrl, cookies) else null
    }

    private fun pollForHlsUrl(view: WebView, attempt: Int, maxAttempts: Int) {
        if (attempt >= maxAttempts) {
            Log.d(TAG, "Poll exhausted")
            return
        }
        val script = """
            (function() {
                if (window.__hls_captured) return window.__hls_captured;
                var v = document.querySelector('video');
                if (v && v.src && v.src.indexOf('/hls/') !== -1) return v.src;
                if (v && v.currentSrc && v.currentSrc.indexOf('/hls/') !== -1) return v.currentSrc;
                return '';
            })()
        """.trimIndent()
        view.evaluateJavascript(script) { value ->
            val url = value?.trim('"') ?: ""
            if (attempt % 5 == 0) Log.d(TAG, "Poll $attempt: '$url'")
            // Extra DOM check at T+15s to see if player hydrated after onPageFinished
            if (attempt == 15) {
                view.evaluateJavascript(
                    """
                    (function() {
                        var btns = Array.from(document.querySelectorAll('button'))
                            .map(function(b) { return b.textContent.trim().substring(0,30)+'|'+(b.getAttribute('aria-controls')||''); });
                        return JSON.stringify({
                            hasVideo: !!document.querySelector('video'),
                            hasQualityBtn: !!document.querySelector('button[aria-controls="HTVPlayerQualityPopover"]'),
                            buttons: btns.slice(0, 15)
                        });
                    })()
                    """.trimIndent(),
                ) { r -> Log.d(TAG, "DOM at T+15s: $r") }
            }
            if (url.isNotEmpty() && url != "null" && url != "undefined") {
                val full = if (url.startsWith("/")) "https://hanime.tv$url" else url
                Log.d(TAG, "Poll captured: $full")
                // Notify via the same path as the JS callback
                Handler(Looper.getMainLooper()).post {
                    view.evaluateJavascript("AndroidHls.onHlsUrl('$full')", null)
                }
            } else {
                Handler(Looper.getMainLooper()).postDelayed(
                    { pollForHlsUrl(view, attempt + 1, maxAttempts) },
                    1000,
                )
            }
        }
    }
}
