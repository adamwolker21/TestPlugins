package com.example

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import org.jsoup.Jsoup
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.utils.AppUtils.parseJson

// v11: Reworked scraping logic for the new website layout.
// Updated main page sections, content selectors, and search functionality.
class EgybestProvider : MainAPI() {
    // The main URL seems to have changed again based on the provided HTML.
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

    // Updated main page sections as requested.
    override val mainPage = mainPageOf(
        "/movies" to "أفلام",
        "/series" to "مسلسلات", // Corrected from series-Movies to /series based on site structure
        "/netflix" to "Netflix",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = "$mainUrl${request.data}?page=$page"
        val document = app.get(url).document
        // The new layout uses a grid system. We select each item in the grid.
        val home = document.select("div.grid > div").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(request.name, home)
    }

    // Rewritten to parse the new HTML structure for each movie/series item.
    private fun Element.toSearchResult(): SearchResponse? {
        val linkElement = this.selectFirst("a") ?: return null
        val href = linkElement.attr("href")
        if (href.isBlank()) return null

        val title = this.selectFirst("a.text-inherit")?.text()?.trim()
            ?: this.selectFirst("img")?.attr("alt") ?: return null
        val posterUrl = this.selectFirst("img")?.attr("src")

        // The URL structure helps differentiate between movies ('fylm') and series ('mslsl').
        val isMovie = href.contains("fylm")

        return if (isMovie) {
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = posterUrl
            }
        } else {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = posterUrl
            }
        }
    }

    // Updated search URL and result parsing.
    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/search?q=$query"
        val document = app.get(url).document
        return document.select("div.grid > div").mapNotNull { it.toSearchResult() }
    }

    // NOTE: The `load` function might need updates if the movie detail page structure has also changed.
    // This will be addressed in the next step if necessary.
    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        // These selectors are from the old site and will likely fail.
        // For now, we are focusing on getting the main pages to work.
        val title = document.selectFirst("div.movie_title h1")?.ownText()?.trim() ?: "Title Not Found"
        val poster = document.selectFirst("div.movie_img img")?.attr("src")
        val year = document.selectFirst("div.movie_title h1 a")?.text()?.toIntOrNull()
        val plot = document.selectFirst("div.mbox_contenido p")?.text()?.trim() ?: "Plot not found"
        val tags = document.select("div.mbox.tags a").map { it.text() }
        val isMovie = url.contains("fylm")

        return if (isMovie) {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.year = year
                this.plot = plot
                this.tags = tags
            }
        } else {
            val episodes = document.select("#episodes_list div.tr a").map {
                val epHref = it.attr("href")
                val epTitle = it.selectFirst("span.title")?.text()
                val seasonNum = it.selectFirst("span.season")?.text()?.replace("S", "")?.trim()?.toIntOrNull()
                newEpisode(epHref) {
                    this.name = epTitle
                    this.season = seasonNum
                }
            }.reversed()

            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.year = year
                this.plot = plot
                this.tags = tags
            }
        }
    }
    
    // NOTE: `loadLinks` logic is kept from v10. It might need adjustments
    // after we confirm the main content is loading correctly.
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // This logic is likely broken due to the site update.
        // We will fix it after fixing the `load` function.
        // For now, returning false to prevent errors.
        return false // Temporarily disabled to focus on content loading.
    }
}
