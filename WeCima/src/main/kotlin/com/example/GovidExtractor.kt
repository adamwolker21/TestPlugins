package com.example.extractors

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.*
import org.json.JSONObject
import android.util.Log

// v29: The final attempt. This version includes the most complete browser fingerprint
// and adds extremely detailed logging to catch the exact point of failure if it persists.
class GovidExtractor : ExtractorApi() {
    override var name = "GoVID"
    override var mainUrl = "goveed1.space"
    override val requiresReferer = true

    private val interceptor = CloudflareKiller()

    override suspend fun getUrl(url: String, referer: String?): MutableList<ExtractorLink>? {
        Log.e("GovidExtractor", "Extractor v29 started for URL: $url")
        try {
            val response = app.get(url,
                interceptor = interceptor,
                referer = referer,
                headers = mapOf(
                    "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7",
                    "Accept-Language" to "en-US,en;q=0.9",
                    "Connection" to "keep-alive", // Added this crucial header
                    "Sec-Fetch-Dest" to "iframe",
                    "Sec-Fetch-Mode" to "navigate",
                    "Sec-Fetch-Site" to "cross-site",
                    "Upgrade-Insecure-Requests" to "1",
                    "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Mobile Safari/537.36"
                )
            ).document
            Log.e("GovidExtractor", "Successfully retrieved page content.")

            val script = response.selectFirst("script:containsData(eval(function(p,a,c,k,e,d))")?.data()
            if (script == null) {
                Log.e("GovidExtractor", "ERROR: Packed script not found on page.")
                return null
            }
            Log.e("GovidExtractor", "Packed script found.")

            val unpacked = getAndUnpack(script)
            if (unpacked.isBlank()) {
                Log.e("GovidExtractor", "ERROR: Unpacking failed or resulted in empty string.")
                return null
            }
            Log.e("GovidExtractor", "Unpacking successful.")

            var videoUrl = Regex("""sources:\s*\[\{file:\s*"(.*?)"\}\]""").find(unpacked)?.groupValues?.getOrNull(1)
            if (videoUrl == null) {
                Log.e("GovidExtractor", "ERROR: Video URL regex did not match.")
                return null
            }
            Log.e("GovidExtractor", "Successfully extracted video URL: $videoUrl")

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
            Log.e("GovidExtractor", "FATAL ERROR: Exception caught: ${e.message}")
            e.printStackTrace()
            return null
        }
    }
}
