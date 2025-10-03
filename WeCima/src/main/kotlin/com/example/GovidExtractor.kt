package com.example.extractors

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.*

// This extractor handles the GoVID server which uses packed JavaScript.
class GovidExtractor : ExtractorApi() { // To match VidbomExtractor style
    override val name = "GoVID"
    override val mainUrl = "goveed1.space"
    override val requiresReferer = false

    @Suppress("DEPRECATION") // V5 Fix: Suppress the deprecation warning to prevent build failure.
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
            val videoUrl = Regex("""sources:\s*\[\{file:\s*"(.*?)"\}\]""").find(unpacked)?.groupValues?.getOrNull(1)

            if (videoUrl != null) {
                // Using the deprecated constructor because the build environment requires it,
                // and suppressing the associated warning.
                return listOf(
                    ExtractorLink(
                        source = this.name,
                        name = "GoVID",
                        url = videoUrl,
                        referer = url, // The embed URL is the correct referer
                        quality = getQualityFromName("Unknown"),
                        isM3u8 = videoUrl.contains(".m3u8")
                    )
                )
            }
        }
        return null
    }
}
