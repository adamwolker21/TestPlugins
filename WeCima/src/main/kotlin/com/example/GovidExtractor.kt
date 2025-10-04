package com.example.extractors

import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.*
import org.json.JSONObject
import android.util.Log // V18: Import Log

class GovidExtractor : ExtractorApi() {
    override var name = "GoVID"
    override var mainUrl = "goveed1.space"
    override val requiresReferer = true

    override suspend fun getUrl(url: String, referer: String?): MutableList<ExtractorLink>? {
        // V18: Add extensive logging to find the point of failure.
        try {
            Log.e("GovidExtractor", "Extractor started for URL: $url")
            Log.e("GovidExtractor", "Referer: $referer")

            val response = app.get(url, referer = referer).document
            Log.e("GovidExtractor", "Successfully fetched embed page.")

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
            Log.e("GovidExtractor", "Unpacked script successfully.")
            // Log.e("GovidExtractor", "Unpacked content: $unpacked") // Optional: Uncomment to see the full unpacked script

            val videoUrl = Regex("""sources:\s*\[\{file:\s*"(.*?)"\}\]""").find(unpacked)?.groupValues?.getOrNull(1)
            if (videoUrl == null) {
                Log.e("GovidExtractor", "ERROR: Regex failed to find video URL in unpacked script.")
                return null
            }
            Log.e("GovidExtractor", "Video URL found: $videoUrl")
            
            val playerHeaders = mapOf(
                "Referer" to url,
                "User-Agent" to USER_AGENT
            )
            val headersJson = JSONObject(playerHeaders).toString()
            val finalUrl = "$videoUrl#headers=$headersJson"
            Log.e("GovidExtractor", "Extractor finished successfully.")

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
