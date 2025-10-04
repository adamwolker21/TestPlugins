package com.example.extractors

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.*
import org.json.JSONObject

// v26: This version is ready for the final cURL data.
// We are no longer guessing. We will build the request based on the browser's successful blueprint.
class GovidExtractor : ExtractorApi() {
    override var name = "GoVID"
    override var mainUrl = "goveed1.space"
    override val requiresReferer = true

    override suspend fun getUrl(url: String, referer: String?): MutableList<ExtractorLink>? {
        // The logs show that this extractor is being called, but the app.get() request
        // is being blocked silently by an advanced Cloudflare protection that
        // we can only bypass by perfectly mimicking a real browser request.
        // The user will now provide the exact cURL data for the successful request.
        try {
            val response = app.get(url, referer = referer).document

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
