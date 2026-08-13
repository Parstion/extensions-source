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

    // 4-step click chain to trigger the HLS URL:
    // 1. Dismiss ad interstitial ("Continue to Video")
    // 2. Click play button
    // 3. Click quality selector button
    // 4. Click a quality option → player sets <video>.src to HLS URL
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
            setTimeout(function() {
                var r = clickByText('Continue to Video') || clickByText('Continue') || clickByText('Skip');
                clickIf('[class*="continue"]') || clickIf('[class*="skip"]');
                AndroidHls.log('Step1 continue=' + r);
            }, 4000);
            setTimeout(function() {
                var r = clickIf('button[aria-label*="Play"]') || clickIf('button[aria-label*="play"]') ||
                    clickIf('[class*="play-btn"]') || clickIf('[class*="PlayButton"]') || clickIf('video');
                AndroidHls.log('Step2 play=' + r);
            }, 8000);
            setTimeout(function() {
                var r = clickIf('button[aria-controls="HTVPlayerQualityPopover"]') ||
                    clickIf('[aria-label="Stream quality"]') || clickIf('[class*="quality"]');
                AndroidHls.log('Step3 qualityBtn=' + r);
            }, 12000);
            setTimeout(function() {
                var popover = document.getElementById('HTVPlayerQualityPopover');
                var clicked = false;
                AndroidHls.log('Step4 popover=' + !!popover);
                if (popover) {
                    var opts = popover.querySelectorAll('button, li, [role="option"]');
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
                AndroidHls.log('Step4 qualityOpt=' + clicked);
            }, 13500);
        })();
    """.trimIndent()

    @SuppressLint("SetJavaScriptEnabled", "AddJavascriptInterface")
    fun extractHlsUrl(videoPageUrl: String): HlsResult? {
        val latch = CountDownLatch(1)
        var hlsUrl = ""
        var cookies = ""
        val context = Injekt.get<Application>()

        Log.d(TAG, "Starting: $videoPageUrl")

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
                        Log.d(TAG, "HLS captured: $url")
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

                override fun shouldInterceptRequest(
                    view: WebView,
                    request: WebResourceRequest,
                ): WebResourceResponse? {
                    val url = request.url.toString()
                    // Log CDN requests for diagnostics
                    if (url.contains("hanime-cdn.com") && !url.contains("/images/") && !url.contains("/fonts/")) {
                        Log.d(TAG, "CDN non-image: $url")
                    }
                    // Catch HLS URL from native <video>.src change (triggered by quality change)
                    if (hlsUrl.isEmpty() && url.contains("/hls/") && url.contains("hanime")) {
                        Log.d(TAG, "HLS intercepted: $url")
                        hlsUrl = if (url.startsWith("/")) "https://hanime.tv$url" else url
                        cookies = CookieManager.getInstance().getCookie("https://hanime.tv") ?: ""
                        Handler(Looper.getMainLooper()).post { webView.destroy() }
                        latch.countDown()
                    }
                    return null
                }

                override fun onPageFinished(view: WebView, url: String) {
                    Log.d(TAG, "onPageFinished: $url")
                    // Check DOM and Astro island state
                    view.evaluateJavascript(
                        """
                        (function() {
                            var islands = document.querySelectorAll('astro-island');
                            var btns = Array.from(document.querySelectorAll('button'))
                                .map(function(b) { return b.textContent.trim().substring(0,30) + '|' + (b.getAttribute('aria-controls')||''); });
                            return JSON.stringify({
                                hasVideo: !!document.querySelector('video'),
                                hasQualityBtn: !!document.querySelector('button[aria-controls="HTVPlayerQualityPopover"]'),
                                astroIslands: islands.length,
                                islandUrls: Array.from(islands).map(function(i) { return i.getAttribute('component-url') || '?'; }),
                                buttons: btns.slice(0, 10)
                            });
                        })()
                        """.trimIndent(),
                    ) { r -> Log.d(TAG, "DOM: $r") }
                    // Monitor ALL JavaScript-initiated fetch/XHR to see what's being requested
                    view.evaluateJavascript(
                        """
                        (function() {
                            if (window.__monitor_installed) return;
                            window.__monitor_installed = true;
                            var origFetch = window.fetch;
                            window.fetch = function() {
                                var u = (typeof arguments[0] === 'string') ? arguments[0] : ((arguments[0] && arguments[0].url) || '');
                                if (u) AndroidHls.log('fetch:' + u.substring(0, 120));
                                if (u && u.indexOf('/hls/') !== -1) { window.__hls_captured = u; try { AndroidHls.onHlsUrl(u); } catch(e) {} }
                                return origFetch.apply(this, arguments);
                            };
                            var origOpen = XMLHttpRequest.prototype.open;
                            XMLHttpRequest.prototype.open = function(m, url) {
                                if (url) AndroidHls.log('xhr:' + url.toString().substring(0, 120));
                                return origOpen.apply(this, arguments);
                            };
                        })();
                        """.trimIndent(),
                        null,
                    )
                    view.evaluateJavascript(CLICK_CHAIN_SCRIPT, null)
                    pollForHlsUrl(view, attempt = 0, maxAttempts = 55)
                }
            }

            Log.d(TAG, "Loading WebView")
            webView.loadUrl(videoPageUrl)
        }

        val completed = latch.await(75, TimeUnit.SECONDS)
        Log.d(TAG, "Done completed=$completed url=$hlsUrl")
        return if (hlsUrl.isNotEmpty()) HlsResult(hlsUrl, cookies) else null
    }

    private fun pollForHlsUrl(view: WebView, attempt: Int, maxAttempts: Int) {
        if (attempt >= maxAttempts) {
            Log.d(TAG, "Poll exhausted")
            return
        }
        val script = """
            (function() {
                var v = document.querySelector('video');
                if (v && v.src && v.src.indexOf('/hls/') !== -1) return v.src;
                if (v && v.currentSrc && v.currentSrc.indexOf('/hls/') !== -1) return v.currentSrc;
                return '';
            })()
        """.trimIndent()
        view.evaluateJavascript(script) { value ->
            val url = value?.trim('"') ?: ""
            if (attempt % 5 == 0) {
                // Check DOM state every 5 seconds
                view.evaluateJavascript(
                    "(function(){ return JSON.stringify({ hasVideo: !!document.querySelector('video'), hasQualityBtn: !!document.querySelector('button[aria-controls=\"HTVPlayerQualityPopover\"]') }); })()",
                ) { r -> Log.d(TAG, "Poll $attempt DOM: $r") }
            }
            if (url.isNotEmpty() && url != "null" && url != "undefined") {
                val full = if (url.startsWith("/")) "https://hanime.tv$url" else url
                Log.d(TAG, "Poll HLS: $full")
                Handler(Looper.getMainLooper()).post {
                    view.evaluateJavascript("try{AndroidHls.onHlsUrl('$full')}catch(e){}", null)
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
