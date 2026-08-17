package eu.kanade.tachiyomi.animeextension.en.hanime

import android.util.Base64
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class Hanime : AnimeHttpSource() {

    override val name = "hanime.tv"
    override val baseUrl = "https://hanime.tv"
    override val lang = "en"
    override val supportsLatest = true

    private val authUrl = "https://auth.hanime.tv"
    private val catalogUrl = "https://guest.freeanimehentai.net/api/v11/search_hvs"

    private val json = Json { ignoreUnknownKeys = true }

    companion object {
        private const val ITEMS_PER_PAGE = 24

        // Confirmed directly from the real timespan-toggle links on /browse/trending -- note
        // semi-annual and yearly do NOT match their display labels' obvious guesses.
        private val TIMESPAN_LABELS = arrayOf("Monthly", "Daily", "Weekly", "Quarterly", "Semi-Annually", "Annually")
        private val TIMESPAN_VALUES = arrayOf("monthly", "daily", "weekly", "quarterly", "semi-annual", "yearly")

        private val KNOWN_TAGS = listOf(
            "2000-year-old dragon girl", "3d", "ahegao", "anal",
            "bdsm", "big boobs", "blow job", "bondage",
            "boob job", "censored", "comedy", "cosplay",
            "creampie", "dark skin", "facial", "fantasy",
            "filmed", "foot job", "futanari", "gangbang",
            "glasses", "hand job", "harem", "hd",
            "horror", "incest", "inflation", "lactation",
            "maid", "masturbation", "milf", "mind break",
            "mind control", "monster", "nekomimi", "ntr",
            "nurse", "orgy", "plot", "pov",
            "pregnant", "public sex", "rimjob", "scat",
            "school girl", "softcore", "swimsuit", "teacher",
            "tentacle", "threesome", "toys", "trap",
            "tsundere", "ugly bastard", "uncensored", "vanilla",
            "virgin", "watersports", "x-ray", "yaoi",
            "yuri",
        )

        private val KNOWN_STUDIOS = listOf(
            "Any",
            "@ OZ", "AIC", "APPP",
            "Adult Source Media", "Ajia-Do", "Almond Collective",
            "Alpha Polis", "Ameliatie", "Amour",
            "Animac", "Anime Antenna Iinkai", "Antechinus",
            "Arms", "BOMB! CUTE! BOMB!", "Bishop",
            "Blue Eyes", "Bootleg", "BreakBottle",
            "BugBug", "Bunnywalker", "Celeb",
            "Central Park Media", "ChiChinoya", "Chocolat",
            "ChuChu", "Circle Tribute", "CoCoans",
            "Collaboration Works", "Comet", "Comic Media",
            "Cosmos", "Cranberry", "Crimson",
            "D3", "Daiei", "Digital Works",
            "Discovery", "Dollhouse", "EBIMARU-DO",
            "ECOLONUN", "Echo", "Edge",
            "Erozuki", "FINAL FUCK 7", "Fanza",
            "Five Ways", "Friends Media Station", "Front Line",
            "Godoy", "Green Bunny", "Groover",
            "Hoods Entertainment", "Hot Bear", "Hykobo",
            "IRONBELL", "Ivory Tower", "J.C.",
            "Jellyfish", "Jewel", "Juicy Mango",
            "Jumondo", "KENZsoft", "King Bee",
            "Kitty Media", "Knack", "KoaLa",
            "Kuril", "L.", "Lemon Heart",
            "Lilix", "Lune Pictures", "MS Pictures",
            "Magic Bus", "Magin Label", "Majin Petit",
            "Marigold", "Mary Jane", "Media Blasters",
            "MediaBank", "Metro Notes", "MiMiA Cute",
            "Milky", "Moon Rock", "Mousou Senka",
            "Muse", "N43", "New Generation",
            "Nihikime no Dozeu", "NuTech Digital", "Obtain Future",
            "Otodeli", "Pashmina", "Passione",
            "Pastel", "Peach Pie", "Pink Pineapple",
            "Pinkbell", "Pix", "Pixy Soft",
            "PoRO", "Pocomo Premium", "Project No.9",
            "Queen Bee", "ROJIURA JACK", "Rabbit Gate",
            "SELFISH", "SPEED", "STARGATE3D",
            "SYLD", "Sakura Purin", "Schoolzone",
            "Seven", "Shadow Prod. Co.", "Shelf",
            "Shinyusha", "ShoSai", "Showten",
            "Soft on Demand", "SoftCell", "Studio 9 Maiami",
            "Studio Akai Shohosen", "Studio Deen", "Studio FOW",
            "Studio Fantasia", "Studio Gokumi", "Studio Houkiboshi",
            "Studio LEO", "Studio Zealot", "Suiseisha",
            "SurviveMore", "Suzuki Mirano", "T-Rex",
            "TDK Core", "TNK", "TOHO",
            "TYS Work", "Toranoana", "Torudaya",
            "Triangle", "Trimax", "Triple X",
            "U-Jin", "Umemaro-3D", "Union Cho",
            "Valkyria", "Vanilla", "White Bear",
            "X City", "Y.O.U.C.", "ZIZ",
            "Zyc", "demodemon", "evee",
            "fruit", "gomasioken", "kate_sai",
            "nur", "sakamotoJ", "seismic",
            "studio GGB", "t japan", "yosino",
        )
    }

    // Small typed request-tag carriers -- avoids the Class<T>-as-map-key collisions you'd get
    // from tagging multiple plain Int/String values on the same Request, and sidesteps the
    // Int::class.java-vs-javaObjectType primitive/boxing gotcha entirely (see conversation).
    private data class PageTag(val page: Int)
    private data class SearchTag(val page: Int, val query: String, val tags: List<String>, val studio: String?)

    class TimespanFilter : AnimeFilter.Select<String>("Timespan (browse only, ignores search text)", TIMESPAN_LABELS)
    class TagCheckBox(tag: String) : AnimeFilter.CheckBox(tag)
    class TagFilter : AnimeFilter.Group<TagCheckBox>("Tags (any match)", KNOWN_TAGS.map { TagCheckBox(it) })
    class StudioFilter : AnimeFilter.Select<String>("Studio", KNOWN_STUDIOS.toTypedArray())

    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        TimespanFilter(),
        TagFilter(),
        StudioFilter(),
    )

    // =====================================================================
    // Popular (trending) -- real HTML scrape, no auth/signing needed
    // =====================================================================

    override fun popularAnimeRequest(page: Int): Request =
        GET("$baseUrl/browse/trending?timespan=monthly&page=$page", headers).newBuilder()
            .tag(PageTag::class.java, PageTag(page))
            .build()

    override fun popularAnimeParse(response: Response): AnimesPage {
        val page = response.request.tag(PageTag::class.java)?.page ?: 1
        return parseTrendingDocument(response.asJsoup(), page)
    }

    private fun parseTrendingDocument(document: Document, page: Int): AnimesPage {
        val animes = document.select("div.grid > div.relative").mapNotNull { card ->
            val link = card.selectFirst("a[href^=/videos/hentai/]") ?: return@mapNotNull null
            val title = card.selectFirst("h3")?.text() ?: return@mapNotNull null
            val poster = card.selectFirst("img.no-fade")?.attr("src")
            SAnime.create().apply {
                url = link.attr("href")
                this.title = title
                thumbnail_url = poster
            }
        }
        // No reliable server-rendered total-page-count (the modal's `max` attribute turned out to
        // be a stale placeholder, not real data -- see conversation). Just keep paginating until a
        // page comes back empty, which is the standard fallback pattern for this situation.
        return AnimesPage(animes, animes.isNotEmpty())
    }

    // =====================================================================
    // Latest updates -- local sort of the full catalog by created_at_unix
    // =====================================================================

    override fun latestUpdatesRequest(page: Int): Request =
        GET(catalogUrl, headers).newBuilder().tag(PageTag::class.java, PageTag(page)).build()

    override fun latestUpdatesParse(response: Response): AnimesPage {
        val page = response.request.tag(PageTag::class.java)?.page ?: 1
        val sorted = parseCatalog(response).sortedByDescending { it.createdAtUnix }
        return paginate(sorted, page)
    }

    // =====================================================================
    // Search -- either routes to the real trending scrape (if a non-default
    // Timespan filter is chosen -- ignores query/tags/studio in that mode),
    // or filters the local catalog by query + tags + studio (default mode).
    // =====================================================================

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val timespanIndex = filters.filterIsInstance<TimespanFilter>().firstOrNull()?.state ?: 0

        if (timespanIndex != 0) {
            val timespanValue = TIMESPAN_VALUES[timespanIndex]
            return GET("$baseUrl/browse/trending?timespan=$timespanValue&page=$page", headers)
                .newBuilder()
                .tag(PageTag::class.java, PageTag(page))
                .build()
        }

        val selectedTags = filters.filterIsInstance<TagFilter>().firstOrNull()
            ?.state?.filter { it.state }?.map { it.name }
            ?: emptyList()
        val studioIndex = filters.filterIsInstance<StudioFilter>().firstOrNull()?.state ?: 0
        val selectedStudio = if (studioIndex == 0) {
            null
        } else {
            KNOWN_STUDIOS[studioIndex]
        }

        return GET(catalogUrl, headers).newBuilder()
            .tag(SearchTag::class.java, SearchTag(page, query, selectedTags, selectedStudio))
            .build()
    }

    override fun searchAnimeParse(response: Response): AnimesPage {
        if (response.request.url.encodedPath.startsWith("/browse/trending")) {
            val page = response.request.tag(PageTag::class.java)?.page ?: 1
            return parseTrendingDocument(response.asJsoup(), page)
        }

        val tag = response.request.tag(SearchTag::class.java) ?: SearchTag(1, "", emptyList(), null)
        val catalog = parseCatalog(response)

        var filtered = if (tag.query.isBlank()) {
            catalog
        } else {
            catalog.filter { it.searchTitles.contains(tag.query, ignoreCase = true) }
        }
        if (tag.tags.isNotEmpty()) {
            filtered = filtered.filter { entry -> entry.tags.any { it in tag.tags } }
        }
        if (tag.studio != null) {
            filtered = filtered.filter { it.brand == tag.studio }
        }

        val sorted = filtered.sortedByDescending { it.views }
        return paginate(sorted, tag.page)
    }

    private fun parseCatalog(response: Response): List<HanimeCatalogEntry> =
        json.decodeFromString<List<HanimeCatalogEntry>>(response.body.string())

    private fun paginate(entries: List<HanimeCatalogEntry>, page: Int): AnimesPage {
        val fromIndex = (page - 1) * ITEMS_PER_PAGE
        if (fromIndex >= entries.size) return AnimesPage(emptyList(), false)
        val toIndex = minOf(fromIndex + ITEMS_PER_PAGE, entries.size)
        val slice = entries.subList(fromIndex, toIndex).map { it.toSAnime() }
        return AnimesPage(slice, toIndex < entries.size)
    }

    // =====================================================================
    // Anime details -- refresh tags/title from the live video page.
    // Catalog data (set during browse/search) already covers most fields,
    // so this mainly re-confirms tags, which are reliably server-rendered.
    // =====================================================================

    override fun animeDetailsRequest(anime: SAnime): Request = GET(baseUrl + anime.url, headers)

    override fun animeDetailsParse(response: Response): SAnime {
        val document = response.asJsoup()
        return SAnime.create().apply {
            title = document.selectFirst("h1")?.text().orEmpty()
            genre = document.select("a[href^=/browse/tags/]").joinToString(", ") { it.text() }
            status = SAnime.COMPLETED
        }
    }

    // =====================================================================
    // Episodes -- each catalog entry is one standalone video (confirmed:
    // "More from X" is a related-videos module, not an in-page episode
    // list; playlist_slug was empty on every sample we've seen so far).
    // One synthetic SEpisode per SAnime, keyed off the anime's own slug.
    // =====================================================================

    override fun episodeListRequest(anime: SAnime): Request = GET(baseUrl + anime.url, headers)

    override fun episodeListParse(response: Response): List<SEpisode> {
        val path = response.request.url.encodedPath // "/videos/hentai/{slug}"
        return listOf(
            SEpisode.create().apply {
                url = path
                name = "Episode"
                episode_number = 1F
            },
        )
    }

    // =====================================================================
    // Video list -- the actual signed handshake. POST an AES-256-GCM
    // encrypted {timestamp_unix, directive:"htv_player_handshake", slug}
    // to /api/v11/handshake, decrypt the x-token RESPONSE HEADER (same
    // scheme) to get the sources array. Verified byte-for-byte against
    // real captured traffic -- see conversation history for the full
    // reverse-engineering trail.
    // =====================================================================

    override fun videoListRequest(episode: SEpisode): Request {
        val slug = episode.url.substringAfterLast("/")
        val nowSeconds = System.currentTimeMillis() / 1000

        val payload = """{"timestamp_unix":$nowSeconds,"directive":"htv_player_handshake","slug":"$slug"}"""
        val encryptedToken = HtvCrypto.encrypt(payload)
        val body = """{"token":"$encryptedToken"}""".toRequestBody("application/json".toMediaType())

        val signed = HtvSigner.sign(nowSeconds)
        val requestHeaders = headers.newBuilder()
            .set("content-type", "application/json")
            .set("accept", "application/json")
            .set("x-csrf-token", "null")
            .set("x-signature", signed.signature)
            .set("x-signature-version", "web2")
            .set("x-time", signed.time.toString())
            .set("origin", baseUrl)
            .build()

        return Request.Builder()
            .url("$authUrl/api/v11/handshake")
            .post(body)
            .headers(requestHeaders)
            .build()
    }

    override fun videoListParse(response: Response): List<Video> {
        val xToken = response.header("x-token")
            ?: throw Exception("No x-token in handshake response -- signature/encryption may be stale, or this account/video needs auth we don't send")

        val decrypted = HtvCrypto.decrypt(xToken)
        val parsed = json.decodeFromString<HandshakeResponse>(decrypted)

        return parsed.sources
            .filter { it.src.isNotBlank() }
            .map { src ->
                val videoUrl = if (src.src.startsWith("http")) {
                    src.src
                } else {
                    baseUrl + src.src
                }
                Video(videoUrl, src.label, videoUrl)
            }
    }

    private fun Response.asJsoup(): Document = Jsoup.parse(body.string(), request.url.toString())
}

