package com.example

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.fasterxml.jackson.annotation.JsonProperty
import java.net.URLDecoder

// v18: Perfecting the simulation by adding a mobile User-Agent.
// This is a critical header that makes our requests indistinguishable from a real browser.
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

    // A standard mobile browser User-Agent.
    private val mobileUserAgent = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/113.0.0.0 Mobile Safari/537.36"

    data class ApiDataItem(
        @JsonProperty("id") val id: Int?,
        @JsonProperty("slug") val slug: String?,
        @JsonProperty("name") val name: String?,
        @JsonProperty("poster") val poster: String?,
        @JsonProperty("is_series") val isSeries: Boolean?
    )

    data class ApiResponseData(
        @JsonProperty("data") val data: List<ApiDataItem>?
    )

    private val pagePaths = mapOf(
        "movies" to "movies",
        "series-Movies" to "series-Movies",
        "Netflix" to "Netflix"
    )

    override val mainPage = mainPageOf(
        "2" to "movies",
        "4" to "series-Movies",
        "19" to "Netflix",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val channelId = request.data
        val pagePath = pagePaths[request.name] ?: "movies"
        val pageUrl = "$mainUrl/$pagePath"

        // Step 1: Visit the section page to get cookies, mimicking the first part of navigation.
        val mainPageResponse = app.get(pageUrl, headers = mapOf("User-Agent" to mobileUserAgent))
        val cookies = mainPageResponse.cookies
        val xsrfToken = cookies["XSRF-TOKEN"]?.let { URLDecoder.decode(it, "UTF-8") }

        if (xsrfToken == null) {
            throw ErrorLoadingException("Failed to retrieve XSRF token for page: $pageUrl")
        }

        val apiUrl = "$mainUrl/api/v1/channel/$channelId?restriction=&order=created_at:desc&page=$page&paginate=lengthAware&returnContentOnly=true"

        // Step 2: Make the API call with ALL necessary headers, including the User-Agent.
        val headers = mapOf(
            "User-Agent" to mobileUserAgent,
            "Accept" to "application/json",
            "X-Requested-With" to "XMLHttpRequest",
            "X-Xsrf-Token" to xsrfToken,
            "Referer" to pageUrl,
            "Cookie" to cookies.map { (key, value) -> "$key=$value" }.joinToString("; ")
        )

        val apiResponse = app.get(apiUrl, headers = headers).parsed<ApiResponseData>()

        val home = apiResponse.data?.mapNotNull { item ->
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
