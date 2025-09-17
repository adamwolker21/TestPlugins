package com.example

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import org.jsoup.Jsoup
import java.net.URLDecoder

// v15: The definitive solution. Implements a two-step process to mimic browser behavior.
// 1. Fetches the main page to acquire necessary cookies (especially XSRF-TOKEN).
// 2. Makes the API call with all the required headers (Cookie, Referer, X-Xsrf-Token).
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

    // Using the path segments as keys for cleaner code.
    override val mainPage = mainPageOf(
        "movies" to "أفلام",
        "series" to "مسلسلات",
        // Netflix might be a special channel, we can investigate later if needed.
        "netflix" to "Netflix",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val pageUrl = "$mainUrl/${request.data}"

        // Step 1: Visit the main page to get the session cookies and security tokens.
        val mainPageResponse = app.get(pageUrl)
        val cookies = mainPageResponse.cookies
        // The XSRF-TOKEN is crucial for the API call to be accepted.
        val xsrfToken = cookies["XSRF-TOKEN"]?.let { URLDecoder.decode(it, "UTF-8") }

        // If we can't get the token, we can't proceed.
        if (xsrfToken == null) {
            throw ErrorLoadingException("Failed to retrieve XSRF token.")
        }

        // The correct API URL structure discovered by the user.
        val apiUrl = "$mainUrl/api/v1/channel/${request.data}?channelType=channel&restriction=&loader=channelPage&page=$page"

        // Step 2: Make the API call with all the necessary headers to look like a real browser.
        val headers = mapOf(
            "Accept" to "application/json",
            "X-Requested-With" to "XMLHttpRequest",
            "X-Xsrf-Token" to xsrfToken,
            "Referer" to pageUrl,
            "Cookie" to cookies.map { (key, value) -> "$key=$value" }.joinToString("; ")
        )
        
        val apiResponseHtml = app.get(apiUrl, headers = headers).text
        val document = Jsoup.parse(apiResponseHtml)

        val home = document.select("div.grid > div").mapNotNull { it.toSearchResult() }
        
        return newHomePageResponse(request.name, home)
    }

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

    override suspend fun search(query: String): List<SearchResponse> {
        // Will be fixed later, focusing on the main page.
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
