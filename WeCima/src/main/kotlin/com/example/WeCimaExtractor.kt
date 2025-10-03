package com.example.extractors

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.USER_AGENT
import android.net.Uri // Required for encoding

open class WeCimaExtractor : ExtractorApi() {
    override var name = "WeCima"
    override var mainUrl = "https://wecima.now"
    override val requiresReferer = true

    override suspend fun getUrl(url: String, referer: String?): List<ExtractorLink>? {
        // Step 1: Visit the intermediate page with the correct User-Agent
        val doc = app.get(
            url, 
            referer = referer, 
            headers = mapOf("User-Agent" to USER_AGENT)
        ).document
        
        val directLink = Regex("""(https?://[^\s'"]+\.mp4[^\s'"]*)""").find(doc.html())?.groupValues?.get(1)
            ?: return null

        // Step 2: Build the headers map
        val headers = mapOf(
            "Referer" to mainUrl,
            "User-Agent" to USER_AGENT
        )

        // Step 3: Convert the map to a JSON string and URL-encode it
        val headersJson = headers.entries.joinToString(prefix = "{", postfix = "}", separator = ",") {
            """"${it.key}":"${it.value}""""
        }
        val encodedHeaders = Uri.encode(headersJson)
        
        // Step 4: Append the encoded headers to the URL using the #headers hack
        val finalUrl = "$directLink#headers=$encodedHeaders"
        
        // Step 5: Use the simplest form of newExtractorLink which is compatible with your build
        return listOf(
            newExtractorLink(
                source = this.name,
                name = this.name,
                url = finalUrl
            )
        )
    }
}
