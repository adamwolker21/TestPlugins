package com.example.extractors

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.USER_AGENT
import android.net.Uri

open class WeCimaExtractor : ExtractorApi() {
    override var name = "WeCima"
    override var mainUrl = "https://wecima.now"
    override val requiresReferer = true

    override suspend fun getUrl(url: String, referer: String?): List<ExtractorLink>? {
        // This extractor is specifically for wecima.now/run/watch/ links
        val playerPageContent = app.get(url, referer = referer).text
        
        // Find the m3u8 or mp4 link within the player page
        val videoLink = Regex("""(https?://[^\s'"]+\.(?:m3u8|mp4)[^\s'"]*)""").find(playerPageContent)?.groupValues?.get(1)
            ?: return null

        // Build the headers map to bypass protection
        val headers = mapOf(
            "Referer" to mainUrl,
            "User-Agent" to USER_AGENT
        )

        // Convert the map to a JSON string and URL-encode it for the #headers hack
        val headersJson = headers.entries.joinToString(prefix = "{", postfix = "}", separator = ",") {
            """"${it.key}":"${it.value}""""
        }
        val encodedHeaders = Uri.encode(headersJson)
        
        // Append the encoded headers to the URL
        val finalUrl = "$videoLink#headers=$encodedHeaders"
        
        return listOf(
            newExtractorLink(
                source = this.name,
                name = this.name,
                url = finalUrl
            )
        )
    }
}
