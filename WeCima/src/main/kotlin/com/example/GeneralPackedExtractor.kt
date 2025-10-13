package com.example.extractors

import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.JsUnpacker
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONObject

open class GeneralPackedExtractor : ExtractorApi() {
    override var name = "GeneralPacked"
    override var mainUrl = "https://wecima.now" // Placeholder
    override val requiresReferer = true

    override suspend fun getUrl(url: String, referer: String?): List<ExtractorLink>? {
        val serverName = when {
            url.contains("1vid1shar") -> "Vidshare"
            url.contains("dingtezuni") -> "Earnvids"
            else -> "General Packed"
        }

        val playerPageContent = app.get(url, referer = referer, headers = mapOf("User-Agent" to USER_AGENT)).text
        
        val videoLink = JsUnpacker(playerPageContent).unpack()?.let { unpackedJs ->
            Regex("""(https?://[^\s'"]+\.(?:m3u8|mp4)[^\s'"]*)""").find(unpackedJs)?.groupValues?.get(1)
        } ?: return null

        val headers = mapOf("Referer" to url, "User-Agent" to USER_AGENT)
        val headersJson = JSONObject(headers).toString()
        val finalUrlWithHeaders = "$videoLink#headers=$headersJson"
        
        return listOf(
                newExtractorLink(
                    source = this.name,
                    name = this.name,
                    url = finalUrlWithHeaders,
                    type = ExtractorLinkType.M3U8
                ) {
                    this.referer = url
                }
            )
    }
}
