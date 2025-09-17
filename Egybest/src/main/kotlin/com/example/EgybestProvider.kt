package com.example

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.fasterxml.jackson.annotation.JsonProperty
import java.net.URLDecoder

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

    // v2: Added more logging and error handling
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val channelId = request.data
        val pagePath = pagePaths[request.name] ?: "movies"
        val pageUrl = "$mainUrl/$pagePath"

        try {
            // Visit the section page to get cookies
            val mainPageResponse = app.get(pageUrl, headers = mapOf("User-Agent" to mobileUserAgent))
            val cookies = mainPageResponse.cookies

            // Log cookies for debugging
            println("Cookies: $cookies")

            // Extract XSRF token correctly from cookies
            val xsrfToken = cookies["XSRF-TOKEN"]?.let {
                URLDecoder.decode(it, "UTF-8")
            }

            if (xsrfToken == null) {
                throw ErrorLoadingException("Failed to retrieve XSRF token for page: $pageUrl")
            }

            // Build correct API URL with proper parameters
            val apiUrl = "$mainUrl/api/v1/channel/$channelId?restriction=&order=created_at:desc&page=$page&paginate=lengthAware&returnContentOnly=true"

            // Prepare cookies for the request
            val cookieString = cookies.map { (key, value) ->
                "$key=${URLDecoder.decode(value, "UTF-8")}"
            }.joinToString("; ")

            // Get cf_clearance cookie if available (important for Cloudflare)
            val cfClearance = cookies["cf_clearance"] ?: ""

            // Complete headers as seen in the screenshot
            val headers = mapOf(
                "User-Agent" to mobileUserAgent,
                "Accept" to "application/json, text/plain, */*",
                "Accept-Encoding" to "gzip, deflate, br",
                "Accept-Language" to "en-US,en;q=0.9",
                "X-Requested-With" to "XMLHttpRequest",
                "X-Xsrf-Token" to xsrfToken,
                "Referer" to pageUrl,
                "Sec-Fetch-Dest" to "empty",
                "Sec-Fetch-Mode" to "cors",
                "Sec-Fetch-Site" to "same-origin",
                "Cookie" to "$cookieString${if (cfClearance.isNotEmpty()) "; cf_clearance=$cfClearance" else ""}"
            )

            // Log the API URL and headers for debugging
            println("API URL: $apiUrl")
            println("Headers: $headers")

            // Get the raw response text first for debugging
            val response = app.get(apiUrl, headers = headers)
            val responseText = response.text
            println("Response text: $responseText")

            // Now try to parse the JSON
            val apiResponse = response.parsed<ApiResponseData>()

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
        } catch (e: Exception) {
            e.printStackTrace()
            throw ErrorLoadingException("Failed to load content: ${e.message}")
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        // سيتم تنفيذ البحث لاحقاً
        return emptyList()
    }

    override suspend fun load(url: String): LoadResponse {
        // سيتم تنفيذ تحميل التفاصيل لاحقاً
        return newMovieLoadResponse("Placeholder", url, TvType.Movie, url)
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // سيتم تنفيذ استخراج الروابط لاحقاً
        return false
    }
}
