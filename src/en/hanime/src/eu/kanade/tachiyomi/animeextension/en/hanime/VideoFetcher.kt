package eu.kanade.tachiyomi.animeextension.en.hanime

import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.util.parseAs
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request

object VideoFetcher {

    // Stream segments are served from player.hanime.tv context — Referer must match.
    private fun streamHeaders(): Headers = Headers.Builder()
        .add("Origin", "https://player.hanime.tv")
        .add("Referer", "https://player.hanime.tv/")
        .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
        .add("Accept", "video/webm,video/ogg,video/*;q=0.9,application/ogg;q=0.7,audio/*;q=0.6,*/*;q=0.5")
        .build()

    fun fetchVideoListGuest(
        client: OkHttpClient,
        signature: String,
        timestamp: Long,
        videoId: String,
    ): List<Video> {
        val manifestHeaders = Headers.Builder()
            .add("Accept", "application/json, text/plain, */*")
            .add("Accept-Language", "en-US,en;q=0.9")
            .add("Origin", "https://hanime.tv")
            .add("Referer", "https://hanime.tv/")
            .add("Sec-Fetch-Dest", "empty")
            .add("Sec-Fetch-Mode", "cors")
            .add("Sec-Fetch-Site", "cross-site")
            .add("x-signature", signature)
            .add("x-signature-version", "web2")
            .add("x-time", timestamp.toString())
            .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
            .build()

        val request = Request.Builder()
            .url("https://cached.freeanimehentai.net/api/v8/guest/videos/$videoId/manifest")
            .headers(manifestHeaders)
            .get()
            .build()

        return try {
            val response = client.newCall(request).execute()
            val responseString = response.body.string()

            if (responseString.isBlank()) return emptyList()

            val vHeaders = streamHeaders()
            responseString.parseAs<VideoModel>().videosManifest?.servers
                ?.flatMap { server ->
                    server.streams
                        .filter { it.isGuestAllowed == true }
                        .map { stream ->
                            Video(
                                stream.url,
                                "${server.name ?: "Server"} - ${stream.height}p",
                                stream.url,
                                headers = vHeaders,
                            )
                        }
                }?.distinctBy { it.url } ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun fetchVideoListPremium(
        client: OkHttpClient,
        authCookie: String,
        sessionToken: String,
        userLicense: String,
        signature: String,
        timestamp: Long,
        videoId: String,
    ): List<Video> {
        val manifestHeaders = Headers.Builder()
            .add("Accept", "application/json, text/plain, */*")
            .add("Accept-Language", "en-US,en;q=0.9")
            .add("Origin", "https://hanime.tv")
            .add("Referer", "https://hanime.tv/")
            .add("Sec-Fetch-Dest", "empty")
            .add("Sec-Fetch-Mode", "cors")
            .add("Sec-Fetch-Site", "cross-site")
            .add("Cookie", authCookie)
            .add("x-session-token", sessionToken)
            .add("x-signature", signature)
            .add("x-signature-version", "web2")
            .add("x-time", timestamp.toString())
            .add("x-user-license", userLicense)
            .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
            .build()

        val request = Request.Builder()
            .url("https://h.freeanimehentai.net/api/v8/member/videos/$videoId/manifest")
            .headers(manifestHeaders)
            .get()
            .build()

        return try {
            val response = client.newCall(request).execute()
            val responseString = response.body.string()

            if (responseString.isBlank()) return emptyList()

            val vHeaders = streamHeaders()
            responseString.parseAs<VideoModel>().videosManifest?.servers
                ?.flatMap { server ->
                    server.streams.map { stream ->
                        Video(
                            stream.url,
                            "Premium - ${server.name ?: "Server"} - ${stream.height}p",
                            stream.url,
                            headers = vHeaders,
                        )
                    }
                }?.distinctBy { it.url } ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }
}
