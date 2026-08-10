package com.asia2tv

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

@JsonIgnoreProperties(ignoreUnknown = true)
data class MoreEpisodesResponse(
    val status: Boolean,
    val html: String,
    val showmore: Boolean? = false,
    val newpage: Int? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
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

    // ==========================================
    // الكوكيز التي قمت أنت بجلبها من المتصفح (صالحة مؤقتاً للتجربة)
    // ==========================================
    private val myTestCookie = "XSRF-TOKEN=eyJpdiI6ImFQRGNOc1FDMnBidzZ0UzRGNmYwaEE9PSIsInZhbHVlIjoid1N1K0w3UFVFSCtjc0JLVVcwRlJuRW9yZnIzeVlLN1RMbGFrTnM0aWErN3d0V2lRWmxnQUhOUS9hVWxRQW9xaHdLSFZoVlJ4T000QVZwWEwwSU1CRHZLdUVwTEF2V0RMNjVqV2pBbmhUZDk2NFFrV1dTNUpvTHllSGcxVllaeGUiLCJtYWMiOiIxZjg5ZDRiMWQyNDNiN2I1ODg4ZTYzMzZlODRjYmNlNWM4OTQ5MTdmMWRjMTYzNWY4YzhmZTIwNGQwYjExY2ViIiwidGFnIjoiIn0%3D; asia2tvcom_session=eyJpdiI6IkVXVXlIbkdnbHl0aVpobXlLV0hJNVE9PSIsInZhbHVlIjoiVHNrRU5IUTRlTWVoMy9abkE1WXFDWVAzM1hLNTA4dXVYdXNFL29iVUVOTGh3MjhiczlNMi9aSGZYUE9HcHJMbkZxYXVCV2grQ2ZCSXVZSDl2NDFVRjlrVFUraThLSGRFODZRbm9URlphZUM0WlUvMFE4SkwyNFlHU2p2dWN0VEUiLCJtYWMiOiJmOWIwYzFhZTEyYjA1M2VlYzcyNGUxOGRmZjdhNzk3ZWM1OTkzY2Q2YTg4ZmNkMWQxZDE1OWRiOGE5OTg5ODM2IiwidGFnIjoiIn0%3D"

    private fun getBaseHeaders(): Map<String, String> {
        return mapOf(
            "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Mobile Safari/537.36",
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
            "Cookie" to myTestCookie
        )
    }

    private fun getAjaxHeaders(referer: String, csrfToken: String): Map<String, String> {
        return getBaseHeaders() + mapOf(
            "X-CSRF-TOKEN" to csrfToken,
            "X-Requested-With" to "XMLHttpRequest",
            "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8",
            "Referer" to referer
        )
    }

    private fun Element.toSearchResponse(): SearchResponse? {
        val mainLink = this.selectFirst("a[data-type]") ?: this.selectFirst("a") ?: return null
        val href = fixUrlNull(mainLink.attr("href")) ?: return null
        
        if (href == "#" || (!href.contains("/serie/") && !href.contains("/movie/"))) return null

        val title = mainLink.selectFirst("h3")?.text()?.trim() 
            ?: mainLink.selectFirst("img")?.attr("alt")?.trim() ?: "بدون عنوان"
            
        val imgElement = mainLink.selectFirst("img")
        val posterUrl = fixUrlNull(imgElement?.attr("data-src")?.ifBlank { imgElement.attr("src") })
        
        val isMovie = href.contains("/movie/") || mainLink.attr("data-type") == "movie"

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
        val response = app.get("$mainUrl${request.data}?page=$page", headers = getBaseHeaders())
        val document = Jsoup.parse(response.text)
        
        val items = document.select("div.tw-movie-card").mapNotNull { it.toSearchResponse() }
        val hasNext = document.selectFirst("a.next.page-numbers, a[rel=next], ul.pagination li a[rel=next]") != null
        
        return newHomePageResponse(request.name, items, hasNext)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val response = app.get("$mainUrl/search?s=$query", headers = getBaseHeaders())
        val document = Jsoup.parse(response.text)
        return document.select("div.tw-movie-card").mapNotNull { it.toSearchResponse() }
    }

    override suspend fun load(url: String): LoadResponse {
        val response = app.get(url, headers = getBaseHeaders())
        val document = Jsoup.parse(response.text)

        val title = document.selectFirst("h1")?.text()?.trim() ?: "No Title"
        val plot = document.selectFirst("h3:contains(القصة) + p")?.text()?.trim()
        val posterUrl = fixUrlNull(document.selectFirst("meta[property=og:image]")?.attr("content"))
        val year = document.selectFirst("span:contains(سنة العرض:) + a")?.text()?.toIntOrNull()
        val tags = document.select("div.box-tags a").map { it.text().trim() }
        
        val statusBadge = document.selectFirst(".serie_status_pro span, span:contains(أعمال مكتملة), span:contains(يبث حاليا)")?.text() ?: ""
        val status = if (statusBadge.contains("مكتملة")) ShowStatus.Completed else ShowStatus.Ongoing

        val episodes = mutableListOf<Episode>()
        val seenUrls = HashSet<String>()

        fun addUniqueEpisodes(elements: List<Element>) {
            for (element in elements) {
                val href = element.attr("href")
                val name = element.selectFirst(".titlepisode")?.text()?.trim()
                if (href.isNotBlank() && href != "#" && seenUrls.add(href)) {
                    val episodeNum = name?.replace(Regex("[^0-9]"), "")?.toIntOrNull()
                    episodes.add(newEpisode(href) {
                        this.name = name
                        this.episode = episodeNum
                    })
                }
            }
        }

        addUniqueEpisodes(document.select("div.loop-episode a.episode_box_tabs_container"))

        val serieId = document.selectFirst(".add_favorite")?.attr("data-id")
        val csrfToken = document.selectFirst("meta[name=csrf-token]")?.attr("content")

        if (serieId != null && csrfToken != null) {
            var currentPage = 2
            var hasMore = true
            while (hasMore) {
                try {
                    val ajaxHeaders = getAjaxHeaders(url, csrfToken)
                    val postData = "action=moreepisode&page=$currentPage&serieid=$serieId"
                    val requestBody = postData.toRequestBody("application/x-www-form-urlencoded; charset=UTF-8".toMediaType())
                    
                    val responseText = app.post(
                        "$mainUrl/ajaxGetRequest",
                        headers = ajaxHeaders,
                        requestBody = requestBody
                    ).text

                    val ajaxResponse = tryParseJson<MoreEpisodesResponse>(responseText)

                    if (ajaxResponse?.status == true && !ajaxResponse.html.isNullOrBlank()) {
                        addUniqueEpisodes(Jsoup.parse(ajaxResponse.html).select("a.episode_box_tabs_container"))
                        
                        if (ajaxResponse.newpage != null && ajaxResponse.newpage > currentPage) {
                            currentPage = ajaxResponse.newpage
                        } else {
                            hasMore = false
                        }
                    } else {
                        hasMore = false
                    }
                } catch (e: Exception) {
                    hasMore = false
                }
            }
        }

        episodes.reverse()

        val isMovie = url.contains("/movie/")
        return if (isMovie) {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = posterUrl; this.year = year; this.plot = plot; this.tags = tags
            }
        } else {
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = posterUrl; this.year = year; this.plot = plot; this.tags = tags; this.showStatus = status
            }
        }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        val response = app.get(data, headers = getBaseHeaders())
        val document = Jsoup.parse(response.text)
        
        val csrfToken = document.selectFirst("meta[name=csrf-token]")?.attr("content") ?: ""
        val ajaxHeaders = getAjaxHeaders(data, csrfToken)

        document.select("ul.dropdown-menu li a").amap { server ->
            try {
                val code = server.attr("data-code").ifBlank { return@amap }
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
}
