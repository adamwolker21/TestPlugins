package com.example

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.fasterxml.jackson.annotation.JsonProperty
import java.net.URLDecoder

// v21: The WebView Solution
// This version uses a real (but invisible) WebView to solve Cloudflare's JavaScript challenge,
// guaranteeing we get a valid session before making the API call. This is the definitive method for protected sites.
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

    private val mobileUserAgent = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Mobile Safari/537.36"

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

        // --- Step 1: Solve Cloudflare challenge using WebView ---
        // We make a special request that uses an invisible WebView to get valid cookies.
        // This process perfectly mimics a real browser visit.
        val webViewResponse = app.get(
            sectionUrl,
            interceptor = WebViewResolver(
                // We wait for the main grid element to appear, which confirms the page has loaded after the challenge.
                Regex("""class="grid""") 
            )
        )
        
        val validCookies = webViewResponse.cookies
        val validUserAgent = webViewResponse.request.headers["User-Agent"] ?: mobileUserAgent

        // Extract the crucial XSRF-TOKEN from the valid cookies.
        val xsrfToken = validCookies["XSRF-TOKEN"]?.let {
            URLDecoder.decode(it, "UTF-8")
        } ?: throw ErrorLoadingException("Failed to obtain a valid XSRF Token after WebView.")

        // --- Step 2: Make the API call with the "golden" session data ---
        val apiUrl = "$mainUrl/api/v1/channel/$channelId?restriction=&order=$order&page=$page&paginate=lengthAware&returnContentOnly=true"

        val apiHeaders = mapOf(
            "User-Agent" to validUserAgent,
            "Accept" to "application/json, text/plain, */*",
            "X-Requested-With" to "XMLHttpRequest",
            "X-Xsrf-Token" to xsrfToken,
            "Referer" to sectionUrl,
            // We provide the full, valid cookie string obtained from the WebView.
            "Cookie" to validCookies.map { (k, v) -> "$k=$v" }.joinToString("; ")
        )
        
        val apiJsonResponse = app.get(apiUrl, headers = apiHeaders).parsed<SimpleApiResponse>()

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
    
    // Placeholders
    override suspend fun search(query: String): List<SearchResponse> { return emptyList() }
    override suspend fun load(url: String): LoadResponse { throw NotImplementedError() }
    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean { throw NotImplementedError() }
}
