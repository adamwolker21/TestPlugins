package com.example.extractors

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.JsUnpacker
import org.json.JSONObject

// This extractor will handle multiple domains that use the same packed JS protection
open class GeneralPackedExtractor : ExtractorApi() {
    override var name = "GeneralPackedExtractor"
    // Primary domain for matching
    override val mainUrl = "https://1vid1shar.space" 
    // Add other domains this extractor should handle
    override val otherUrlNames = setOf("dingtezuni.com") 
    override val requiresReferer = true

    override suspend fun getUrl(url: String, referer: String?): List<ExtractorLink>? {
        val serverName = when {
            url.contains("1vid1shar") -> "Vidshare"
            url.contains("dingtezuni") -> "Earnvids"
            else -> "General Extractor"
        }

        val playerPageContent = app.get(url, referer = referer, headers = mapOf("User-Agent" to USER_AGENT)).text
        
        val videoLink = JsUnpacker(playerPageContent).unpack()?.let { unpackedJs ->
            Regex("""(https?://[^\s'"]+\.(?:m3u8|mp4)[^\s'"]*)""").find(unpackedJs)?.groupValues?.get(1)
        } ?: return null

        // The correct way to pass headers for your environment
        val headers = mapOf("Referer" to url, "User-Agent" to USER_AGENT)
        val headersJson = JSONObject(headers).toString()
        val finalUrlWithHeaders = "$videoLink#headers=$headersJson"
        
        return listOf(
            newExtractorLink(
                serverName,
                serverName,
                finalUrlWithHeaders // Pass the URL with embedded headers
            )
        )
    }
}
