package com.example

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.fasterxml.jackson.annotation.JsonProperty
import java.net.URLDecoder

// v23: Final Build & Correct WebView Implementation
// This version corrects all previous build errors by implementing the WebViewResolver
// with the proper syntax and a stop-condition predicate, ensuring a valid Cloudflare session.
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

    // Data classes for parsing the JSON API response
    data class ContentItem(
        @JsonProperty("id") val id: Int?,
        @JsonProperty("name") val name: String?,
        @JsonProperty("slug") val slug: String?,
        @JsonProperty("poster") val poster: String?,
        @JsonProperty("is_series") val isSeries: Boolean?,
    )

    data class SimpleApiResponse(
        @JsonProperty("data") val data: List<ContentItem>?,
    )

    // Maps to hold the dynamic parts of the API URLs
    private val channelIds = mapOf("movies" to "2", "series" to "4", "netflix" to "19")
    private val pageOrders = mapOf("movies" to "created_at:desc", "series" to "budget:desc", "netflix" to "created_at:desc")
    private val pageSlugs = mapOf("movies" to "movies", "series" to "series-Movies", "netflix" to "Netflix")

    override val mainPage = mainPageOf(
        "movies" to "أفلام",
        "series" to "مسلسلات",
        "netflix" to "نتفليكس"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val pageType = request.data
        val channelId = channelIds[pageType] ?: "2"
        val order = pageOrders[pageType] ?: "created_at:desc"
        val pageSlug = pageSlugs[pageType] ?: "movies"
        val sectionUrl = "$mainUrl/$pageSlug"

        // Step 1: Use WebViewResolver to solve the Cloudflare JS challenge.
        // This is the key part that was failing before due to syntax errors.
        val webViewResponse = app.get(
            sectionUrl,
            // The interceptor will run the page in a WebView until the predicate is met.
            interceptor = WebViewResolver { html ->
                // The predicate returns true when it finds "class="grid"" in the HTML,
                // which confirms the actual page content has loaded.
                html.contains("class=\"grid\"")
            }
        )

        // Extract the valid session data obtained after solving the challenge.
        val validCookies = webViewResponse.cookies
        val validUserAgent = webViewResponse.request.headers["User-Agent"] ?: "" // Use the agent from the successful request

        val xsrfToken = validCookies["XSRF-TOKEN"]?.let {
            URLDecoder.decode(it, "UTF-8")
        } ?: throw ErrorLoadingException("Failed to obtain a valid XSRF Token from WebView.")

        // Step 2: Make the API call using the valid session data.
        val apiUrl = "$mainUrl/api/v1/channel/$channelId?restriction=&order=$order&page=$page&paginate=lengthAware&returnContentOnly=true"
        val apiHeaders = mapOf(
            "User-Agent" to validUserAgent,
            "Accept" to "application/json, text/plain, */*",
            "X-Requested-With" to "XMLHttpRequest",
            "X-Xsrf-Token" to xsrfToken,
            "Referer" to sectionUrl,
            "Cookie" to validCookies.map { (k, v) -> "$k=$v" }.joinToString("; ")
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
