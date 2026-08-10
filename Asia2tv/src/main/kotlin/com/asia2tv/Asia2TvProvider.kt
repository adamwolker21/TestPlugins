package com.asia2tv

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
    // بيانات تسجيل الدخول
    // ==========================================
    private val userEmail = "kelly.brown93@mail.ru"
    private val userPassword = "kelly.brown93@"
    private var isLoggedIn = false

    private suspend fun performLogin() {
        if (isLoggedIn) return

        try {
            val loginPageUrl = "$mainUrl/login"
            val loginPage = app.get(loginPageUrl)
            val document = Jsoup.parse(loginPage.text)
            
            val csrfToken = document.selectFirst("meta[name=csrf-token]")?.attr("content") 
                ?: document.selectFirst("input[name=_token]")?.attr("value") 
                ?: ""

            val response = app.post(
                loginPageUrl,
                headers = mapOf(
                    "Referer" to loginPageUrl,
                    "X-CSRF-TOKEN" to csrfToken
                ),
                data = mapOf(
                    "email" to userEmail,
                    "password" to userPassword,
                    "_token" to csrfToken
                )
            )

            // إذا تغير الرابط بعد الإرسال (مثلاً ذهب للرئيسية) فهذا يعني النجاح
            if (!response.url.contains("login")) {
                isLoggedIn = true
                Log.d("Asia2Tv", "Login Successful!")
            }
        } catch (e: Exception) {
            Log.e("Asia2Tv", "Login Failed: ${e.message}")
        }
    }
    // ==========================================

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

    // ==========================================
    // دالة استخراج الأفلام للصفحة الرئيسية (تم تعديلها لمنع التكرار)
    // ==========================================
    private fun Element.toSearchResponse(): SearchResponse? {
        val href = fixUrlNull(this.attr("href")) ?: return null
        
        // استبعاد أي روابط لا تخص المسلسلات أو الأفلام (مثل روابط qtip)
        if (href == "#" || (!href.contains("/serie/") && !href.contains("/movie/"))) return null

        val title = this.selectFirst("h3")?.text()?.trim() ?: return null
        val posterUrl = fixUrlNull(this.selectFirst("img")?.let {
            it.attr("data-src").ifBlank { it.attr("src") }
        })
        
        val isMovie = href.contains("/movie/") || this.attr("data-type") == "movie"

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
        performLogin()
        
        val response = app.get("$mainUrl${request.data}?page=$page")
        val document = Jsoup.parse(response.text)
        
        // اختيار الرابط المباشر فقط (a.z-10) لتجنب جلب الأزرار الأخرى داخل البطاقة وتكرار المحتوى
        val items = document.select("div.tw-movie-card > a.z-10").mapNotNull { it.toSearchResponse() }
        val hasNext = document.selectFirst("a.next.page-numbers, a[rel=next], ul.pagination li a[rel=next]") != null
        
        return newHomePageResponse(request.name, items, hasNext)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        performLogin()
        
        val response = app.get("$mainUrl/search?s=$query")
        val document = Jsoup.parse(response.text)
        
        return document.select("div.tw-movie-card > a.z-10").mapNotNull { it.toSearchResponse() }
    }

    override suspend fun load(url: String): LoadResponse {
        performLogin()
        
        val response = app.get(url)
        val cookies = response.cookies
        val document = Jsoup.parse(response.text)

        // 1. استخراج العنوان والقصة بناءً على التصميم الجديد
        val title = document.selectFirst("h1")?.text()?.trim() ?: "No Title"
        val plot = document.selectFirst("h3:contains(القصة) + p")?.text()?.trim()
        
        // 2. استخراج البوستر باستخدام الميتا تاج (أضمن طريقة)
        val posterUrl = fixUrlNull(document.selectFirst("meta[property=og:image]")?.attr("content"))
        
        // 3. استخراج سنة العرض
        val year = document.selectFirst("span:contains(سنة العرض:) + a")?.text()?.toIntOrNull()
        
        // 4. استخراج الأقسام (التصنيفات)
        val tags = document.select("div.box-tags a").map { it.text().trim() }
        
        // 5. استخراج حالة المسلسل (مكتمل / يبث حالياً)
        val statusBadge = document.selectFirst(".serie_status_pro span, span:contains(أعمال مكتملة), span:contains(يبث حاليا)")?.text() ?: ""
        val status = if (statusBadge.contains("مكتملة")) ShowStatus.Completed else ShowStatus.Ongoing

        // 6. استخراج الحلقات
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

        // جلب الحلقات الموجودة في الصفحة الحالية
        addUniqueEpisodes(document.select("div.loop-episode a.episode_box_tabs_container"))

        // 7. جلب باقي الحلقات عن طريق AJAX (إن وجدت)
        val serieId = document.selectFirst(".add_favorite")?.attr("data-id")
        val csrfToken = document.selectFirst("meta[name=csrf-token]")?.attr("content")

        if (serieId != null && csrfToken != null) {
            var currentPage = 2
            var hasMore = true
            while (hasMore) {
                try {
                    val ajaxHeaders = getAjaxHeaders(url, csrfToken, cookies)
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
                        
                        // في الكود الخاص بهم، يرسلون رقم الصفحة التالية أو يعيدون status=false
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

        // عكس ترتيب الحلقات لتصبح من 1 إلى الأخير (لأن المواقع غالباً تضع الأحدث بالأعلى)
        episodes.reverse()

        val isMovie = url.contains("/movie/")
        return if (isMovie) {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = posterUrl
                this.year = year
                this.plot = plot
                this.tags = tags
            }
        } else {
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = posterUrl
                this.year = year
                this.plot = plot
                this.tags = tags
                this.showStatus = status
            }
        }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        // إذا كان الرابط هو رابط مسلسل (episode) أو فيلم (movie)، سنحتاج لدراسة صفحة التشغيل 
        // مؤقتاً سأترك الكود القديم هنا الخاص بسيرفرات المشاهدة
        val response = app.get(data)
        val cookies = response.cookies
        val document = Jsoup.parse(response.text)
        
        val csrfToken = document.selectFirst("meta[name=csrf-token]")?.attr("content") ?: ""
        val ajaxHeaders = getAjaxHeaders(data, csrfToken, cookies)

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
