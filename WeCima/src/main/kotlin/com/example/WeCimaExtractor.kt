package com.example.extractors

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import org.json.JSONObject

// The final, definitive extractor for the WeCima server.
// This version intelligently follows redirects to extract the final video URL.
// Build fixed for the user's specific CloudStream environment.
class WeCimaExtractor : ExtractorApi() {
    override var name = "WeCima"
    override var mainUrl = "https://wecima.now"
    override val requiresReferer = true

    private val interceptor = CloudflareKiller()

    // This function will follow the redirect chain to get the final video URL
    private suspend fun getFinalUrl(url: String, referer: String): String {
        try {
            val response = app.get(
                url,
                referer = referer,
                interceptor = interceptor,
                allowRedirects = false // We need to handle redirects manually
            )
            // Check if the response is a redirect (HTTP status 3xx)
            if (response.code in 300..399) {
                // The final URL is in the 'Location' header
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

        // Using map instead of apmap for compatibility
        return sources.mapNotNull { source ->
            source.src?.let { intermediateUrl ->
                // This is a suspend function call, so it needs to be handled correctly
                val finalVideoUrl = getFinalUrl(intermediateUrl, mainUrl)

                // The final video stream requires the main site as a referer to play
                val playerHeaders = mapOf("Referer" to mainUrl)
                val urlWithHeaders = "$finalVideoUrl#headers=${JSONObject(playerHeaders)}"

                newExtractorLink(
                    source = this.name,
                    name = "${this.name} - ${source.label ?: source.size}",
                    url = urlWithHeaders
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
