package com.example.extractors

import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.*
import org.json.JSONObject

// This extractor will handle servers that use packed JavaScript
open class GeneralPackedExtractor : ExtractorApi() {
    override var name = "GeneralPacked"
    override var mainUrl = "https://wecima.now" // Placeholder
    override val requiresReferer = true

    override suspend fun getUrl(url: String, referer: String?): List<ExtractorLink>? {
        // Determine the server name for display
        val serverName = when {
            url.contains("1vid1shar") -> "Vidshare"
            url.contains("dingtezuni") -> "Earnvids"
            url.contains("zfghrew10") -> "GoVID"
            else -> "General Packed"
        }

        val playerPageContent = app.get(url, referer = referer, headers = mapOf("User-Agent" to USER_AGENT)).text
        
        // Unpack the JavaScript to find the hidden master playlist link
        val masterPlaylistUrl = JsUnpacker(playerPageContent).unpack()?.let { unpackedJs ->
            Regex("""(https?://[^\s'"]+\.m3u8[^\s'"]*)""").find(unpackedJs)?.groupValues?.get(1)
        } ?: return null // If not an m3u8 link, exit

        val headers = mapOf("Referer" to url, "User-Agent" to USER_AGENT)
        
        // Fetch the master playlist content to get the actual video streams
        val masterPlaylistContent = app.get(masterPlaylistUrl, headers = headers).text

        // Use a helper to parse M3U8 content and extract quality links
        // This gives us a list of simple links (e.g., 720p, 480p)
        return M3u8Helper.generateM3u8(serverName, masterPlaylistContent, masterPlaylistUrl).map { link ->
            // Re-apply the headers to each individual quality link using the #headers trick
            val headersJson = JSONObject(headers).toString()
            val finalUrlWithHeaders = "${link.url}#headers=$headersJson"
            
            newExtractorLink(
                serverName,
                "${link.name} - $serverName", // e.g., "720p - Vidshare"
                finalUrlWithHeaders,
            ).apply {
                quality = link.quality
            }
        }
    }
}
