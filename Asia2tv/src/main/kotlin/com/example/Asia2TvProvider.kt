package com.example

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

// V22 Fix: Make showmore nullable and provide a default value to handle missing JSON keys
data class MoreEpisodesResponse(
    val status: Boolean,
    val html: String,
    val showmore: Boolean? = false // The ? makes it nullable, = false is the default
)

data class PlayerAjaxResponse(
    val status: Boolean,
    val codeplay: String
)

class Asia2Tv : MainAPI() {
    override var name = "Asia2Tv"
    override var mainUrl = "https://asia2tv.com"
    override var lang = "ar"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    private val userAgent = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/113.0.0.0 Mobile Safari/537.36"
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
        val title = document.selectFirst("div.info-detail-single h1")?.text()?.trim() ?: "No Title"
        var plot = document.selectFirst("div.info-detail-single p")?.text()?.trim()
        val posterUrl = fixUrlNull(document.selectFirst("meta[property=og:image]")?.attr("content"))
        val year = document.select("ul.mb-2 li:contains(سنة العرض) a")?.text()?.toIntOrNull()
        val rating = document.selectFirst("div.post_review_avg")?.text()?.trim()?.toFloatOrNull()?.times(100)?.toInt()
        val tags = document.select("div.post_tags a")?.map { it.text() }
        val status = getStatus(document.selectFirst("span.serie-isstatus"))

        val country = document.select("ul.mb-2 li:contains(البلد المنتج) a")?.text()?.trim()
        val totalEpisodes = document.selectFirst("ul.mb-2 li:contains(عدد الحلقات)")?.ownText()?.trim()?.removePrefix(": ")
        val statusText = document.selectFirst("span.serie-isstatus")?.text()?.trim()
        val extraInfo = listOfNotNull(
            statusText?.let { "الحالة: $it" },
            country?.let { "البلد: $it" },
            totalEpisodes?.let { "عدد الحلقات: $it" }
        ).joinToString(" | ")
        if (extraInfo.isNotBlank()) {
            plot = listOfNotNull(plot, extraInfo).joinToString("\n\n")
        }

        val episodes = ArrayList<Episode>()
        val seenUrls = HashSet<String>()

        fun addUniqueEpisodes(elements: List<Element>) {
            for (element in elements) {
                val href = element.attr("href")
                if (href.isNotBlank() && seenUrls.add(href)) {
                    val episode = newEpisode(href) {
                        name = element.selectFirst(".titlepisode")?.text()?.trim()
                        this.episode = name?.replace(Regex("[^0-9]"), "")?.toIntOrNull()
                    }
                    episodes.add(episode)
                }
            }
        }

        addUniqueEpisodes(document.select("div.box-loop-episode a.colorsw"))

        val serieId = document.select("script").mapNotNull { script ->
            script.data().let { scriptData ->
                Regex("""single_id\s*=\s*"(\d+)"""").find(scriptData)?.groupValues?.get(1)
            }
        }.firstOrNull()
        val csrfToken = document.selectFirst("meta[name=csrf-token]")?.attr("content")

        if (serieId != null && csrfToken != null) {
            var currentPage = 2
            var hasMore = true
            while (hasMore) {
                try {
                    val ajaxHeaders = baseHeaders + mapOf(
                        "X-CSRF-TOKEN" to csrfToken,
                        "X-Requested-With" to "XMLHttpRequest",
                        "Referer" to url
                    )
                    val response = app.post(
                        "$mainUrl/ajaxGetRequest",
                        headers = ajaxHeaders,
                        data = mapOf(
                            "action" to "moreepisode",
                            "serieid" to serieId,
                            "page" to currentPage.toString()
                        )
                    ).parsedSafe<MoreEpisodesResponse>()

                    if (response?.status == true) {
                        addUniqueEpisodes(Jsoup.parse(response.html).select("a.colorsw"))
                        // V22 Fix: Safely handle nullable showmore with the Elvis operator (?:)
                        hasMore = response.showmore ?: false
                        currentPage++
                    } else {
                        hasMore = false
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                    hasMore = false
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
            "X-CSRF-TOKEN" to csrfToken,
            "X-Requested-With" to "XMLHttpRequest",
            "Referer" to data
        )

        document.select("ul.dropdown-menu li a").apmap { server ->
            try {
                val code = server.attr("data-code").ifBlank { return@apmap }
                val response = app.post(
                    "$mainUrl/ajaxGetRequest",
                    headers = ajaxHeaders,
                    data = mapOf("action" to "iframe_server", "code" to code)
                ).parsedSafe<PlayerAjaxResponse>()

                if (response?.status == true) {
                    val iframeSrc = Jsoup.parse(response.codeplay).selectFirst("iframe")?.attr("src")
                    if (!iframeSrc.isNullOrBlank()) {
                        loadExtractor(iframeSrc, data, subtitleCallback, callback)
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        return true
    }
                      }