// =========================================================================
// Catalog model (/api/v11/search_hvs)
// =========================================================================

@Serializable
data class HanimeCatalogEntry(
    val id: Int,
    val name: String,
    @SerialName("search_titles") val searchTitles: String,
    val slug: String,
    val description: String? = null,
    val views: Long = 0,
    @SerialName("cover_url") val coverUrl: String? = null,
    @SerialName("poster_url") val posterUrl: String? = null,
    val brand: String? = null,
    val tags: List<String> = emptyList(),
    @SerialName("created_at_unix") val createdAtUnix: Long = 0,
    @SerialName("released_at_unix") val releasedAtUnix: Long = 0,
) {
    fun toSAnime(): SAnime = SAnime.create().apply {
        url = "/videos/hentai/$slug"
        title = name
        thumbnail_url = coverUrl ?: posterUrl
        author = brand
        genre = tags.joinToString(", ")
        description = this@HanimeCatalogEntry.description?.let { Jsoup.parse(it).text() }
        status = SAnime.COMPLETED
    }
}

// =========================================================================
// Handshake response model (decrypted x-token)
// =========================================================================

@Serializable
data class HandshakeResponse(
    val sources: List<HandshakeSource> = emptyList(),
)

@Serializable
data class HandshakeSource(
    val src: String,
    val label: String,
    val kind: String? = null,
)

