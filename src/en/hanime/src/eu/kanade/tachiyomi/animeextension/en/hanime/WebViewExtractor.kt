package eu.kanade.tachiyomi.animeextension.en.hanime

import android.annotation.SuppressLint
import android.app.Application
import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import android.webkit.WebViewClient
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

object WebViewExtractor {

    // window.ssignature and window.stime are set by the vhtv2 JS bundle after
    // execution — they are not in the raw HTML. We must load the page in a real
    // WebView and let the JS run before we can extract them.
    @SuppressLint("SetJavaScriptEnabled")
    fun extractSignatureAndTime(url: String): Pair<String, Long> {
        val latch = CountDownLatch(1)
        var signature = ""
        var time = 0L
        val context = Injekt.get<Application>()

        Handler(Looper.getMainLooper()).post {
            val webView = WebView(context)
            webView.settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                userAgentString = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                    "AppleWebKit/537.36 (KHTML, like Gecko) " +
                    "Chrome/120.0.0.0 Safari/537.36"
            }

            webView.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String) {
                    // Poll for the signature every second — the JS bundle may
                    // take a moment to execute after the DOM finishes loading.
                    pollForSignature(view, attempt = 0, maxAttempts = 15) { sig, ts ->
                        signature = sig
                        time = ts
                        webView.destroy()
                        latch.countDown()
                    }
                }
            }

            webView.loadUrl(url)
        }

        // Wait up to 30 seconds total
        latch.await(30, TimeUnit.SECONDS)
        return Pair(signature, time)
    }

    private fun pollForSignature(
        view: WebView,
        attempt: Int,
        maxAttempts: Int,
        onResult: (String, Long) -> Unit,
    ) {
        if (attempt >= maxAttempts) {
            onResult("", 0L)
            return
        }

        view.evaluateJavascript("(function(){ return window.ssignature || ''; })()") { sigValue ->
            val sig = sigValue?.trim('"') ?: ""
            if (sig.isNotEmpty() && sig != "null" && sig != "undefined") {
                view.evaluateJavascript("(function(){ return window.stime ? window.stime.toString() : '0'; })()") { timeValue ->
                    val ts = timeValue?.trim('"')?.toLongOrNull() ?: 0L
                    onResult(sig, ts)
                }
            } else {
                // Signature not ready yet — retry after 1 second
                Handler(Looper.getMainLooper()).postDelayed(
                    { pollForSignature(view, attempt + 1, maxAttempts, onResult) },
                    1000,
                )
            }
        }
    }
}
