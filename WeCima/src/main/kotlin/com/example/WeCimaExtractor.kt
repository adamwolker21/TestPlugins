package com.example.extractors

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson

// The Master Key extractor for the WeCima server, built from cURL data.
class WeCimaExtractor : ExtractorApi() {
    override var name = "WeCima"
    override var mainUrl = "https://wecima.now"
    override val requiresReferer = true

    private val interceptor = CloudflareKiller()

    // Function to safely follow redirects and get the final URL
    private suspend fun getFinalUrl(url: String, referer: String): String? {
        try {
            val response = app.get(
                url,
                referer = referer,
                interceptor = interceptor,
                allowRedirects = false // We handle the redirect manually
            )
            // If the response is a redirect (3xx), return the new location
            if (response.code in 300..399) {
                return response.headers["Location"]
            }
            // If it's not a redirect, something is wrong, but we return the original url as a fallback
            return url
        } catch (e: Exception) {
            return null
        }
    }

    override suspend fun getUrl(url: String, referer: String?): List<ExtractorLink>? {
        // Step 1: Get the initial embed page, bypassing Cloudflare
        val doc = app.get(url, referer = referer, interceptor = interceptor).document
        val script = doc.selectFirst("script:containsData(const sources)")?.data()
            ?: return null

        // Step 2: Extract the JSON array of video sources
        val sourcesJson = Regex("""const sources\s*=\s*(\[.*?\]);""").find(script)?.groupValues?.getOrNull(1)
            ?: return null

        // Step 3: Parse the JSON into a list of data objects
        val sources = tryParseJson<List<VideoSource>>(sourcesJson) ?: return null

        // Step 4: Process each source to get the final playable link
        return sources.apmap { source ->
            source.src?.let { intermediateUrl ->
                // Step 5: Follow the intermediate URL to get the final video URL
                val finalVideoUrl = getFinalUrl(intermediateUrl, mainUrl) ?: return@let null

                // Step 6: THE FINAL FIX - Add the correct Referer to the final video link
                // This solves the 403 Forbidden error.
                newExtractorLink(
                    source = this.name,
                    name = "${this.name} - ${source.label ?: "${source.size}p"}",
                    url = finalVideoUrl,
                    referer = mainUrl, // The crucial part
                    quality = source.size ?: Qualities.Unknown.value,
                    isM3u8 = finalVideoUrl.contains(".m3u8")
                )
            }
        }
    }

    // Data class to hold the parsed video source information
    private data class VideoSource(
        val src: String? = null,
        val type: String? = null,
        val size: Int? = null,
        val label: String? = null,
    )
}
