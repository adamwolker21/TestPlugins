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

    // Interceptor to handle Cloudflare
    private val interceptor = CloudflareKiller()

    override val mainPage = mainPageOf(
        "/movies/arabic-movies/" to "أفلام عربية",
        "/movies/foreign-movies/" to "أفلام أجنبي",
        "/movies/indian-movies/" to "أفلام هندية",
        "/movies/asian-movies/" to "أفلام اسيوية",
        "/movies/turkish-movies/" to "أفلام تركية",
        "/series/arabic-series/" to "مسلسلات عربية",
        "/series/foreign-series/" to "مسلسلات اجنبى",
        "/series/turkish-series/" to "مسلسلات تركية",
        "/series/asian-series/" to "مسلسلات اسيوية",
        "/series/indian-series/" to "مسلسلات هندية",
        "/ramadan-2025/" to "مسلسلات رمضان 2025",
        "/category/netflix/" to "حصريات Netflix"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val url = if (page == 1) {
            "$mainUrl${request.data}"
        } else {
            "$mainUrl${request.data}page/$page/"
        }
        val document = app.get(url, interceptor = interceptor).document
        val home = document.select("div.GridItem").mapNotNull {
            it.toSearchResult()
        }
        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val linkElement = this.selectFirst("a") ?: return null
        val href = linkElement.attr("href")
        val title = this.selectFirst("div.Title")?.text() ?: return null
        val posterUrl = this.selectFirst("img")?.attr("data-src")

        // Distinguish between movies and series
        val isSeries = href.contains("/series/") ||
                this.selectFirst("span.year:contains(موسم)") != null ||
                title.contains("مسلسل") || title.contains("برنامج")

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
        val url = "$mainUrl/search/$query/"
        val document = app.get(url, interceptor = interceptor).document
        return document.select("div.GridItem").mapNotNull {
            it.toSearchResult()
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url, interceptor = interceptor).document
        val title = document.selectFirst("h1[itemprop=name], .Title--SEO")?.text()?.trim() ?: return null
        val posterUrl = document.selectFirst("div.Poster--Single-begin img")?.attr("src")
        val plot = document.selectFirst("div.StoryMovieContent")?.text()?.trim()
        val tags = document.select("ul.Terms--Content--Single-begin li a[rel=tag]").map { it.text() }
        val year = document.select("li:contains(السنة) a").firstOrNull()?.text()?.toIntOrNull()
        val duration = document.select("li:contains(المدة)").firstOrNull()?.ownText()?.filter { it.isDigit() }?.toIntOrNull()
        val ratingText = document.selectFirst("span.Rate--Vote")?.text()
        val rating = ratingText?.let {
            if (it.equals("N/A", true)) null else (it.toFloatOrNull()?.times(1000))?.toInt()
        }

        // Check if it is a TV Series by looking for season tabs
        val isTvSeries = document.select("div.Seasons--List").isNotEmpty()

        if (isTvSeries) {
            val episodes = mutableListOf<Episode>()
            document.select("div.Seasons--List div.Season--Item").forEach { seasonTab ->
                val seasonNumText = seasonTab.text()
                val seasonNum = Regex("""\d+""").find(seasonNumText)?.value?.toIntOrNull()

                // Get episodes for this season
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
            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
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

    // Data class for parsing the AJAX JSON response
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

        // Extract the post ID required for the AJAX call
        val postId = document.body().className().let {
            Regex("""postid-(\d+)""").find(it)?.groupValues?.get(1)
        } ?: return false

        val ajaxUrl = "$mainUrl/wp-content/themes/Elshaikh/Ajaxat/Single/Server.php"

        // Iterate over server tabs
        document.select("ul#servers-list li").apmap { serverElement ->
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
                    // The embed URL might need a referer
                    val embedContent = app.get(embedUrl, referer = data, interceptor = interceptor).text

                    // Check for packed JavaScript
                    if (embedContent.contains("eval(function(p,a,c,k,e,d)")) {
                        val unpackedJs = JsUnpacker(embedContent).unpack()
                        if (unpackedJs != null) {
                            val m3u8Link = Regex("""(https?://[^'"]+\.m3u8)""").find(unpackedJs)?.groupValues?.get(1)
                            if (m3u8Link != null) {
                                M3u8Helper.generateM3u8(
                                    source = "$name - $serverName", // FIX: name -> source
                                    streamUrl = m3u8Link,
                                    referer = embedUrl
                                ).forEach(callback)
                            }
                        }
                    } else {
                         // Simple regex for non-packed links
                        val m3u8Link = Regex("""(https?://[^'"]+\.m3u8)""").find(embedContent)?.groupValues?.get(1)
                        if (m3u8Link != null) {
                            M3u8Helper.generateM3u8(
                                source = "$name - $serverName", // FIX: name -> source
                                streamUrl = m3u8Link,
                                referer = embedUrl
                            ).forEach(callback)
                        }
                    }
                }
            } catch (e: Exception) {
                // Ignore errors and continue to the next server
            }
        }

        return true
    }
}
