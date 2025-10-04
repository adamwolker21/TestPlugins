package com.example.extractors

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.*
import org.json.JSONObject
import android.util.Log

// v30_Base: The simplest approach. No CloudflareKiller, minimal headers.
class GovidExtractor_Base : ExtractorApi() {
    override var name = "GoVID_Base"
    override var mainUrl = "goveed1.space"
    override val requiresReferer = true

    override suspend fun getUrl(url: String, referer: String?): MutableList<ExtractorLink>? {
        Log.e(name, "Extractor started for URL: $url")
        try {
            val response = app.get(url, referer = referer).document
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
