package com.example

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.fasterxml.jackson.annotation.JsonProperty
import java.net.URLDecoder

// v10: Focus on numeric channel ID endpoint with improved token handling
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
        @JsonProperty("runtime") val runtime: Int?,
        @JsonProperty("model_type") val modelType: String?,
        @JsonProperty("status") val status: String?,
        @JsonProperty("certification") val certification: String?,
        @JsonProperty("description") val description: String?,
        @JsonProperty("primary_video") val primaryVideo: PrimaryVideo?
    )

    data class PrimaryVideo(
        @JsonProperty("id") val id: Int?,
        @JsonProperty("title_id") val titleId: Int?,
        @JsonProperty("name") val name: String?,
        @JsonProperty("category") val category: String?,
        @JsonProperty("episode_id") val episodeId: Int?,
        @JsonProperty("season_num") val seasonNum: Int?,
        @JsonProperty("episode_num") val episodeNum: Int?,
        @JsonProperty("score") val score: Int?,
        @JsonProperty("model_type") val modelType: String?
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

    private val pageOrders = mapOf(
        "movies" to "created_at.desc",
        "series" to "budget.desc",
        "netflix" to "created_at.desc"
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
        val order = pageOrders[pageType] ?: "created_at.desc"

        try {
            // Step 1: Get initial cookies by visiting the main page
            val mainResponse = app.get(mainUrl, headers = mapOf("User-Agent" to mobileUserAgent))
            val cookies = mainResponse.cookies
            
            println("Egybest Debug: Initial cookies: ${cookies.keys}")

            // Step 2: Extract XSRF token from cookies
            val xsrfToken = cookies["XSRF-TOKEN"]?.let { 
                URLDecoder.decode(it, "UTF-8") 
            }

            if (xsrfToken == null) {
                println("Egybest Error: XSRF-TOKEN not found in cookies")
                return newHomePageResponse(pageName, emptyList())
            }

            // Step 3: Build API URL
            val apiUrl = "$mainUrl/api/v1/channel/$channelId?restriction=&order=$order&page=$page&paginate=lengthAware&returnContentOnly=true"
            println("Egybest Debug: API URL: $apiUrl")

            // Step 4: Prepare headers exactly as in the browser
            val headers = mapOf(
                "User-Agent" to mobileUserAgent,
                "Accept" to "application/json, text/plain, */*",
                "Accept-Encoding" to "gzip, deflate, br",
                "Accept-Language" to "en-US,en;q=0.9",
                "X-Requested-With" to "XMLHttpRequest",
                "X-Xsrf-Token" to xsrfToken,
                "Referer" to "$mainUrl/${getPageSlug(pageType)}",
                "Sec-Fetch-Dest" to "empty",
                "Sec-Fetch-Mode" to "cors",
                "Sec-Fetch-Site" to "same-origin",
                "Cookie" to cookies.map { (key, value) -> "$key=$value" }.joinToString("; ")
            )

            // Step 5: Make API request
            val response = app.get(apiUrl, headers = headers)
            
            println("Egybest Debug: Response status: ${response.code}")
            
            if (!response.isSuccessful) {
                println("Egybest Error: API request failed with status ${response.code}")
                println("Egybest Error: Response body: ${response.text.take(500)}")
                return newHomePageResponse(pageName, emptyList())
            }

            // Step 6: Parse the response
            val apiResponse = response.parsedSafe<SimpleApiResponse>()
            
            if (apiResponse == null || apiResponse.data == null) {
                println("Egybest Error: Failed to parse API response or no data found")
                println("Egybest Debug: Full response: ${response.text}")
                return newHomePageResponse(pageName, emptyList())
            }

            val contentData = apiResponse.data!!
            println("Egybest Debug: Found ${contentData.size} items in response")

            val home = contentData.mapNotNull { item ->
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
            
            println("Egybest Debug: Successfully loaded ${home.size} items for $pageName")
            return newHomePageResponse(pageName, home)
            
        } catch (e: Exception) {
            println("Egybest Error: Exception in getMainPage: ${e.message}")
            e.printStackTrace()
            return newHomePageResponse(pageName, emptyList())
        }
    }

    private fun getPageSlug(pageType: String): String {
        return when (pageType) {
            "movies" -> "movies"
            "series" -> "series-Movies"
            "netflix" -> "Netflix"
            else -> "movies"
        }
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
