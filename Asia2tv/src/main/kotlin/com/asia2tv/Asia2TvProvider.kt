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
import com.lagradost.cloudstream3.network.CloudflareInterceptor

// الاستيرادات الصحيحة تماماً كما في إضافة Arabseed
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

    private val TAG = "Asia2TvDebug"

    private val loginUsername = "kelly93"
    private val loginPassword = "kelly.brown93@"
    
    private var sessionCookies: Map<String, String> = emptyMap()
    
    // استخدام المتغير الاحترافي Mutex لمنع ازدحام الطلبات (تماما كما في Arabseed)
    private val loginMutex = Mutex()

    private suspend fun performSilentLogin() {
        // نستخدم القفل لضمان عدم دخول أكثر من طلب في نفس الوقت إلى كود تسجيل الدخول
        loginMutex.withLock {
            // إذا كان هناك طلب آخر قد جلب الكوكيز مسبقاً، ننهي الدالة فوراً
            if (sessionCookies.isNotEmpty()) return

            Log.d(TAG, "بدء عملية تسجيل الدخول التلقائي في الخلفية بطلب واحد...")
            try {
                val loginUrl = "$mainUrl/login"
                
                val getResp = app.get(loginUrl, interceptor = CloudflareInterceptor())
                val document = Jsoup.parse(getResp.text)
                
                val csrfToken = document.selectFirst("meta[name=csrf-token]")?.attr("content") 
                    ?: document.selectFirst("input[name=_token]")?.attr("value") 
                    ?: ""

                val initialCookies = getResp.cookies

                val postResp = app.post(
                    loginUrl,
                    headers = mapOf(
                        "Referer" to loginUrl,
                        "X-CSRF-TOKEN" to csrfToken,
                        "Content-Type" to "application/x-www-form-urlencoded"
                    ),
                    cookies = initialCookies,
                    data = mapOf(
                        "email" to loginUsername,
                        "password" to loginPassword,
                        "_token" to csrfToken
                    ),
                    interceptor = CloudflareInterceptor()
                )

                if (!postResp.url.contains("login")) {
                    sessionCookies = initialCookies + postResp.cookies
                    Log.d(TAG, "نجاح تسجيل الدخول! تم حفظ الكوكيز: $sessionCookies")
                } else {
                    Log.e(TAG, "فشل تسجيل الدخول التلقائي.")
                }
            } catch (e: Exception) {
                Log.e(TAG, "خطأ برمجي أثناء الدخول: ${e.message}")
            }
        }
    }

    private fun getAjaxHeaders(referer: String, csrfToken: String): Map<String, String> {
        return mapOf(
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
        performSilentLogin()
        
        val url = "$mainUrl${request.data}?page=$page"
        val response = app.get(url, cookies = sessionCookies, interceptor = CloudflareInterceptor())
        val document = Jsoup.parse(response.text)

        val items = document.select("div.tw-movie-card").mapNotNull { it.toSearchResponse() }
        val hasNext = document.selectFirst("a.next.page-numbers, a[rel=next], ul.pagination li a[rel=next]") != null
        
        return newHomePageResponse(request.name, items, hasNext)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        performSilentLogin()
        val response = app.get("$mainUrl/search?s=$query", cookies = sessionCookies, interceptor = CloudflareInterceptor())
        val document = Jsoup.parse(response.text)
        return document.select("div.tw-movie-card").mapNotNull { it.toSearchResponse() }
    }

    override suspend fun load(url: String): LoadResponse {
        performSilentLogin()
        val response = app.get(url, cookies = sessionCookies, interceptor = CloudflareInterceptor())
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
                    val postData = "action=moreepisode&page=$currentPage&serieid=$serieId"
                    val requestBody = postData.toRequestBody("application/x-www-form-urlencoded; charset=UTF-8".toMediaType())
                    
                    val responseText = app.post(
                        "$mainUrl/ajaxGetRequest",
                        headers = getAjaxHeaders(url, csrfToken),
                        cookies = sessionCookies,
                        requestBody = requestBody,
                        interceptor = CloudflareInterceptor()
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
        performSilentLogin()
        val response = app.get(data, cookies = sessionCookies, interceptor = CloudflareInterceptor())
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
                    requestBody = requestBody,
                    interceptor = CloudflareInterceptor()
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
