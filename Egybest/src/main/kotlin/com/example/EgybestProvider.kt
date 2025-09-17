package com.example

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.fasterxml.jackson.annotation.JsonProperty
import java.net.URLDecoder

// v7: Enhanced debugging and alternative approach
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

    override val mainPage = mainPageOf(
        "movies" to "أفلام",
        "series" to "مسلسلات",
        "netflix" to "نتفليكس"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val pageName = request.name
        val pageType = request.data

        try {
            // Step 1: Get initial page to simulate browser behavior
            val mainResponse = app.get(mainUrl, headers = mapOf("User-Agent" to mobileUserAgent))
            val cookies = mainResponse.cookies
            
            println("Egybest Debug: Initial cookies: ${cookies.keys}")

            // Step 2: Try different API endpoints discovered in network analysis
            val apiUrls = listOf(
                "$mainUrl/api/v1/channel/$pageType?channelType=channel&restriction=&loader=channelPage&page=$page",
                "$mainUrl/api/v1/channel/$pageType?restriction=&order=created_at.desc&page=$page&paginate=lengthAware&returnContentOnly=true"
            )

            for ((index, apiUrl) in apiUrls.withIndex()) {
                try {
                    println("Egybest Debug: Trying API URL ${index + 1}: $apiUrl")
                    
                    val headers = mapOf(
                        "User-Agent" to mobileUserAgent,
                        "Accept" to "application/json, text/plain, */*",
                        "Accept-Language" to "en-US,en;q=0.9",
                        "X-Requested-With" to "XMLHttpRequest",
                        "Referer" to "$mainUrl/$pageType",
                        "Sec-Fetch-Dest" to "empty",
                        "Sec-Fetch-Mode" to "cors",
                        "Sec-Fetch-Site" to "same-origin",
                        "Cookie" to cookies.map { (key, value) -> "$key=$value" }.joinToString("; ")
                    )

                    val response = app.get(apiUrl, headers = headers)
                    
                    println("Egybest Debug: Response status for URL ${index + 1}: ${response.code}")
                    println("Egybest Debug: Response headers: ${response.headers}")

                    if (response.isSuccessful) {
                        val responseText = response.text
                        println("Egybest Debug: Response length: ${responseText.length}")
                        
                        if (responseText.length > 100) {
                            println("Egybest Debug: First 200 chars: ${responseText.take(200)}")
                        }

                        val apiResponse = response.parsedSafe<ApiResponseData>()
                        if (apiResponse != null && apiResponse.data != null) {
                            val home = apiResponse.data!!.mapNotNull { item ->
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
                            
                            println("Egybest Debug: Successfully loaded ${home.size} items from URL ${index + 1}")
                            return newHomePageResponse(pageName, home)
                        }
                    } else {
                        println("Egybest Debug: Failed to load from URL ${index + 1}, status: ${response.code}")
                        println("Egybest Debug: Response error: ${response.text.take(200)}")
                    }
                } catch (e: Exception) {
                    println("Egybest Debug: Error with URL ${index + 1}: ${e.message}")
                }
            }

            // Fallback: Try to scrape the HTML page directly
            println("Egybest Debug: Trying HTML scraping fallback")
            return tryHtmlScrapingFallback(pageType, pageName)

        } catch (e: Exception) {
            println("Egybest Error: Exception in getMainPage: ${e.message}")
            e.printStackTrace()
            return newHomePageResponse(pageName, emptyList())
        }
    }

    private suspend fun tryHtmlScrapingFallback(pageType: String, pageName: String): HomePageResponse {
        try {
            val pageUrl = "$mainUrl/$pageType"
            val response = app.get(pageUrl, headers = mapOf("User-Agent" to mobileUserAgent))
            
            if (!response.isSuccessful) {
                return newHomePageResponse(pageName, emptyList())
            }

            // Parse HTML to extract content (this is a basic example)
            val document = response.document
            val items = document.select("div.movie") // Adjust selector based on actual HTML
            
            val home = items.mapNotNull { item ->
                val titleElement = item.selectFirst("h2.title, a.title, [class*='title']")
                val title = titleElement?.text() ?: return@mapNotNull null
                
                val linkElement = item.selectFirst("a[href]")
                val href = linkElement?.attr("href") ?: return@mapNotNull null
                val absoluteUrl = if (href.startsWith("http")) href else "$mainUrl$href"
                
                val posterElement = item.selectFirst("img[src]")
                val posterUrl = posterElement?.attr("src")
                
                newMovieSearchResponse(title, absoluteUrl, TvType.Movie) {
                    this.posterUrl = posterUrl
                }
            }
            
            println("Egybest Debug: HTML scraping found ${home.size} items")
            return newHomePageResponse(pageName, home)
            
        } catch (e: Exception) {
            println("Egybest Error: HTML scraping failed: ${e.message}")
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
