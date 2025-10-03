package com.example.extractors

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.getAndUnpack
import com.lagradost.cloudstream3.Qualities

// This extractor handles the GoVID server which uses packed JavaScript.
open class GovidExtractor : ExtractorApi() {
    override val name = "GoVID"
    override val mainUrl = "goveed1.space" // The primary domain for this extractor
    override val requiresReferer = false

    override suspend fun getUrl(url: String, referer: String?): List<ExtractorLink>? {
        // Fetch the embed page content
        val doc = app.get(url, referer = referer).document
        
        // Find the script tag containing the packed (obfuscated) JavaScript code.
        val packedScript = doc.select("script").firstOrNull { script ->
            script.data().contains("eval(function(p,a,c,k,e,d)")
        }?.data()

        if (packedScript != null) {
            // Unpack the JavaScript code to reveal the actual video source links.
            val unpacked = getAndUnpack(packedScript)
            
            // Regex to find the source URL from the unpacked JavaScript.
            // The pattern commonly looks for something like: sources:[{file:"..."}]
            val videoUrl = Regex("""sources:\s*\[\{file:\s*"(.*?)"\}\]""").find(unpacked)?.groupValues?.getOrNull(1)
            
            if (videoUrl != null) {
                // Return the found video link as an ExtractorLink.
                return listOf(
                    ExtractorLink(
                        source = this.name,
                        name = "GoVID", // Server name to be displayed
                        url = videoUrl,
                        referer = url, // Important to pass the embed URL as referer
                        quality = Qualities.Unknown.value, // Quality is usually not available in the link
                        isM3u8 = videoUrl.contains(".m3u8")
                    )
                )
            }
        }
        return null
    }
}
