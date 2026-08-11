package eu.kanade.tachiyomi.animeextension.en.hanime

import android.app.Application
import android.content.SharedPreferences
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import kotlinx.serialization.json.Json
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

class Hanime : ConfigurableAnimeSource, AnimeHttpSource() {

    override val name = "hanime.tv"
    override val baseUrl = "https://hanime.tv"
    override val lang = "en"
    override val supportsLatest = true

    // hanime.tv uses an SSL certificate that Android's OkHttp stack doesn't always
    // trust. Without this, Aniyomi's WebViewInterceptor (Cloudflare handler) kicks in,
    // fails with SSLHandshakeException, and the entire request chain aborts before
    // getVideoList is ever reached.
    private val trustAllCerts = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
        override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
    }

    private val sslContext = SSLContext.getInstance("TLS").also {
        it.init(null, arrayOf(trustAllCerts), SecureRandom())
    }

    override val client: OkHttpClient = network.cloudflareClient.newBuilder()
        .sslSocketFactory(sslContext.socketFactory, trustAllCerts)
        .hostnameVerifier { _, _ -> true }
        .build()

    private val searchApiUrl = "https://guest.freeanimehentai.net/api/v11/search_hvs"

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    private val json: Json by injectLazy()

    // The search API returns the full library as a flat array — we cache it and
    // paginate locally to avoid hammering the API on every page flip.
    private var cachedVideos: List<SearchItem> = emptyList()
    private var cacheTimestamp = 0L
    private val cacheTtlMs = 10 * 60 * 1000L // 10 minutes

    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .add(
            "User-Agent",
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
        )
        .add("Referer", "$baseUrl/")

    // ===== Popular =====
    // Sorted by views descending, served as paginated slices of the local cache

    override fun popularAnimeRequest(page: Int): Request =
        GET(searchApiUrl, headers)

    override fun popularAnimeParse(response: Response): AnimesPage {
        val items = parseAndCache(response)
        val sorted = items.sortedByDescending { it.views ?: 0L }
        return paginateLocally(sorted, page = 1)
    }

    // ===== Latest =====
    // Sorted by released_at_unix descending

    override fun latestUpdatesRequest(page: Int): Request =
        GET(searchApiUrl, headers)

    override fun latestUpdatesParse(response: Response): AnimesPage {
        val items = parseAndCache(response)
        val sorted = items.sortedByDescending { it.releasedAtUnix ?: 0L }
        return paginateLocally(sorted, page = 1)
    }

    // ===== Search =====
    // Text search and filters are applied locally against the cached library

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request =
        GET(searchApiUrl, headers)

    override fun searchAnimeParse(response: Response): AnimesPage {
        val items = parseAndCache(response)
        // Will be filtered in searchAnimeParse — but Aniyomi calls this with the page
        // already embedded. We re-filter here using the last query via anime URL hack,
        // but since Aniyomi passes query separately we filter by name match.
        return paginateLocally(items, page = 1)
    }

    // Override to apply query filtering before pagination
    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage {
        val response = client.newCall(searchAnimeRequest(page, query, filters)).execute()
        val items = parseAndCache(response)

        val filtered = items.filter { item ->
            if (query.isBlank()) {
                true
            } else {
                item.name.contains(query, ignoreCase = true) ||
                    item.tags.any { it.contains(query, ignoreCase = true) } ||
                    (item.brand?.contains(query, ignoreCase = true) == true)
            }
        }

        val sorted = filtered.sortedByDescending { it.releasedAtUnix ?: 0L }
        return paginateLocally(sorted, page)
    }

    private fun parseAndCache(response: Response): List<SearchItem> {
        val now = System.currentTimeMillis()
        if (cachedVideos.isNotEmpty() && (now - cacheTimestamp) < cacheTtlMs) {
            return cachedVideos
        }
        val items = json.decodeFromString<List<SearchItem>>(response.body.string())
        cachedVideos = items
        cacheTimestamp = now
        return items
    }

    private fun paginateLocally(items: List<SearchItem>, page: Int): AnimesPage {
        val fromIndex = (page - 1) * PAGE_SIZE
        if (fromIndex >= items.size) return AnimesPage(emptyList(), false)
        val toIndex = minOf(fromIndex + PAGE_SIZE, items.size)
        val animes = items.subList(fromIndex, toIndex).map { it.toSAnime() }
        return AnimesPage(animes, toIndex < items.size)
    }

    private fun SearchItem.toSAnime(): SAnime = SAnime.create().apply {
        setUrlWithoutDomain("/videos/hentai/$slug")
        title = name
        thumbnail_url = coverUrl
        author = brand
        genre = tags.joinToString()
        description = this@toSAnime.description
        status = SAnime.COMPLETED
        // All data is already here — skip animeDetailsParse
        initialized = true
    }

    // ===== Anime Details =====
    // Only called if initialized = false or on manual refresh.
    // We parse the video page HTML as fallback.

    override fun animeDetailsRequest(anime: SAnime): Request =
        GET(baseUrl + anime.url, headers)

    override fun animeDetailsParse(response: Response): SAnime {
        val doc = response.asJsoup()
        return SAnime.create().apply {
            title = doc.selectFirst("h1, h2, [class*='title']")?.text()?.trim() ?: ""
            thumbnail_url = doc.selectFirst("meta[property=og:image]")?.attr("content")
            description = doc.selectFirst("meta[property=og:description]")?.attr("content")
            genre = doc.select("a[href*='/tags/']")
                .mapNotNull { el -> el.text().trim().takeIf { it.isNotEmpty() } }
                .joinToString()
            status = SAnime.COMPLETED
            initialized = true
        }
    }

    // ===== Episode List =====
    // Each video is standalone — one episode per anime. The slug is already in
    // anime.url so we skip the HTTP request entirely to avoid OkHttp SSL issues.

    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        return listOf(
            SEpisode.create().apply {
                name = "Video"
                episode_number = 1f
                setUrlWithoutDomain(anime.url)
            },
        )
    }

    override fun episodeListRequest(anime: SAnime): Request = GET(baseUrl + anime.url)
    override fun episodeListParse(response: Response): List<SEpisode> = emptyList()

    // ===== Video List =====
    // Load the video page in WebView, intercept the WASM-token-signed HLS URL,
    // and return it with the session cookies needed for AES-128 key fetching.

    override fun videoListRequest(episode: SEpisode) = GET(baseUrl + episode.url)

    override suspend fun getVideoList(episode: SEpisode): List<Video> {
        val videoPageUrl = baseUrl + episode.url

        val result = WebViewExtractor.extractHlsUrl(videoPageUrl)
            ?: return emptyList()

        // Build headers for the Video — these are used for both the m3u8 playlist
        // request and the AES-128 sign.bin key fetch (ct.htv-services.com/sign.bin).
        val videoHeaders = Headers.Builder()
            .add(
                "User-Agent",
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
            )
            .add("Referer", "$baseUrl/")
            .add("Origin", baseUrl)
            .apply {
                if (result.cookies.isNotEmpty()) add("Cookie", result.cookies)
            }
            .build()

        return listOf(
            Video(result.url, "HLS", result.url, headers = videoHeaders),
        )
    }

    override fun videoListParse(response: Response): List<Video> = emptyList()

    override fun List<Video>.sort(): List<Video> = this

    override fun getFilterList() = AnimeFilterList()

    // ===== Preferences =====

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
            key = PREF_QUALITY_KEY
            title = "Preferred quality"
            entries = QUALITY_LIST
            entryValues = QUALITY_LIST
            setDefaultValue(PREF_QUALITY_DEFAULT)
            summary = "%s"
            setOnPreferenceChangeListener { _, newValue ->
                val index = findIndexOfValue(newValue as String)
                preferences.edit().putString(key, entryValues[index] as String).commit()
            }
        }.also(screen::addPreference)
    }

    companion object {
        private const val PAGE_SIZE = 24
        const val PREF_QUALITY_KEY = "preferred_quality"
        const val PREF_QUALITY_DEFAULT = "720p"
        val QUALITY_LIST = arrayOf("720p", "480p", "360p")
    }
}
