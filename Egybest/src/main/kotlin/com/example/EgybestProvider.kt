package com.example

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.fasterxml.jackson.annotation.JsonProperty
import java.net.URLDecoder

// v13: Full browser simulation with precise header replication
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

    // Updated user agent to match the one seen in network analysis
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
        val pageName = request.name
        val pageType = request.data
        val channelId = channelIds[pageType] ?: "2"
        val order = pageOrders[pageType] ?: "created_at.desc"
        val pageSlug = pageSlugs[pageType] ?: "movies"

        try {
            // Step 1: Get initial session with proper cookies
            val sessionData = getSessionData(pageSlug)
            
            // Step 2: Build API URL
            val apiUrl = "$mainUrl/api/v1/channel/$channelId?restriction=&order=$order&page=$page&paginate=lengthAware&returnContentOnly=true"
            
            // Step 3: Make API request with exact headers
            val response = app.get(
                apiUrl,
                headers = getApiHeaders(apiUrl, pageSlug, sessionData)
            )

            // Debug response
            println("Egybest API Response: ${response.code}")
            if (!response.isSuccessful) {
                println("Egybest API Error: ${response.text}")
                return newHomePageResponse(pageName, emptyList())
            }

            // Parse response
            val apiResponse = response.parsedSafe<SimpleApiResponse>()
            val items = apiResponse?.data ?: emptyList()

            println("Egybest Found ${items.size} items")

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

            return newHomePageResponse(pageName, home)

        } catch (e: Exception) {
            println("Egybest Error: ${e.message}")
            e.printStackTrace()
            return newHomePageResponse(pageName, emptyList())
        }
    }

    private suspend fun getSessionData(pageSlug: String): Map<String, String> {
        // First request to main page to get initial cookies
        val mainResponse = app.get(mainUrl, headers = mapOf(
            "User-Agent" to mobileUserAgent,
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8",
            "Accept-Language" to "en-US,en;q=0.9",
            "Sec-Fetch-Dest" to "document",
            "Sec-Fetch-Mode" to "navigate",
            "Sec-Fetch-Site" to "none"
        ))

        val initialCookies = mainResponse.cookies
        println("Initial cookies: ${initialCookies.keys}")

        // Second request to section page to get proper session cookies
        val sectionUrl = "$mainUrl/$pageSlug"
        val sectionResponse = app.get(sectionUrl, headers = mapOf(
            "User-Agent" to mobileUserAgent,
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8",
            "Accept-Language" to "en-US,en;q=0.9",
            "Referer" to mainUrl,
            "Sec-Fetch-Dest" to "document",
            "Sec-Fetch-Mode" to "navigate",
            "Sec-Fetch-Site" to "same-origin",
            "Cookie" to initialCookies.map { (k, v) -> "$k=$v" }.joinToString("; ")
        ))

        val finalCookies = sectionResponse.cookies
        println("Final cookies: ${finalCookies.keys}")

        // Extract XSRF token
        val xsrfToken = finalCookies["XSRF-TOKEN"]?.let { 
            URLDecoder.decode(it, "UTF-8") 
        } ?: ""

        // Extract cf_clearance (critical for Cloudflare)
        val cfClearance = finalCookies["cf_clearance"] ?: ""

        return mapOf(
            "cookies" to finalCookies.map { (k, v) -> "$k=$v" }.joinToString("; "),
            "xsrfToken" to xsrfToken,
            "cfClearance" to cfClearance
        )
    }

    private fun getApiHeaders(apiUrl: String, pageSlug: String, sessionData: Map<String, String>): Map<String, String> {
        return mapOf(
            "User-Agent" to mobileUserAgent,
            "Accept" to "application/json, text/plain, */*",
            "Accept-Encoding" to "gzip, deflate, br",
            "Accept-Language" to "en-US,en;q=0.9",
            "X-Requested-With" to "XMLHttpRequest",
            "X-Xsrf-Token" to (sessionData["xsrfToken"] ?: ""),
            "Referer" to "$mainUrl/$pageSlug",
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
