// v29: Added "Coming Soon" status and moved extra info back to the plot.
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

    // دالة مساعدة لتحديد حالة المسلسل بناءً على الـ class
    private fun getStatus(element: Element?): ShowStatus {
        return when {
            element?.hasClass("live") == true -> ShowStatus.Ongoing
            element?.hasClass("complete") == true -> ShowStatus.Completed
            element?.hasClass("coming-soon") == true -> ShowStatus.ComingSoon // -- تم التعديل هنا --
            else -> ShowStatus.Completed 
        }
    }

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
        "/status/coming-soon" to "الأعمال القادمة",
        "/status/complete" to "أعمال مكتملة",
        "/series" to "المسلسلات",
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
        
        val status = getStatus(document.selectFirst("span.serie-isstatus"))
        
        // -- تم التعديل هنا --
        var country: String? = null
        var totalEpisodes: String? = null

        detailsContainer?.select("ul.mb-2 li")?.forEach { li ->
            val text = li.text()
            if (text.contains("البلد المنتج")) {
                country = li.selectFirst("a")?.text()?.trim()
            } else if (text.contains("عدد الحلقات")) {
                totalEpisodes = li.ownText().trim().removePrefix(": ")
            }
        }

        // بناء سطر المعلومات الإضافية
        val extraInfoList = listOfNotNull(
            country?.let { "<b>البلد:</b> $it" },
            totalEpisodes?.let { "<b>عدد الحلقات:</b> $it" }
        )
        val extraInfo = extraInfoList.joinToString("<br>")

        // دمج القصة مع المعلومات الإضافية
        plot = if (extraInfo.isNotBlank()) {
            listOfNotNull(plot, extraInfo).joinToString("<br><br>")
        } else {
            plot 
        }

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
                this.showStatus = status
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
