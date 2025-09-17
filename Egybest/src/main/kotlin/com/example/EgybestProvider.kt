package com.example

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.module.kotlin.readValue
import java.net.URLDecoder
import java.util.zip.GZIPInputStream
import java.io.ByteArrayInputStream

// v3: Added response decompression and better JSON handling
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
    private val mapper = jsonMapper

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

    private fun decompressGzip(compressed: ByteArray): String {
        return GZIPInputStream(ByteArrayInputStream(compressed)).bufferedReader().use { it.readText() }
    }

    private fun parseJsonResponse(responseText: String): ApiResponseData {
        return try {
            mapper.readValue(responseText)
        } catch (e: Exception) {
            // حاول إصلاح JSON إذا كان هناك مشاكل في الترميز
            val cleanedText = responseText.replace(Regex("[^\\x00-\\x7F]"), "").trim()
            if (cleanedText.isNotEmpty()) {
                mapper.readValue(cleanedText)
            } else {
                throw e
            }
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val channelId = request.data
        val pageName = request.name
        val pagePath = pagePaths[pageName] ?: "movies"
        val pageUrl = "$mainUrl/$pagePath"

        try {
            // Step 1: Get initial cookies by visiting the page
            val mainPageResponse = app.get(pageUrl, headers = mapOf("User-Agent" to mobileUserAgent))
            val cookies = mainPageResponse.cookies
            
            // Debug: Print all cookies
            println("Egybest Debug: Cookies from $pageUrl")
            cookies.forEach { (key, value) ->
                println("$key: $value")
            }

            // Extract XSRF token from cookies
            val xsrfToken = cookies["XSRF-TOKEN"]?.let { 
                URLDecoder.decode(it, "UTF-8") 
            }

            if (xsrfToken == null) {
                println("Egybest Error: XSRF-TOKEN not found in cookies")
                return newHomePageResponse(pageName, emptyList())
            }

            // Build API URL
            val apiUrl = "$mainUrl/api/v1/channel/$channelId?restriction=&order=created_at:desc&page=$page&paginate=lengthAware&returnContentOnly=true"
            println("Egybest Debug: API URL: $apiUrl")

            // Prepare cookies string
            val cookieString = cookies.map { (key, value) -> 
                "$key=${URLDecoder.decode(value, "UTF-8")}" 
            }.joinToString("; ")

            // Prepare headers without compression to handle it manually
            val headers = mapOf(
                "User-Agent" to mobileUserAgent,
                "Accept" to "application/json, text/plain, */*",
                "Accept-Language" to "en-US,en;q=0.9",
                "X-Requested-With" to "XMLHttpRequest",
                "X-Xsrf-Token" to xsrfToken,
                "Referer" to pageUrl,
                "Sec-Fetch-Dest" to "empty",
                "Sec-Fetch-Mode" to "cors",
                "Sec-Fetch-Site" to "same-origin",
                "Cookie" to cookieString,
                "Accept-Encoding" to "identity" // طلب عدم ضغط الاستجابة
            )

            // Make API request
            val response = app.get(apiUrl, headers = headers)
            
            // Debug: Print response status and headers
            println("Egybest Debug: Response Status: ${response.statusCode}")
            println("Egybest Debug: Response Headers: ${response.headers}")
            
            // Check if response is successful
            if (!response.isSuccessful) {
                println("Egybest Error: API request failed with status ${response.statusCode}")
                return newHomePageResponse(pageName, emptyList())
            }

            // Get response body as text
            val responseText = response.text
            println("Egybest Debug: Response length: ${responseText.length}")
            println("Egybest Debug: First 200 chars: ${responseText.take(200)}")

            // Parse response
            val apiResponse = parseJsonResponse(responseText)
            
            // Debug: Print API response data
            println("Egybest Debug: API Response data count: ${apiResponse.data?.size}")

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
            
            println("Egybest Debug: Successfully loaded ${home.size} items for $pageName")
            return newHomePageResponse(pageName, home)
            
        } catch (e: Exception) {
            println("Egybest Error: Exception in getMainPage: ${e.message}")
            e.printStackTrace()
            return newHomePageResponse(pageName, emptyList())
        }
    }
    
    override suspend fun search(query: String): List<SearchResponse> {
        // TODO: Implement search functionality
        return emptyList()
    }

    override suspend fun load(url: String): LoadResponse {
        // TODO: Implement load functionality
        return newMovieLoadResponse("Placeholder", url, TvType.Movie, url)
    }
    
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // TODO: Implement loadLinks functionality
        return false 
    }
}
