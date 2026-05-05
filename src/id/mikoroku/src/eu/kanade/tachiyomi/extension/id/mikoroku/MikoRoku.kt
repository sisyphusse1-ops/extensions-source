package eu.kanade.tachiyomi.extension.id.mikoroku

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import okhttp3.Request
import okhttp3.Response
import uy.kohesive.injekt.injectLazy

class MikoRoku : HttpSource() {

    override val name = "MikoRoku"
    override val baseUrl = "https://mikoroku.com"
    override val lang = "id"
    override val supportsLatest = true

    private val json: Json by injectLazy()

    // 1. FIREBASE API FOR CATALOG
    private val firebaseApiUrl = "https://miko-roku.firebaseio.com/manga.json"

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
                    title = obj["title"]?.toString()?.replace("\"", "") ?: ""
                    thumbnail_url = obj["cover"]?.toString()?.replace("\"", "") ?: ""
                    // Type, Status, etc. match the firebasepost.js structure
                }
            } catch (e: Exception) {
                null
            }
        }
        return MangasPage(mangas, false) // Firebase loads everything at once, no pagination needed yet
    }

    override fun latestUpdatesRequest(page: Int) = popularMangaRequest(page)
    override fun latestUpdatesParse(response: Response) = popularMangaParse(response)

    // ==============================
    // MANGA DETAILS (FIREBASE)
    // ==============================
    override fun mangaDetailsRequest(manga: SManga): Request {
        // We can just use the webview URL for the details page
        return GET(baseUrl + manga.url, headers)
    }

    override fun mangaDetailsParse(response: Response): SManga {
        // In a full implementation, we'd parse the Firebase data or HTML here
        // For now, returning an empty SManga lets the catalog data carry over
        return SManga.create()
    }

    // ==============================
    // CHAPTER LIST (MIKODRIVE BLOGGER API)
    // ==============================
    override fun chapterListRequest(manga: SManga): Request {
        // Fetch chapters from the hidden mikodrive.my.id blogger backend
        val query = manga.title.replace(" ", "+")
        return GET("$driveApiUrl&q=$query&max-results=200", headers)
    }

    override fun chapterListParse(response: Response): List<SChapter> {
        // Needs proper Blogger JSON parsing logic here based on script.js
        return emptyList()
    }

    // ==============================
    // PAGES
    // ==============================
    override fun pageListRequest(chapter: SChapter): Request = GET(chapter.url, headers)

    override fun pageListParse(response: Response): List<Page> {
        // Needs proper Blogger HTML image scraping here
        return emptyList()
    }

    override fun imageUrlParse(response: Response): String = ""

    // We disable search for this initial rewrite structure to keep it simple
    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request = throw UnsupportedOperationException("Not used.")
    override fun searchMangaParse(response: Response): MangasPage = throw UnsupportedOperationException("Not used.")
}
