package com.example.extractors

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.*
import org.json.JSONObject

// The final extractor, built as a perfect replica of the successful cURL command.
class GovidExtractor : ExtractorApi() {
    override var name = "GoVID"
    override var mainUrl = "goveed1.space"
    override val requiresReferer = true

    override suspend fun getUrl(url: String, referer: String?): MutableList<ExtractorLink>? {
        try {
            // Replicating the exact browser fingerprint from the cURL data.
            // This is the key to bypassing the advanced Cloudflare protection.
            val headers = mapOf(
                "authority" to "xcv2.goveed1.space",
                "accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7",
                "accept-language" to "en-US,en;q=0.9",
                "sec-ch-ua" to """"Chromium";v="137", "Not/A)Brand";v="24"""",
                "sec-ch-ua-mobile" to "?1",
                "sec-ch-ua-platform" to """"Android"""",
                "sec-fetch-dest" to "iframe",
                "sec-fetch-mode" to "navigate",
                "sec-fetch-site" to "cross-site",
                "sec-fetch-user" to "?1",
                "upgrade-insecure-requests" to "1",
                "user-agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Mobile Safari/537.36",
            )

            // A direct, clean request with the perfect headers. No CloudflareKiller needed.
            val response = app.get(url, referer = referer, headers = headers).document

            val script = response.selectFirst("script:containsData(eval(function(p,a,c,k,e,d))")?.data()
                ?: return null

            val unpacked = getAndUnpack(script)
            var videoUrl = Regex("""sources:\s*\[\{file:\s*"(.*?)"\}\]""").find(unpacked)?.groupValues?.getOrNull(1)
                ?: return null

            if (videoUrl.startsWith("//")) {
                videoUrl = "https:$videoUrl"
            }

            // The player itself requires the embed page as a referer.
            val playerHeaders = mapOf("Referer" to url)
            val finalUrl = "$videoUrl#headers=${JSONObject(playerHeaders)}"
            
            return mutableListOf(
                newExtractorLink(
                    this.name,
                    this.name,
                    finalUrl
                )
            )
        } catch (e: Exception) {
            return null // Fails silently on any error
        }
    }
}
