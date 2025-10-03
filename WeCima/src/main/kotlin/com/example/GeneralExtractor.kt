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
    override var mainUrl = "https://1vid1shar.space" // Just a placeholder
    override val requiresReferer = true

    override suspend fun getUrl(url: String, referer: String?): List<ExtractorLink>? {
        // Determine the server name for display
        val serverName = when {
            url.contains("1vid1shar") -> "Vidshare"
            url.contains("dingtezuni") -> "Earnvids"
            else -> "General Packed"
        }

        val playerPageContent = app.get(url, referer = referer, headers = mapOf("User-Agent" to USER_AGENT)).text
        
        // These servers use packed JS
        val videoLink = JsUnpacker(playerPageContent).unpack()?.let { unpackedJs ->
            Regex("""(https?://[^\s'"]+\.(?:m3u8|mp4)[^\s'"]*)""").find(unpackedJs)?.groupValues?.get(1)
        } ?: return null

        // Use the "header trick"
        val headers = mapOf("Referer" to url, "User-Agent" to USER_AGENT)
        val headersJson = JSONObject(headers).toString()
        val finalUrlWithHeaders = "$videoLink#headers=$headersJson"
        
        return listOf(
            newExtractorLink(
                serverName,
                serverName,
                finalUrlWithHeaders
            )
        )
    }
}
