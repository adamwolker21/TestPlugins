package com.example.extractors

import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.*

import org.json.JSONObject

class GovidExtractor : ExtractorApi() {
    override var name = "GoVID"
    override var mainUrl = "goveed1.space"
    override val requiresReferer = false // We will handle the referer manually

    override suspend fun getUrl(url: String, referer: String?): MutableList<ExtractorLink>? {
        // ================== V11 Change Start ==================
        // The server requires the correct Referer header to prevent redirection.
        // We will explicitly add the episode page URL as the Referer.
        val response = app.get(url, headers = mapOf("Referer" to referer!!)).document
        // ================== V11 Change End ==================

        val script = response.selectFirst("script:containsData(eval(function(p,a,c,k,e,d))")?.data()
            ?: return null

        val unpacked = getAndUnpack(script)
        val videoUrl = Regex("""sources:\s*\[\{file:\s*"(.*?)"\}\]""").find(unpacked)?.groupValues?.getOrNull(1)
            ?: return null
        
        // This part is correct for adding headers to the final video URL for playback.
        val headers = mapOf(
            "Referer" to url, // The player needs the embed URL as a referer
            "User-Agent" to USER_AGENT
        )
        val headersJson = JSONObject(headers).toString()
        val finalUrl = "$videoUrl#headers=$headersJson"

        return mutableListOf(
            newExtractorLink(
                this.name,
                this.name,
                finalUrl
            )
        )
    }
}
