package com.example

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import org.jsoup.nodes.Element

// Definir بنية بيانات لاستقبال استجابة AJAX
data class PlayerAjaxResponse(
    val embed_url: String
)

// v7: استخدام قائمة الأقسام الصحيحة، إعادة إضافة الترويسات (Headers)، وتبسيط الكود.
class Asia2Tv : MainAPI() {
    override var name = "Asia2Tv"
    override var mainUrl = "https://asia2tv.com"
    override var lang = "ar"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    // دالة لتحويل عنصر HTML إلى نتيجة بحث
    private fun Element.toSearchResponse(): SearchResponse? {
        val titleElement = this.selectFirst("h3 a") ?: return null
        val title = titleElement.text()
        val href = fixUrlNull(titleElement.attr("href")) ?: return null
        // **تحسين**: جلب الصورة من `data-src` أولاً، ثم `src` كخيار احتياطي
        val posterUrl = fixUrlNull(this.selectFirst("div.thumbnail img")?.let {
            it.attr("data-src").ifBlank { it.attr("src") }
        })

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

    // **التصحيح**: استخدام قائمة الأقسام الجديدة والمبسطة
    override val mainPage = mainPageOf(
        "/newepisode" to "الحلقات الجديدة",
        "/status/live" to "يبث حاليا",
        "/series" to "المسلسلات",
        "/status/complete" to "أعمال مكتملة",
        "/movies" to "الأفلام"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = "$mainUrl${request.data}/page/$page"

        // **التصحيح الحاسم**: إعادة إضافة الترويسات لخداع الموقع وجلب المحتوى
        val headers = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36",
            "Referer" to "$mainUrl/"
        )

        val document = app.get(url, headers = headers).document
        
        val items = document.select("article.item").mapNotNull { it.toSearchResponse() }
        
        // التحقق من وجود صفحة تالية لمنع التحميل اللانهائي
        val hasNext = document.selectFirst("a.next.page-numbers") != null
        
        return newHomePageResponse(request.name, items, hasNext)
    }


    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/?s=$query"
        val headers = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36",
            "Referer" to "$mainUrl/"
        )
        val document = app.get(url, headers = headers).document
        return document.select("article.item").mapNotNull { it.toSearchResponse() }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url, referer = mainUrl).document

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
