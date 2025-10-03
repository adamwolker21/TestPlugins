package com.example

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.getQualityFromName
import org.jsoup.nodes.Element

class WeCimaProvider : MainAPI() {
    override var mainUrl = "https://wecima.now.gg"
    override var name = "WeCima"
    override val hasMainPage = true
    override var lang = "ar"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
    )

    override val mainPage = mainPageOf(
        "$mainUrl/movies" to "Movies",
        "$mainUrl/series" to "Series",
        "$mainUrl/category/western-movies-1" to "Western Movies",
        "$mainUrl/category/turkish-series-1" to "Turkish Series",

    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val document = app.get(request.data + "/page/$page").document
        val home = document.select("div.Grid--WecimaPosts div.GridItem")
            .mapNotNull {
                it.toSearchResult()
            }
        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): SearchResult? {
        val title = this.selectFirst("strong.has-text-align-center")?.text() ?: return null
        val href = this.selectFirst("a")?.attr("href") ?: return null
        val posterUrl = this.selectFirst("img")?.attr("src")

        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String): List<SearchResult> {
        val searchResponse = app.get("$mainUrl/?s=$query").document
        return searchResponse.select("div.Grid--WecimaPosts div.GridItem").mapNotNull {
            it.toSearchResult()
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url).document

        val title = doc.selectFirst("div.Title--Content--Single-begin h1.text-white")?.text()?.trim()
            ?: doc.selectFirst("meta[property=og:title]")?.attr("content")?.trim() ?: ""
        val poster = doc.selectFirst("div.Poster--Single-begin a img")?.attr("src")
            ?: doc.selectFirst("meta[property=og:image]")?.attr("content")
        val tags = doc.select("div.Meta--Single-begin li:nth-child(1) a").map { it.text() }
        val year =
            doc.selectFirst("div.Meta--Single-begin li:nth-child(3) a")?.text()?.trim()
                ?.toInt()
        val tvType = if (doc.selectFirst("div.List--Seasons--Episodes")?.text()
                ?.contains("الموسم") == true
        ) TvType.TvSeries else TvType.Movie
        val description = doc.selectFirst("div.StoryMovieContent")?.text()?.trim()
        val recommendations = doc.select("div.Grid--WecimaPosts div.GridItem").mapNotNull {
            it.toSearchResult()
        }

        return if (tvType == TvType.TvSeries) {
            val episodes =
                doc.select("div.Season--Main--Grid a").map {
                    val href = it.attr("href")
                    val name = it.select("h3").text().trim()
                    val image = it.select("img").attr("src")
                    Episode(
                        href,
                        name,
                        posterUrl = image
                    )
                }
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.tags = tags
                this.recommendations = recommendations
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.tags = tags
                this.recommendations = recommendations
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = app.get(data).document
        doc.select("ul#episode-servers li a").apmap {
            val serverUrl = it.attr("data-link")

            if (serverUrl.contains("vidbom")) {
                invokeExtractor(VidbomExtractor(), serverUrl, data, subtitleCallback, callback)
            } 
            // ================== V1 Addition Start ==================
            else if (serverUrl.contains("goveed")) {
                invokeExtractor(GovidExtractor(), serverUrl, data, subtitleCallback, callback)
            }
            // =================== V1 Addition End ===================
        }

        doc.select("ul.download-links li a").apmap {
            val quality = it.select("quality").text()
            val url = it.attr("href")
            callback.invoke(
                ExtractorLink(
                    this.name,
                    this.name,
                    url,
                    "",
                    getQualityFromName(quality),
                    isM3u8 = url.contains(".m3u8")
                )
            )
        }
        return true
    }
}
