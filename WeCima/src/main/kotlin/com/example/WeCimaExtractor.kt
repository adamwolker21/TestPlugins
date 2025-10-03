package com.example.extractors

import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.JsUnpacker
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONObject

open class WeCimaExtractor : ExtractorApi() {
    override var name = "WeCima"
    override var mainUrl = "https://wecima.now"
    override val requiresReferer = true

    override suspend fun getUrl(url: String, referer: String?): List<ExtractorLink>? {
        // Get the player page content with correct referer and user-agent
        val playerPageContent = app.get(url, referer = referer, headers = mapOf("User-Agent" to USER_AGENT)).text
        
        // Try to find the video link (m3u8 or mp4)
        // First, try unpacking JS if it exists
        val videoLink = JsUnpacker(playerPageContent).unpack()?.let { unpackedJs ->
            Regex("""(https?://[^\s'"]+\.(?:m3u8|mp4)[^\s'"]*)""").find(unpackedJs)?.groupValues?.get(1)
        } // If not found, search in the plain HTML
        ?: Regex("""(https?://[^\s'"]+\.(?:m3u8|mp4)[^\s'"]*)""").find(playerPageContent)?.groupValues?.get(1)
        ?: return null // Return null if no link is found

        // This is the "header trick" that works for your build environment
        val headers = mapOf("Referer" to url, "User-Agent" to USER_AGENT)
        val headersJson = JSONObject(headers).toString()
        // Append the JSON headers to the video link
        val finalUrlWithHeaders = "$videoLink#headers=$headersJson"
        
        // Return the final link
        return listOf(
            newExtractorLink(
                this.name, // Source name
                this.name, // Display name
                finalUrlWithHeaders, // The URL with embedded headers
            )
        )
    }
}
