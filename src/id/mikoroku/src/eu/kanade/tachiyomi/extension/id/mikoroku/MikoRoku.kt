package eu.kanade.tachiyomi.extension.id.mikoroku

import android.net.Uri
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import uy.kohesive.injekt.injectLazy
import java.text.SimpleDateFormat
import java.util.Locale

class MikoRoku : HttpSource() {

    override val name = "MikoRoku"
    override val baseUrl = "https://mikoroku.com"
    override val lang = "id"
    override val supportsLatest = true

    private val json: Json by injectLazy()

    // 1. THE CLOUDFLARE BYPASS: Direct Google Blogger API for the main catalog
    private val blogId = "8503640819736488624"
    private val catalogApiUrl = "https://www.blogger.com/feeds/$blogId/posts/default"

    // 2. MikoDrive API for the chapters
    private val driveApiUrl = "https://www.mikodrive.my.id/feeds/posts/default"

    // ==============================
    // CATALOG & PAGINATION
    // ==============================
    override fun popularMangaRequest(page: Int): Request {
        val startIndex = (page - 1) * 50 + 1
        return GET("$catalogApiUrl?alt=json&max-results=50&start-index=$startIndex", headers)
    }

    override fun latestUpdatesRequest(page: Int): Request {
        val startIndex = (page - 1) * 50 + 1
        return GET("$catalogApiUrl?alt=json&max-results=50&start-index=$startIndex&orderby=published", headers)
    }

    override fun popularMangaParse(response: Response): MangasPage = parseCatalog(response)
    override fun latestUpdatesParse(response: Response): MangasPage = parseCatalog(response)

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val startIndex = (page - 1) * 50 + 1
        var url = catalogApiUrl
        val categories = mutableListOf<String>()

        filters.forEach { filter ->
            when (filter) {
                is StatusFilter -> if (filter.state != 0) categories.add(filter.values[filter.state])
                is TypeFilter -> if (filter.state != 0) categories.add(filter.values[filter.state])
                is GenreFilter -> if (filter.state != 0) categories.add(filter.values[filter.state])
                else -> {}
            }
        }

        if (categories.isNotEmpty()) {
            url += "/-/" + categories.joinToString("/") { Uri.encode(it) }
        }

        url += "?alt=json&max-results=50&start-index=$startIndex"

        if (query.isNotBlank()) {
            url += "&q=${Uri.encode(query)}"
        }

