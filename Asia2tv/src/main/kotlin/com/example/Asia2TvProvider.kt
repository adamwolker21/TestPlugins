package com.example

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.awaitAll

// v16: The Final Touch. Adding the 'Referer' header to `load` and `loadLinks` to ensure they can fetch content.
class Asia2Tv : MainAPI() {
    override var name = "Asia2Tv"
    override var mainUrl = "https://asia2tv.com"
    override var lang = "ar"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override val mainPage = mainPageOf(
        "/" to "الرئيسية",
        "/movies" to "الأفلام",
        "/series" to "المسلسلات",
        "/status/live" to "يبث حاليا",
        "/status/complete" to "أعمال مكتملة"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page > 1) {
            // Appending /page/ correctly
            "$mainUrl${request.data.removeSuffix("/")}/page/$page/"
        } else {
            "$mainUrl${request.data}"
        }
        
        // This Referer is crucial for category pages to load their content.
        val headers = mapOf("Referer" to mainUrl)
        val document = app.get(url, headers = headers).document

        if (request.data == "/") {
            // Logic for the TRUE main page (using <article>)
            val homePageList = mutableListOf<HomePageList>()
            document.select("div.mov-cat-d").forEach { block ->
                val title = block.selectFirst("h2.mov-cat-d-title")?.text() ?: return@forEach
                val items = block.select("article").mapNotNull { it.toSearchResponse(true) }
                if (items.isNotEmpty()) {
                    homePageList.add(HomePageList(title, items))
                }
            }
            return HomePageResponse(homePageList)
        } else {
            // Logic for CATEGORY pages (using div.postmovie)
            val items = document.select("div.postmovie").mapNotNull { it.toSearchResponse(false) }
            val hasNext = document.selectFirst("a.next") != null
            return newHomePageResponse(request.name, items, hasNext)
        }
    }
    
    private fun Element.toSearchResponse(isHomePage: Boolean): SearchResponse? {
        val linkElement: Element?
        val title: String?
        
        if (isHomePage) {
            linkElement = this.selectFirst("h3.post-box-title a")
            title = linkElement?.text()
        } else {
            linkElement = this.selectFirst("h4 > a")
            title = linkElement?.text()
        }

        val href = linkElement?.attr("href")?.let { fixUrl(it) } ?: return null
        if (title.isNullOrBlank()) return null

        val posterUrl = this.selectFirst("img")?.attr("data-src")

        return if (href.contains("/movie/")) {
            newMovieSearchResponse(title, href) { this.posterUrl = posterUrl }
        } else {
            newTvSeriesSearchResponse(title, href) { this.posterUrl = posterUrl }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/?s=$query"
        val document = app.get(url).document
        return document.select("div.postmovie").mapNotNull { it.toSearchResponse(false) }
    }

    override suspend fun load(url: String): LoadResponse? {
        // The key fix for v16: Adding the Referer header to the load request.
        val headers = mapOf("Referer" to mainUrl)
        val document = app.get(url, headers = headers).document
        
        val title = document.selectFirst("h1.name")?.text()?.trim() ?: return null
        val poster = document.selectFirst("div.poster img")?.attr("src")
        val plot = document.selectFirst("div.story")?.text()?.trim()
        val year = document.selectFirst("div.extra-info span:contains(سنة) a")?.text()?.toIntOrNull()
        val tags = document.select("div.extra-info span:contains(النوع) a").map { it.text() }

        return if (url.contains("/movie/")) {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster; this.year = year; this.plot = plot; this.tags = tags
            }
        } else {
            val episodes = document.select("div#DivEpisodes a").mapNotNull { epElement ->
                val epHref = epElement.attr("data-url")
                if (epHref.isBlank()) return@mapNotNull null
                val epName = epElement.text().trim()
                val epNum = epName.filter { it.isDigit() }.toIntOrNull()
                newEpisode(epHref) { this.name = epName; this.episode = epNum }
            }
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes.reversed()) {
                this.posterUrl = poster; this.year = year; this.plot = plot; this.tags = tags
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // Precautionary fix for v16: Adding the Referer header here as well.
        val headers = mapOf("Referer" to data)
        val document = app.get(data, headers = headers).document
        
        val iframes = document.select("iframe")

        coroutineScope {
            iframes.map { iframe ->
                async {
                    val iframeSrc = fixUrl(iframe.attr("src"))
                    if (iframeSrc.isNotBlank()) {
                        // Pass the referer to the extractor as well
                        loadExtractor(iframeSrc, data, subtitleCallback, callback)
                    }
                }
            }.awaitAll()
        }
        return true
    }
}
