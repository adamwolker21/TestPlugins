package com.asia2tv

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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

    private var sessionCookies: Map<String, String> = emptyMap()
    private val loginMutex = Mutex()

    private val myHeaders = mapOf(
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7",
        "Accept-Language" to "en-US,en;q=0.9",
        "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Mobile Safari/537.36"
    )

    private suspend fun performSilentLogin() {
        loginMutex.withLock {
            if (sessionCookies.isNotEmpty()) return

            try {
                val loginUrl = "$mainUrl/login"
                val getResp = app.get(loginUrl, headers = myHeaders)
                val document = Jsoup.parse(getResp.text)
                
                val csrfToken = document.selectFirst("input[name=_token]")?.attr("value") 
                    ?: document.selectFirst("meta[name=csrf-token]")?.attr("content") 
                    ?: ""

                val initialCookies = getResp.cookies

                val postResp = app.post(
                    loginUrl,
                    headers = myHeaders + mapOf(
                        "Referer" to loginUrl,
                        "Origin" to mainUrl
                    ),
                    cookies = initialCookies,
                    data = mapOf(
                        "_token" to csrfToken,
                        "email" to "kelly93",
                        "password" to "kelly.brown93@"
                    ),
                    allowRedirects = false
                )

                val location = postResp.headers["location"] ?: postResp.headers["Location"] ?: ""

                if (postResp.code == 302 && !location.contains("login")) {
                    sessionCookies = initialCookies + postResp.cookies
                    Log.d("Asia2Tv", "تم تسجيل الدخول بنجاح! الكوكيز: $sessionCookies")
                } else {
                    sessionCookies = initialCookies
                }
            } catch (e: Exception) {
                Log.e("Asia2Tv", "خطأ بالدخول: ${e.message}")
            }
        }
    }

    private fun getAjaxHeaders(referer: String, csrfToken: String): Map<String, String> {
        return myHeaders + mapOf(
            "X-CSRF-TOKEN" to csrfToken,
            "X-Requested-With" to "XMLHttpRequest",
            "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8",
            "Referer" to referer
        )
    }

    private fun Element.toSearchResponse(): SearchResponse? {
        val mainLink = this.selectFirst("a[data-type]") ?: this.selectFirst("a") ?: return null
        val href = fixUrlNull(mainLink.attr("href")) ?: return null
        
        if (href == "#" || (!href.contains("/serie/") && !href.contains("/movie/") && !href.contains("/episode/"))) return null

        val baseTitle = mainLink.selectFirst("h3")?.text()?.trim() 
            ?: mainLink.selectFirst("img")?.attr("alt")?.trim() ?: "بدون عنوان"
            
        val badge = this.selectFirst(".tw-badge")?.text()?.trim()
        val title = if (!badge.isNullOrBlank() && href.contains("/episode/")) "$baseTitle ($badge)" else baseTitle

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
        "/series" to "المسلسلات"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        performSilentLogin()
        
        val response = app.get("$mainUrl${request.data}?page=$page", headers = myHeaders, cookies = sessionCookies)
        val document = Jsoup.parse(response.text)

        val items = document.select("div.tw-movie-card").mapNotNull { it.toSearchResponse() }
        val hasNext = document.selectFirst("a.next.page-numbers, a[rel=next], ul.pagination li a[rel=next]") != null
        
        return newHomePageResponse(request.name, items, hasNext)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        performSilentLogin()
        
        val response = app.get("$mainUrl/search?s=$query", headers = myHeaders, cookies = sessionCookies)
        val document = Jsoup.parse(response.text)
        return document.select("div.tw-movie-card").mapNotNull { it.toSearchResponse() }
    }

    override suspend fun load(url: String): LoadResponse {
        performSilentLogin()
        
        val response = app.get(url, headers = myHeaders, cookies = sessionCookies)
        val document = Jsoup.parse(response.text)

        val title = document.selectFirst("h1")?.text()?.trim() ?: "No Title"
        val plotRaw = document.selectFirst("h3:contains(القصة) + p")?.text()?.trim() ?: ""
        val posterUrl = fixUrlNull(document.selectFirst("meta[property=og:image]")?.attr("content"))
        val year = document.selectFirst("span:contains(سنة العرض:) + a")?.text()?.toIntOrNull()
        val tags = document.select("div.box-tags a").map { it.text().trim() }
        
        val statusBadge = document.selectFirst(".serie_status_pro span, span:contains(أعمال مكتملة), span:contains(يبث حاليا)")?.text() ?: ""
        val status = if (statusBadge.contains("مكتملة")) ShowStatus.Completed else ShowStatus.Ongoing

        val actorsList = document.select("div.flex.flex-wrap.gap-3 a[href*=/artist/]").mapNotNull {
            val name = it.selectFirst("span")?.text()?.trim() ?: return@mapNotNull null
            val image = fixUrlNull(it.selectFirst("img")?.attr("data-src")?.ifBlank { it.selectFirst("img")?.attr("src") })
            ActorData(actor = Actor(name, image))
        }

        var country = ""
        var epsCount = ""
        var airDate = ""

        document.select("div.grid.grid-cols-1.gap-2 div.flex.items-center").forEach { row ->
            val text = row.text()
            if (text.contains("البلد المنتج:")) country = text.replace("البلد المنتج:", "").trim()
            if (text.contains("عدد الحلقات:")) epsCount = text.replace("عدد الحلقات:", "").trim()
            if (text.contains("موعد البث:")) airDate = text.replace("موعد البث:", "").trim()
        }

        val extraInfoList = mutableListOf<String>()
        // استخدام <b> لجعل العناوين بخط خشن
        if (country.isNotBlank()) extraInfoList.add("<b>البلد المنتج:</b> $country")
        if (epsCount.isNotBlank()) extraInfoList.add("<b>عدد الحلقات:</b> $epsCount")
        if (airDate.isNotBlank()) extraInfoList.add("<b>موعد البث:</b> $airDate")

        val extraInfo = extraInfoList.joinToString(" | ")

        // استخدام <br><br> بدلاً من \n\n لضمان ظهور السطر الفارغ
        val finalPlot = if (extraInfo.isNotBlank()) {
            if (plotRaw.isBlank()) extraInfo else "$plotRaw<br><br>$extraInfo"
        } else {
            plotRaw
        }

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
                    val postData = "action=moreepisode&page=$currentPage&serieid=$serieId"
                    val requestBody = postData.toRequestBody("application/x-www-form-urlencoded; charset=UTF-8".toMediaType())
                    
                    val responseText = app.post(
                        "$mainUrl/ajaxGetRequest",
                        headers = getAjaxHeaders(url, csrfToken),
                        cookies = sessionCookies,
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

        if (episodes.isEmpty() && url.contains("/episode/")) {
            episodes.add(newEpisode(url) {
                this.name = title
                this.episode = title.replace(Regex("[^0-9]"), "").toIntOrNull()
            })
        }

        episodes.reverse()

        val isMovie = url.contains("/movie/")
        return if (isMovie) {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = posterUrl
                this.year = year
                this.plot = finalPlot
                this.tags = tags
                this.actors = actorsList
            }
        } else {
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = posterUrl
                this.year = year
                this.plot = finalPlot
                this.tags = tags
                this.showStatus = status
                this.actors = actorsList
            }
        }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        performSilentLogin()
        
        val response = app.get(data, headers = myHeaders, cookies = sessionCookies)
        val document = Jsoup.parse(response.text)
        
        val csrfToken = document.selectFirst("meta[name=csrf-token]")?.attr("content") ?: ""

        document.select("ul.dropdown-menu li a").amap { server ->
            try {
                val code = server.attr("data-code").ifBlank { return@amap }
                val postData = "action=iframe_server&code=$code"
                val requestBody = postData.toRequestBody("application/x-www-form-urlencoded; charset=UTF-8".toMediaType())
                
                val responseText = app.post(
                    "$mainUrl/ajaxGetRequest",
                    headers = getAjaxHeaders(data, csrfToken),
                    cookies = sessionCookies,
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
