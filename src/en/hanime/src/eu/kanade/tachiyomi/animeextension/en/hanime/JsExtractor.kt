package eu.kanade.tachiyomi.animeextension.en.hanime

import okhttp3.OkHttpClient
import okhttp3.Request

object JsExtractor {
    // window.ssignature and window.stime are plain global variables injected
    // directly into the page HTML — no need to fetch any external JS files.
    fun extractSignatureAndTime(pageUrl: String, client: OkHttpClient): Pair<String, Long> {
        return try {
            val request = Request.Builder()
                .url(pageUrl)
                .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                .addHeader("Referer", "https://hanime.tv/")
                .build()

            val html = client.newCall(request).execute().body.string()

            val signature = Regex("""window\.ssignature\s*=\s*["']([^"']+)["']""")
                .find(html)?.groupValues?.get(1) ?: ""

            val time = Regex("""window\.stime\s*=\s*(\d+)""")
                .find(html)?.groupValues?.get(1)?.toLongOrNull() ?: 0L

            Pair(signature, time)
        } catch (e: Exception) {
            Pair("", 0L)
        }
    }
}
