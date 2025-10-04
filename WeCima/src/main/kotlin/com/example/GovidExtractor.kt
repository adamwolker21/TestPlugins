package com.example.extractors

import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.*

import org.json.JSONObject

class GovidExtractor : ExtractorApi() {
    override var name = "GoVID"
    override var mainUrl = "goveed1.space"
    override val requiresReferer = true

    private val interceptor = CloudflareKiller()

    override suspend fun getUrl(url: String, referer: String?): MutableList<ExtractorLink>? {
        // ================== V13 Change Start ==================
        // Create a complete set of headers to fully mimic a real browser request.
        // This includes the User-Agent, which was the missing piece.
        val headers = mapOf(
            "User-Agent" to USER_AGENT,
            "Referer" to referer!! // Referer must be the WeCima episode page
        )

        // Make the request using BOTH the Cloudflare interceptor AND the complete headers.
        val response = app.get(url, interceptor = interceptor, headers = headers).document
        // ================== V13 Change End ==================

        val script = response.selectFirst("script:containsData(eval(function(p,a,c,k,e,d))")?.data()
            ?: return null // If script is not found, exit.

        val unpacked = getAndUnpack(script)
        val videoUrl = Regex("""sources:\s*\[\{file:\s*"(.*?)"\}\]""").find(unpacked)?.groupValues?.getOrNull(1)
            ?: return null // If video URL is not found, exit.
        
        // This part correctly adds the headers needed by the video player itself.
        val playerHeaders = mapOf(
            "Referer" to url, // The player's referer is the embed page URL
            "User-Agent" to USER_AGENT
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
    }
}
