package com.example.extractors

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import org.json.JSONObject

// The final, definitive extractor for the WeCima server.
// This version is built on precise user-provided cURL and HTML data.
// Build fixed for older CloudStream versions.
class WeCimaExtractor : ExtractorApi() {
    override var name = "WeCima"
    override var mainUrl = "https://wecima.now"
    override val requiresReferer = true

    // We need the Cloudflare interceptor to bypass the first layer of protection.
    private val interceptor = CloudflareKiller()

    override suspend fun getUrl(url: String, referer: String?): List<ExtractorLink>? {
        // Step 1: Bypass Cloudflare and get the real embed page HTML.
        // We pass the episode page URL as the referer to pass the JavaScript check.
        val doc = app.get(url, referer = referer, interceptor = interceptor).document

        // Step 2: Find the script tag containing the video sources.
        val script = doc.selectFirst("script:containsData(const sources)")?.data()
            ?: return null // If script is not found, fail silently.

        // Step 3: Extract the JSON array of sources using Regex.
        val sourcesJson = Regex("""const sources\s*=\s*(\[.*?\]);""").find(script)?.groupValues?.getOrNull(1)
            ?: return null

        // Step 4: Parse the JSON string into a list of VideoSource objects.
        val sources = tryParseJson<List<VideoSource>>(sourcesJson) ?: return null

        // Step 5: Map each source to a valid ExtractorLink.
        return sources.mapNotNull { source ->
            source.src?.let { videoUrl ->
                // The final video stream also requires a referer to play.
                // We pass it in the URL hash for the player to use.
                val playerHeaders = mapOf("Referer" to mainUrl)
                val finalUrl = "$videoUrl#headers=${JSONObject(playerHeaders)}"

                ExtractorLink(
                    source = this.name,
                    name = "${this.name} - ${source.label}", // e.g., "WeCima - 720p WEBRip"
                    url = finalUrl,
                    referer = referer ?: mainUrl,
                    quality = getQualityFromName(source.size.toString()),
                )
            }
        }
    }

    // A data class to match the structure of the JSON object in the script.
    private data class VideoSource(
        val src: String? = null,
        val type: String? = null,
        val size: Int? = null,
        val label: String? = null,
    )
}
