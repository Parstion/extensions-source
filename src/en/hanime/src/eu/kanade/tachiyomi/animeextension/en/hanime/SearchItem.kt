package eu.kanade.tachiyomi.animeextension.en.hanime

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// Response from guest.freeanimehentai.net/api/v11/search_hvs
// The API returns the full library as a flat JSON array.
@Serializable
data class SearchItem(
    val id: Int,
    val name: String,
    val slug: String,
    val description: String? = null,
    @SerialName("cover_url") val coverUrl: String? = null,
    @SerialName("poster_url") val posterUrl: String? = null,
    val brand: String? = null,
    @SerialName("brand_id") val brandId: Int? = null,
    val views: Long? = null,
    val likes: Long? = null,
    val dislikes: Long? = null,
    val downloads: Long? = null,
    val tags: List<String> = emptyList(),
    @SerialName("created_at_unix") val createdAtUnix: Long? = null,
    @SerialName("released_at_unix") val releasedAtUnix: Long? = null,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("released_at") val releasedAt: String? = null,
)
