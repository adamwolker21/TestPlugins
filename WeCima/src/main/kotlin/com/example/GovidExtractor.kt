package com.example.extractors

import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.*
import org.json.JSONObject
import android.util.Log

class GovidExtractor : ExtractorApi() {
    override var name = "GoVID"
    override var mainUrl = "goveed1.space"
    override val requiresReferer = true
    
    // V22: Add the CloudflareKiller interceptor
    private val interceptor = CloudflareKiller()

    override suspend fun getUrl(url: String, referer: String?): MutableList<ExtractorLink>? {
        try {
            // V22: Use the interceptor in the app.get call
            val response = app.get(url, referer = referer, interceptor = interceptor).document
            
            val script = response.selectFirst("script:containsData(eval(function(p,a,c,k,e,d))")?.data()
            if (script == null) {
                return null
            }
            
            val unpacked = getAndUnpack(script)
            if (unpacked.isBlank()) {
                return null
            }

            val videoUrl = Regex("""sources:\s*\[\{file:\s*"(.*?)"\}\]""").find(unpacked)?.groupValues?.getOrNull(1)
            if (videoUrl == null) {
                return null
            }
            
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
        } catch (e: Exception) {
            e.printStackTrace()
            return null
        }
    }
}
