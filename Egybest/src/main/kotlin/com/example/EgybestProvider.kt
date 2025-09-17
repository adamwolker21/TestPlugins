package com.example

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.fasterxml.jackson.annotation.JsonProperty
import java.net.URLDecoder

// v12: Fixed type issues in mapOf functions
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
        @JsonProperty("backdrop") val backdrop: String?,
        @JsonProperty("is_series") val isSeries: Boolean?,
        @JsonProperty("release_date") val releaseDate: String?,
        @JsonProperty("rating") val rating: Double?,
        @JsonProperty("runtime") val runtime: Int?
    )

    data class SimpleApiResponse(
        @JsonProperty("current_page") val currentPage: Int?,
        @JsonProperty("data") val data: List<ContentItem>?,
        @JsonProperty("from") val from: Int?,
        @JsonProperty("next_page") val nextPage: Int?,
        @JsonProperty("per_page") val perPage: Int?,
        @JsonProperty("prev_page") val prevPage: Int?,
        @JsonProperty("to") val to: Int?,
        @JsonProperty("total") val total: Int?
    )

    private val channelIds = mapOf(
        "movies" to "2",
        "series" to "4",
        "netflix" to "19"
    )

    override val mainPage = mainPageOf(
        "movies" to "أفلام",
        "series" to "مسلسلات",
        "netflix" to "نتفليكس"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val pageName = request.name
        val pageType = request.data
        val channelId = channelIds[pageType] ?: "2"

        return try {
            // Get cookies and session data by simulating browser navigation
            val sessionData = establishSession()
            
            // Use the API endpoint that we know works
            val apiUrl = "$mainUrl/api/v1/channel/$channelId?restriction=&order=created_at.desc&page=$page&paginate=lengthAware&returnContentOnly=true"
            
            val response = app.get(
                apiUrl,
                headers = getApiHeaders(apiUrl, sessionData)
            )

            if (!response.isSuccessful) {
                println("Egybest API failed: ${response.code} - ${response.text}")
                return newHomePageResponse(pageName, emptyList())
            }

            val apiResponse = response.parsedSafe<SimpleApiResponse>()
            val items = apiResponse?.data ?: emptyList()

            val home = items.mapNotNull { item ->
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
            }

            newHomePageResponse(pageName, home)

        } catch (e: Exception) {
            println("Egybest error: ${e.message}")
            newHomePageResponse(pageName, emptyList())
        }
    }

    private suspend fun establishSession(): Map<String, String> {
        // First request to get initial cookies
        val initialResponse = app.get(mainUrl, headers = mapOf("User-Agent" to mobileUserAgent))
        val cookies = initialResponse.cookies

        // Visit a section page to get proper session cookies
        val sectionResponse = app.get(
            "$mainUrl/movies",
            headers = mapOf(
                "User-Agent" to mobileUserAgent,
                "Cookie" to cookies.map { (k, v) -> "$k=$v" }.joinToString("; ")
            )
        )

        val finalCookies = sectionResponse.cookies
        val xsrfToken = finalCookies["XSRF-TOKEN"]?.let { URLDecoder.decode(it, "UTF-8") } ?: ""

        return mapOf(
            "cookies" to finalCookies.map { (k, v) -> "$k=$v" }.joinToString("; "),
            "xsrfToken" to xsrfToken,
            "cfClearance" to (finalCookies["cf_clearance"] ?: "")
        )
    }

    private fun getApiHeaders(apiUrl: String, sessionData: Map<String, String>): Map<String, String> {
        return mapOf(
            "User-Agent" to mobileUserAgent,
            "Accept" to "application/json, text/plain, */*",
            "Accept-Language" to "en-US,en;q=0.9",
            "X-Requested-With" to "XMLHttpRequest",
            "X-Xsrf-Token" to (sessionData["xsrfToken"] ?: ""),
            "Referer" to "$mainUrl/movies",
            "Origin" to mainUrl,
            "Sec-Fetch-Dest" to "empty",
            "Sec-Fetch-Mode" to "cors",
            "Sec-Fetch-Site" to "same-origin",
            "Cookie" to (sessionData["cookies"] ?: "")
        )
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
