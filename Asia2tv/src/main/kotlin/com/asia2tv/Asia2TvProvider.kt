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
    private var csrfToken: String = ""
    
    private val loginMutex = Mutex()

    private suspend fun performSilentLogin(force: Boolean = false): Boolean {
        return loginMutex.withLock {
            if (sessionCookies.isNotEmpty() && !force) {
                try {
                    val testResp = app.get("$mainUrl/", cookies = sessionCookies)
                    if (!testResp.url.contains("login")) {
                        Log.d(TAG, "الكوكيز صالحة، لا حاجة لتسجيل الدخول")
                        return@withLock true
                    } else {
                        Log.d(TAG, "الكوكيز غير صالحة، نعيد تسجيل الدخول")
                        sessionCookies = emptyMap()
                    }
                } catch (e: Exception) {
                    Log.d(TAG, "فشل اختبار الكوكيز، نعيد تسجيل الدخول: ${e.message}")
                    sessionCookies = emptyMap()
                }
            }

            Log.d(TAG, "بدء عملية تسجيل الدخول التلقائي...")
            try {
                val loginUrl = "$mainUrl/login"
                val getResp = app.get(loginUrl)
                val document = Jsoup.parse(getResp.text)
                
                csrfToken = document.selectFirst("meta[name=csrf-token]")?.attr("content") 
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
                    )
                )

                if (!postResp.url.contains("login")) {
                    sessionCookies = initialCookies + postResp.cookies
                    Log.d(TAG, "نجاح تسجيل الدخول! الكوكيز: $sessionCookies")
                    csrfToken = postResp.headers["X-CSRF-TOKEN"] ?: csrfToken
                    return@withLock true
                } else {
                    Log.e(TAG, "فشل تسجيل الدخول: تم إعادة التوجيه إلى login")
                    return@withLock false
                }
            } catch (e: Exception) {
                Log.e(TAG, "خطأ أثناء تسجيل الدخول: ${e.message}")
                return@withLock false
            }
        }
    }

    private suspend fun requestWithLogin(
        url: String,
        method: String = "GET",
        data: Map<String, String>? = null,
        headers: Map<String, String> = emptyMap(),
        requestBody: okhttp3.RequestBody? = null,
        retry: Boolean = true
    ): String {
        Log.d(TAG, "requestWithLogin: $method $url")
        if (!performSilentLogin()) {
            throw Exception("فشل تسجيل الدخول")
        }

        val finalHeaders = headers.toMutableMap().apply {
            this["User-Agent"] = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
            this["Accept"] = "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8"
            this["Accept-Language"] = "ar,en;q=0.9"
            if (csrfToken.isNotBlank()) {
                this["X-CSRF-TOKEN"] = csrfToken
            }
        }

        try {
            val response = if (method.equals("POST", ignoreCase = true)) {
                if (requestBody != null) {
                    app.post(url, headers = finalHeaders, cookies = sessionCookies, requestBody = requestBody)
                } else {
                    app.post(url, headers = finalHeaders, cookies = sessionCookies, data = data ?: emptyMap())
                }
            } else {
                app.get(url, headers = finalHeaders, cookies = sessionCookies)
            }

            if (response.url.contains("login")) {
                Log.d(TAG, "تم إعادة التوجيه إلى login، نعيد تسجيل الدخول")
                if (retry) {
                    sessionCookies = emptyMap()
                    performSilentLogin(force = true)
                    return requestWithLogin(url, method, data, headers, requestBody, retry = false)
                } else {
                    throw Exception("لا يمكن تجاوز إعادة التوجيه إلى login")
                }
            }

            Log.d(TAG, "تم جلب الصفحة بنجاح، الطول: ${response.text.length}")
            return response.text
        } catch (e: Exception) {
            Log.e(TAG, "خطأ في الطلب: ${e.message}")
            throw e
        }
    }

    private fun getAjaxHeaders(referer: String): Map<String, String> {
        return mapOf(
            "X-Requested-With" to "XMLHttpRequest",
            "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8",
            "Referer" to referer,
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
            "Accept" to "application/json, text/javascript, */*; q=0.01"
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
        Log.d(TAG, "getMainPage: page=$page, request.data=${request.data}")
        try {
            val url = "$mainUrl${request.data}?page=$page"
            Log.d(TAG, "getMainPage: جلب $url")
            val html = requestWithLogin(url)
            Log.d(TAG, "getMainPage: تم جلب الصفحة، طول النص=${html.length}")
            val document = Jsoup.parse(html)
            val items = document.select("div.tw-movie-card").mapNotNull { it.toSearchResponse() }
            Log.d(TAG, "getMainPage: عدد العناصر المسترجعة = ${items.size}")
            val hasNext = document.selectFirst("a.next.page-numbers, a[rel=next], ul.pagination li a[rel=next]") != null
            Log.d(TAG, "getMainPage: hasNext = $hasNext")
            return newHomePageResponse(request.name, items, hasNext)
        } catch (e: Exception) {
            Log.e(TAG, "getMainPage: خطأ - ${e.message}", e)
            // إعادة صفحة فارغة لتجنب تعطل التطبيق
            return newHomePageResponse(request.name, emptyList(), false)
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        Log.d(TAG, "search: $query")
        try {
            val html = requestWithLogin("$mainUrl/search?s=$query")
            val document = Jsoup.parse(html)
            return document.select("div.tw-movie-card").mapNotNull { it.toSearchResponse() }
        } catch (e: Exception) {
            Log.e(TAG, "search: خطأ - ${e.message}", e)
            return emptyList()
        }
    }

    override suspend fun load(url: String): LoadResponse {
        Log.d(TAG, "load: $url")
        try {
            val html = requestWithLogin(url)
            val document = Jsoup.parse(html)

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
            val pageCsrf = document.selectFirst("meta[name=csrf-token]")?.attr("content")
            if (pageCsrf != null) csrfToken = pageCsrf

            if (serieId != null && csrfToken.isNotBlank()) {
                var currentPage = 2
                var hasMore = true
                while (hasMore) {
                    try {
                        val postData = "action=moreepisode&page=$currentPage&serieid=$serieId"
                        val requestBody = postData.toRequestBody("application/x-www-form-urlencoded; charset=UTF-8".toMediaType())
                        
                        val responseText = app.post(
                            "$mainUrl/ajaxGetRequest",
                            headers = getAjaxHeaders(url) + mapOf("X-CSRF-TOKEN" to csrfToken),
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
        } catch (e: Exception) {
            Log.e(TAG, "load: خطأ - ${e.message}", e)
            throw e
        }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        Log.d(TAG, "loadLinks: $data")
        try {
            val html = requestWithLogin(data)
            val document = Jsoup.parse(html)
            
            val pageCsrf = document.selectFirst("meta[name=csrf-token]")?.attr("content")
            if (pageCsrf != null) csrfToken = pageCsrf

            document.select("ul.dropdown-menu li a").amap { server ->
                try {
                    val code = server.attr("data-code").ifBlank { return@amap }
                    val postData = "action=iframe_server&code=$code"
                    val requestBody = postData.toRequestBody("application/x-www-form-urlencoded; charset=UTF-8".toMediaType())
                    
                    val responseText = app.post(
                        "$mainUrl/ajaxGetRequest",
                        headers = getAjaxHeaders(data) + mapOf("X-CSRF-TOKEN" to csrfToken),
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
        } catch (e: Exception) {
            Log.e(TAG, "loadLinks: خطأ - ${e.message}", e)
            return false
        }
    }
}
