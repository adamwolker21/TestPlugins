package com.example.extractors

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import org.json.JSONObject

// The final, definitive extractor for the WeCima server.
// This version provides the player with a complete and correct browser fingerprint.
class WeCimaExtractor : ExtractorApi() {
    override var name = "WeCima"
    override var mainUrl = "https://wecima.now"
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

        return sources.mapNotNull { source ->
            source.src?.let { intermediateUrl ->
                val finalVideoUrl = getFinalUrl(intermediateUrl, mainUrl)

                // THE FINAL FIX: Create a complete fingerprint for the player.
                // This includes the correct Referer AND a matching User-Agent.
                val playerHeaders = mapOf(
                    "Referer" to mainUrl,
                    "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Mobile Safari/537.36"
                )
                val urlWithHeaders = "$finalVideoUrl#headers=${JSONObject(playerHeaders)}"

                newExtractorLink(
                    source = this.name,
                    name = "${this.name} - ${source.label ?: "${source.size}p"}",
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
