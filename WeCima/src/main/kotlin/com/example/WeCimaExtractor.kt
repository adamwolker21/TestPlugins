package com.example.extractors

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import org.json.JSONObject

// The final, definitive extractor for the WeCima server.
// This version intelligently follows redirects and, crucially,
// appends the correct Referer header to the final video URL.
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
                allowRedirects = false // We handle redirects manually
            )
            // If the response is a redirect, the final URL is in the 'Location' header.
            if (response.code in 300..399) {
                return response.headers["Location"] ?: url
            }
            return url
        } catch (e: Exception) {
            return url
        }
    }

    override suspend fun getUrl(url: String, referer: String?): List<ExtractorLink>? {
        // Step 1: Get the embed page, bypassing Cloudflare.
        val doc = app.get(url, referer = referer, interceptor = interceptor).document
        val script = doc.selectFirst("script:containsData(const sources)")?.data()
            ?: return null

        val sourcesJson = Regex("""const sources\s*=\s*(\[.*?\]);""").find(script)?.groupValues?.getOrNull(1)
            ?: return null

        val sources = tryParseJson<List<VideoSource>>(sourcesJson) ?: return null

        // Process each source found on the page.
        return sources.mapNotNull { source ->
            source.src?.let { intermediateUrl ->
                // Step 2: Follow the redirect to get the final video URL.
                val finalVideoUrl = getFinalUrl(intermediateUrl, mainUrl)

                // Step 3: This is the crucial fix. We create a headers map
                // with the correct Referer that the final video server expects.
                val playerHeaders = mapOf("Referer" to mainUrl)
                // We then serialize this map to a JSON string and append it
                // to the URL. The player will automatically use these headers.
                val urlWithHeaders = "$finalVideoUrl#headers=${JSONObject(playerHeaders)}"

                newExtractorLink(
                    source = this.name,
                    name = "${this.name} - ${source.label ?: "${source.size}p"}", // Improved naming
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
