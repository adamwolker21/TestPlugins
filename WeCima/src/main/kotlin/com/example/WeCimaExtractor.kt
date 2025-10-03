package com.example.extractors

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.USER_AGENT

open class WeCimaExtractor : ExtractorApi() {
    override var name = "WeCima"
    override var mainUrl = "https://wecima.now" // Should match the provider's mainUrl
    override val requiresReferer = true

    override suspend fun getUrl(url: String, referer: String?): List<ExtractorLink>? {
        // Step 1: Visit the intermediate page to get the final MP4 link
        val doc = app.get(url, referer = referer).document
        val directLink = Regex("""(https?://[^\s'"]+\.mp4[^\s'"]*)""").find(doc.html())?.groupValues?.get(1)
            ?: return null

        // Step 2: Return the final link with the necessary headers for the player to use
        return listOf(
            ExtractorLink(
                source = this.name,
                name = this.name,
                url = directLink,
                referer = mainUrl, // The final video server needs the main site as referer
                quality = getQualityFromName(url),
                isM3u8 = false,
                headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Referer" to mainUrl
                )
            )
        )
    }
}
