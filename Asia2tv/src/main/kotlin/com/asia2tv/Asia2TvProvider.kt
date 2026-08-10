package com.asia2tv

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import android.util.Log

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
    
    // دالة تقوم بتجهيز جلسة (Session) فعالة
    private suspend fun getAuthenticatedDocument(url: String): Element {
        Log.d(TAG, "جاري طلب الرابط: $url")
        
        // 1. فتح الصفحة لغرض جلب الـ Token
        val getResp = app.get("$mainUrl/login")
        val loginDoc = Jsoup.parse(getResp.text)
        
        val csrfToken = loginDoc.selectFirst("meta[name=csrf-token]")?.attr("content") 
            ?: loginDoc.selectFirst("input[name=_token]")?.attr("value") 
            ?: ""

        // 2. إرسال بيانات الدخول للحصول على كوكيز الجلسة الفعالة
        val postResp = app.post(
            "$mainUrl/login",
            headers = mapOf(
                "Referer" to "$mainUrl/login",
                "X-CSRF-TOKEN" to csrfToken
            ),
            cookies = getResp.cookies,
            data = mapOf(
                "email" to loginUsername,
                "password" to loginPassword,
                "_token" to csrfToken
            )
        )
        
        val activeCookies = getResp.cookies + postResp.cookies

        // 3. جلب الرابط المطلوب باستخدام الكوكيز الفعالة
        val finalResp = app.get(url, cookies = activeCookies)
        val finalDoc = Jsoup.parse(finalResp.text)
        
        Log.d(TAG, "تم تحميل الصفحة: ${finalDoc.title()}")
        return finalDoc
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
        val url = "$mainUrl${request.data}?page=$page"
        val document = getAuthenticatedDocument(url)

        val items = document.select("div.tw-movie-card").mapNotNull { it.toSearchResponse() }
        
        if (items.isEmpty()) {
            Log.e(TAG, "لا يوجد عناصر في الرئيسية! الـ HTML: \n ${document.html().take(500)}")
        } else {
            Log.d(TAG, "نجاح! تم جلب ${items.size} عنصر للرئيسية.")
        }

        val hasNext = document.selectFirst("a.next.page-numbers, a[rel=next], ul.pagination li a[rel=next]") != null
        
        return newHomePageResponse(request.name, items, hasNext)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/search?s=$query"
        val document = getAuthenticatedDocument(url)
        return document.select("div.tw-movie-card").mapNotNull { it.toSearchResponse() }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = getAuthenticatedDocument(url)

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

    // تم تبسيط دالة الروابط حالياً لتعمل مباشرة
    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        return true
    }
}
