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

    // ==========================================
    // بيانات تسجيل الدخول بناءً على تحليلك
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
            
            // استخراج التوكن من الصفحة
            val csrfToken = document.selectFirst("meta[name=csrf-token]")?.attr("content") 
                ?: document.selectFirst("input[name=_token]")?.attr("value") 
                ?: ""

            // إرسال طلب الدخول بنفس الطريقة التي ظهرت لك في Payload
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

            // إذا تم توجيهنا لصفحة أخرى (مثل الرئيسية) فهذا يعني نجاح الدخول
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
    // تحديث دالة استخراج البيانات لتتوافق مع التصميم الجديد (Tailwind CSS)
    // ==========================================
    private fun Element.toSearchResponse(): SearchResponse? {
        // this هنا تمثل الوسم <a> الذي يحتوي على الكلاس tw-movie-card
        val href = fixUrlNull(this.attr("href")) ?: return null
        val title = this.selectFirst("h3")?.text()?.trim() ?: return null
        val posterUrl = fixUrlNull(this.selectFirst("img")?.let {
            it.attr("data-src").ifBlank { it.attr("src") }
        })
        
        // التحقق مما إذا كان فيلم أو مسلسل من الرابط أو من خصائص العنصر
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
        performLogin() // تشغيل الدخول التلقائي
        
        val response = app.get("$mainUrl${request.data}?page=$page")
        val document = Jsoup.parse(response.text)
        
        // استخدام الكلاسات الجديدة لاستخراج العناصر
        val items = document.select("div.tw-movie-card a").mapNotNull { it.toSearchResponse() }
        val hasNext = document.selectFirst("a.next.page-numbers, a[rel=next], ul.pagination li a[rel=next]") != null
        
        return newHomePageResponse(request.name, items, hasNext)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        performLogin()
        
        val response = app.get("$mainUrl/search?s=$query")
        val document = Jsoup.parse(response.text)
        
        // استخدام الكلاسات الجديدة
        return document.select("div.tw-movie-card a").mapNotNull { it.toSearchResponse() }
    }

    override suspend fun load(url: String): LoadResponse {
        performLogin()
        
        val response = app.get(url)
        val cookies = response.cookies
        val document = Jsoup.parse(response.text)

        // ملاحظة: إذا تم تغيير تصميم الصفحة الرئيسية، فبنسبة كبيرة تم تغيير تصميم صفحة التفاصيل أيضاً.
        // تركت الأكواد القديمة هنا مؤقتاً. إذا لم يفتح المسلسل عند الضغط عليه، فسنحتاج لتحديث هذا القسم أيضاً.
        val title = document.selectFirst("div.info-detail-single h1")?.text()?.trim() ?: "No Title"
        var plot = document.selectFirst("div.info-detail-single p")?.text()?.trim()
        
        val posterUrl = fixUrlNull(
            document.selectFirst("meta[property=og:image]")?.attr("content").ifNullOrBlank {
                document.selectFirst("div.single-photo img, div.single-thumb-bg img")?.let {
                    it.attr("data-src").ifBlank { it.attr("src") }
                }
            }
        )
        
        val year = document.select("ul.mb-2 li:contains(سنة العرض) a")?.text()?.toIntOrNull()
        
        val isPro = document.selectFirst("span.series-ispro") != null
        val tags = document.select("div.post_tags a")?.map { it.text() }?.toMutableList() ?: mutableListOf()
        if (isPro) {
            tags.add(0, "مميز ☆彡")
        }

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
                this.posterUrl = posterUrl
                this.year = year
                this.plot = plot
                this.tags = tags
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = posterUrl
                this.year = year
                this.plot = plot
                this.tags = tags
            }
        }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
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

    private fun String?.ifNullOrBlank(defaultValue: () -> String?): String? {
        return if (this.isNullOrBlank()) defaultValue() else this
    }
}
