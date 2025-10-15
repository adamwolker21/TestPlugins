package com.example

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

data class NewPlayerAjaxResponse(
    val status: Boolean,
    val codeplay: String
)

data class MoreEpisodesResponse(
    val status: Boolean,
    val html: String,
    val showmore: Boolean
)

class Asia2Tv : MainAPI() {
    override var name = "Asia2Tv"
    override var mainUrl = "https://asia2tv.com"
    override var lang = "ar"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    private val userAgent = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Mobile Safari/537.36"
    private val baseHeaders
        get() = mapOf(
            "User-Agent" to userAgent,
            "Referer" to "$mainUrl/"
        )

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
        val document = app.get("$mainUrl${request.data}?page=$page", headers = baseHeaders).document
        val items = document.select("div.postmovie").mapNotNull { it.toSearchResponse() }
        val hasNext = document.selectFirst("a.next.page-numbers, a[rel=next]") != null
        return newHomePageResponse(request.name, items, hasNext)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl/search?s=$query", headers = baseHeaders).document
        return document.select("div.postmovie").mapNotNull { it.toSearchResponse() }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url, headers = baseHeaders).document
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
            if (text.contains("البلد المنتج")) country = li.selectFirst("a")?.text()?.trim()
            else if (text.contains("عدد الحلقات")) totalEpisodes = li.ownText().trim().removePrefix(": ")
        }
        val statusText = document.selectFirst("span.serie-isstatus")?.text()?.trim()
        val extraInfo = listOfNotNull(
            statusText?.let { "الحالة: $it" },
            country?.let { "البلد: $it" },
            totalEpisodes?.let { "عدد الحلقات: $it" }
        ).joinToString(" | ")
        plot = if (extraInfo.isNotBlank()) listOfNotNull(plot, extraInfo).joinToString("<br><br>") else plot

        // V19: Final Build-Safe Logic
        val episodes = ArrayList<Episode>()
        val seenUrls = HashSet<String>()

        fun addUniqueEpisodes(elements: List<Element>) {
            elements.forEach { a ->
                val href = a.attr("href")
                if (href.isNotBlank() && seenUrls.add(href)) {
                    val epNumText = a.selectFirst(".titlepisode")?.text()?.replace(Regex("[^0-9]"), "")
                    val epNum = epNumText?.toIntOrNull()
                    val episode = newEpisode(href) {
                        name = a.selectFirst(".titlepisode")?.text()?.trim()
                        episode = epNum
                    }
                    episodes.add(episode)
                }
            }
        }

        // Process initial episodes
        addUniqueEpisodes(document.select("div.box-loop-episode a"))

        // Process AJAX episodes
        val serieId = document.select("script").mapNotNull { script ->
            script.data().let {
                Regex("""'serie_id':\s*'(\d+)'""").find(it)?.groupValues?.get(1)
            }
        }.firstOrNull()
        val csrfToken = document.selectFirst("meta[name=csrf-token]")?.attr("content")

        if (document.selectFirst("a.more-episode") != null && serieId != null && csrfToken != null) {
            var currentPage = 2
            var hasMore = true
            while (hasMore) {
                try {
                    val ajaxHeaders = baseHeaders + mapOf(
                        "x-csrf-token" to csrfToken,
                        "x-requested-with" to "XMLHttpRequest",
                        "Referer" to url
                    )
                    val response = app.post(
                        "$mainUrl/wp-admin/admin-ajax.php",
                        data = mapOf("action" to "moreepisode", "serieid" to serieId, "page" to currentPage.toString()),
                        headers = ajaxHeaders
                    ).parsedSafe<MoreEpisodesResponse>()

                    if (response != null && response.status) {
                        addUniqueEpisodes(Jsoup.parse(response.html).select("a.colorsw"))
                        hasMore = response.showmore
                        currentPage++
                    } else {
                        hasMore = false
                    }
                } catch (e: Exception) {
                    e.printStackTrace(); hasMore = false
                }
            }
        }

        return if (episodes.isNotEmpty()) {
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes.reversed()) {
                this.posterUrl = posterUrl; this.year = year; this.plot = plot; this.tags = tags; this.rating = rating; this.showStatus = status
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = posterUrl; this.year = year; this.plot = plot; this.tags = tags; this.rating = rating
            }
        }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        val document = app.get(data, headers = baseHeaders).document
        val csrfToken = document.selectFirst("meta[name=csrf-token]")?.attr("content") ?: ""
        val ajaxHeaders = baseHeaders + mapOf(
            "x-csrf-token" to csrfToken,
            "x-requested-with" to "XMLHttpRequest",
            "Referer" to data
        )

        document.select("ul.dropdown-menu li a").apmap { server ->
            try {
                val code = server.attr("data-code").ifBlank { return@apmap }
                val response = app.post(
                    "$mainUrl/wp-admin/admin-ajax.php",
                    data = mapOf("action" to "iframe_server", "code" to code),
                    headers = ajaxHeaders
                ).text
                val jsonResponse = parseJson<NewPlayerAjaxResponse>(response)
                if (!jsonResponse.status) return@apmap
                val iframeSrc = Jsoup.parse(jsonResponse.codeplay).selectFirst("iframe")?.attr("src")?.ifBlank { return@apmap }
                loadExtractor(iframeSrc!!, data, subtitleCallback, callback)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        return true
    }
}
