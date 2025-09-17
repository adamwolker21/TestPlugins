package com.example

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.fasterxml.jackson.annotation.JsonProperty
import java.net.URLDecoder

// v8: Using the correct API endpoint based on network analysis
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

    data class ContentItem(
        @JsonProperty("id") val id: Int?,
        @JsonProperty("name") val name: String?,
        @JsonProperty("slug") val slug: String?,
        @JsonProperty("poster") val poster: String?,
        @JsonProperty("backdrop") val backdrop: String?,
        @JsonProperty("is_series") val isSeries: Boolean?,
        @JsonProperty("release_date") val releaseDate: String?,
        @JsonProperty("rating") val rating: Double?,
        @JsonProperty("runTime") val runTime: Int?,
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
        @JsonProperty("channel") val channel: Channel?,
        @JsonProperty("content") val content: ChannelContent?
    )

    data class Channel(
        @JsonProperty("id") val id: Int?,
        @JsonProperty("name") val name: String?,
        @JsonProperty("slug") val slug: String?,
        @JsonProperty("public") val public: Boolean?,
        @JsonProperty("description") val description: String?,
        @JsonProperty("internal") val internal: Boolean?,
        @JsonProperty("config") val config: Config?,
        @JsonProperty("model_type") val modelType: String?,
        @JsonProperty("restriction") val restriction: String?,
        @JsonProperty("type") val type: String?,
        @JsonProperty("updated_at") val updatedAt: String?,
        @JsonProperty("user_id") val userId: Int?,
        @JsonProperty("user") val user: String?
    )

    data class Config(
        @JsonProperty("contentType") val contentType: String?,
        @JsonProperty("contentOrder") val contentOrder: String?,
        @JsonProperty("nestendEvent") val nestendEvent: String?,
        @JsonProperty("contentModel") val contentModel: String?,
        @JsonProperty("layout") val layout: String?,
        @JsonProperty("preventDeletion") val preventDeletion: Boolean?,
        @JsonProperty("autopldersMethod") val autopldersMethod: String?,
        @JsonProperty("restriction") val restriction: String?,
        @JsonProperty("paginationType") val paginationType: String?,
        @JsonProperty("selectedLayout") val selectedLayout: Boolean?
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

        try {
            // Step 1: Get initial cookies by visiting the main page
            val mainResponse = app.get(mainUrl, headers = mapOf("User-Agent" to mobileUserAgent))
            val cookies = mainResponse.cookies
            
            println("Egybest Debug: Initial cookies: ${cookies.keys}")

            // Step 2: Use the correct API endpoint that works
            val apiUrl = "$mainUrl/api/v1/channel/$pageSlug?channelType=channel&restriction=&loader=channelPage&page=$page"
            println("Egybest Debug: API URL: $apiUrl")

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

            // Make API request
            val response = app.get(apiUrl, headers = headers)
            
            println("Egybest Debug: Response status: ${response.code}")
            
            if (!response.isSuccessful) {
                println("Egybest Error: API request failed with status ${response.code}")
                println("Egybest Error: Response body: ${response.text.take(500)}")
                return newHomePageResponse(pageName, emptyList())
            }

            // Parse the response
            val channelResponse = response.parsedSafe<ChannelResponse>()
            
            if (channelResponse == null || channelResponse.content == null || channelResponse.content?.data == null) {
                println("Egybest Error: Failed to parse API response or no data found")
                println("Egybest Debug: Response: ${response.text.take(500)}")
                return newHomePageResponse(pageName, emptyList())
            }

            val contentData = channelResponse.content!!.data!!
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
