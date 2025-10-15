package com.wolker.asia2tv

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

// بنية بيانات جديدة لتناسب الرد الجديد من الموقع
data class NewPlayerAjaxResponse(
    val status: Boolean,
    val codeplay: String
)

class Asia2Tv : MainAPI() {
    override var name = "Asia2Tv"
    override var mainUrl = "https://asia2tv.com"
    override var lang = "ar"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    private fun getStatus(element: Element?): ShowStatus {
        return when {
            element?.hasClass("live") == true -> ShowStatus.Ongoing
            element?.hasClass("complete") == true -> ShowStatus.Completed
            else -> ShowStatus.Completed
        }
    }

    private fun Element.toSearchResponse(): SearchResponse? {
        val titleElement = this.selectFirst("h4 a") ?: return null
        val href = fixUrlNull(titleElement.attr("href")) ?: return null
        val title = titleElement.text()

        val posterUrl = fixUrlNull(this.selectFirst("div.postmovie-photo img")?.let {
            it.attr("data-src").ifBlank { it.attr("src") }
        })

        val isMovie = href.contains("/movie/")

        return if (isMovie) {
            newMovieSearchResponse(title, href, TvType.Movie) { this.posterUrl = posterUrl }
        } else {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) { this.posterUrl = posterUrl }
        }
    }

    override val mainPage = mainPageOf(
        "/newepisode" to "الحلقات الجديدة",
        "/status/live" to "يبث حاليا",
        "/status/coming-soon" to "الأعمال القادمة",
        "/status/complete" to "أعمال مكتملة",
        "/series" to "المسلسلات",
        "/movies" to "الأفلام"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = "$mainUrl${request.data}?page=$page"
        val document = app.get(url).document

        val items = document.select("div.postmovie").mapNotNull {
            it.toSearchResponse()
        }

        val hasNext = document.selectFirst("a.next.page-numbers, a[rel=next]") != null
        return newHomePageResponse(request.name, items, hasNext)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/search?s=$query"
        val document = app.get(url).document

        return document.select("div.postmovie").mapNotNull { it.toSearchResponse() }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

        val detailsContainer = document.selectFirst("div.info-detail-single")

        val title = detailsContainer?.selectFirst("h1")?.text()?.trim() ?: "No Title"
        var plot = detailsContainer?.selectFirst("p")?.text()?.trim()

        val posterUrl = fixUrlNull(document.selectFirst("meta[property=og:image]")?.attr("content"))

        val year = detailsContainer?.select("ul.mb-2 li")
            ?.find { it.text().contains("سنة العرض") }
            ?.selectFirst("a")?.text()?.toIntOrNull()

        val rating = detailsContainer?.selectFirst("div.post_review_avg")?.text()?.trim()
            ?.toFloatOrNull()?.times(100)?.toInt()

        val tags = detailsContainer?.select("div.post_tags a")?.map { it.text() }

        val status = getStatus(document.selectFirst("span.serie-isstatus"))

        var country: String? = null
        var totalEpisodes: String? = null

        detailsContainer?.select("ul.mb-2 li")?.forEach { li ->
            val text = li.text()
            if (text.contains("البلد المنتج")) {
                country = li.selectFirst("a")?.text()?.trim()
            } else if (text.contains("عدد الحلقات")) {
                totalEpisodes = li.ownText().trim().removePrefix(": ")
            }
        }

        val statusText = document.selectFirst("span.serie-isstatus")?.text()?.trim()
        
        val extraInfoList = listOfNotNull(
            statusText?.let { "الحالة: $it" },
            country?.let { "البلد: $it" },
            totalEpisodes?.let { "عدد الحلقات: $it" }
        )
        val extraInfo = extraInfoList.joinToString(" | ")

        plot = if (extraInfo.isNotBlank()) {
            listOfNotNull(plot, extraInfo).joinToString("<br><br>")
        } else {
            plot
        }

        // --- V2: Logic to fetch all episodes ---

        // Helper function to parse episode elements from a given document/element
        fun parseEpisodes(doc: Element): List<Episode> {
            return doc.select("a.colorsw").mapNotNull { a ->
                val href = a.attr("href").ifBlank { return@mapNotNull null }
                val name = a.selectFirst(".titlepisode")?.text()?.trim()
                val epNum = name?.replace(Regex("[^0-9]"), "")?.toIntOrNull()

                newEpisode(href) {
                    this.name = name
                    this.episode = epNum
                }
            }
        }

        val allEpisodes = mutableListOf<Episode>()

        // 1. Get initial episodes from the main page document
        document.selectFirst("div.loop-episode")?.let {
            allEpisodes.addAll(parseEpisodes(it))
        }

        // 2. Check for the "more episodes" button and load them via AJAX
        val postId = document.selectFirst("link[rel=shortlink]")?.attr("href")?.substringAfter("?p=")
        if (document.selectFirst("a.more-episode") != null && postId != null) {
            var currentPage = 2
            var hasMore = true

            while (hasMore) {
                try {
                    val responseText = app.post(
                        "$mainUrl/ajaxGetRequest",
                        data = mapOf(
                            "action" to "load_more_episodes",
                            "post_id" to postId,
                            "page" to currentPage.toString()
                        ),
                        referer = url,
                        headers = mapOf("X-Requested-With" to "XMLHttpRequest")
                    ).text

                    if (responseText.isBlank()) {
                        hasMore = false
                        continue
                    }

                    val responseDoc = Jsoup.parse(responseText)
                    val newEpisodes = parseEpisodes(responseDoc)

                    if (newEpisodes.isNotEmpty()) {
                        allEpisodes.addAll(newEpisodes)
                        currentPage++
                    } else {
                        hasMore = false // No more episodes returned, stop looping
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                    hasMore = false // Stop on any error
                }
            }
        }

        val finalEpisodes = allEpisodes.distinctBy { it.url }.reversed()
        
        // --- End of V2 logic ---

        return if (finalEpisodes.isNotEmpty()) {
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, finalEpisodes) {
                this.posterUrl = posterUrl
                this.year = year
                this.plot = plot
                this.tags = tags
                this.rating = rating
                this.showStatus = status
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = posterUrl
                this.year = year
                this.plot = plot
                this.tags = tags
                this.rating = rating
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
        
        val servers = document.select("ul.dropdown-menu li a")
        
        servers.apmap { server ->
            try {
                val code = server.attr("data-code")
                if (code.isBlank()) return@apmap

                val ajaxUrl = "$mainUrl/ajaxGetRequest"
                val response = app.post(
                    ajaxUrl,
                    data = mapOf("action" to "iframe_server", "code" to code),
                    referer = data,
                    headers = mapOf("X-Requested-With" to "XMLHttpRequest")
                ).text

                val jsonResponse = parseJson<NewPlayerAjaxResponse>(response)
                if (!jsonResponse.status) return@apmap

                val iframeHtml = jsonResponse.codeplay
                val iframeSrc = Jsoup.parse(iframeHtml).selectFirst("iframe")?.attr("src")
                if (iframeSrc.isNullOrBlank()) return@apmap
                
                loadExtractor(iframeSrc, data, subtitleCallback, callback)

            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        return true
    }
}
