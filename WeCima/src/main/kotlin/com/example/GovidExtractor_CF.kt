package com.example.extractors

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.*
import org.json.JSONObject
import android.util.Log

// v31_CF: Build fix applied.
class GovidExtractor_CF : ExtractorApi() {
    override var name = "GoVID_CF"
    override var mainUrl = "goveed1.space"
    override val requiresReferer = true

    private val interceptor = CloudflareKiller()

    override suspend fun getUrl(url: String, referer: String?): MutableList<ExtractorLink>? {
        Log.e(name, "Extractor started for URL: $url")
        try {
            val response = app.get(url, referer = referer, interceptor = interceptor).document
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
            val finalUrl = "$videoUrl#headers=${JSONObject(playerHeaders)}"

            return mutableListOf(
                newExtractorLink(
                    this.name,
                    this.name,
                    finalUrl
                )
            )
        } catch (e: Exception) {
            Log.e(name, "FATAL ERROR: Exception caught: ${e.message}")
            return null
        }
    }
}
