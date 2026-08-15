package eu.kanade.tachiyomi.animeextension.en.hanime

import android.util.Base64
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
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class HanimeTv : AnimeHttpSource() {

    override val name = "hanime.tv"
    override val baseUrl = "https://hanime.tv"
    override val lang = "all"
    override val supportsLatest = true

    private val authUrl = "https://auth.hanime.tv"
    private val catalogUrl = "https://guest.freeanimehentai.net/api/v11/search_hvs"

    private val json = Json { ignoreUnknownKeys = true }

    companion object {
        private const val ITEMS_PER_PAGE = 24
    }

    // =====================================================================
    // Popular (trending) -- real HTML scrape, no auth/signing needed
    // =====================================================================

    override fun popularAnimeRequest(page: Int): Request =
        GET("$baseUrl/browse/trending?page=$page", headers)

    override fun popularAnimeParse(response: Response): AnimesPage {
        val document = response.asJsoup()
        val page = response.request.tag(Int::class.java) ?: 1

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

        val maxPage = document.selectFirst("input[type=number][max]")
            ?.attr("max")?.toIntOrNull() ?: page

        return AnimesPage(animes, page < maxPage)
    }

    // =====================================================================
    // Latest updates -- local sort of the full catalog by created_at_unix
    // =====================================================================

    override fun latestUpdatesRequest(page: Int): Request =
        GET(catalogUrl, headers).newBuilder().tag(Int::class.java, page).build()

    override fun latestUpdatesParse(response: Response): AnimesPage {
        val page = response.request.tag(Int::class.java) ?: 1
        val sorted = parseCatalog(response).sortedByDescending { it.createdAtUnix }
        return paginate(sorted, page)
    }

    // =====================================================================
    // Search -- local filter of the full catalog against search_titles
    // =====================================================================

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request =
        GET(catalogUrl, headers).newBuilder()
            .tag(Int::class.java, page)
            .tag(String::class.java, query)
            .build()

    override fun searchAnimeParse(response: Response): AnimesPage {
        val page = response.request.tag(Int::class.java) ?: 1
        val query = response.request.tag(String::class.java).orEmpty()
        val catalog = parseCatalog(response)

        val filtered = if (query.isBlank()) catalog else {
            catalog.filter { it.searchTitles.contains(query, ignoreCase = true) }
        }
        val sorted = filtered.sortedByDescending { it.views }
        return paginate(sorted, page)
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
                val videoUrl = if (src.src.startsWith("http")) src.src else baseUrl + src.src
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

    private val secretKey: SecretKeySpec by lazy {
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
        cipher.init(Cipher.ENCRYPT_MODE, secretKey, GCMParameterSpec(128, iv))
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
        cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(128, iv))
        cipher.updateAAD(AAD.toByteArray(Charsets.UTF_8))
        val plaintext = cipher.doFinal(data + tag) // Java expects ciphertext+tag concatenated
        return String(plaintext, Charsets.UTF_8)
    }
}
