package com.example.extractors

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import org.json.JSONObject

// The final, definitive extractor for the WeCima server.
// This version is built on precise user-provided cURL and HTML data.
// Build fixed for the user's specific CloudStream environment.
class WeCimaExtractor : ExtractorApi() {
    override var name = "WeCima"
    override var mainUrl = "https://wecima.now"
    override val requiresReferer = true

    private val interceptor = CloudflareKiller()

    override suspend fun getUrl(url: String, referer: String?): List<ExtractorLink>? {
        val doc = app.get(url, referer = referer, interceptor = interceptor).document
        val script = doc.selectFirst("script:containsData(const sources)")?.data()
            ?: return null

        val sourcesJson = Regex("""const sources\s*=\s*(\[.*?\]);""").find(script)?.groupValues?.getOrNull(1)
            ?: return null

        val sources = tryParseJson<List<VideoSource>>(sourcesJson) ?: return null

        return sources.mapNotNull { source ->
            source.src?.let { videoUrl ->
                // Headers are passed in the URL hash for the player to use.
                // This is the correct way to handle referers for the final video link.
                val playerHeaders = mapOf("Referer" to mainUrl)
                val finalUrl = "$videoUrl#headers=${JSONObject(playerHeaders)}"

                // IMPORTANT: Using the specific newExtractorLink signature that is known to work.
                // Quality information is passed in the name.
                newExtractorLink(
                    source = this.name,
                    name = "${this.name} - ${source.label ?: source.size}",
                    url = finalUrl
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