// =========================================================================
// x-signature / x-time -- see HtvSigner.kt for the full derivation +
// verification against real captured traffic (8 test vectors incl. the
// real capture). Reproduced here so this file is self-contained.
// =========================================================================

private object HtvSigner {
    private const val SALT_1 = ",Xkdi29,"
    private const val SALT_2 = ",mn2,"
    private const val ORIGIN = "https://hanime.tv"

    data class SignedHeaders(val signature: String, val time: Long)

    fun sign(unixTimeSeconds: Long, origin: String = ORIGIN): SignedHeaders {
        val timeStr = unixTimeSeconds.toString()
        val preimage = timeStr + SALT_1 + origin + SALT_2 + timeStr
        val digest = MessageDigest.getInstance("SHA-256").digest(preimage.toByteArray(Charsets.UTF_8))
        val hex = digest.joinToString("") { "%02x".format(it) }
        return SignedHeaders(hex, unixTimeSeconds)
    }
}

// =========================================================================
// Handshake body/response AES-256-GCM envelope -- ported 1:1 from
// app_init.<hash>.js's encrypt()/decrypt() functions (functions `p`/`m`
// there). Envelope: base64url(JSON{v:1, alg:"AES-256-GCM", iv, tag, data}),
// each of iv/tag/data itself base64url. Key = SHA-256("htv-insecure-handshake-v1"),
// AAD = "htv-insecure-v1", 12-byte IV, 128-bit tag. Verified by decrypting
// a real captured handshake response end-to-end (see conversation).
// =========================================================================

