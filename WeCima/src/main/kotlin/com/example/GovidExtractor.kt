package com.example.extractors

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.*
import org.json.JSONObject

// v28: The final, logical conclusion. No "warm-up" call, just one direct, powerful request.
class GovidExtractor : ExtractorApi() {
    override var name = "GoVID"
    override var mainUrl = "goveed1.space"
    override val requiresReferer = true

    private val interceptor = CloudflareKiller()

    override suspend fun getUrl(url: String, referer: String?): MutableList<ExtractorLink>? {
        try {
            // No more "warm-up". We make one direct request to the embed URL,
            // providing the interceptor and all necessary headers at once.
            // This is the cleanest way to solve the challenge.
            val response = app.get(url,
                interceptor = interceptor,
                referer = referer,
                headers = mapOf(
                    "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7",
                    "Sec-Fetch-Dest" to "iframe",
                    "Sec-Fetch-Mode" to "navigate",
                    "Sec-Fetch-Site" to "cross-site",
                    "Upgrade-Insecure-Requests" to "1",
                    "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Mobile Safari/537.36"
                )
            ).document

            val script = response.selectFirst("script:containsData(eval(function(p,a,c,k,e,d))")?.data()
                ?: return null

            val unpacked = getAndUnpack(script)
            if (unpacked.isBlank()) return null

            var videoUrl = Regex("""sources:\s*\[\{file:\s*"(.*?)"\}\]""").find(unpacked)?.groupValues?.getOrNull(1)
                ?: return null

            if (videoUrl.startsWith("//")) {
                videoUrl = "https:$videoUrl"
            }

            val playerHeaders = mapOf("Referer" to url)
            val headersJson = JSONObject(playerHeaders).toString()
            val finalUrl = "$videoUrl#headers=$headersJson"

            return mutableListOf(
                newExtractorLink(
                    this.name,
                    this.name,
                    finalUrl
                )
            )
        } catch (e: Exception) {
            e.printStackTrace()
            return null
        }
    }
}
