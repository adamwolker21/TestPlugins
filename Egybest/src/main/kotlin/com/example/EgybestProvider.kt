package com.example

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.fasterxml.jackson.annotation.JsonProperty
import java.net.URLDecoder

// v9: Trying both API endpoints that were found in network analysis
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

    data class ChannelResponse(
        @JsonProperty("channel") val channel: Any?,
        @JsonProperty("content") val content: ChannelContent?
    )

    data class ChannelContent(
        @JsonProperty("current_page") val currentPage: Int?,
        @JsonProperty("data") val data: List<ContentItem>?,
        @JsonProperty("from") val from: Int?,
        @JsonProperty("next_page") val nextPage: Int?,
        @JsonProperty("per_page") val perPage: Int?,
        @JsonProperty("prev_page") val prevPage: Int?,
        @JsonProperty("to") val to: Int?,
        @JsonProperty("total") val total: Int?
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

    private val pageApiEndpoints = mapOf(
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
        val pageSlug = pageApiEndpoints[pageType] ?: "movies"
        val channelId = channelIds[pageType] ?: "2"

        try {
            // Step 1: Get initial cookies by visiting the main page
            val mainResponse = app.get(mainUrl, headers = mapOf("User-Agent" to mobileUserAgent))
            val cookies = mainResponse.cookies
            
            println("Egybest Debug: Initial cookies: ${cookies.keys}")

            // Try both API endpoints
            val apiUrls = listOf(
                // First endpoint: channel slug with loader
                "$mainUrl/api/v1/channel/$pageSlug?channelType=channel&restriction=&loader=channelPage&page=$page",
                // Second endpoint: channel ID with order and pagination
                "$mainUrl/api/v1/channel/$channelId?restriction=&order=created_at.desc&page=$page&paginate=lengthAware&returnContentOnly=true"
            )

            for ((index, apiUrl) in apiUrls.withIndex()) {
                try {
                    println("Egybest Debug: Trying API URL ${index + 1}: $apiUrl")
                    
                    // Prepare headers exactly as in the browser
                    val headers = mapOf(
                        "User-Agent" to mobileUserAgent,
                        "Accept" to "application/json, text/plain, */*",
                        "Accept-Encoding" to "gzip, deflate, br",
                        "Accept-Language" to "en-US,en;q=0.9",
                        "X-Requested-With" to "XMLHttpRequest",
                        "Referer" to "$mainUrl/$pageSlug",
                        "Sec-Fetch-Dest" to "empty",
                        "Sec-Fetch-Mode" to "cors",
                        "Sec-Fetch-Site" to "same-origin",
                        "Cookie" to cookies.map { (key, value) -> "$key=$value" }.joinToString("; ")
                    )

                    val response = app.get(apiUrl, headers = headers)
                    
                    println("Egybest Debug: Response status for URL ${index + 1}: ${response.code}")
                    
                    if (response.isSuccessful) {
                        val responseText = response.text
                        println("Egybest Debug: Response length: ${responseText.length}")
                        
                        // Try to parse as ChannelResponse (first endpoint)
                        val channelResponse = response.parsedSafe<ChannelResponse>()
                        if (channelResponse != null && channelResponse.content != null && channelResponse.content?.data != null) {
                            val contentData = channelResponse.content!!.data!!
                            println("Egybest Debug: Found ${contentData.size} items from endpoint 1")
                            return createHomePageResponse(contentData, pageName)
                        }
                        
                        // Try to parse as SimpleApiResponse (second endpoint)
                        val simpleResponse = response.parsedSafe<SimpleApiResponse>()
                        if (simpleResponse != null && simpleResponse.data != null) {
                            val contentData = simpleResponse.data!!
                            println("Egybest Debug: Found ${contentData.size} items from endpoint 2")
                            return createHomePageResponse(contentData, pageName)
                        }
                        
                        println("Egybest Debug: Failed to parse response from URL ${index + 1}")
                    } else {
                        println("Egybest Debug: Failed to load from URL ${index + 1}, status: ${response.code}")
                    }
                } catch (e: Exception) {
                    println("Egybest Debug: Error with URL ${index + 1}: ${e.message}")
                }
            }

            println("Egybest Error: All API endpoints failed")
            return newHomePageResponse(pageName, emptyList())
            
        } catch (e: Exception) {
            println("Egybest Error: Exception in getMainPage: ${e.message}")
            e.printStackTrace()
            return newHomePageResponse(pageName, emptyList())
        }
    }

    private fun createHomePageResponse(data: List<ContentItem>, pageName: String): HomePageResponse {
        val home = data.mapNotNull { item ->
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