private object HtvCrypto {
    private const val KEY_SEED = "htv-insecure-handshake-v1"
    private const val AAD = "htv-insecure-v1"

    private val SECRET_KEY: SecretKeySpec by lazy {
        val digest = MessageDigest.getInstance("SHA-256").digest(KEY_SEED.toByteArray(Charsets.UTF_8))
        SecretKeySpec(digest, "AES")
    }

    private fun b64UrlEncode(bytes: ByteArray): String =
        Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)

    private fun b64UrlDecode(s: String): ByteArray =
        Base64.decode(s, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)

    fun encrypt(plaintext: String): String {
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SECRET_KEY, GCMParameterSpec(128, iv))
        cipher.updateAAD(AAD.toByteArray(Charsets.UTF_8))
        val ciphertextAndTag = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))

        // Java appends the 16-byte tag to the end of the ciphertext, matching
        // the JS side's manual slicing -- just split it the same way.
        val ciphertext = ciphertextAndTag.copyOfRange(0, ciphertextAndTag.size - 16)
        val tag = ciphertextAndTag.copyOfRange(ciphertextAndTag.size - 16, ciphertextAndTag.size)

        val envelope = """{"v":1,"alg":"AES-256-GCM","iv":"${b64UrlEncode(iv)}","tag":"${b64UrlEncode(tag)}","data":"${b64UrlEncode(ciphertext)}"}"""
        return b64UrlEncode(envelope.toByteArray(Charsets.UTF_8))
    }

    fun decrypt(token: String): String {
        val envelopeJson = String(b64UrlDecode(token), Charsets.UTF_8)
        val envelope = Json.parseToJsonElement(envelopeJson).let { it as JsonObject }

        val iv = b64UrlDecode(envelope.getValue("iv").jsonPrimitive.content)
        val tag = b64UrlDecode(envelope.getValue("tag").jsonPrimitive.content)
        val data = b64UrlDecode(envelope.getValue("data").jsonPrimitive.content)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SECRET_KEY, GCMParameterSpec(128, iv))
        cipher.updateAAD(AAD.toByteArray(Charsets.UTF_8))
        val plaintext = cipher.doFinal(data + tag) // Java expects ciphertext+tag concatenated
        return String(plaintext, Charsets.UTF_8)
    }
}
