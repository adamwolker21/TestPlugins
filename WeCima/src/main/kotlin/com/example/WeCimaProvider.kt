package com.example

import android.util.Base64
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import com.example.extractors.GeneralPackedExtractor
import com.example.extractors.VidbomExtractor
import com.example.extractors.WeCimaExtractor
import org.jsoup.nodes.Element

class WeCimaProvider : MainAPI() {
    // Basic provider information
    override var mainUrl = "https://wecima.now/"
    override var name = "WeCima"
    override val hasMainPage = true
    override var lang = "ar"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.AsianDrama,
    )

    // Cloudflare interceptor
    private val interceptor = CloudflareKiller()

    // Main page sections
    override val mainPage = mainPageOf(
        "/category/%d9%85%d8%b3%d9%84%d8%b3%d9%84%d8%a7%d8%aa/1-%d9%85%d8%b3%d9%84%d8%b3%d9%84%d8%a7%d8%aa-%d8%a7%d8%b3%d9%8a%d9%88%d9%8a%d8%a9/" to "مسلسلات آسيوية",
        "/category/%d9%85%d8%b3%d9%84%d8%b3%d9%84%d8%a7%d8%aa/7-series-english-%d9%85%d8%b3%d9%84%d8%b3%d9%84%d8%a7%d8%aa-%d8%a7%d8%ac%d9%86%d8%a8%d9%8a/" to "مسلسلات أجنبي",
        "/category/%d8%a3%d9%81%d9%84%d8%a7%d9%85/10-movies-english-%d8%a7%d9%81%d9%84%d8%a7%d9%85-%d8%a7%d8%ac%d9%86%d8%a8%d9%8a/" to "أفلام أجنبي"
    )

    // Fetch main page content
    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val url = if (page == 1) {
            "$mainUrl${request.data.removePrefix("/")}"
        } else {
            "$mainUrl${request.data.removePrefix("/")}?page=$page"
        }
        val document = app.get(url, interceptor = interceptor).document
        val home = document.select("div.media-card").mapNotNull {
            it.toSearchResult()
        }
        return newHomePageResponse(request.name, home)
    }
    
    // Helper function to parse search results from HTML elements
    private fun Element.toSearchResult(): SearchResponse? {
        val linkElement = this.selectFirst("a") ?: return null
        val href = linkElement.attr("href")
        val title = this.selectFirst("h2[itemprop=name]")?.text() ?: return null

        val style = this.selectFirst("span.media-card__bg")?.attr("style")
        val posterUrl = style?.let {
            Regex("""url\(['"]?(.*?)['"]?\)""").find(it)?.groupValues?.get(1)
        } ?: this.selectFirst("meta[itemprop=thumbnailUrl]")?.attr("content")

        val isSeries = title.contains("مسلسل") || title.contains("برنامج") || title.contains("موسم")

        return if (isSeries) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = posterUrl
            }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = posterUrl
            }
        }
    }
    
    // Search function
    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl?s=$query"
        val document = app.get(url, interceptor = interceptor).document
        return document.select("div.media-card").mapNotNull {
            it.toSearchResult()
        }
    }
    
    // Load movie/series details and episode list
    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url, interceptor = interceptor).document

        val title = document.selectFirst("h1[itemprop=name]")?.ownText()?.trim() ?: return null

        val posterStyle = document.selectFirst("wecima.media-entry--hero")?.attr("style")
        val posterUrl = posterStyle?.let {
            Regex("""url\(['"]?(.*?)['"]?\)""").find(it)?.groupValues?.get(1)
        }

        val plot = document.selectFirst("div.story__content")?.text()?.trim()
        val tags = document.select("li:has(span:contains(النوع)) p a").map { it.text() }
        val year = document.selectFirst("h1[itemprop=name] a.unline")?.text()?.toIntOrNull()

        val seasons = document.select("div.seasons__list li a")
        val isTvSeries = seasons.isNotEmpty() || document.select("div.episodes__list").isNotEmpty()

        if (isTvSeries) {
            val episodes = mutableListOf<Episode>()
            if (seasons.isNotEmpty()) {
                // Multi-season series: iterate through each season page
                seasons.apmap { seasonLink ->
                    val seasonUrl = seasonLink.attr("href")
                    val seasonName = seasonLink.text()
                    val seasonNum = Regex("""\d+""").find(seasonName)?.value?.toIntOrNull()

                    val seasonDoc = app.get(seasonUrl, interceptor = interceptor).document
                    seasonDoc.select("div.episodes__list > a").forEach { epElement ->
                        val epHref = epElement.attr("href")
                        val epTitle = epElement.selectFirst("episodetitle.episode__title")?.text() ?: ""
                        val epNum = Regex("""\d+""").find(epTitle)?.value?.toIntOrNull()

                        episodes.add(
                            newEpisode(epHref) {
                                name = epTitle
                                season = seasonNum
                                episode = epNum
                            }
                        )
                    }
                }
            } else {
                // Single-season series: get episodes from the current page
                document.select("div.episodes__list > a").forEach { epElement ->
                    val epHref = epElement.attr("href")
                    val epTitle = epElement.selectFirst("episodetitle.episode__title")?.text() ?: ""
                    val epNum = Regex("""\d+""").find(epTitle)?.value?.toIntOrNull()
                    episodes.add(newEpisode(epHref) { name = epTitle; season = 1; episode = epNum })
                }
            }
            
            // Return TV series response
            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes.distinctBy { it.data }.sortedWith(compareBy({ it.season }, { it.episode }))) {
                this.posterUrl = posterUrl; this.plot = plot; this.year = year; this.tags = tags
            }
        } else {
            // Return movie response
            return newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = posterUrl; this.plot = plot; this.year = year; this.tags = tags
            }
        }
    }
    
    // Load video links from servers
    override suspend fun loadLinks(
        data: String, // Episode URL
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data, interceptor = interceptor).document

        // Use apmap for parallel processing of servers
        document.select("ul.watch__server-list li btn").apmap { serverBtn ->
            try {
                val encodedUrl = serverBtn.attr("data-url")
                if (encodedUrl.isBlank()) return@apmap

                val decodedUrl = String(Base64.decode(encodedUrl, Base64.DEFAULT))
                
                // Manually route to the correct extractor
                when {
                    // We keep WeCimaExtractor separate as it's very site-specific
                    decodedUrl.contains("wecima.now/run/watch/") -> {
                        WeCimaExtractor().getUrl(decodedUrl, data, subtitleCallback, callback)
                    }
                    // Vidbom seems to have its own logic
                    decodedUrl.contains("vdbtm.shop") -> {
                        VidbomExtractor().getUrl(decodedUrl, data, subtitleCallback, callback)
                    }
                    // These servers all use a similar "packed JS" method
                    decodedUrl.contains("1vid1shar.space") || decodedUrl.contains("dingtezuni.com") || decodedUrl.contains("zfghrew10.shop") -> {
                        GeneralPackedExtractor().getUrl(decodedUrl, data, subtitleCallback, callback)
                    }
                    // Fallback for other known servers like DoodStream
                    else -> {
                        loadExtractor(decodedUrl, data, subtitleCallback, callback)
                    }
                }
            } catch (e: Exception) {
                // Silently ignore errors
            }
        }
        return true
    }
}
