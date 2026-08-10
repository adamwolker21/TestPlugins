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

    // علامة التتبع في الـ Logcat
    private val TAG = "Asia2TvDebug"

    private val loginUsername = "kelly93"
    private val loginPassword = "kelly.brown93@"
    
    private var currentCookies: Map<String, String> = emptyMap()
    private var isLoggedIn = false

    private suspend fun performLogin() {
        if (isLoggedIn) {
            Log.d(TAG, "تم تسجيل الدخول مسبقاً، تخطي العملية.")
            return
        }

        Log.d(TAG, "--- بدء عملية تسجيل الدخول ---")
        try {
            val loginUrl = "$mainUrl/login"
            
            val getResp = app.get(loginUrl)
            Log.d(TAG, "1. طلب GET لصفحة الدخول: الكود ${getResp.code}, الرابط: ${getResp.url}")
            
            val document = Jsoup.parse(getResp.text)
            
            if (document.title().contains("Just a moment", true) || document.selectFirst("div.cf-browser-verification") != null) {
                Log.e(TAG, "حظر Cloudflare! نحن في صفحة التحدي.")
                return
            }

            val csrfToken = document.selectFirst("meta[name=csrf-token]")?.attr("content") 
                ?: document.selectFirst("input[name=_token]")?.attr("value") 
                ?: ""
            
            Log.d(TAG, "2. تم استخراج CSRF Token: $csrfToken")

            val initialCookies = getResp.cookies
            Log.d(TAG, "3. الكوكيز المبدئية: $initialCookies")

            val postResp = app.post(
                loginUrl,
                headers = mapOf(
                    "Referer" to loginUrl,
                    "X-CSRF-TOKEN" to csrfToken
                ),
                cookies = initialCookies,
                data = mapOf(
                    "email" to loginUsername,
                    "password" to loginPassword,
                    "_token" to csrfToken
                )
            )

            Log.d(TAG, "4. طلب POST أرجع الكود: ${postResp.code}, الرابط: ${postResp.url}")

            if (!postResp.url.contains("login")) {
                isLoggedIn = true
                currentCookies = initialCookies + postResp.cookies
                Log.d(TAG, "--- نجاح تسجيل الدخول! الكوكيز النهائية: $currentCookies ---")
            } else {
                Log.e(TAG, "فشل تسجيل الدخول (ما زلنا في صفحة Login). جزء من HTML: ${postResp.text.take(300)}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "خطأ (Exception) أثناء الدخول: ${e.message}")
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
        Log.d(TAG, "--- طلب الصفحة الرئيسية: ${request.name}, الصفحة: $page ---")
        performLogin()
        
        val url = "$mainUrl${request.data}?page=$page"
        Log.d(TAG, "الرابط المطلوب: $url")
        
        val response = app.get(url, cookies = currentCookies)
        Log.d(TAG, "كود استجابة الصفحة الرئيسية: ${response.code}")
        
        val document = Jsoup.parse(response.text)
        
        // التحقق مما إذا كنا في صفحة حماية كلاودفلير
        if (document.title().contains("Just a moment", true)) {
            Log.e(TAG, "تم حظرنا من Cloudflare في الصفحة الرئيسية!")
        }

        val items = document.select("div.tw-movie-card").mapNotNull { it.toSearchResponse() }
        
        Log.d(TAG, "تم العثور على ${items.size} عنصر في هذه الصفحة.")
        
        // إذا كان العدد 0، سنقوم بطباعة جزء من كود HTML لمعرفة ما يوجد في الصفحة
        if (items.isEmpty()) {
            Log.e(TAG, "لم يتم العثور على أي عناصر! جزء من كود الصفحة: \n ${response.text.take(600)}")
        }

        val hasNext = document.selectFirst("a.next.page-numbers, a[rel=next], ul.pagination li a[rel=next]") != null
        
        return newHomePageResponse(request.name, items, hasNext)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        Log.d(TAG, "--- طلب بحث: $query ---")
        performLogin()
        val response = app.get("$mainUrl/search?s=$query", cookies = currentCookies)
        val document = Jsoup.parse(response.text)
        
        val items = document.select("div.tw-movie-card").mapNotNull { it.toSearchResponse() }
        Log.d(TAG, "تم العثور على ${items.size} نتيجة للبحث.")
        return items
    }

    override suspend fun load(url: String): LoadResponse {
        Log.d(TAG, "--- طلب تفاصيل: $url ---")
        performLogin()
        val response = app.get(url, cookies = currentCookies)
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
        
        Log.d(TAG, "تم العثور على ${episodes.size} حلقة في الصفحة الأولى.")

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
                        cookies = currentCookies,
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
        val response = app.get(data, cookies = currentCookies)
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
                    cookies = currentCookies,
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
