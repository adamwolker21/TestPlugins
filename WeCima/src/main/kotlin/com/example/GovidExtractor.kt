package com.example.extractors

import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.*

import org.json.JSONObject

class GovidExtractor : ExtractorApi() {
    override var name = "GoVID"
    override var mainUrl = "goveed1.space"
    override val requiresReferer = true // The provider will pass the referer

    // ================== V12 Change Start ==================
    // Add the Cloudflare interceptor to bypass Cloudflare protection on the embed page.
    private val interceptor = CloudflareKiller()
    // ================== V12 Change End ==================

    override suspend fun getUrl(url: String, referer: String?): MutableList<ExtractorLink>? {
        // Use both the interceptor for Cloudflare and the referer for server-side protection.
        val response = app.get(url, interceptor = interceptor, referer = referer).document

        val script = response.selectFirst("script:containsData(eval(function(p,a,c,k,e,d))")?.data()
            ?: return null

        val unpacked = getAndUnpack(script)
        val videoUrl = Regex("""sources:\s*\[\{file:\s*"(.*?)"\}\]""").find(unpacked)?.groupValues?.getOrNull(1)
            ?: return null
        
        val headers = mapOf(
            "Referer" to url,
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
