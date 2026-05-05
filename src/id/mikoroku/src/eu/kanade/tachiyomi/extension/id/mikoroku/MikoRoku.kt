package eu.kanade.tachiyomi.extension.id.mikoroku

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
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import uy.kohesive.injekt.injectLazy
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Locale

class MikoRoku : HttpSource() {

    override val name = "MikoRoku"
    override val baseUrl = "https://mikoroku.com"
    override val lang = "id"
    override val supportsLatest = true

    private val json: Json by injectLazy()

    private val blogId = "8503640819736488624"
    private val catalogApiUrl = "https://www.blogger.com/feeds/$blogId/posts/default"
    private val driveApiUrl = "https://www.mikodrive.my.id/feeds/posts/default"

    override fun headersBuilder() = super.headersBuilder()
        .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/114.0.0.0 Safari/537.36")
        .add("Referer", baseUrl)

    override fun getMangaUrl(manga: SManga): String = baseUrl + manga.url
    override fun getChapterUrl(chapter: SChapter): String = chapter.url.substringAfter("||")

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
                is StatusFilter -> {
                    val status = filter.statuses[filter.state].value
                    if (status.isNotEmpty()) categories.add(status)
                }
                is GenreFilter -> {
                    val genre = filter.genres[filter.state].value
                    if (genre.isNotEmpty()) categories.add(genre)
                }
                else -> {}
            }
        }

        if (categories.isNotEmpty()) {
            url += "/-/" + categories.joinToString("/") { URLEncoder.encode(it, "UTF-8").replace("+", "%20") }
        }

        url += "?alt=json&max-results=50&start-index=$startIndex"

        if (query.isNotBlank()) {
            url += "&q=${URLEncoder.encode(query, "UTF-8")}"
        }

        return GET(url, headers)
    }

    override fun searchMangaParse(response: Response): MangasPage = parseCatalog(response)

    private fun parseCatalog(response: Response): MangasPage {
        val jsonString = response.body.string()
        if (response.code == 404 || jsonString.isEmpty() || !jsonString.contains("\"entry\":")) {
            return MangasPage(emptyList(), false)
        }

        val resJson = json.parseToJsonElement(jsonString) as? JsonObject ?: return MangasPage(emptyList(), false)
        val feed = resJson["feed"] as? JsonObject ?: return MangasPage(emptyList(), false)
        val entries = feed["entry"] as? JsonArray ?: return MangasPage(emptyList(), false)

        val mangas = entries.mapNotNull { element: JsonElement ->
            val entry = element as? JsonObject ?: return@mapNotNull null
            val titleObj = entry["title"] as? JsonObject
            val title = titleObj?.get("\$t")?.jsonPrimitive?.content ?: return@mapNotNull null

            val linkArray = entry["link"] as? JsonArray
            val link = linkArray?.firstNotNullOfOrNull { item ->
                val l = item as? JsonObject
                if (l?.get("rel")?.jsonPrimitive?.content == "alternate") l["href"]?.jsonPrimitive?.content else null
            } ?: return@mapNotNull null

            val slug = link.substringAfterLast("/").removeSuffix(".html")
            val mediaThumb = entry["media\$thumbnail"] as? JsonObject
            val thumbRaw = mediaThumb?.get("url")?.jsonPrimitive?.content ?: ""

            SManga.create().apply {
                this.title = title
                this.url = "/detail?slug=$slug"
                this.thumbnail_url = thumbRaw.replace("/s72-c/", "/s600/")
            }
        }
        return MangasPage(mangas, mangas.size == 50)
    }

    override fun mangaDetailsParse(response: Response): SManga = SManga.create()

    override fun chapterListRequest(manga: SManga): Request {
        val encodedTitle = URLEncoder.encode(manga.title.trim(), "UTF-8").replace("+", "%20")
        return GET("$driveApiUrl/-/$encodedTitle?alt=json&max-results=500", headers)
    }

    override fun chapterListParse(response: Response): List<SChapter> {
        val jsonString = response.body.string()
        if (response.code == 404 || jsonString.isEmpty() || !jsonString.contains("\"entry\":")) {
            return emptyList()
        }

        val resJson = json.parseToJsonElement(jsonString) as? JsonObject ?: return emptyList()
        val feed = resJson["feed"] as? JsonObject ?: return emptyList()
        val entries = feed["entry"] as? JsonArray ?: return emptyList()

        return entries.mapNotNull { element: JsonElement ->
            val entryObj = element as? JsonObject ?: return@mapNotNull null
            val titleObj = entryObj["title"] as? JsonObject
            val title = titleObj?.get("\$t")?.jsonPrimitive?.content ?: ""

            val idObj = entryObj["id"] as? JsonObject
            val fullId = idObj?.get("\$t")?.jsonPrimitive?.content ?: ""
            val postId = fullId.substringAfter("post-")

            val linkArray = entryObj["link"] as? JsonArray
            val link = linkArray?.firstNotNullOfOrNull { item ->
                val l = item as? JsonObject
                if (l?.get("rel")?.jsonPrimitive?.content == "alternate") l["href"]?.jsonPrimitive?.content else null
            } ?: ""

            val chapterRegex = "chapter\\s*(\\d+(\\.\\d+)?)".toRegex(RegexOption.IGNORE_CASE)
            val match = chapterRegex.find(title)
            val chapNum = match?.groupValues?.get(1)?.toFloatOrNull() ?: 0f
            val cleanTitle = match?.value?.replace("chapter", "Chapter", ignoreCase = true) ?: title

            val pubObj = entryObj["published"] as? JsonObject
            val pubDate = pubObj?.get("\$t")?.jsonPrimitive?.content

            SChapter.create().apply {
                this.url = "$postId||$link"
                this.name = cleanTitle
                this.chapter_number = chapNum
                this.date_upload = try {
                    SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.getDefault()).parse(pubDate!!.substring(0, 19))?.time ?: 0L
                } catch (e: Exception) {
                    0L
                }
            }
        }.sortedByDescending { it.chapter_number }
    }

    override fun pageListRequest(chapter: SChapter): Request {
        val postId = chapter.url.substringBefore("||")
        return GET("$driveApiUrl/$postId?alt=json", headers)
    }

    override fun pageListParse(response: Response): List<Page> {
        val jsonString = response.body.string()
        if (response.code == 404 || jsonString.isEmpty()) return emptyList()

        val resJson = json.parseToJsonElement(jsonString) as? JsonObject ?: return emptyList()
        val entry = resJson["entry"] as? JsonObject ?: return emptyList()
        val contentObj = entry["content"] as? JsonObject
        val contentHtml = contentObj?.get("\$t")?.jsonPrimitive?.content.orEmpty()

        val document = Jsoup.parse(contentHtml)

        val images = when {
            document.selectFirst("div.check-box") != null ->
                document.select("div.check-box div.separator img[src]")
            document.selectFirst("div[data=imageProtection]") != null ->
                document.select("div[data=imageProtection] div.separator img[src]")
            document.selectFirst("#post-body div.separator") != null ->
                document.select("#post-body div.separator img[src]")
            else ->
                document.select(".post-body div.separator img[src], img[src]")
        }

        return images.mapIndexedNotNull { index, img ->
            var url = img.attr("abs:src")
            if (url.isBlank()) url = img.attr("src")
            if (url.isBlank()) url = img.attr("data-src")
            if (url.startsWith("http")) Page(index, "", url) else null
        }
    }

    override fun imageUrlParse(response: Response): String = ""

    // ==============================
    // FILTERS
    // ==============================

    // Custom data classes to replicate ZeistManga functionality
    class Status(val name: String, val value: String)
    class Genre(val name: String, val value: String)

    private fun getStatusList() = listOf(
        Status("Semua", ""),
        Status("Ongoing", "Ongoing"),
        Status("Completed", "Completed"),
        Status("Hiatus", "Hiatus"),
        Status("Dropped", "Dropped"),
    )

    private fun getGenreList() = listOf(
        Genre("Semua", ""), // Added fallback default
        Genre("Action", "Action"),
        Genre("Adventure", "Adventure"),
        Genre("Comedy", "Comedy"),
        Genre("Dark Fantasy", "Dark Fantasy"),
        Genre("Drama", "Drama"),
        Genre("Fantasy", "Fantasy"),
        Genre("Historical", "Historical"),
        Genre("Horror", "Horror"),
        Genre("Isekai", "Isekai"),
        Genre("Magic", "Magic"),
        Genre("Mecha", "Mecha"),
        Genre("Military", "Military"),
        Genre("Mystery", "Mystery"),
        Genre("Psychological", "Psychological"),
        Genre("Romance", "Romance"),
        Genre("School Life", "School Life"),
        Genre("Sci-Fi", "Sci-Fi"),
        Genre("Seinen", "Seinen"),
        Genre("Shounen", "Shounen"),
        Genre("Slice of Life", "Slice of Life"),
        Genre("Supernatural", "Supernatural"),
        Genre("Survival", "Survival"),
        Genre("Tragedy", "Tragedy"),
    )

    private class StatusFilter(val statuses: List<Status>) : Filter.Select<String>("Status", statuses.map { it.name }.toTypedArray())
    private class GenreFilter(val genres: List<Genre>) : Filter.Select<String>("Genre", genres.map { it.name }.toTypedArray())

    override fun getFilterList() = FilterList(
        StatusFilter(getStatusList()),
        GenreFilter(getGenreList()),
    )
}
