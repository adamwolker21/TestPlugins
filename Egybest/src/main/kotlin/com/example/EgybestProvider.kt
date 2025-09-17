package com.example

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import org.jsoup.Jsoup
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
// v8: Found the definitive, correct import path for the webView helper function.
import com.lagradost.cloudstream3.utils.Coroutines.webView

// v8: Final version with the correct Coroutines.webView import.
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

    private data class EgybestApiResponse(
        @JsonProperty("status") val status: String,
        @JsonProperty("html") val html: String
    )

    override val mainPage = mainPageOf(
        "/movies" to "أفلام",
        "/tv" to "مسلسلات",
        "/movies/latest-bluray-movies" to "أحدث أفلام البلوراي",
        "/movies/latest-hd-movies" to "أحدث أفلام HD"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = "$mainUrl${request.data}?page=$page"
        val document = app.get(url).document
        val home = document.select("div.movies a").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = this.selectFirst("span.title")?.text()?.trim() ?: return null
        val href = this.attr("href")
        val posterUrl = this.selectFirst("img")?.attr("src")
        val isMovie = href.contains("/movie/")

        return if (isMovie) {
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = posterUrl
            }
        } else {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = posterUrl
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/explore/?q=$query"
        val document = app.get(url).document
        return document.select("div.movies a").mapNotNull { it.toSearchResult() }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        val title = document.selectFirst("div.movie_title h1")?.ownText()?.trim() ?: ""
        val poster = document.selectFirst("div.movie_img img")?.attr("src")
        val year = document.selectFirst("div.movie_title h1 a")?.text()?.toIntOrNull()
        val plot = document.selectFirst("div.mbox_contenido p")?.text()?.trim() ?: ""
        val tags = document.select("div.mbox.tags a").map { it.text() }
        val isMovie = url.contains("/movie/")

        return if (isMovie) {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.year = year
                this.plot = plot
                this.tags = tags
            }
        } else {
            val episodes = document.select("#episodes_list div.tr a").map {
                val epHref = it.attr("href")
                val epTitle = it.selectFirst("span.title")?.text()
                val seasonNum = it.selectFirst("span.season")?.text()?.replace("S", "")?.trim()?.toIntOrNull()
                newEpisode(epHref) {
                    this.name = epTitle
                    this.season = seasonNum
                }
            }.reversed()

            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.year = year
                this.plot = plot
                this.tags = tags
            }
        }
    }
    
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document
        val videoPageUrl = document.selectFirst("table.dls_table tbody tr a")?.attr("href") ?: return false
        val videoPageDoc = app.get(videoPageUrl, referer = data).document
        val script = videoPageDoc.select("script").find { it.data().contains("get_video()") }?.data() ?: return false
        
        val videoId = data.split("/")[4].split("-")[0]
        val postKey = Regex("""'([a-z0-9]{32})':""").find(script)?.groupValues?.get(1) ?: return false

        val apiResponse = app.post(
            "$mainUrl/api/get_video/$videoId",
            headers = mapOf(
                "X-Requested-With" to "XMLHttpRequest",
                "Referer" to videoPageUrl
            ),
            data = mapOf(postKey to "")
        ).text

        val iframeHtml = parseJson<EgybestApiResponse>(apiResponse).html
        val iframeSrc = Jsoup.parse(iframeHtml).selectFirst("iframe")?.attr("src") ?: return false

        val webViewResult = webView(
            url = iframeSrc,
            referer = videoPageUrl,
            interceptorUrl = ".*\\.m3u8.*"
        )

        val m3u8Link = webViewResult?.url ?: return false

        M3u8Helper.generateM3u8(
            this.name,
            m3u8Link,
            iframeSrc,
        ).forEach(callback)

        return true
    }
}
