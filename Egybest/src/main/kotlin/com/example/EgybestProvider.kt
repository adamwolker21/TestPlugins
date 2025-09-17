package com.example

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import org.jsoup.Jsoup
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.utils.AppUtils.parseJson

// v12: Corrected the content scraping logic based on user-provided HTML.
// The `toSearchResult` function now uses precise selectors for title, link, and poster.
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

    private data class EgybestApiResponse(
        @JsonProperty("status") val status: String,
        @JsonProperty("html") val html: String
    )
    
    override val mainPage = mainPageOf(
        "/movies" to "أفلام",
        "/series" to "مسلسلات",
        "/netflix" to "Netflix",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = "$mainUrl${request.data}?page=$page"
        val document = app.get(url).document
        // This selector should now work correctly with the updated `toSearchResult`
        val home = document.select("div.grid > div").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(request.name, home)
    }

    // v12: Completely rewritten based on the new HTML structure provided by the user.
    private fun Element.toSearchResult(): SearchResponse? {
        // Find the link which contains the clean title text. This is more reliable.
        val linkElement = this.selectFirst("a.text-inherit") ?: return null
        
        val href = linkElement.attr("href")
        // Ensure the link is not empty before proceeding
        if (href.isBlank()) return null

        val title = linkElement.text().trim()
        val posterUrl = this.selectFirst("img")?.attr("src")

        // Differentiate based on keywords in the URL
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

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/search?q=$query"
        val document = app.get(url).document
        return document.select("div.grid > div").mapNotNull { it.toSearchResult() }
    }

    // `load` and `loadLinks` are still pending and will be addressed next.
    override suspend fun load(url: String): LoadResponse {
        // This is still placeholder logic from the old site.
        // After we confirm the main page works, we will fix this.
        val document = app.get(url).document
        val title = "Placeholder Title" // Placeholder
        val plot = "Placeholder Plot" // Placeholder
        
        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.plot = plot
        }
    }
    
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // Logic from v10 is kept but disabled to focus on content loading.
        // Will be re-enabled and fixed after `load` is working.
        return false 
    }
}
