package com.example

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

@JsonIgnoreProperties(ignoreUnknown = true)
data class MoreEpisodesResponse(
    val status: Boolean,
    val html: String,
    val showmore: Boolean? = false
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class PlayerAjaxResponse(
    val status: Boolean,
    val codeplay: String
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class SchemaItem(
    @JsonProperty("itemReviewed") val itemReviewed: SchemaImage? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class SchemaImage(
    @JsonProperty("image") val image: String? = null
)

class Asia2Tv : MainAPI() {
    override var name = "Asia2Tv"
    override var mainUrl = "https://asia2tv.com"
    override var lang = "ar"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    private fun getBaseHeaders(cookies: Map<String, String>): Map<String, String> {
        return mapOf(
            "Authority" to mainUrl.substringAfter("://").substringBefore("/"),
            "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/113.0.0.0 Mobile Safari/537.36",
            "Referer" to "$mainUrl/",
            "Origin" to mainUrl,
            "Accept" to "*/*",
            "Accept-Language" to "en-US,en;q=0.9",
            "Cookie" to cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }
        )
    }

    private fun getAjaxHeaders(referer: String, csrfToken: String, cookies: Map<String, String>): Map<String, String> {
        val base = getBaseHeaders(cookies)
        return base + mapOf(
            "X-CSRF-TOKEN" to csrfToken,
            "X-Requested-With" to "XMLHttpRequest",
            "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8",
            "Referer" to referer
        )
    }

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
        val response = app.get("$mainUrl${request.data}?page=$page")
        val document = Jsoup.parse(response.text)
        val items = document.select("div.postmovie").mapNotNull { it.toSearchResponse() }
        val hasNext = document.selectFirst("a.next.page-numbers, a[rel=next]") != null
        return newHomePageResponse(request.name, items, hasNext)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val response = app.get("$mainUrl/search?s=$query")
        val document = Jsoup.parse(response.text)
        return document.select("div.postmovie").mapNotNull { it.toSearchResponse() }
    }

    override suspend fun load(url: String): LoadResponse {
        val response = app.get(url)
        val cookies = response.cookies
        val document = Jsoup.parse(response.text)

        val title = document.selectFirst("div.info-detail-single h1")?.text()?.trim() ?: "No Title"
        var plot = document.selectFirst("div.info-detail-single p")?.text()?.trim()
        
        val posterUrl = fixUrlNull(
            document.selectFirst("meta[property=og:image]")?.attr("content").ifNullOrBlank {
                document.selectFirst("div.single-photo img, div.single-thumb-bg img")?.let {
                    it.attr("data-src").ifBlank { it.attr("src") }
                }
            }.ifNullOrBlank {
                tryParseJson<SchemaItem>(document.selectFirst("script[type=\"application/ld+json\"]")?.data() ?: "")?.itemReviewed?.image
            }
        )
        
        val year = document.select("ul.mb-2 li:contains(سنة العرض) a")?.text()?.toIntOrNull()
        
        val rating = document.selectFirst("div.post_review_avg")?.text()?.trim()
            ?.split(".")?.firstOrNull()?.toIntOrNull()?.times(1000)

        // V43: Add "Featured" tag if present
        val isPro = document.selectFirst("span.series-ispro") != null
        val tags = document.select("div.post_tags a")?.map { it.text() }?.toMutableList() ?: mutableListOf()
        if (isPro) {
            tags.add(0, "☆彡 مميز")
        }

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
            plot = listOfNotNull(plot, "<br><br>${extraInfo}").joinToString("")
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
                Regex("""single_id\s*=\s*["'](\d+)["']""").find(scriptData)?.groupValues?.get(1)
            }
        }.firstOrNull()
        val csrfToken = document.selectFirst("meta[name=csrf-token]")?.attr("content")

        if (serieId != null && csrfToken != null) {
            var currentPage = 2
            var hasMore = true
            while (hasMore) {
                try {
                    val ajaxHeaders = getAjaxHeaders(url, csrfToken, cookies)
                    val postData = "action=moreepisode&serieid=$serieId&page=$currentPage"
                    val requestBody = postData.toRequestBody("application/x-www-form-urlencoded; charset=UTF-8".toMediaType())
                    
                    val responseText = app.post(
                        "$mainUrl/ajaxGetRequest",
                        headers = ajaxHeaders,
                        requestBody = requestBody
                    ).text

                    val ajaxResponse = tryParseJson<MoreEpisodesResponse>(responseText)

                    if (ajaxResponse?.status == true && ajaxResponse.html.isNotBlank()) {
                        addUniqueEpisodes(Jsoup.parse(ajaxResponse.html).select("a.colorsw"))
                        hasMore = ajaxResponse.showmore ?: false
                        currentPage++
                    } else {
                        hasMore = false
                    }
                } catch (e: Exception) {
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
        val response = app.get(data)
        val cookies = response.cookies
        val document = Jsoup.parse(response.text)
        
        val csrfToken = document.selectFirst("meta[name=csrf-token]")?.attr("content") ?: ""
        val ajaxHeaders = getAjaxHeaders(data, csrfToken, cookies)

        document.select("ul.dropdown-menu li a").apmap { server ->
            try {
                val code = server.attr("data-code").ifBlank { return@apmap }
                val postData = "action=iframe_server&code=$code"
                val requestBody = postData.toRequestBody("application/x-www-form-urlencoded; charset=UTF-8".toMediaType())
                
                val responseText = app.post(
                    "$mainUrl/ajaxGetRequest",
                    headers = ajaxHeaders,
                    requestBody = requestBody
                ).text
                val ajaxResponse = tryParseJson<PlayerAjaxResponse>(responseText)

                if (ajaxResponse?.status == true) {
                    val iframeSrc = Jsoup.parse(ajaxResponse.codeplay).selectFirst("iframe")?.attr("src")
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

    private fun String?.ifNullOrBlank(defaultValue: () -> String?): String? {
        return if (this.isNullOrBlank()) defaultValue() else this
    }
}
