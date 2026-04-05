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
                    is TagFilter -> {
                        if (filter.state != 0) {
                            addQueryParameter("tags[]", TAGS[filter.state - 1])
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
        }.filter { anime ->
            // Filter out EROLABS ad entries that appear on every page
            anime.author?.contains("EROLABS", ignoreCase = true) != true &&
                anime.title.contains("EROLABS", ignoreCase = true).not()
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
        TagFilter(arrayOf("None") + TAGS),
    )

    private class GenreFilter : AnimeFilter.Select<String>(
        "Genre",
        GENRES,
    )

    private class SortFilter : AnimeFilter.Select<String>(
        "Sort",
        SORT_NAMES,
    )

    private class TagFilter(tags: Array<String>) : AnimeFilter.Select<String>("Tag", tags)

    companion object {
        val TAGS = arrayOf(
            "無碼", "AI解碼", "中文字幕", "中文配音", "同人作品", "斷面圖", "ASMR", "1080p", "60FPS",
            "近親", "姐", "妹", "母", "女兒", "師生", "情侶", "青梅竹馬", "同事",
            "JK", "處女", "御姐", "熟女", "人妻", "女教師", "男教師", "女醫生", "女病人",
            "護士", "OL", "女警", "大小姐", "偶像", "女僕", "巫女", "魔女", "修女",
            "風俗娘", "公主", "女忍者", "女戰士", "女騎士", "魔法少女", "異種族", "天使",
            "妖精", "魔物娘", "魅魔", "吸血鬼", "女鬼", "獸娘", "福瑞", "乳牛", "機械娘",
            "碧池", "痴女", "雌小鬼", "不良少女", "傲嬌", "病嬌", "無口", "無表情", "眼神死",
            "正太", "偽娘", "扶他",
            "短髮", "馬尾", "雙馬尾", "丸子頭", "巨乳", "乳環", "舌環", "貧乳",
            "黑皮膚", "曬痕", "眼鏡娘", "獸耳", "尖耳朵", "異色瞳", "美人痣", "肌肉女",
            "白虎", "陰毛", "腋毛", "大屌",
            "著衣", "水手服", "體操服", "泳裝", "比基尼", "死庫水", "和服", "兔女郎",
            "圍裙", "啦啦隊", "絲襪", "吊襪帶", "熱褲", "迷你裙", "性感內衣", "緊身衣",
            "丁字褲", "高跟鞋", "睡衣", "婚紗", "旗袍", "古裝", "哥德", "口罩", "刺青",
            "淫紋", "身體寫字",
            "校園", "教室", "圖書館", "保健室", "游泳池", "愛情賓館", "醫院", "辦公室",
            "浴室", "窗邊", "公共廁所", "公眾場合", "戶外野戰", "電車", "車震", "遊艇",
            "露營帳篷", "電影院", "健身房", "沙灘", "溫泉", "夜店", "監獄", "教堂",
            "純愛", "戀愛喜劇", "後宮", "十指緊扣", "開大車", "NTR", "精神控制", "藥物",
            "痴漢", "阿嘿顏", "精神崩潰", "獵奇", "BDSM", "綑綁", "眼罩", "項圈", "調教",
            "異物插入", "尋歡洞", "肉便器", "性奴隸", "胃凸", "強制", "輪姦", "凌辱",
            "性暴力", "逆強制", "女王樣", "榨精", "母女丼", "姐妹丼", "出軌", "醉酒",
            "攝影", "睡眠姦", "機械姦", "蟲姦", "性轉換", "百合", "耽美", "時間停止",
            "異世界", "怪獸", "哥布林", "世界末日",
            "手交", "指交", "乳交", "乳頭交", "肛交", "雙洞齊下", "腳交", "素股", "拳交",
            "3P", "群交", "口交", "跪舔", "深喉嚨", "口爆", "吞精", "舔蛋蛋", "舔穴",
            "69", "自慰", "腋交", "舔腋下", "髮交", "舔耳朵", "舔腳",
            "內射", "外射", "顏射", "潮吹", "懷孕", "噴奶", "放尿", "排便",
            "騎乘位", "背後位", "顏面騎乘", "火車便當", "一字馬",
            "性玩具", "飛機杯", "跳蛋", "毒龍鑽", "觸手", "獸交", "頸手枷",
            "扯頭髮", "掐脖子", "打屁股", "肉棒打臉", "陰道外翻", "男乳首責",
            "接吻", "舌吻", "POV",
        )

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
