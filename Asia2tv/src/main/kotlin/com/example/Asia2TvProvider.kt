// v23: تجربة استخدام وسوم HTML لتنسيق الوصف.
package com.wolker.asia2tv

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import org.jsoup.nodes.Element

// Definir بنية بيانات لاستقبال استجابة AJAX
data class PlayerAjaxResponse(
    val embed_url: String
)

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
        val url = "$mainUrl${request.data}?page=$page"
        val document = app.get(url).document

        val items = document.select("div.postmovie").mapNotNull {
            it.toSearchResponse()
        }

        val hasNext = document.selectFirst("a.next.page-numbers, a[rel=next]") != null
        return newHomePageResponse(request.name, items, hasNext)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/search?s=$query"
        val document = app.get(url).document
        
        return document.select("div.postmovie").mapNotNull { it.toSearchResponse() }
    }
    
    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        
        val detailsContainer = document.selectFirst("div.info-detail-single")
        
        val title = detailsContainer?.selectFirst("h1")?.text()?.trim() ?: "No Title"
        var plot = detailsContainer?.selectFirst("p")?.text()?.trim()

        val posterUrl = fixUrlNull(document.selectFirst("meta[property=og:image]")?.attr("content"))

        val year = detailsContainer?.select("ul.mb-2 li")
            ?.find { it.text().contains("سنة العرض") }
            ?.selectFirst("a")?.text()?.toIntOrNull()

        val rating = detailsContainer?.selectFirst("div.post_review_avg")?.text()?.trim()
            ?.toFloatOrNull()?.times(100)?.toInt()

        val tags = detailsContainer?.select("div.post_tags a")?.map { it.text() }

        val statusText = document.selectFirst("span.serie-isstatus")?.text()?.trim()
        
        var country: String? = null
        var broadcastDate: String? = null
        var totalEpisodes: String? = null

        detailsContainer?.select("ul.mb-2 li")?.forEach { li ->
            val text = li.text()
            if (text.contains("البلد المنتج")) {
                country = li.selectFirst("a")?.text()?.trim()
            } else if (text.contains("موعد البث")) {
                broadcastDate = li.ownText().trim().removePrefix(": ")
            } else if (text.contains("عدد الحلقات")) {
                totalEpisodes = li.ownText().trim().removePrefix(": ")
            }
        }

        // --- تجربة تنسيق HTML ---
        // 1. تجميع المعلومات الإضافية في قائمة HTML
        val extraInfoList = listOfNotNull(
            statusText?.let { "<b>الحالة:</b> $it" },
            country?.let { "<b>البلد:</b> $it" },
            totalEpisodes?.let { "<b>عدد الحلقات:</b> $it" },
            broadcastDate?.let { "<b>موعد البث:</b> $it" }
        )

        // 2. تحويل القائمة إلى نص مفصول بوسم <br>
        val extraInfo = extraInfoList.joinToString("<br>")

        // 3. إضافة عنوان "القصة:" للوصف الرئيسي
        val mainPlot = if (plot.isNullOrBlank()) {
            null
        } else {
            "<b>القصة:</b><br>$plot"
        }

        // 4. دمج كل شيء معًا للحصول على التنسيق النهائي
        plot = listOfNotNull(extraInfo, mainPlot).joinToString("<br><br>").trim()

        val episodes = document.select("div.box-loop-episode a").mapNotNull { a ->
            val href = a.attr("href") ?: return@mapNotNull null
            val epNumText = a.selectFirst(".titlepisode")?.text()?.replace(Regex("[^0-9]"), "")
            val epNum = epNumText?.toIntOrNull()

            newEpisode(href) {
                name = a.selectFirst(".titlepisode")?.text()?.trim()
                episode = epNum
            }
        }.reversed()

        return if (episodes.isNotEmpty()) {
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = posterUrl
                this.year = year
                this.plot = plot
                this.tags = tags
                this.rating = rating
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = posterUrl
                this.year = year
                this.plot = plot
                this.tags = tags
                this.rating = rating
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
