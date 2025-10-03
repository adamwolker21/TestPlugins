package com.example.extractors

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.JsUnpacker

open class VidbomExtractor : ExtractorApi() {
    override var name = "Vidbom"
    override var mainUrl = "https://vdbtm.shop" // This will match vdbtm.shop domains
    override val requiresReferer = true

    override suspend fun getUrl(url: String, referer: String?): List<ExtractorLink>? {
        val playerPageContent = app.get(url, referer = referer, headers = mapOf("User-Agent" to USER_AGENT)).text
        
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
                            referer = url
                        )
                    )
                }
            }
        }
        return null
    }
}
