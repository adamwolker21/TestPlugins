package com.example

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import org.jsoup.nodes.Element

// Definir بنية بيانات لاستقبال استجابة AJAX
data class PlayerAjaxResponse(
    val embed_url: String
)

// v10: إعادة بناء كاملة. الكود الآن يتعرف تلقائيًا على بنية الصفحة (article.item أو div.postmovie) ويستخدم المحلل المناسب.
class Asia2Tv : MainAPI() {
    override var name = "Asia2Tv"
    override var mainUrl = "https://asia2tv.com"
    override var lang = "ar"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    // محلل خاص ببنية <article class="item">
    private fun parseArticleItem(element: Element): SearchResponse? {
        val titleElement = element.selectFirst("h3 a") ?: return null
        val href = fixUrlNull(titleElement.attr("href")) ?: return null
        val title = titleElement.text()

        val posterUrl = fixUrlNull(element.selectFirst("div.thumbnail img")?.let {
            it.attr("data-src").ifBlank { it.attr("src") }
        })
        val isMovie = href.contains("/movie/")

        return if (isMovie) {
            newMovieSearchResponse(title, href, TvType.Movie) { this.posterUrl = posterUrl }
        } else {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) { this.posterUrl = posterUrl }
        }
    }

    // محلل خاص ببنية <div class="postmovie">
    private fun parsePostMovie(element: Element): SearchResponse? {
        val titleElement = element.selectFirst("h4 a") ?: return null // لاحظ: h4 هنا
        val href = fixUrlNull(titleElement.attr("href")) ?: return null
        val title = titleElement.text()

        val posterUrl = fixUrlNull(element.selectFirst("div.movief img")?.let {
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
        val url = "$mainUrl${request.data}/page/$page"
        val document = app.get(url).document

        // **المنطق المزدوج الذكي**
        // أولاً، حاول البحث عن البنية الأكثر شيوعًا في صفحات الأقسام
        var items = document.select("article.item").mapNotNull { parseArticleItem(it) }

        // إذا لم يتم العثور على شيء، جرب البنية الثانية (الخاصة بالصفحة الرئيسية القديمة)
        if (items.isEmpty()) {
            items = document.select("div.postmovie").mapNotNull { parsePostMovie(it) }
        }

        val hasNext = document.selectFirst("a.next.page-numbers") != null
        return newHomePageResponse(request.name, items, hasNext)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/?s=$query"
        val document = app.get(url).document
        // صفحات البحث تستخدم بنية article.item
        return document.select("article.item").mapNotNull { parseArticleItem(it) }
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
