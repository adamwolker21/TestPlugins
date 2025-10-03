package com.example

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.loadExtractor
import android.util.Base64 // Required for Base64 decoding

class WeCimaProvider : MainAPI() {
    override var mainUrl = "https://wecima.now"
    override var name = "WeCima"
    override val hasMainPage = true
    override var lang = "ar"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.AsianDrama,
    )

    private val interceptor = CloudflareKiller()

    override val mainPage = mainPageOf(
        "/category/%d9%85%ds%d9%84%d8%b3%d9%84%d8%a7%d8%aa/1-%d9%85%d8%b3%d9%84%d8%b3%d9%84%d8%a7%d8%aa-%d8%a7%d8%b3%d9%8a%d9%88%d9%8a%d8%a9/" to "مسلسلات آسيوية",
        "/category/%d9%85%d8%b3%d9%84%d8%b3%d9%84%d8%a7%d8%aa/7-series-english-%d9%85%d8%b3%d9%84%d8%b3%d9%84%d8%a7%d8%aa-%d8%a7%d8%ac%d9%86%d8%a8%d9%8a/" to "مسلسلات أجنبي",
        "/category/%d8%a3%d9%81%d9%84%d8%a7%d9%85/10-movies-english-%d8%a7%d9%81%d9%84%d8%a7%d9%85-%d8%a7%d8%ac%d9%86%d8%a8%d9%8a/" to "أفلام 19 أجنبي",
    )

    // Functions getMainPage, toSearchResult, search, and load remain the same.
    // To save space, they are not repeated here.
    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val url = if (page == 1) {
            "$mainUrl${request.data}"
        } else {
            "$mainUrl${request.data}?page=$page"
        }
        val document = app.get(url, interceptor = interceptor).document
        val home = document.select("div.media-card").mapNotNull {
            it.toSearchResult()
        }
        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val linkElement = this.selectFirst("a") ?: return null
        val href = linkElement.attr("href")
        val title = this.selectFirst("h2[itemprop=name]")?.text() ?: return null
        
        var posterUrl: String?
        val style = this.selectFirst("span.media-card__bg")?.attr("style")
        posterUrl = style?.let {
            Regex("""url\(['"]?(.*?)['"]?\)""").find(it)?.groupValues?.get(1)
        }
        if (posterUrl.isNullOrBlank()) {
            posterUrl = this.selectFirst("meta[itemprop=thumbnailUrl]")?.attr("content")
        }

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

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/?s=$query"
        val document = app.get(url, interceptor = interceptor).document
        return document.select("div.media-card").mapNotNull {
            it.toSearchResult()
        }
    }

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
                document.select("div.episodes__list > a").forEach { epElement ->
                    val epHref = epElement.attr("href")
                    val epTitle = epElement.selectFirst("episodetitle.episode__title")?.text() ?: ""
                    val epNum = Regex("""\d+""").find(epTitle)?.value?.toIntOrNull()
                    episodes.add(newEpisode(epHref) { name = epTitle; season = 1; episode = epNum })
                }
            }

            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes.distinctBy { it.data }.sortedWith(compareBy({ it.season }, { it.episode }))) {
                this.posterUrl = posterUrl; this.plot = plot; this.year = year; this.tags = tags
            }
        } else {
            return newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = posterUrl; this.plot = plot; this.year = year; this.tags = tags
            }
        }
    }
    
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data, interceptor = interceptor).document

        // v19: The final and correct logic.
        document.select("ul.watch__server-list li btn").apmap { serverBtn ->
            try {
                val encodedUrl = serverBtn.attr("data-url")
                // Decode the Base64 URL to get the real embed URL
                val decodedUrl = String(Base64.decode(encodedUrl, Base64.DEFAULT))
                
                // Pass the real URL to the appropriate extractor
                if (decodedUrl.isNotBlank()) {
                    loadExtractor(decodedUrl, data, subtitleCallback, callback)
                }
            } catch (e: Exception) {
                // Ignore errors and continue to the next server
            }
        }
        
        return true
    }
}
