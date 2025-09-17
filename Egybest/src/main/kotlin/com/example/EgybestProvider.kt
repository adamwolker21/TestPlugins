package com.example

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import org.jsoup.Jsoup
import com.fasterxml.jackson.annotation.JsonProperty

// v14: Final implementation using the correct API endpoint discovered by the user.
// The app now calls the same API as the website, ensuring content is always loaded correctly.
class EgybestProvider : MainAPI() {
    override var mainUrl = "https://egybest.la"
    override var name = "Egybest"
    override val hasMainPage = true
    override var lang = "ar"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
    )

    override val mainPage = mainPageOf(
        "movies" to "أفلام",
        "series" to "مسلسلات",
        "netflix" to "Netflix", // This might need a different channel name, we can adjust later
    )

    // v14: This function now calls the correct API endpoint.
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        // The API URL discovered by the user. This is the key to the solution.
        val apiUrl = "$mainUrl/api/v1/channel?channel=${request.data}&page=$page&paginate=true&returnContentOnly=true"

        // The API returns HTML content directly, not JSON.
        val apiResponseHtml = app.get(apiUrl).text
        // We parse this returned HTML string.
        val document = Jsoup.parse(apiResponseHtml)

        // We use the same reliable parsing logic from v12 on the API's HTML response.
        val home = document.select("div.grid > div").mapNotNull { it.toSearchResult() }
        
        return newHomePageResponse(request.name, home)
    }

    // This function is now correct for parsing the HTML fragment returned by the API.
    private fun Element.toSearchResult(): SearchResponse? {
        val linkElement = this.selectFirst("a.text-inherit") ?: return null
        
        val href = linkElement.attr("href")
        if (href.isBlank()) return null

        val title = linkElement.text().trim()
        val posterUrl = this.selectFirst("img")?.attr("src")

        val isMovie = href.contains("fylm")
        val absoluteUrl = if (href.startsWith("http")) href else "$mainUrl$href"

        return if (isMovie) {
            newMovieSearchResponse(title, absoluteUrl, TvType.Movie) {
                this.posterUrl = posterUrl
            }
        } else {
            newTvSeriesSearchResponse(title, absoluteUrl, TvType.TvSeries) {
                this.posterUrl = posterUrl
            }
        }
    }

    // v14: Search will also need to be updated to use an API.
    // For now, let's confirm the main page works.
    override suspend fun search(query: String): List<SearchResponse> {
        // The old search will not work. We need to find the search API endpoint next.
        // Let's focus on the main page first. Returning an empty list for now.
        return emptyList()
    }

    override suspend fun load(url: String): LoadResponse {
        // Placeholder
        return newMovieLoadResponse("Placeholder", url, TvType.Movie, url)
    }
    
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return false 
    }
}
