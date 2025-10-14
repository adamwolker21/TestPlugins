package com.example.extractors

import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.JsUnpacker
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
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
        
        // V2 Update: Explicitly identify HLS (m3u8) streams for the player.
        val isM3u8 = videoLink.contains(".m3u8")

        return listOf(
            newExtractorLink(
                source = serverName,
                name = serverName,
                url = finalUrlWithHeaders,
                isM3u8 = isM3u8,
                quality = Qualities.Unknown.value
            )
        )
    }
}
