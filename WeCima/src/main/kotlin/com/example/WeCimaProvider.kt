package com.example

import android.util.Base64
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import com.example.extractors.GeneralPackedExtractor
import com.example.extractors.VidbomExtractor
import com.example.extractors.WeCimaExtractor
import com.example.extractors.GovidExtractor // V16: Import GovidExtractor
import org.jsoup.nodes.Element

class WeCimaProvider : MainAPI() {
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

    private val interceptor = CloudflareKiller()

    override val mainPage = mainPageOf(
        "/category/%d9%85%d8%b3%d9%84%d8%b3%d9%84%d8%a7%d8%aa/1-%d9%85%d8%b3%d9%84%d8%b3%d9%84%d8%a7%d8%aa-%d8%a7%d8%b3%d9%8a%d9%88%d9%8a%d8%a9/" to "مسلسلات آسيوية",
        "/category/%d9%85%d8%b3%d9%84%d8%b3%d9%84%d8%a7%d8%aa/7-series-english-%d9%85%d8%b3%d9%84%d8%b3%d9%84%d8%a7%d8%aa-%d8%a7%d8%ac%d9%86%d8%a8%d9%8a/" to "مسلسلات أجنبي",
        "/category/%d8%a3%d9%81%d9%84%d8%a7%d9%85/10-movies-english-%d8%a7%d9%81%d9%84%d8%a7%d9%85-%d8%a7%d8%ac%d9%86%d8%a8%d9%8a/" to "أفلام أجنبي"
    )

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
    
    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl?s=$query"
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

        // ================== V16 Change Start ==================
        // This selector now correctly finds the server buttons in BOTH HTML layouts.
        document.select("ul.watch__server-list li btn, div.Watch--Servers--Single").apmap { serverBtn ->
            try {
                // This logic robustly extracts server name and URL from either layout.
                val serverName = serverBtn.selectFirst("strong")?.text() // Layout 1
                                 ?: serverBtn.selectFirst("span.ServerName")?.text() // Layout 2
                                 ?: return@apmap
                
                // Layout 1 uses 'data-url', which is a full Base64 encoded URL.
                // We assume Layout 2 would have a similar attribute if it's for streaming.
                // If it's a 'data-id', it might require a different handling logic,
                // but for now, we focus on the provided HTML structures.
                val encodedUrl = serverBtn.attr("data-url")
                if (encodedUrl.isBlank()) return@apmap

                val decodedUrl = String(Base64.decode(encodedUrl, Base64.DEFAULT))
                
                // The routing logic is now based on the extracted serverName.
                when {
                    serverName.contains("وي سيما", true) -> {
                        WeCimaExtractor().getUrl(decodedUrl, data)?.forEach(callback)
                    }
                    serverName.contains("VIDBOM", true) -> {
                        VidbomExtractor().getUrl(decodedUrl, data)?.forEach(callback)
                    }
                    serverName.contains("GoViD", true) -> {
                        GovidExtractor().getUrl(decodedUrl, data)?.forEach(callback)
                    }
                    serverName.contains("vidshare", true) || serverName.contains("EarnvidS", true) -> {
                         // Vidshare and Earnvids seem to use the same packed extractor
                        GeneralPackedExtractor().getUrl(decodedUrl, data)?.forEach(callback)
                    }
                    // Add other servers as needed...
                    else -> {
                        // Fallback for any other servers not explicitly handled
                        loadExtractor(decodedUrl, data, subtitleCallback, callback)
                    }
                }
            } catch (e: Exception) {
                // Ignore errors to prevent one broken server from stopping the others.
            }
        }
        // ================== V16 Change End ==================
        return true
    }
}
