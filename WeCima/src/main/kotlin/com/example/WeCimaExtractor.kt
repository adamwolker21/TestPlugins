package com.example.extractors

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import org.json.JSONObject

// The final, definitive extractor for the WeCima server.
// This version is built on precise user-provided cURL data and correctly
// follows the redirect chain while preserving the necessary Referer header.
class WeCimaExtractor : ExtractorApi() {
    override var name = "WeCima"
    override var mainUrl = "https://wecima.now"
    override val requiresReferer = true

    private val interceptor = CloudflareKiller()

    // This function will follow the redirect chain to get the final video URL
    private suspend fun getFinalUrl(url: String, referer: String): String {
        try {
            // We ask the app to perform a GET request but not to follow redirects automatically.
            val response = app.get(
                url,
                referer = referer,
                interceptor = interceptor,
                allowRedirects = false // This is crucial.
            )
            // If the server responds with a 3xx status, it's a redirect.
            // The final URL is in the 'Location' header.
            if (response.code in 300..399) {
                return response.headers["Location"] ?: url
            }
            return url
        } catch (e: Exception) {
            // In case of error, return the original URL to avoid crashing.
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

        // Process each source (e.g., 720p, 480p) found on the page.
        return sources.mapNotNull { source ->
            source.src?.let { intermediateUrl ->
                // Step 2: Follow the redirect from the intermediate URL to get the final video URL.
                // We must use the mainUrl as the referer for this step.
                val finalVideoUrl = getFinalUrl(intermediateUrl, mainUrl)

                // Step 3: Prepare the final link for the player, ensuring the correct Referer is passed.
                // The video server itself requires "https://wecima.now/" as the Referer.
                val playerHeaders = mapOf("Referer" to mainUrl)
                val urlWithHeaders = "$finalVideoUrl#headers=${JSONObject(playerHeaders)}"

                // Create the extractor link with all necessary info.
                newExtractorLink(
                    source = this.name,
                    name = "${this.name} - ${source.label ?: source.size}",
                    url = urlWithHeaders
                )
            }
        }
    }

    // A data class to easily parse the JSON from the "sources" variable.
    private data class VideoSource(
        val src: String? = null,
        val type: String? = null,
        val size: Int? = null,
        val label: String? = null,
    )
}
