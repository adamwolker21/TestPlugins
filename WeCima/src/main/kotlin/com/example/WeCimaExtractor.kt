package com.example.extractors

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson

class WeCimaExtractor : ExtractorApi() {
    override var name = "WeCima"
    override var mainUrl = "https://cima.wecima.show"
    override val requiresReferer = true

    private val interceptor = CloudflareKiller()

    private suspend fun getFinalUrl(url: String, referer: String): String {
        try {
            val response = app.get(
                url,
                referer = referer,
                interceptor = interceptor,
                allowRedirects = false
            )
            if (response.code in 300..399) {
                return response.headers["Location"] ?: url
            }
            return url
        } catch (e: Exception) {
            return url
        }
    }

    override suspend fun getUrl(url: String, referer: String?): List<ExtractorLink>? {
        val doc = app.get(url, referer = referer, interceptor = interceptor).document
        val script = doc.selectFirst("script:containsData(const sources)")?.data()
            ?: return null

        val sourcesJson = Regex("""const sources\s*=\s*(\[.*?\]);""").find(script)?.groupValues?.getOrNull(1)
            ?: return null

        val sources = tryParseJson<List<VideoSource>>(sourcesJson) ?: return null

        // V8 Update: Using the correct, non-deprecated `newExtractorLink` function
        // with a trailing lambda to set the referer and other properties.
        return sources.mapNotNull { source ->
            source.src?.let { intermediateUrl ->
                val finalVideoUrl = getFinalUrl(intermediateUrl, mainUrl)
                
                newExtractorLink(
                    source = this.name,
                    name = "${this.name} - ${source.label ?: "${source.size}p"}",
                    url = finalVideoUrl
                ) {
                    // This is the correct way to pass the referer.
                    this.referer = mainUrl
                    this.quality = Qualities.Unknown.value
                }
            }
        }
    }

    private data class VideoSource(
        val src: String? = null,
        val type: String? = null,
        val size: Int? = null,
        val label: String? = null,
    )
}
