package com.example

import android.util.Base64
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import com.example.extractors.GeneralPackedExtractor
import com.example.extractors.VidbomExtractor
import com.example.extractors.WeCimaExtractor
import com.example.extractors.GovidExtractor
import org.jsoup.nodes.Element
import android.util.Log

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

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data, interceptor = interceptor).document

        document.select("ul.watch__server-list li btn, div.Watch--Servers--Single").apmap { serverBtn ->
            try {
                val encodedUrl = serverBtn.attr("data-url")
                if (encodedUrl.isBlank()) return@apmap

                val decodedUrl = String(Base64.decode(encodedUrl, Base64.DEFAULT))
                
                // V21: Rely on the decoded URL for server identification, which is more reliable.
                when {
                    decodedUrl.contains("wecima.now/run/watch/", true) -> {
                        WeCimaExtractor().getUrl(decodedUrl, data)?.forEach(callback)
                    }
                    decodedUrl.contains("vdbtm.shop", true) -> {
                        VidbomExtractor().getUrl(decodedUrl, data)?.forEach(callback)
                    }
                    decodedUrl.contains("goveed", true) -> {
                        GovidExtractor().getUrl(decodedUrl, data)?.forEach(callback)
                    }
                    decodedUrl.contains("1vid1shar.space", true) || decodedUrl.contains("dingtezuni.com", true) -> {
                        GeneralPackedExtractor().getUrl(decodedUrl, data)?.forEach(callback)
                    }
                    else -> {
                        // Fallback for any other server
                        loadExtractor(decodedUrl, data, subtitleCallback, callback)
                    }
                }
            } catch (e: Exception) {
                // Ignore errors to prevent crashing
            }
        }
        return true
    }
    
    // ... (The rest of the file remains unchanged and correct) ...
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
}
