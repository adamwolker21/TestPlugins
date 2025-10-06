package com.egydead

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

class EgyDeadProvider : MainAPI() {
    override var mainUrl = "https://tv6.egydead.live"
    override var name = "EgyDead"
    override val hasMainPage = true
    override var lang = "ar"
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
    )

    override val mainPage = mainPageOf(
        "/category/%d8%a7%d9%81%d9%84%d8%a7%d9%85-%d8%a7%d8%ac%d9%86%d8%a8%d9%8a-%d8%a7%d9%88%d9%86%d9%84%d8%a7%d9%8a%d9%86/" to "أفلام أجنبي",
        "/category/%d8%a7%d9%81%d9%84%d8%a7%d9%85-%d8%a7%d8%b3%d9%8a%d9%88%d9%8a%d8%a9/" to "أفلام آسيوية",
        "/series-category/%d9%85%d8%b3%d9%84%d8%b3%d9%84%d8%a7%d8%aa-%d8%a7%d8%b3%d9%8a%d9%88%d9%8a%d8%a9/" to "مسلسلات اسيوية",
    )

    private suspend fun getWatchPage(url: String): Document? {
        try {
            val initialResponse = app.get(url)
            val document = initialResponse.document
            if (document.selectFirst("div.watchNow form") != null) {
                val cookies = initialResponse.cookies
                val headers = mapOf(
                    "Content-Type" to "application/x-www-form-urlencoded",
                    "Referer" to url,
                    "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Mobile Safari/537.36",
                    "Origin" to mainUrl,
                    "sec-fetch-dest" to "document",
                    "sec-fetch-mode" to "navigate",
                    "sec-fetch-site" to "same-origin",
                    "sec-fetch-user" to "?1"
                )
                val data = mapOf("View" to "1")
                return app.post(url, headers = headers, data = data, cookies = cookies).document
            }
            return document
        } catch (e: Exception) {
            e.printStackTrace()
            return null
        }
    }

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val url = if (page == 1) {
            "$mainUrl${request.data}"
        } else {
            "$mainUrl${request.data}page/$page/"
        }

        val document = app.get(url).document
        val home = document.select("li.movieItem").mapNotNull {
            it.toSearchResult()
        }
        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val linkTag = this.selectFirst("a") ?: return null
        val href = fixUrl(linkTag.attr("href"))
        val title = this.selectFirst("h1.BottomTitle")?.text() ?: return null
        val posterUrl = fixUrlNull(this.selectFirst("img")?.attr("src"))

        val cleanedTitle = title.replace("مشاهدة", "").trim()
            .replace(Regex("^(فيلم|مسلسل)"), "").trim()

        val isSeries = title.contains("مسلسل") || title.contains("الموسم")

        return if (isSeries) {
            newTvSeriesSearchResponse(cleanedTitle, href, TvType.TvSeries) {
                this.posterUrl = posterUrl
            }
        } else {
            newMovieSearchResponse(cleanedTitle, href, TvType.Movie) {
                this.posterUrl = posterUrl
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/?s=$query"
        val document = app.get(searchUrl).document
        return document.select("li.movieItem").mapNotNull {
            it.toSearchResult()
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document
        val pageTitle = document.selectFirst("div.singleTitle em")?.text()?.trim() ?: return null
        
        val posterUrl = fixUrlNull(document.selectFirst("div.single-thumbnail img")?.attr("src"))
        val plot = document.selectFirst("div.extra-content p")?.text()?.trim() ?: ""
        val year = document.selectFirst("li:has(span:contains(السنه)) a")?.text()?.toIntOrNull()
        val tags = document.select("li:has(span:contains(النوع)) a").map { it.text() }
        val duration = document.selectFirst("li:has(span:contains(مده العرض)) a")?.text()?.filter { it.isDigit() }?.toIntOrNull()

        val categoryText = document.selectFirst("li:has(span:contains(القسم)) a")?.text() ?: ""
        val isSeries = categoryText.contains("مسلسلات") || pageTitle.contains("مسلسل") || pageTitle.contains("الموسم") || document.select("div.EpsList").isNotEmpty()

        if (isSeries) {
            val episodesDoc = getWatchPage(url) ?: document

            val episodes = episodesDoc.select("div.EpsList li a").mapNotNull { epElement ->
                val href = fixUrl(epElement.attr("href"))
                val titleAttr = epElement.attr("title")
                val epNum = titleAttr.substringAfter("الحلقة").trim().split(" ")[0].toIntOrNull()
                if (epNum == null) return@mapNotNull null
                newEpisode(href) {
                    this.name = epElement.text().trim()
                    this.episode = epNum
                }
            }.distinctBy { it.episode }.toMutableList()
            
            val seriesTitle = pageTitle
                .replace(Regex("""(الحلقة \d+|مترجمة|الاخيرة)"""), "")
                .trim()
            
            val currentEpNum = pageTitle.substringAfter("الحلقة").trim().split(" ")[0].toIntOrNull()
            if (currentEpNum != null && episodes.none { it.episode == currentEpNum }) {
                 episodes.add(newEpisode(url) {
                    this.name = pageTitle.substringAfter(seriesTitle).trim().ifBlank { "حلقة $currentEpNum" }
                    this.episode = currentEpNum
                })
            }
            
            return newTvSeriesLoadResponse(seriesTitle, url, TvType.TvSeries, episodes.sortedBy { it.episode }) {
                this.posterUrl = posterUrl
                this.plot = plot
                this.year = year
                this.tags = tags
            }
        } else {
             val movieTitle = pageTitle.replace("مشاهدة فيلم", "").trim()

            return newMovieLoadResponse(movieTitle, url, TvType.Movie, url) {
                this.posterUrl = posterUrl
                this.plot = plot
                this.year = year
                this.tags = tags
                this.duration = duration
            }
        }
    }
    
    // --- START OF INNER EXTRACTORS ---

    private val extractorList = listOf(
        StreamHGExtractor(),
        ForafileExtractor(),
        BigwarpExtractor(),
        EarnVidsExtractor(),
        VidGuardExtractor()
    )
    
    abstract class BaseEvalExtractor : ExtractorApi() {
        override val requiresReferer = true
        override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
            val doc = app.get(url, referer = referer).document
            val packedJs = doc.selectFirst("script:containsData(eval(function(p,a,c,k,e,d))")?.data()
            if (packedJs != null) {
                val unpacked = getAndUnpack(packedJs)
                 Regex("""sources:\s*\[\{file:"([^"]+)""").findAll(unpacked).map { it.groupValues[1] }.forEach { link ->
                    callback.invoke(
                        newExtractorLink(
                            source = this.name,
                            name = this.name,
                            url = httpsify(link),
                            referer = url,
                            quality = Qualities.Unknown.value,
                            isM3u8 = link.contains("m3u8")
                        )
                    )
                }
            }
        }
    }

    inner class StreamHGExtractor : BaseEvalExtractor() {
        override var name = "StreamHG"
        override var mainUrl = "hglink.to"
    }
    
    inner class EarnVidsExtractor : BaseEvalExtractor() {
        override var name = "EarnVids"
        override var mainUrl = "dingtezuni.com"
    }

    inner class ForafileExtractor : ExtractorApi() {
        override var name = "Forafile"
        override var mainUrl = "forafile.com"
        override val requiresReferer = true
        override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
            val document = app.get(url, referer = referer).document
            val videoUrl = document.selectFirst("video.jw-video")?.attr("src")
            if (videoUrl != null) {
                callback.invoke(newExtractorLink(this.name, this.name, videoUrl, url, Qualities.Unknown.value, false))
            }
        }
    }

    inner class BigwarpExtractor : ExtractorApi() {
        override var name = "Bigwarp"
        override var mainUrl = "bigwarp.pro"
        override val requiresReferer = true
        override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
            val document = app.get(url, referer = referer).document
            val jwPlayerScript = document.select("script:containsData(jwplayer(\"vplayer\").setup)").firstOrNull()?.data()
            if(jwPlayerScript != null) {
                 Regex("""sources:\s*\[\{file:"([^"]+)""").findAll(jwPlayerScript).map { it.groupValues[1] }.forEach { link ->
                     callback.invoke(
                        newExtractorLink(
                            source = this.name,
                            name = this.name,
                            url = httpsify(link),
                            referer = url,
                            quality = Qualities.Unknown.value,
                            isM3u8 = link.contains("m3u8")
                        )
                    )
                }
            }
        }
    }
    
    inner class VidGuardExtractor : ExtractorApi() {
        override var name = "VidGuard"
        override var mainUrl = "listeamed.net"
        override val requiresReferer = true
        override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
            val document = app.get(url, referer = referer).document
            val realEmbedUrl = document.selectFirst("iframe")?.attr("src") ?: return
            
            if (realEmbedUrl.contains("dumbalag.com")) {
                StreamHGExtractor().getUrl(realEmbedUrl, url, subtitleCallback, callback)
                return
            }
            
            val matchingExtractor = extractorList.find { realEmbedUrl.contains(it.mainUrl) }
            matchingExtractor?.getUrl(realEmbedUrl, url, subtitleCallback, callback)
        }
    }

    // --- END OF INNER EXTRACTORS ---

    override suspend fun loadLinks(
        data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val watchPageDoc = getWatchPage(data) ?: return false
        
        val allLinks = mutableSetOf<String>()

        watchPageDoc.select("div.mob-servers li").forEach {
            val link = it.attr("data-link")
            if(link.isNotBlank()) allLinks.add(link)
        }

        watchPageDoc.select("ul.donwload-servers-list li a.ser-link").forEach {
            val link = it.attr("href")
            if(link.isNotBlank()) allLinks.add(link)
        }
        
        coroutineScope {
            allLinks.forEach { link ->
                launch {
                    if (link.contains("dumbalag.com")) {
                        StreamHGExtractor().getUrl(link, data, subtitleCallback, callback)
                        return@launch
                    }

                    val matchingExtractor = extractorList.find { link.contains(it.mainUrl) }
                    if (matchingExtractor != null) {
                        matchingExtractor.getUrl(link, data, subtitleCallback, callback)
                    } else {
                        loadExtractor(link, data, subtitleCallback, callback)
                    }
                }
            }
        }
        return true
    }
}
