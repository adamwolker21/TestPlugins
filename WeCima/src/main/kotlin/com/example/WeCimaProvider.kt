package com.example

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.Qualities
import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.utils.JsUnpacker

class WeCimaProvider : MainAPI() {
    // The main URL for the site
    override var mainUrl = "https://wecima.video"
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

    override val mainPage = mainPageOf(
        "/category/%d9%85%d8%b3%d9%84%d8%b3%d9%84%d8%a7%d8%aa/1-%d9%85%d8%b3%d9%84%d8%b3%d9%84%d8%a7%d8%aa-%d8%a7%d8%b3%d9%8a%d9%88%d9%8a%d8%a9/list/" to "مسلسلات آسيوية",
        "/category/%d9%85%d8%b3%d9%84%d8%b3%d9%84%d8%a7%d8%aa/1-%d9%85%d8%b3%d9%84%d8%b3%d9%84%d8%a7%d8%aa-%d8%a7%d8%b3%d9%8a%d9%88%d9%8a%d8%a9/" to "آخر حلقات المسلسلات الآسيوية",
        "/category/%d9%85%d8%b3%d9%84%d8%b3%d9%84%d8%a7%d8%aa/7-series-english-%d9%85%d8%b3%d9%84%d8%b3%d9%84%d8%a7%d8%aa-%d8%a7%d8%ac%d9%86%d8%a8%d9%8a/" to "مسلسلات أجنبي",
        "/category/%d8%a3%d9%81%d9%84%d8%a7%d9%85/10-movies-english-%d8%a7%d9%81%d9%84%d8%a7%d9%85-%d8%a7%d8%ac%d9%86%d8%a8%d9%8a/" to "أفلام أجنبي",
    )

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
        
        // v4 Update: Robust poster extraction with fallback
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

        // v4 Update: New selectors for loading details
        val title = document.selectFirst("h1[itemprop=name]")?.ownText()?.trim() ?: return null
        
        val posterStyle = document.selectFirst("wecima.media-entry--hero")?.attr("style")
        val posterUrl = posterStyle?.let {
            Regex("""url\(['"]?(.*?)['"]?\)""").find(it)?.groupValues?.get(1)
        }
        
        val plot = document.selectFirst("div.story__content")?.text()?.trim()
        val tags = document.select("li:has(span:contains(النوع)) p a").map { it.text() }
        val year = document.selectFirst("h1[itemprop=name] a.unline")?.text()?.toIntOrNull()
        
        // These selectors might need updating if the structure changed
        val duration = document.select("li:contains(المدة)").firstOrNull()?.ownText()?.filter { it.isDigit() }?.toIntOrNull()
        val ratingText = document.selectFirst("span.Rate--Vote")?.text()
        val rating = ratingText?.let {
            if (it.equals("N/A", true)) null else (it.toFloatOrNull()?.times(1000))?.toInt()
        }

        // Check for episodes. This selector is an assumption and might need an update.
        val isTvSeries = document.select("div.Seasons--List, .Episodes--single-watching-link").isNotEmpty()

        if (isTvSeries) {
            val episodes = mutableListOf<Episode>()
            // This logic is from the old structure and might need updating
            // if the new structure is different.
            document.select("div.Seasons--List div.Season--Item").forEach { seasonTab ->
                val seasonNumText = seasonTab.text()
                val seasonNum = Regex("""\d+""").find(seasonNumText)?.value?.toIntOrNull()

                val seasonId = seasonTab.attr("data-season")
                document.select("div.Episodes--List--All[data-season=$seasonId] a.Episode--single-watching-link").forEach { epElement ->
                    val epHref = epElement.attr("href")
                    val epTitle = epElement.selectFirst("span")?.text() ?: ""
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
            // Fallback for pages that list episodes directly without season tabs
            if (episodes.isEmpty()) {
                 document.select("a.Episodes--single-watching-link, a.Episode--single-watching-link").forEach { epElement ->
                    val epHref = epElement.attr("href")
                    val epTitle = epElement.selectFirst("span")?.text()?.ifBlank { epElement.text() } ?: ""
                    val epNum = Regex("""\d+""").find(epTitle)?.value?.toIntOrNull()
                    
                    episodes.add(
                        newEpisode(epHref) {
                            name = epTitle.trim()
                            // Assuming season 1 if not specified
                            season = 1 
                            episode = epNum
                        }
                    )
                }
            }

            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes.distinctBy { it.data }.reversed()) {
                this.posterUrl = posterUrl
                this.plot = plot
                this.year = year
                this.tags = tags
                this.duration = duration
                this.rating = rating
            }
        } else {
            // It's a Movie
            return newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = posterUrl
                this.plot = plot
                this.year = year
                this.tags = tags
                this.duration = duration
                this.rating = rating
            }
        }
    }

    private data class ServerResponse(
        @JsonProperty("embed_url") val embedUrl: String?,
        @JsonProperty("success") val success: Boolean
    )

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data, interceptor = interceptor).document

        val postId = document.body().className().let {
            Regex("""postid-(\d+)""").find(it)?.groupValues?.get(1)
        } ?: return false

        val ajaxUrl = "$mainUrl/wp-content/themes/Elshaikh/Ajaxat/Single/Server.php"

        document.select("ul#servers-list li, ul.servers-list li").apmap { serverElement ->
            val serverId = serverElement.attr("data-id")
            val serverName = serverElement.text().trim()

            try {
                val response = app.post(
                    ajaxUrl,
                    headers = mapOf(
                        "referer" to data,
                        "x-requested-with" to "XMLHttpRequest"
                    ),
                    data = mapOf(
                        "post_id" to postId,
                        "server" to serverId
                    ),
                    interceptor = interceptor
                ).parsed<ServerResponse>()

                if (response.success && response.embedUrl != null) {
                    val embedUrl = response.embedUrl
                    val embedContent = app.get(embedUrl, referer = data, interceptor = interceptor).text

                    if (embedContent.contains("eval(function(p,a,c,k,e,d)")) {
                        val unpackedJs = JsUnpacker(embedContent).unpack()
                        if (unpackedJs != null) {
                            val m3u8Link = Regex("""(https?://[^'"]+\.m3u8)""").find(unpackedJs)?.groupValues?.get(1)
                            if (m3u8Link != null) {
                                M3u8Helper.generateM3u8(
                                    source = "$name - $serverName",
                                    streamUrl = m3u8Link,
                                    referer = embedUrl
                                ).forEach(callback)
                            }
                        }
                    } else {
                        val m3u8Link = Regex("""(https?://[^'"]+\.m3u8)""").find(embedContent)?.groupValues?.get(1)
                        if (m3u8Link != null) {
                            M3u8Helper.generateM3u8(
                                source = "$name - $serverName",
                                streamUrl = m3u8Link,
                                referer = embedUrl
                            ).forEach(callback)
                        }
                    }
                }
            } catch (e: Exception) {
                // Ignore errors
            }
        }

        return true
    }
}
