package com.example.extractors

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.JsUnpacker

// This extractor will handle multiple domains that use the same packed JS protection
open class GeneralPackedExtractor : ExtractorApi() {
    override var name = "GeneralPackedExtractor"
    // We will add all relevant domains here
    override var mainUrl = "https://1vid1shar.space" // for Vidshare
    override var directUrl = "https://dingtezuni.com" // for Earnvids
    // We can add more domains here later if needed, e.g. for GoVid
    override val requiresReferer = true

    override suspend fun getUrl(url: String, referer: String?): List<ExtractorLink>? {
        val serverName = when {
            url.contains("1vid1shar") -> "Vidshare"
            url.contains("dingtezuni") -> "Earnvids"
            else -> "Unknown Server"
        }

        val playerPageContent = app.get(url, referer = referer, headers = mapOf("User-Agent" to USER_AGENT)).text
        
        if (playerPageContent.contains("eval(function(p,a,c,k,e,d)")) {
            val unpackedJs = JsUnpacker(playerPageContent).unpack()
            if (unpackedJs != null) {
                val videoLink = Regex("""(https?://[^\s'"]+\.(?:m3u8|mp4)[^\s'"]*)""").find(unpackedJs)?.groupValues?.get(1)
                if (videoLink != null) {
                    return listOf(
                        newExtractorLink(
                            serverName,
                            serverName,
                            videoLink,
                            referer = url
                        )
                    )
                }
            }
        }
        return null
    }
}
