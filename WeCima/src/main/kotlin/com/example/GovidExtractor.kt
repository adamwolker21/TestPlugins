package com.example.extractors

import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.*

import org.json.JSONObject

class GovidExtractor : ExtractorApi() {
    override var name = "GoVID"
    override var mainUrl = "goveed1.space"
    override val requiresReferer = true

    // NO CloudflareKiller interceptor in this version.

    override suspend fun getUrl(url: String, referer: String?): MutableList<ExtractorLink>? {
        // ================== V14 Change Start ==================
        // We are removing the Cloudflare interceptor and relying ONLY on the correct headers.
        // This is a more direct approach that mimics a standard browser.
        val browserHeaders = mapOf(
            "User-Agent" to USER_AGENT,
            "Referer" to referer!!
        )

        // Make the request WITHOUT the interceptor.
        val response = app.get(url, headers = browserHeaders).document
        // ================== V14 Change End ==================

        val script = response.selectFirst("script:containsData(eval(function(p,a,c,k,e,d))")?.data()
            ?: return null

        val unpacked = getAndUnpack(script)
        val videoUrl = Regex("""sources:\s*\[\{file:\s*"(.*?)"\}\]""").find(unpacked)?.groupValues?.getOrNull(1)
            ?: return null
        
        val playerHeaders = mapOf(
            "Referer" to url,
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
