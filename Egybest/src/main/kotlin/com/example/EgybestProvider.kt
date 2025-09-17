package com.example

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.fasterxml.jackson.annotation.JsonProperty
import java.net.URLDecoder

// v16: The REAL solution. Targeting the correct JSON API endpoint discovered by the user.
// This version parses JSON directly, which is far more reliable than HTML scraping.
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

    // Data classes that perfectly match the JSON structure from the user's screenshot.
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

    // Using the correct Channel IDs instead of URL paths.
    // We need the user to confirm the ID for "series" and "netflix".
    override val mainPage = mainPageOf(
        "2" to "أفلام", // ID 2 is confirmed for Movies
        "3" to "مسلسلات", // Assuming 3 for series, needs confirmation
        "10" to "Netflix", // Assuming 10 for Netflix, needs confirmation
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val channelId = request.data
        // We still need to visit a page first to get valid cookies.
        val mainPageResponse = app.get("$mainUrl/movies")
        val cookies = mainPageResponse.cookies
        val xsrfToken = cookies["XSRF-TOKEN"]?.let { URLDecoder.decode(it, "UTF-8") }

        if (xsrfToken == null) {
            throw ErrorLoadingException("Failed to retrieve XSRF token.")
        }

        // The correct JSON API endpoint.
        val apiUrl = "$mainUrl/api/v1/channel/$channelId?restriction=&order=created_at:desc&page=$page&paginate=lengthAware&returnContentOnly=true"

        val headers = mapOf(
            "Accept" to "application/json",
            "X-Requested-With" to "XMLHttpRequest",
            "X-Xsrf-Token" to xsrfToken,
            "Referer" to "$mainUrl/movies",
            "Cookie" to cookies.map { (key, value) -> "$key=$value" }.joinToString("; ")
        )

        // Make the API call and parse the JSON response directly into our data classes.
        val apiResponse = app.get(apiUrl, headers = headers).parsed<ApiResponseData>()

        val home = apiResponse.data?.mapNotNull { item ->
            val title = item.name ?: return@mapNotNull null
            val slug = item.slug ?: return@mapNotNull null
            // The API provides the full poster path, no need to prepend TMDB URL.
            val posterUrl = item.poster

            // The URL is constructed from the slug.
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

    // `toSearchResult` is no longer needed for the main page as we are parsing JSON.
    
    override suspend fun search(query: String): List<SearchResponse> {
        // Will be fixed later.
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
