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
        // This function follows redirects (3xx) to find the final video URL.
        try {
            val response = app.get(
                url,
                referer = referer,
                interceptor = interceptor,
                allowRedirects = false // We handle the redirect manually to get the location header.
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

        // V7 Update: Switched to the direct ExtractorLink constructor to reliably send the Referer.
        return sources.mapNotNull { source ->
            source.src?.let { intermediateUrl ->
                // This gets the final video URL after any redirects.
                val finalVideoUrl = getFinalUrl(intermediateUrl, mainUrl)
                
                // We no longer append headers to the URL string.
                // Instead, we pass them directly to the ExtractorLink constructor.
                // This is the most reliable way to ensure headers are sent.
                ExtractorLink(
                    source = this.name,
                    name = "${this.name} - ${source.label ?: "${source.size}p"}",
                    url = finalVideoUrl, // The clean video URL
                    referer = this.mainUrl, // The correct referer, passed as a dedicated parameter
                    quality = Qualities.Unknown.value
                )
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
