package com.example.extractors

import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.JsUnpacker
import com.lagradost.cloudstream3.utils.Qualities
import org.json.JSONObject

open class VidbomExtractor : ExtractorApi() {
    override var name = "Vidbom"
    override var mainUrl = "https://vdbtm.shop"
    override val requiresReferer = true

    override suspend fun getUrl(url: String, referer: String?): List<ExtractorLink>? {
        val playerPageContent = app.get(url, referer = referer, headers = mapOf("User-Agent" to USER_AGENT)).text
        
        val videoLink = JsUnpacker(playerPageContent).unpack()?.let { unpackedJs ->
            Regex("""(https?://[^\s'"]+\.(?:m3u8|mp4)[^\s'"]*)""").find(unpackedJs)?.groupValues?.get(1)
        } ?: return null

        val headers = mapOf("Referer" to url, "User-Agent" to USER_AGENT)
        val headersJson = JSONObject(headers).toString()
        val finalUrlWithHeaders = "$videoLink#headers=$headersJson"
        
        // V3 Update: Fix build error and explicitly handle as URL type based on your feedback.
        val type = if (videoLink.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.URL

        return listOf(
            ExtractorLink(
                source = this.name,
                name = this.name,
                url = finalUrlWithHeaders,
                referer = referer ?: url,
                quality = Qualities.Unknown.value,
                type = type
            )
        )
    }
}
