package com.example

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import org.jsoup.nodes.Element

class WeCimaProvider : MainAPI() {
    override var mainUrl = "https://wecima.now/"
    override var name = "WeCima"
    override val hasMainPage = true
    override var lang = "ar"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.AsianDrama,
    )

    private val interceptor = CloudflareKiller()

    override val mainPage = mainPageOf(
        "/category/%d9%85%d8%b3%d9%84%d8%b3%d9%84%d8%a7%d8%aa/1-%d9%85%d8%b3%d9%84%d8%b3%d9%84%d8%a7%d8%aa-%d8%a7%d8%b3%d9%8a%d9%88%d9%8a%d8%a9/" to "مسلسلات آسيوية",
        "/category/%d9%85%d8%b3%d9%84%d8%b3%d9%84%d8%a7%d8%aa/7-series-english-%d9%85%d8%b3%d9%84%d8%b3%d9%84%d8%a7%d8%aa-%d8%a7%d8%ac%d9%86%d8%a8%d9%8a/" to "مسلسلات أجنبي",
        "/category/%d8%a3%d9%81%d9%84%d8%a7%d9%85/10-movies-english-%d8%a7%d9%81%d9%84%d8%a7%d9%85-%d8%a7%d8%ac%d9%86%d8%a8%d9%8a/" to "أفلام أجنبي"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val url = if (page == 1) {
            "$mainUrl${request.data.removePrefix("/")}"
        } else {
            "$mainUrl${request.data.removePrefix("/")}page/$page/"
        }
        val document = app.get(url, interceptor = interceptor).document
        val home = document.select("div.media-card").mapNotNull {
            it.toSearchResult()
        }
        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val linkElement = this.selectFirst("a") ?: return null
        val href = fixUrl(linkElement.attr("href"))
        val title = this.selectFirst("h2[itemprop=name]")?.text() ?: return null

        val style = this.selectFirst("span.media-card__bg")?.attr("style")
        val posterUrl = style?.let {
            Regex("""url\(['"]?(.*?)['"]?\)""").find(it)?.groupValues?.get(1)
        } ?: this.selectFirst("meta[itemprop=thumbnailUrl]")?.attr("content")

        val isSeries = href.contains("/series/") || href.contains("/episode/") || 
                      title.contains("مسلسل") || title.contains("موسم") || title.contains("حلقة")

        return if (isSeries) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = posterUrl
            }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = posterUrl
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl?s=$query"
        val document = app.get(url, interceptor = interceptor).document
        return document.select("div.media-card").mapNotNull {
            it.toSearchResult()
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url, interceptor = interceptor).document

        val title = document.selectFirst("h1[itemprop=name]")?.ownText()?.trim() ?: return null

        val posterStyle = document.selectFirst(".media-entry--hero")?.attr("style")
        val posterUrl = posterStyle?.let {
            Regex("""url\(['"]?(.*?)['"]?\)""").find(it)?.groupValues?.get(1)
        }

        val plot = document.selectFirst("div.story__content")?.text()?.trim()
        val tags = document.select("li:has(span:contains(النوع)) p a").map { it.text() }
        val year = document.selectFirst("h1[itemprop=name] a.unline")?.text()?.toIntOrNull()

        val seasons = document.select("div.seasons__list li a")
        val episodesList = document.select("div.episodes__list a")
        val isTvSeries = seasons.isNotEmpty() || episodesList.isNotEmpty()

        if (isTvSeries) {
            val episodes = mutableListOf<Episode>()
            
            if (seasons.isNotEmpty()) {
                seasons.forEach { seasonLink ->
                    val seasonUrl = fixUrl(seasonLink.attr("href"))
                    val seasonName = seasonLink.text()
                    val seasonNum = Regex("""\d+""").find(seasonName)?.value?.toIntOrNull() ?: 1

                    val seasonDoc = app.get(seasonUrl, interceptor = interceptor).document
                    seasonDoc.select("div.episodes__list a").forEach { epElement ->
                        val epHref = fixUrl(epElement.attr("href"))
                        val epTitle = epElement.selectFirst(".episode__title")?.text() ?: "الحلقة ${episodes.size + 1}"
                        val epNum = Regex("""\d+""").find(epTitle)?.value?.toIntOrNull() ?: (episodes.size + 1)

                        episodes.add(
                            newEpisode(epHref) {
                                name = epTitle
                                season = seasonNum
                                episode = epNum
                            }
                        )
                    }
                }
            } else {
                episodesList.forEach { epElement ->
                    val epHref = fixUrl(epElement.attr("href"))
                    val epTitle = epElement.selectFirst(".episode__title")?.text() ?: "الحلقة ${episodes.size + 1}"
                    val epNum = Regex("""\d+""").find(epTitle)?.value?.toIntOrNull() ?: (episodes.size + 1)
                    
                    episodes.add(
                        newEpisode(epHref) { 
                            name = epTitle
                            season = 1
                            episode = epNum 
                        }
                    )
                }
            }

            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = posterUrl
                this.plot = plot
                this.year = year
                this.tags = tags
            }
        } else {
            return newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = posterUrl
                this.plot = plot
                this.year = year
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
        val document = app.get(data, interceptor = interceptor).document

        document.select("ul.downloads__list li a").forEach { link ->
            try {
                var downloadUrl = link.attr("href")
                if (downloadUrl.isBlank()) return@forEach

                // إذا كان الرابط نسبيًا، أضف العنوان الأساسي
                if (downloadUrl.startsWith("/")) {
                    downloadUrl = mainUrl.removeSuffix("/") + downloadUrl
                }

                val response = app.get(
                    downloadUrl,
                    referer = data,
                    interceptor = interceptor,
                    allowRedirects = false
                )

                // معالجة التوجيهات
                var finalUrl = downloadUrl
                if (response.code in 300..399) {
                    finalUrl = response.headers["Location"] ?: downloadUrl
                }

                val qualityText = link.select("span.resolution").text().trim()
                val quality = when {
                    qualityText.contains("1080") -> Qualities.P1080.value
                    qualityText.contains("720") -> Qualities.P720.value
                    qualityText.contains("480") -> Qualities.P480.value
                    qualityText.contains("360") -> Qualities.P360.value
                    else -> Qualities.Unknown.value
                }

                // استخدام newExtractorLink بدلاً من المُنشئ المُهمل
                callback(
                    newExtractorLink(
                        source = name,
                        name = "$name - $qualityText",
                        url = finalUrl,
                        referer = mainUrl,
                        quality = quality
                    )
                )

            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        
        return true
    }

    private fun fixUrl(url: String): String {
        return if (url.startsWith("http")) url else mainUrl.removeSuffix("/") + "/" + url.removePrefix("/")
    }
}
