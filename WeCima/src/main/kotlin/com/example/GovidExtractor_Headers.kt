package com.example.extractors

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.*
import org.json.JSONObject
import android.util.Log

// v30_Headers: The headers-focused approach.
class GovidExtractor_Headers : ExtractorApi() {
    override var name = "GoVID_Headers"
    override var mainUrl = "goveed1.space"
    override val requiresReferer = true

    override suspend fun getUrl(url: String, referer: String?): MutableList<ExtractorLink>? {
        Log.e(name, "Extractor started for URL: $url")
        try {
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
            val response = app.get(url, referer = referer, headers = headers).document
            Log.e(name, "Successfully retrieved page content.")

            val script = response.selectFirst("script:containsData(eval(function(p,a,c,k,e,d))")?.data()
                ?: run { Log.e(name, "ERROR: Packed script not found on page."); return null }

            val unpacked = getAndUnpack(script)
            var videoUrl = Regex("""sources:\s*\[\{file:\s*"(.*?)"\}\]""").find(unpacked)?.groupValues?.getOrNull(1)
                ?: run { Log.e(name, "ERROR: Video URL regex did not match."); return null }

            if (videoUrl.startsWith("//")) {
                videoUrl = "https:$videoUrl"
            }

            Log.e(name, "SUCCESS! Extracted video URL: $videoUrl")
            val playerHeaders = mapOf("Referer" to url)
            return mutableListOf(
                newExtractorLink(
                    this.name,
                    this.name,
                    "$videoUrl#headers=${JSONObject(playerHeaders)}",
                    referer = referer ?: "",
                    quality = Qualities.Unknown.value
                )
            )
        } catch (e: Exception) {
            Log.e(name, "FATAL ERROR: Exception caught: ${e.message}")
            return null
        }
    }
}
