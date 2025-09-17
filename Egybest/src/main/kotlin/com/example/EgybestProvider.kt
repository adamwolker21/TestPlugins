package com.example

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.fasterxml.jackson.annotation.JsonProperty
import java.net.URLDecoder

// v24: The Stable Regex Solution
// This version abandons all version-specific helpers (like WebViewResolver) and uses a robust,
// universal method: fetching the page's HTML, extracting the dynamic API URL with Regex,
// and then calling that URL with the captured session cookies. This will compile and run reliably.
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

    private val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/108.0.0.0 Safari/537.36"

    // Data classes for parsing the JSON API response
    data class ContentItem(
        @JsonProperty("name") val name: String?,
        @JsonProperty("slug") val slug: String?,
        @JsonProperty("poster") val poster: String?,
        @JsonProperty("is_series") val isSeries: Boolean?,
    )

    data class SimpleApiResponse(
        @JsonProperty("data") val data: List<ContentItem>?,
    )

    // Maps to hold the dynamic parts of the API URLs
    private val pageSlugs = mapOf(
        "movies" to "movies",
        "series" to "series-Movies",
        "netflix" to "Netflix"
    )

    override val mainPage = mainPageOf(
        "movies" to "أفلام",
        "series" to "مسلسلات",
        "netflix" to "نتفليكس"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val pageSlug = pageSlugs[request.data] ?: "movies"
        val sectionUrl = "$mainUrl/$pageSlug"

        // Step 1: Make an initial request to the section page to get cookies and the raw HTML.
        val initialResponse = app.get(sectionUrl, headers = mapOf("User-Agent" to userAgent))
        val sessionCookies = initialResponse.cookies
        val pageHtml = initialResponse.text

        // Step 2: Use Regex to find the dynamic API path hidden inside the JavaScript in the HTML.
        // This pattern looks for the specific API call the page makes.
        val apiPathRegex = Regex("""/api/v1/channel/\d+\?restriction=&order=[^'"]*""")
        val apiPath = apiPathRegex.find(pageHtml)?.value
            ?: throw ErrorLoadingException("Failed to find API path in page source. The site structure may have changed.")

        // Append the page number to the found path.
        val fullApiPath = "$apiPath&page=$page"
        val apiUrl = "$mainUrl$fullApiPath"

        // Extract the necessary XSRF token from the cookies.
        val xsrfToken = sessionCookies["XSRF-TOKEN"]?.let {
            URLDecoder.decode(it, "UTF-8")
        } ?: throw ErrorLoadingException("Failed to obtain XSRF Token.")

        // Step 3: Make the final API call with the extracted path and valid session data.
        val apiHeaders = mapOf(
            "User-Agent" to userAgent,
            "Accept" to "application/json, text/plain, */*",
            "X-Requested-With" to "XMLHttpRequest",
            "X-Xsrf-Token" to xsrfToken,
            "Referer" to sectionUrl,
            "Cookie" to sessionCookies.map { (k, v) -> "$k=$v" }.joinToString("; ")
        )

        val apiJsonResponse = app.get(apiUrl, headers = apiHeaders).parsed<SimpleApiResponse>()

        // Map the JSON data to search results for the UI.
        val home = apiJsonResponse.data?.mapNotNull { item ->
            val title = item.name ?: return@mapNotNull null
            val slug = item.slug ?: return@mapNotNull null
            val posterUrl = item.poster
            val absoluteUrl = "$mainUrl/titles/$slug"

            if (item.isSeries == true) {
                newTvSeriesSearchResponse(title, absoluteUrl, TvType.TvSeries) { this.posterUrl = posterUrl }
            } else {
                newMovieSearchResponse(title, absoluteUrl, TvType.Movie) { this.posterUrl = posterUrl }
            }
        } ?: listOf()

        return newHomePageResponse(request.name, home)
    }

    // Placeholders for other functionalities
    override suspend fun search(query: String): List<SearchResponse> { return emptyList() }
    override suspend fun load(url: String): LoadResponse { throw NotImplementedError() }
    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean { throw NotImplementedError() }
}