        return GET(url, headers)
    }

    override fun searchMangaParse(response: Response): MangasPage = parseCatalog(response)

    private fun parseCatalog(response: Response): MangasPage {
        val jsonString = response.body?.string().orEmpty()
        if (response.code == 404 || jsonString.isEmpty() || !jsonString.contains("\"entry\":")) {
            return MangasPage(emptyList(), false)
        }

        // Using safe casting (as?) to prevent any compiler errors
        val resJson = json.parseToJsonElement(jsonString) as? JsonObject ?: return MangasPage(emptyList(), false)
        val feed = resJson["feed"] as? JsonObject ?: return MangasPage(emptyList(), false)
        val entries = feed["entry"] as? JsonArray ?: return MangasPage(emptyList(), false)

        val mangas = entries.mapNotNull { element ->
            val entry = element as? JsonObject ?: return@mapNotNull null
            val titleObj = entry["title"] as? JsonObject
            val title = titleObj?.get("\$t")?.jsonPrimitive?.content ?: return@mapNotNull null

            val linkArray = entry["link"] as? JsonArray
            val link = linkArray?.firstNotNullOfOrNull { item ->
                val l = item as? JsonObject
                if (l?.get("rel")?.jsonPrimitive?.content == "alternate") l["href"]?.jsonPrimitive?.content else null
            } ?: return@mapNotNull null

            val mediaThumb = entry["media\$thumbnail"] as? JsonObject
            val thumbRaw = mediaThumb?.get("url")?.jsonPrimitive?.content ?: ""
            val thumbHighRes = thumbRaw.replace("/s72-c/", "/s600/")

            SManga.create().apply {
                this.title = title
                this.url = link.substringAfter(".com").substringAfter(".top")
                this.thumbnail_url = thumbHighRes
            }
        }
        return MangasPage(mangas, mangas.size == 50)
    }

    // ==============================
    // CHAPTER LIST (MIKODRIVE API)
    // ==============================
    override fun chapterListRequest(manga: SManga): Request {
        val encodedTitle = Uri.encode(manga.title)
        return GET("$driveApiUrl/-/$encodedTitle?alt=json&max-results=500", headers)
    }

    override fun chapterListParse(response: Response): List<SChapter> {
        val jsonString = response.body?.string().orEmpty()
        if (response.code == 404 || jsonString.isEmpty() || !jsonString.contains("\"entry\":")) {
            return emptyList()
        }

        val resJson = json.parseToJsonElement(jsonString) as? JsonObject ?: return emptyList()
        val feed = resJson["feed"] as? JsonObject ?: return emptyList()
        val entries = feed["entry"] as? JsonArray ?: return emptyList()

        return entries.mapNotNull { element ->
            val entryObj = element as? JsonObject ?: return@mapNotNull null
            val titleObj = entryObj["title"] as? JsonObject
            val title = titleObj?.get("\$t")?.jsonPrimitive?.content ?: ""

            val linkArray = entryObj["link"] as? JsonArray
            val link = linkArray?.firstNotNullOfOrNull { item ->
                val l = item as? JsonObject
                if (l?.get("rel")?.jsonPrimitive?.content == "alternate") l["href"]?.jsonPrimitive?.content else null
            } ?: return@mapNotNull null

            val chapterRegex = "chapter\\s*(\\d+(\\.\\d+)?)".toRegex(RegexOption.IGNORE_CASE)
            val match = chapterRegex.find(title)
            val chapNum = match?.groupValues?.get(1)?.toFloatOrNull() ?: 0f

            val cleanTitle = match?.value?.replace("chapter", "Chapter", ignoreCase = true) ?: title

            val pubObj = entryObj["published"] as? JsonObject
            val pubDate = pubObj?.get("\$t")?.jsonPrimitive?.content

            SChapter.create().apply {
                this.url = link
                this.name = cleanTitle
                this.chapter_number = chapNum
                this.date_upload = parseDate(pubDate)
            }
        }.sortedByDescending { it.chapter_number }
    }

    private fun parseDate(dateStr: String?): Long = try {
        val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.getDefault())
        sdf.parse(dateStr!!.substring(0, 19))?.time ?: 0L
    } catch (e: Exception) {
        0L
    }

    // ==============================
    // PAGES
    // ==============================
    override fun pageListRequest(chapter: SChapter): Request = GET(chapter.url, headersBuilder().add("Referer", chapter.url).build())

    override fun pageListParse(response: Response): List<Page> {
        val html = response.body?.string().orEmpty()
        val doc = Jsoup.parse(html)

        val imgTags = doc.select(".post-body img, #post-body img, .separator img, div[itemprop=articleBody] img")

        return imgTags.mapIndexedNotNull { i, img ->
            var url = img.attr("src")
            if (url.isBlank()) url = img.attr("data-src")
            if (url.startsWith("http")) Page(i, "", url) else null
        }
    }

    override fun imageUrlParse(response: Response): String = ""

    // ==============================
    // FILTERS & DETAILS
    // ==============================
    override fun getFilterList() = FilterList(StatusFilter(), TypeFilter(), GenreFilter())

    private class StatusFilter : Filter.Select<String>("Status", arrayOf("Semua", "Ongoing", "Completed", "Hiatus", "Dropped", "Cancelled"))
    private class TypeFilter : Filter.Select<String>("Type", arrayOf("Semua", "Manga", "Manhua", "Manhwa", "Doujin", "Doujinshi"))
    private class GenreFilter : Filter.Select<String>("Genre", arrayOf("Semua", "Action", "Adventure", "Comedy", "Dark Fantasy", "Demon", "Drama", "Ecchi", "Fantasy", "Game", "Gore", "Harem", "Hentai", "Historical", "Horror", "Isekai", "Loli", "Magic", "Mature", "Mecha", "Military", "Monsters", "Mystery", "Psychological", "Reincarnation", "Romance", "School Life", "Sci-Fi", "Seinen", "Shota", "Shounen", "Slice of Life", "Supernatural", "Survival", "Tragedy", "Yandere", "Zombie"))

    override fun mangaDetailsParse(response: Response): SManga = SManga.create()
}
