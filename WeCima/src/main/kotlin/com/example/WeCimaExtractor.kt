package com.example.extractors

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONObject

open class WeCimaExtractor : ExtractorApi() {
    override var name = "WeCima"
    override var mainUrl = "https://wecima.now/" // Must match the server URL to be triggered
    override val requiresReferer = true

    override suspend fun getUrl(url: String, referer: String?): List<ExtractorLink>? {
        // Step 1: Get the player page
        val playerPageContent = app.get(
            url, 
            referer = referer, 
            headers = mapOf("User-Agent" to USER_AGENT)
        ).text

        // Step 2: Find the final video link (.m3u8 or .mp4) inside the player page
        val videoLink = Regex("""(https?://[^\s'"]+\.(?:m3u8|mp4)[^\s'"]*)""").find(playerPageContent)?.groupValues?.get(1)
            ?: return null

        // Step 3: Prepare headers for the final video request
        val headers = mapOf(
            "Referer" to mainUrl,
            "User-Agent" to USER_AGENT
        )

        // Step 4: The "Headers Trick". Since your build environment fails on named parameters,
        // we encode the headers into a JSON object and append it to the URL after a '#'.
        // CloudStream is smart enough to parse this and use the headers.
        val headersJson = JSONObject(headers).toString()
        val finalUrlWithHeaders = "$videoLink#headers=$headersJson"

        // Step 5: Use the simplest form of newExtractorLink that is compatible with your build environment.
        return listOf(
            newExtractorLink(
                this.name,
                this.name,
                finalUrlWithHeaders
            )
        )
    }
}
