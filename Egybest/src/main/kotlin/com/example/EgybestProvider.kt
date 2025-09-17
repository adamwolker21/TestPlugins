package com.example

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.LoadResponse.Companion.newMovieLoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.newTvSeriesLoadResponse
import org.jsoup.nodes.Element
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper

// v2: This provider has been heavily modified to handle Egybest's complex link extraction.
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

    // Data class for parsing JSON responses from the API
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
                val seasonNum = it.selectFirst("span.season")?.text()?.replace("S", "")?.toIntOrNull()
                Episode(epHref, epTitle, seasonNum)
            }.reversed()

            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.year = year
                this.plot = plot
                this.tags = tags
            }
        }
    }

    // v2: Complete overhaul of the loadLinks function to handle advanced extraction.
    // This function now performs a multi-step process to get the final video URL.
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // Step 1: Get the main movie/episode page
        val document = app.get(data).document

        // Step 2: Find the video player page URL from the download buttons table
        // We select the first available quality (usually the highest)
        val videoPageUrl = document.selectFirst("table.dls_table tbody tr a")?.attr("href") ?: return false

        // Step 3: Fetch the video player page
        val videoPageDoc = app.get(videoPageUrl, referer = data).document

        // Step 4: The player page uses an AJAX call to load the iframe.
        // We need to replicate this call. We extract the parameters from the script.
        val script = videoPageDoc.select("script").find { it.data().contains("get_video()") }?.data() ?: return false
        
        // Extract the POST data key required by the API. This is often dynamic.
        val videoId = data.split("/")[4].split("-")[0]
        val postKey = Regex("""'([a-z0-9]{32})':""").find(script)?.groupValues?.get(1) ?: return false

        // Step 5: Make the API call that the website's Javascript would make.
        // This call returns the actual iframe player source.
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

        // Step 6: Now we have the final player iframe. We need to extract links from it.
        // This often requires a WebView to solve challenges or evaluate Javascript.
        // Using a generic WebView resolver to extract links.
        val webViewLinks = WebViewResolver(
            url = iframeSrc,
            referer = videoPageUrl,
            // A predicate to identify the correct M3U8 link from network requests
            requestPredicate = { it.endsWith(".m3u8") }
        ).resolve()

        if (webViewLinks.isEmpty()) return false

        // Step 7: Process the found links and invoke the callback.
        webViewLinks.forEach { link ->
             M3u8Helper.generateM3u8(
                name,
                link,
                iframeSrc, // Referer for the m3u8 link must be the iframe page
            ).forEach(callback)
        }

        return true
    }
}

