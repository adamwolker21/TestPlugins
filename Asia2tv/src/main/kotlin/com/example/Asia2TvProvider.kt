package com.example

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import org.jsoup.nodes.Element
import java.util.ArrayList

// Definir بنية بيانات لاستقبال استجابة AJAX
data class PlayerAjaxResponse(
    val embed_url: String
)

// v5: إعادة كتابة منطق جلب الصفحة الرئيسية بالكامل ليتوافق مع بنية الموقع الفعلية
class Asia2Tv : MainAPI() {
    override var name = "Asia2Tv"
    override var mainUrl = "https://asia2tv.com"
    override var lang = "ar"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    // دالة لتحويل عنصر HTML إلى نتيجة بحث (لا تغيير هنا)
    private fun Element.toSearchResponse(): SearchResponse {
        val titleElement = this.selectFirst("h3 a")
        val title = titleElement?.text() ?: "No Title"
        val href = fixUrlNull(titleElement?.attr("href")) ?: ""
        val posterUrl = fixUrlNull(this.selectFirst("div.thumbnail img")?.attr("data-src"))

        val typeText = this.selectFirst("span.type")?.text()?.lowercase()
        val isMovie = typeText?.contains("فيلم") == true || href.contains("/movie/")

        return if (isMovie) {
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = posterUrl
            }
        } else {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = posterUrl
            }
        }
    }

    // تم حذف `mainPageOf` لأننا سنبني الصفحة الرئيسية يدويًا
    // override val mainPage = mainPageOf(...)

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        // الصفحة الرئيسية لا تدعم الترقيم، لذا نرجع فارغًا إذا طلب المستخدم صفحة غير الأولى
        if (page > 1) return HomePageResponse(emptyList())

        val document = app.get(mainUrl).document
        val homePageList = ArrayList<HomePageList>()

        // استهداف جميع أقسام المحتوى في الصفحة الرئيسية
        val allSections = document.select("div.bdaia-home-container-wrap")

        allSections.forEach { section ->
            // استخراج عنوان القسم
            val title = section.selectFirst("div.widget-title h4.block-title span")?.text() ?: return@forEach
            
            // استخراج جميع العناصر (أفلام/مسلسلات) داخل هذا القسم
            val items = section.select("article.item").mapNotNull {
                try {
                    it.toSearchResponse()
                } catch (e: Exception) {
                    null
                }
            }

            // إضافة القسم إلى القائمة فقط إذا كان يحتوي على عناصر
            if (items.isNotEmpty()) {
                homePageList.add(HomePageList(title, items))
            }
        }

        return HomePageResponse(homePageList)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/?s=$query" // استخدام رابط البحث الصحيح
        val document = app.get(url).document
        return document.select("article.item").mapNotNull {
            try {
                it.toSearchResponse()
            } catch (e: Exception) {
                null
            }
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

        val title = document.selectFirst("h1.entry-title")?.text()?.trim() ?: "No Title"
        val posterUrl = fixUrlNull(document.selectFirst("div.thumb img")?.attr("src"))
        val plot = document.selectFirst("div.entry-content p")?.text()?.trim() ?: ""
        val tags = document.select("div.genres a").map { it.text() }
        
        val episodesList = document.select("ul.seasons-list li.episode-item")

        return if (episodesList.isNotEmpty()) {
            val episodes = episodesList.mapNotNull { el ->
                val a = el.selectFirst("a")
                val href = a?.attr("href") ?: return@mapNotNull null
                val epTitle = a.selectFirst(".episode-title")?.text()
                val epNumText = a.selectFirst(".episode-number")?.text()?.replace(Regex("[^0-9]"), "")
                val epNum = epNumText?.toIntOrNull()
                
                newEpisode(href) {
                    name = epTitle ?: "الحلقة $epNumText"
                    episode = epNum
                }
            }.reversed()

            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = posterUrl
                this.plot = plot
                this.tags = tags
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = posterUrl
                this.plot = plot
                this.tags = tags
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document
        
        val servers = document.select("ul.servers-tabs li")
        
        servers.apmap { server ->
            try {
                val videoId = server.attr("data-id")
                val postId = server.attr("data-post")

                val ajaxUrl = "$mainUrl/wp-admin/admin-ajax.php"
                val response = app.post(
                    ajaxUrl,
                    data = mapOf(
                        "action" to "bdaia_player_ajax",
                        "post_id" to postId,
                        "video_id" to videoId
                    ),
                    referer = data
                ).text

                val embedUrlFragment = parseJson<PlayerAjaxResponse>(response).embed_url
                val embedUrl = if (embedUrlFragment.startsWith("//")) "https:$embedUrlFragment" else embedUrlFragment

                val iframeSrc = app.get(embedUrl, referer = data).document.selectFirst("iframe")?.attr("src")
                
                if (iframeSrc != null) {
                    loadExtractor(iframeSrc, data, subtitleCallback, callback)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        return true
    }
}
