package com.example.extractors

import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.*

import org.json.JSONObject

class GovidExtractor : ExtractorApi() {
    override var name = "GoVID"
    override var mainUrl = "goveed1.space"
    override val requiresReferer = true

    override suspend fun getUrl(url: String, referer: String?): MutableList<ExtractorLink>? {
        // ================== V15 Change Start ==================
        // The key is allowWebView = true.
        // This tells CloudStream: "Try a normal request first. If you get blocked by
        // something like a Cloudflare JavaScript challenge, open the page in a hidden
        // WebView to solve it, and then give me the final HTML."
        // This simulates a real browser visit perfectly.
        val response = app.get(url, referer = referer, allowWebView = true).document
        // ================== V15 Change End ==================

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
