package com.example.extractors

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.*
import org.json.JSONObject

class GovidExtractor : ExtractorApi() {
    override var name = "GoVID"
    override var mainUrl = "goveed1.space"
    override val requiresReferer = true

    // Keep the CloudflareKiller, it's essential
    private val interceptor = CloudflareKiller()

    override suspend fun getUrl(url: String, referer: String?): MutableList<ExtractorLink>? {
        try {
            // V23: Perfectly mimic the browser's request headers to bypass advanced Cloudflare
            val browserHeaders = mapOf(
                "Referer" to referer!!,
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7",
                "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Mobile Safari/537.36",
                "Sec-CH-UA" to "\"Chromium\";v=\"137\", \"Not/A)Brand\";v=\"24\"",
                "Sec-CH-UA-Mobile" to "?1",
                "Sec-CH-UA-Platform" to "\"Android\"",
                "Upgrade-Insecure-Requests" to "1"
            )

            // Use both the interceptor AND the custom headers for maximum effectiveness
            val response = app.get(url, headers = browserHeaders, interceptor = interceptor).document

            val script = response.selectFirst("script:containsData(eval(function(p,a,c,k,e,d))")?.data()
                ?: return null // Fail silently if script not found

            val unpacked = getAndUnpack(script)
            if (unpacked.isBlank()) return null

            val videoUrl = Regex("""sources:\s*\[\{file:\s*"(.*?)"\}\]""").find(unpacked)?.groupValues?.getOrNull(1)
                ?: return null

            // The final video URL requires its own referer (the embed page url)
            val playerHeaders = mapOf(
                "Referer" to url
            )
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
            // If an error occurs, print it to the logs but don't crash the app
            e.printStackTrace()
            return null
        }
    }
}
