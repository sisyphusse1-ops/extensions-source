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
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Request
import okhttp3.Response
import uy.kohesive.injekt.injectLazy

class MikoRoku : HttpSource() {

    override val name = "MikoRoku"
    override val baseUrl = "https://mikoroku.com"
    override val lang = "id"
    override val supportsLatest = true

    private val json: Json by injectLazy()

    // 1. FIREBASE API FOR CATALOG (Updated URL)[cite: 3]
    private val firebaseApiUrl = "https://miko-roku-default-rtdb.firebaseio.com/manga.json"

    // 2. BLOGGER API FOR CHAPTERS
    private val driveApiUrl = "https://www.mikodrive.my.id/feeds/posts/default?alt=json"

    // ==============================
    // POPULAR & LATEST MANGA (FIREBASE)
    // ==============================
    override fun popularMangaRequest(page: Int): Request = GET(firebaseApiUrl, headers)

    override fun popularMangaParse(response: Response): MangasPage {
        val jsonString = response.body?.string().orEmpty()
        if (jsonString.isEmpty() || jsonString == "null") return MangasPage(emptyList(), false)

        val data = json.parseToJsonElement(jsonString).jsonObject
        val mangas = data.entries.mapNotNull { (slug, element) ->
            try {
                val obj = element.jsonObject
                SManga.create().apply {
                    url = "/detail?slug=$slug"
                    title = obj["title"]?.jsonPrimitive?.content ?: ""
                    thumbnail_url = obj["cover"]?.jsonPrimitive?.content ?: ""
                }
            } catch (e: Exception) {
                null
            }
        }
        return MangasPage(mangas, false)
    }

    override fun latestUpdatesRequest(page: Int) = popularMangaRequest(page)
    override fun latestUpdatesParse(response: Response) = popularMangaParse(response)

    // ==============================
    // SEARCH & FILTERS (CLIENT-SIDE)
    // ==============================
    override fun getFilterList() = FilterList(
        StatusFilter(),
        TypeFilter(),
        GenreFilter()
    )

    private class StatusFilter : Filter.Select<String>("Status", arrayOf("Semua", "Ongoing", "Completed", "Hiatus", "Dropped"))
    private class TypeFilter : Filter.Select<String>("Type", arrayOf("Semua", "Manga", "Manhua", "Manhwa", "Novel"))
    private class GenreFilter : Filter.Select<String>("Genre", arrayOf("Semua", "Action", "Adventure", "Comedy", "Dark Fantasy", "Drama", "Fantasy", "Historical", "Horror", "Isekai", "Magic", "Mecha", "Military", "Mystery", "Psychological", "Romance", "School Life", "Sci-Fi", "Seinen", "Shounen", "Slice of Life", "Supernatural", "Survival", "Tragedy"))

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val headersBuilder = headersBuilder().apply {
            add("tachi-query", query)
            filters.forEach { filter ->
                when (filter) {
                    is StatusFilter -> if (filter.state != 0) add("tachi-status", filter.values[filter.state])
                    is TypeFilter -> if (filter.state != 0) add("tachi-type", filter.values[filter.state])
                    is GenreFilter -> if (filter.state != 0) add("tachi-genre", filter.values[filter.state])
                    else -> {} // Exhaustive fix
                }
            }
        }
        return GET(firebaseApiUrl, headersBuilder.build())
    }

    override fun searchMangaParse(response: Response): MangasPage {
        val query = response.request.header("tachi-query") ?: ""
        val statusFilter = response.request.header("tachi-status")
        val typeFilter = response.request.header("tachi-type")
        val genreFilter = response.request.header("tachi-genre")

        val jsonString = response.body?.string().orEmpty()
        if (jsonString.isEmpty() || jsonString == "null") return MangasPage(emptyList(), false)

        val data = json.parseToJsonElement(jsonString).jsonObject
        val mangas = data.entries.mapNotNull { (slug, element) ->
            try {
                val obj = element.jsonObject
                val title = obj["title"]?.jsonPrimitive?.content ?: ""
                val status = obj["status"]?.jsonPrimitive?.content ?: ""
                val type = obj["type"]?.jsonPrimitive?.content ?: ""
                
                val genresElement = obj["genres"]
                val genres = if (genresElement is JsonArray) {
                    genresElement.map { it.jsonPrimitive.content }
                } else emptyList()

                // Client-side filtering logic[cite: 4]
                if (query.isNotBlank() && !title.contains(query, ignoreCase = true)) return@mapNotNull null
                if (statusFilter != null && !status.equals(statusFilter, ignoreCase = true)) return@mapNotNull null
                if (typeFilter != null && !type.equals(typeFilter, ignoreCase = true)) return@mapNotNull null
                if (genreFilter != null && !genres.any { it.equals(genreFilter, ignoreCase = true) }) return@mapNotNull null

                SManga.create().apply {
                    this.url = "/detail?slug=$slug"
                    this.title = title
                    this.thumbnail_url = obj["cover"]?.jsonPrimitive?.content ?: ""
                }
            } catch (e: Exception) {
                null
            }
        }
        return MangasPage(mangas, false)
    }

    // ==============================
    // MANGA DETAILS (FIREBASE)
    // ==============================
    override fun mangaDetailsRequest(manga: SManga): Request {
        return GET(baseUrl + manga.url, headers)
    }

    override fun mangaDetailsParse(response: Response): SManga {
        return SManga.create()
    }

    // ==============================
    // CHAPTER LIST (MIKODRIVE BLOGGER API)
    // ==============================
    override fun chapterListRequest(manga: SManga): Request {
        val query = manga.title.replace(" ", "+")
        return GET("$driveApiUrl&q=$query&max-results=200", headers)
    }

    override fun chapterListParse(response: Response): List<SChapter> {
        return emptyList() // Placeholder for detailed Blogger parsing
    }

    // ==============================
    // PAGES
    // ==============================
    override fun pageListRequest(chapter: SChapter): Request = GET(chapter.url, headers)

    override fun pageListParse(response: Response): List<Page> {
        return emptyList()
    }

    override fun imageUrlParse(response: Response): String = ""
}
