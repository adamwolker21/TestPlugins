package com.example.extractors

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.JsUnpacker
import org.json.JSONObject

open class WeCimaExtractor : ExtractorApi() {
    override var name = "WeCima"
    override var mainUrl = "https://wecima.now"
    override val requiresReferer = true

    override suspend fun getUrl(url: String, referer: String?): List<ExtractorLink>? {
        val playerPageContent = app.get(url, referer = referer, headers = mapOf("User-Agent" to USER_AGENT)).text
        
        val videoLink = JsUnpacker(playerPageContent).unpack()?.let { unpackedJs ->
            Regex("""(https?://[^\s'"]+\.(?:m3u8|mp4)[^\s'"]*)""").find(unpackedJs)?.groupValues?.get(1)
        } ?: Regex("""(https?://[^\s'"]+\.(?:m3u8|mp4)[^\s'"]*)""").find(playerPageContent)?.groupValues?.get(1)
        ?: return null

        // The correct way to pass headers for your environment
        val headers = mapOf("Referer" to url, "User-Agent" to USER_AGENT)
        val headersJson = JSONObject(headers).toString()
        val finalUrlWithHeaders = "$videoLink#headers=$headersJson"
        
        return listOf(
            newExtractorLink(
                this.name,
                this.name,
                finalUrlWithHeaders // Pass the URL with embedded headers
            )
        )
    }
}
