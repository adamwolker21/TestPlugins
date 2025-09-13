package com.example

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import org.jsoup.nodes.Element

// Definir بنية بيانات لاستقبال استجابة AJAX
data class PlayerAjaxResponse(
    val embed_url: String
)

// v12: استخدام بنية الروابط الصحيحة للبحث والتنقل بين الصفحات بناءً على التحقيق.
class Asia2Tv : MainAPI() {
    override var name = "Asia2Tv"
    override var mainUrl = "https://asia2tv.com"
    override var lang = "ar"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    private fun Element.toSearchResponse(): SearchResponse? {
        val titleElement = this.selectFirst("h4 a") ?: return null
        val href = fixUrlNull(titleElement.attr("href")) ?: return null
        val title = titleElement.text()

        val posterUrl = fixUrlNull(this.selectFirst("div.postmovie-photo img")?.let {
            it.attr("data-src").ifBlank { it.attr("src") }
        })

        val isMovie = href.contains("/movie/")

        return if (isMovie) {
            newMovieSearchResponse(title, href, TvType.Movie) { this.posterUrl = posterUrl }
        } else {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) { this.posterUrl = posterUrl }
        }
    }

    override val mainPage = mainPageOf(
        "/newepisode" to "الحلقات الجديدة",
        "/status/live" to "يبث حاليا",
        "/series" to "المسلسلات",
        "/status/complete" to "أعمال مكتملة",
        "/movies" to "الأفلام"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        // **الإصلاح الحاسم الأول: استخدام بنية التنقل الصحيحة ?page=**
        val url = "$mainUrl${request.data}?page=$page"
        val document = app.get(url).document

        val items = document.select("div.postmovie").mapNotNull {
            it.toSearchResponse()
        }
        
        // قد نحتاج لتحديث محدد الصفحة التالية أيضًا
        val hasNext = document.selectFirst("a.next.page-numbers, a[rel=next]") != null
        return newHomePageResponse(request.name, items, hasNext)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        // **الإصلاح الحاسم الثاني: استخدام رابط ومسار البحث الصحيح**
        val url = "$mainUrl/search?s=$query"
        val document = app.get(url).document
        
        return document.select("div.postmovie").mapNotNull { it.toSearchResponse() }
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
                
                val iframeSrc = app.get(embedUrl, referer = data).document.selectFirst("iframe")?.attr("src") ?: embedUrl
                
                loadExtractor(iframeSrc, data, subtitleCallback, callback)

            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        return true
    }
}
