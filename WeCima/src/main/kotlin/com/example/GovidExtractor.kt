package com.example.extractors

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.*
import org.json.JSONObject

// v25: The final, simplified version. A direct clone of the successful Vidbom logic.
class GovidExtractor : ExtractorApi() {
    override var name = "GoVID"
    override var mainUrl = "goveed1.space"
    override val requiresReferer = true // The referer is crucial

    override suspend fun getUrl(url: String, referer: String?): MutableList<ExtractorLink>? {
        try {
            // No complex headers, no CloudflareKiller. Just a clean, simple request.
            val response = app.get(url, referer = referer).document

            val script = response.selectFirst("script:containsData(eval(function(p,a,c,k,e,d))")?.data()
                ?: return null // If script is not found, fail silently.

            val unpacked = getAndUnpack(script)
            if (unpacked.isBlank()) return null

            // Use the same robust regex that works for Vidbom and other sources
            var videoUrl = Regex("""sources:\s*\[\{file:\s*"(.*?)"\}\]""").find(unpacked)?.groupValues?.getOrNull(1)
                ?: return null

            // The small but critical fix for protocol-relative URLs
            if (videoUrl.startsWith("//")) {
                videoUrl = "https:$videoUrl"
            }

            // The final video URL requires its own referer (the embed page url)
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
            // If any error occurs, do not crash the app.
            e.printStackTrace()
            return null
        }
    }
}
