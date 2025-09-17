package com.example

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.fasterxml.jackson.annotation.JsonProperty
import java.net.URLDecoder

// v20: Final Refinement.
// This version refactors the user's excellent code to ensure a single, perfectly consistent
// session from the first request to the API call, solving potential Cloudflare mismatches.
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

    // Data classes are perfectly structured by the user.
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

    // Maps are correctly identified by the user.
    private val channelIds = mapOf(
        "movies" to "2",
        "series" to "4",
        "netflix" to "19"
    )

    private val pageOrders = mapOf(
        "movies" to "created_at:desc",
        "series" to "budget:desc",
        "netflix" to "created_at:desc"
    )

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
        val pageType = request.data
        val channelId = channelIds[pageType] ?: "2"
        val order = pageOrders[pageType] ?: "created_at:desc"
        val pageSlug = pageSlugs[pageType] ?: "movies"
        val sectionUrl = "$mainUrl/$pageSlug"

        // --- Refined Session Handling ---
        // By performing the session-gathering request and the API request in sequence,
        // we let the underlying HTTP client handle the cookies perfectly, ensuring consistency.
        
        // Step 1: Visit the section page to acquire the necessary session cookies (including cf_clearance).
        // The `app` object will automatically store and reuse these cookies for the next request.
        val sectionResponse = app.get(sectionUrl, headers = mapOf("User-Agent" to mobileUserAgent))
        val sessionCookies = sectionResponse.cookies
        
        val xsrfToken = sessionCookies["XSRF-TOKEN"]?.let { 
            URLDecoder.decode(it, "UTF-8") 
        } ?: throw ErrorLoadingException("Failed to obtain XSRF Token.")
        
        // Step 2: Build the API URL.
        val apiUrl = "$mainUrl/api/v1/channel/$channelId?restriction=&order=$order&page=$page&paginate=lengthAware&returnContentOnly=true"

        // Step 3: Build the headers for the API request, using the cookies from the previous step.
        val apiHeaders = mapOf(
            "User-Agent" to mobileUserAgent,
            "Accept" to "application/json, text/plain, */*",
            "X-Requested-With" to "XMLHttpRequest",
            "X-Xsrf-Token" to xsrfToken,
            "Referer" to sectionUrl // The referer should be the section page we just visited.
        )
        
        // Step 4: Make the API request. The `app` object will automatically include the cookies.
        val apiJsonResponse = app.get(apiUrl, headers = apiHeaders).parsed<SimpleApiResponse>()

        val home = apiJsonResponse.data?.mapNotNull { item ->
            val title = item.name ?: return@mapNotNull null
            val slug = item.slug ?: return@mapNotNull null
            val posterUrl = item.poster
            val absoluteUrl = "$mainUrl/titles/$slug"
            
            if (item.isSeries == true) {
                newTvSeriesSearchResponse(title, absoluteUrl, TvType.TvSeries) {
                    this.posterUrl = posterUrl
                }
            } else {
                newMovieSearchResponse(title, absoluteUrl, TvType.Movie) {
                    this.posterUrl = posterUrl
                }
            }
        } ?: listOf()

        return newHomePageResponse(request.name, home)
    }
    
    // Placeholders remain for future implementation.
    override suspend fun search(query: String): List<SearchResponse> {
        return emptyList()
    }

    override suspend fun load(url: String): LoadResponse {
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
