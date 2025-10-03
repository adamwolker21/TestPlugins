package com.example.extractors

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.JsUnpacker

open class WeCimaExtractor : ExtractorApi() {
    override var name = "WeCima"
    override var mainUrl = "https://wecima.now" // This will match wecima.now domains
    override val requiresReferer = true

    override suspend fun getUrl(url: String, referer: String?): List<ExtractorLink>? {
        val playerPageContent = app.get(url, referer = referer, headers = mapOf("User-Agent" to USER_AGENT)).text

        // Check for packed JavaScript first
        if (playerPageContent.contains("eval(function(p,a,c,k,e,d)")) {
            val unpackedJs = JsUnpacker(playerPageContent).unpack()
            if (unpackedJs != null) {
                val videoLink = Regex("""(https?://[^\s'"]+\.(?:m3u8|mp4)[^\s'"]*)""").find(unpackedJs)?.groupValues?.get(1)
                if (videoLink != null) {
                    return listOf(
                        newExtractorLink(
                            this.name,
                            this.name,
                            videoLink,
                            referer = url // The embed url itself is the referer for the video link
                        )
                    )
                }
            }
        }

        // Fallback to simple regex if no packed JS is found
        val videoLink = Regex("""(https?://[^\s'"]+\.(?:m3u8|mp4)[^\s'"]*)""").find(playerPageContent)?.groupValues?.get(1)
            ?: return null

        return listOf(
            newExtractorLink(
                this.name,
                this.name,
                videoLink,
                referer = url
            )
        )
    }
}
