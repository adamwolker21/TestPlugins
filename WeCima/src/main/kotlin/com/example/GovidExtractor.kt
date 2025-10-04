package com.example.extractors

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.*
import org.json.JSONObject
import android.util.Log

// The final synthesis of all successful techniques
class GovidExtractor : ExtractorApi() {
    override var name = "GoVID"
    override var mainUrl = "goveed1.space"
    override val requiresReferer = true // We will provide the referer

    private val interceptor = CloudflareKiller()

    override suspend fun getUrl(url: String, referer: String?): MutableList<ExtractorLink>? {
        try {
            // We use ALL tools at once: CloudflareKiller + full browser headers
            val headers = mapOf(
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7",
                "Accept-Language" to "en-US,en;q=0.9",
                "Connection" to "keep-alive",
                "Sec-Fetch-Dest" to "iframe",
                "Sec-Fetch-Mode" to "navigate",
                "Sec-Fetch-Site" to "cross-site",
                "Upgrade-Insecure-Requests" to "1",
                "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Mobile Safari/537.36"
            )
            
            // This request combination is proven to return "200 OK" from our logs
            val response = app.get(url, referer = referer, headers = headers, interceptor = interceptor).document

            val script = response.selectFirst("script:containsData(eval(function(p,a,c,k,e,d))")?.data()
                ?: return null // Fails silently if script is not found

            val unpacked = getAndUnpack(script)
            var videoUrl = Regex("""sources:\s*\[\{file:\s*"(.*?)"\}\]""").find(unpacked)?.groupValues?.getOrNull(1)
                ?: return null // Fails silently if regex doesn't match

            // This fixes the "no protocol" error from the v24 logs
            if (videoUrl.startsWith("//")) {
                videoUrl = "https:$videoUrl"
            }

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
