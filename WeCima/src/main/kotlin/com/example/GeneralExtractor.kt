package com.example.extractors

import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.JsUnpacker
import com.lagradost.cloudstream3.utils.loadExtractor

// This extractor will handle servers that use packed JavaScript
open class GeneralPackedExtractor : ExtractorApi() {
    override var name = "GeneralPacked"
    override var mainUrl = "https://wecima.now" // Placeholder
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        // Get the player page content
        val playerPageContent = app.get(url, referer = referer, headers = mapOf("User-Agent" to USER_AGENT)).text
        
        // Unpack the JavaScript to find the hidden video link
        val unpackedLink = JsUnpacker(playerPageContent).unpack()?.let { unpackedJs ->
            Regex("""(https?://[^\s'"]+\.(?:m3u8|mp4)[^\s'"]*)""").find(unpackedJs)?.groupValues?.get(1)
        } ?: return // If nothing is found, exit

        // Instead of processing the link ourselves, we pass it to the built-in loadExtractor.
        // This lets Cloudstream's own powerful extractors (like for M3U8 files) handle it.
        // We provide the original player page URL as the referer.
        loadExtractor(unpackedLink, url, subtitleCallback, callback)
    }
}
