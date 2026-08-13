package eu.kanade.tachiyomi.animeextension.en.hanime

import android.util.Base64
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.FilterList
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

// Data classes for handshake
@Serializable
private data class HandshakePayload(
    val timestamp_unix: Long,
    val directive: String,
    val slug: String,
)

@Serializable
private data class HandshakeTokenRequest(
    val token: String,
)

@Serializable
private data class HandshakeResponse(
    val sources: List<Source>?,
)

@Serializable
private data class Source(
    val src: String,
    val height: Int?,
    val label: String?,
    val kind: String?,
)

class Hanime : HttpSource() {
    override val id: Long = 1234567890L // Change to a unique ID
    override val name: String = "hanime.tv"
    override val lang: String = "en"
    override val baseUrl: String = "https://hanime.tv"
    override val supportsLatest: Boolean = true

    private val client: OkHttpClient = network.client.newBuilder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    // ========== Cryptographic Handshake ==========

    private val keyString = "htv-insecure-handshake-v1"
    private val aadString = "htv-insecure-v1"
    private val keyBytes = MessageDigest.getInstance("SHA-256")
        .digest(keyString.toByteArray(Charsets.UTF_8))

    private fun encryptInsecureMessage(payload: HandshakePayload): String {
        val json = Json.encodeToString(payload)
        val data = json.toByteArray(Charsets.UTF_8)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val key = SecretKeySpec(keyBytes, "AES")
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
        cipher.updateAAD(aadString.toByteArray(Charsets.UTF_8))
        val ciphertext = cipher.doFinal(data)
        val tag = ciphertext.takeLast(16).toByteArray()
        val encrypted = ciphertext.dropLast(16).toByteArray()
        val obj = mapOf(
            "v" to 1,
            "alg" to "AES-256-GCM",
            "iv" to base64UrlEncode(iv),
            "tag" to base64UrlEncode(tag),
            "data" to base64UrlEncode(encrypted),
        )
        val jsonString = Json.encodeToString(obj)
        return base64UrlEncode(jsonString.toByteArray(Charsets.UTF_8))
    }

    private fun decryptInsecureMessage(token: String): String {
        val jsonString = String(base64UrlDecode(token), Charsets.UTF_8)
        val obj = Json.decodeFromString<Map<String, String>>(jsonString) // Or use Json.parseToJsonElement
        val iv = base64UrlDecode(obj["iv"]!!)
        val tag = base64UrlDecode(obj["tag"]!!)
        val encrypted = base64UrlDecode(obj["data"]!!)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val key = SecretKeySpec(keyBytes, "AES")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
        cipher.updateAAD(aadString.toByteArray(Charsets.UTF_8))
        val full = encrypted + tag
        val decrypted = cipher.doFinal(full)
        return String(decrypted, Charsets.UTF_8)
    }

    private fun base64UrlEncode(data: ByteArray): String =
        Base64.encodeToString(data, Base64.URL_SAFE or Base64.NO_WRAP)

    private fun base64UrlDecode(str: String): ByteArray =
        Base64.decode(str, Base64.URL_SAFE)

    private fun extractVideoSource(slug: String): String {
        val timestamp = System.currentTimeMillis() / 1000
        val payload = HandshakePayload(
            timestamp_unix = timestamp,
            directive = "htv_player_handshake",
            slug = slug,
        )
        val token = encryptInsecureMessage(payload)
        val requestBody = HandshakeTokenRequest(token = token)
        val jsonBody = Json.encodeToString(requestBody)
        val request = Request.Builder()
            .url("https://auth.hanime.tv/api/v11/handshake")
            .post(jsonBody.toRequestBody("application/json".toMediaType()))
            .header("x-signature-version", "web2")
            .header("x-csrf-token", "null")
            .header("x-time", timestamp.toString())
            .build()

        val response = client.newCall(request).execute()
        if (!response.isSuccessful) throw Exception("Handshake failed: ${response.code}")
        val xToken = response.header("x-token") ?: throw Exception("Missing x-token")
        response.close()

        val decryptedJson = decryptInsecureMessage(xToken)
        val handshakeResponse = Json.decodeFromString<HandshakeResponse>(decryptedJson)
        val sources = handshakeResponse.sources ?: throw Exception("No sources in response")

        val realSources = sources.filter { it.kind != "promotion" }

        if (realSources.isEmpty()) throw Exception("No playable sources found")

        // Pick highest quality by height
        val best = realSources.maxByOrNull { it.height ?: 0 }
            ?: throw Exception("No valid source")

        return best.src
    }

    // ========== Parsing Helper ==========

    private fun parseSearchResults(doc: Document): List<SManga> {
        val cards = doc.select("div.grid div.w-full")
        return cards.mapNotNull { card ->
            try {
                val link = card.selectFirst("a[href^=/videos/hentai/]")
                    ?: return@mapNotNull null
                val title = link.attr("title")
                val cover = link.selectFirst("img")?.attr("src") ?: ""
                val href = link.attr("href")
                val slug = href.substringAfterLast("/")
                SManga.create().apply {
                    url = href
                    title = title
                    thumbnail_url = cover
                    initialChapter = slug
                }
            } catch (_: Exception) { null }
        }
    }

    // ========== Source Methods ==========

    override fun getMangaList(page: Int): List<SManga> {
        val url = "$baseUrl/search?order=created_at_desc&page=$page"
        val doc = client.newCall(GET(url)).execute().use { response ->
            Jsoup.parse(response.body!!.string())
        }
        return parseSearchResults(doc)
    }

    override fun getLatestUpdates(page: Int): List<SManga> {
        return getMangaList(page)
    }

    override fun getPopularManga(page: Int): List<SManga> {
        val url = "$baseUrl/browse/trending?page=$page"
        val doc = client.newCall(GET(url)).execute().use { response ->
            Jsoup.parse(response.body!!.string())
        }
        return parseSearchResults(doc)
    }

    override fun searchManga(query: String, page: Int, filters: FilterList): List<SManga> {
        val url = "$baseUrl/search?q=${query.replace(" ", "+")}&page=$page"
        val doc = client.newCall(GET(url)).execute().use { response ->
            Jsoup.parse(response.body!!.string())
        }
        return parseSearchResults(doc)
    }

    override fun getMangaDetails(manga: SManga): SManga {
        return manga
    }

    override fun getChapterList(manga: SManga): List<SChapter> {
        val slug = manga.initialChapter ?: manga.url.substringAfterLast("/")
        return listOf(
            SChapter.create().apply {
                name = manga.title
                url = manga.url
                scanlator = slug
            },
        )
    }

    override fun getPageList(chapter: SChapter): List<Page> {
        val slug = chapter.scanlator ?: chapter.url.substringAfterLast("/")
        val sourceUrl = extractVideoSource(slug)
        return listOf(Page(0, "", sourceUrl))
    }

    override fun getFilterList(): FilterList {
        return FilterList()
    }
}
