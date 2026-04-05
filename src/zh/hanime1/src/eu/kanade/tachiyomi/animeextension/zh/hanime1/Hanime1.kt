package eu.kanade.tachiyomi.animeextension.zh.hanime1

import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response

class Hanime1 : AnimeHttpSource() {

    override val name = "Hanime1"
    override val baseUrl = "https://hanime1.me"
    override val lang = "zh"
    override val supportsLatest = true

    // Covers and stream segments are served from vdownload.hembed.com
    // and only require Referer + a real User-Agent to load correctly.
    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .add(
            "User-Agent",
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
        )
        .add("Referer", "$baseUrl/")

    private fun videoHeaders(): Headers = headersBuilder()
        .add("Origin", baseUrl)
        .build()

    // ===== Popular =====

    override fun popularAnimeRequest(page: Int): Request =
        GET("$baseUrl/search?sort=views&page=$page", headers)

    override fun popularAnimeParse(response: Response): AnimesPage =
        parseSearchPage(response)

    // ===== Latest =====

    override fun latestUpdatesRequest(page: Int): Request =
        GET("$baseUrl/search?page=$page", headers)

    override fun latestUpdatesParse(response: Response): AnimesPage =
        parseSearchPage(response)

    // ===== Search =====

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val url = "$baseUrl/search".toHttpUrl().newBuilder().apply {
            if (query.isNotBlank()) addQueryParameter("query", query)
            addQueryParameter("page", page.toString())
            filters.forEach { filter ->
                when (filter) {
                    is GenreFilter -> {
                        if (filter.state != 0) {
                            addQueryParameter("genre", GENRES[filter.state])
                        }
                    }
                    is SortFilter -> {
                        if (filter.state != 0) {
                            addQueryParameter("sort", SORT_VALUES[filter.state])
                        }
                    }
                    else -> {}
                }
            }
        }.build()
        return GET(url.toString(), headers)
    }

    override fun searchAnimeParse(response: Response): AnimesPage =
        parseSearchPage(response)

    private fun parseSearchPage(response: Response): AnimesPage {
        val doc = response.asJsoup()
        val animes = doc.select("div.video-item-container").map { el ->
            SAnime.create().apply {
                val link = el.selectFirst("a.video-link")!!
                setUrlWithoutDomain(link.attr("href"))
                title = el.selectFirst("div.title")!!.text().trim()
                thumbnail_url = el.selectFirst("img.main-thumb")?.attr("src")
                author = el.selectFirst("div.subtitle a")?.text()
                    ?.substringBefore("•")?.trim()
                status = SAnime.UNKNOWN
            }
        }
        val hasNextPage = doc.selectFirst("a[rel=next]") != null
        return AnimesPage(animes, hasNextPage)
    }

    // ===== Anime Details =====

    override fun animeDetailsParse(response: Response): SAnime {
        val doc = response.asJsoup()
        return SAnime.create().apply {
            title = doc.selectFirst("h3#shareBtn-title")?.text()?.trim() ?: ""
            // Cover is in og:image meta — higher res than the thumbnail
            thumbnail_url = doc.selectFirst("meta[property=og:image]")?.attr("content")
            author = doc.selectFirst("a#video-artist-name")?.text()?.trim()
            description = doc.selectFirst("div.video-caption-text")?.text()?.trim()
            // Tags: filter out the add/remove icon buttons (they have no meaningful text)
            genre = doc.select("div.single-video-tag a[href]")
                .mapNotNull { tag ->
                    // Remove the # prefix, count suffix (N), and whitespace
                    tag.text()
                        .replace(Regex("^#\\s*"), "")
                        .replace(Regex("\\s*\\(\\d+\\)$"), "")
                        .trim()
                        .takeIf { it.isNotEmpty() }
                }
                .joinToString()
            status = SAnime.COMPLETED
            initialized = true
        }
    }

    // ===== Episode List =====
    // Each video on hanime1.me is standalone — there is no episode series structure.
    // We return a single episode pointing back to the watch page.

    override fun episodeListRequest(anime: SAnime): Request =
        GET(baseUrl + anime.url, headers)

    override fun episodeListParse(response: Response): List<SEpisode> {
        return listOf(
            SEpisode.create().apply {
                name = "Video"
                episode_number = 1f
                // Use the path portion of the URL (/watch?v=ID)
                val path = response.request.url.encodedPath +
                    "?" + response.request.url.encodedQuery
                setUrlWithoutDomain("$path")
            },
        )
    }

    // ===== Video List =====
    // Stream sources are embedded directly in the HTML as <source> tags — no
    // API call, no signature, no token extraction needed. The signed URLs in
    // the src attributes are generated server-side and ready to use.

    override fun videoListRequest(episode: SEpisode): Request =
        GET(baseUrl + episode.url, headers)

    override fun videoListParse(response: Response): List<Video> {
        val doc = response.asJsoup()
        val vHeaders = videoHeaders()
        return doc.select("video#player source[src]").mapNotNull { source ->
            val url = source.attr("src").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val size = source.attr("size")
            val quality = if (size.isNotBlank()) "${size}p" else "Unknown"
            Video(url, quality, url, headers = vHeaders)
        }
    }

    override fun List<Video>.sort(): List<Video> =
        sortedByDescending { it.quality.removeSuffix("p").toIntOrNull() ?: 0 }

    // ===== Filters =====

    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        AnimeFilter.Header("Filters are ignored when using text search"),
        GenreFilter(),
        SortFilter(),
    )

    private class GenreFilter : AnimeFilter.Select<String>(
        "Genre",
        GENRES,
    )

    private class SortFilter : AnimeFilter.Select<String>(
        "Sort",
        SORT_NAMES,
    )

    companion object {
        val GENRES = arrayOf(
            "All",
            "裏番",
            "泡麵番",
            "Motion Anime",
            "3DCG",
            "2.5D",
            "2D動畫",
            "AI生成",
            "MMD",
            "Cosplay",
        )

        val SORT_NAMES = arrayOf(
            "Latest",
            "Most Viewed",
            "Most Liked",
        )

        val SORT_VALUES = arrayOf(
            "",
            "views",
            "likes",
        )
    }
}