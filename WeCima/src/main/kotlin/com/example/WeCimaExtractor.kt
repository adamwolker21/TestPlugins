package com.example.extractors

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.USER_AGENT

open class WeCimaExtractor : ExtractorApi() {
    override var name = "WeCima"
    override var mainUrl = "https://wecima.now/"
    override val requiresReferer = true

    override suspend fun getUrl(url: String, referer: String?): List<ExtractorLink>? {
        val playerPageContent = app.get(url, referer = referer, headers = mapOf("User-Agent" to USER_AGENT)).text

        // Regex to find m3u8 or mp4 links in the page source
        val videoLinkRegex = Regex("""(https?://[^\s'"]+\.(?:m3u8|mp4)[^\s'"]*)""")
        val videoLink = videoLinkRegex.find(playerPageContent)?.groupValues?.get(1)
            ?: return null

        val headers = mapOf(
            "Referer" to mainUrl,
            "User-Agent" to USER_AGENT
        )
        
        return listOf(
            ExtractorLink(
                source = this.name,
                name = this.name,
                url = videoLink,
                referer = mainUrl, // The final video request needs the main site as referer
                quality = getQualityFromName(""), // Let CloudStream determine quality
                isM3u8 = videoLink.contains(".m3u8"),
                headers = headers
            )
        )
    }
}
