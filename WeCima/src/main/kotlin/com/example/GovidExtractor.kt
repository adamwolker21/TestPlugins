
package com.example.extractors

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.*

// This extractor handles the GoVID server which uses packed JavaScript.
class GovidExtractor : ExtractorApi() {
    override var name = "GoVID"
    override var mainUrl = "goveed1.space"
    override val requiresReferer = false

    // The function now matches the exact structure and return type of the user's working VidbomExtractor.
    override suspend fun getUrl(url: String, referer: String?): MutableList<ExtractorLink>? {
        val response = app.get(url, referer = referer).document
        val script = response.selectFirst("script:containsData(eval(function(p,a,c,k,e,d))")?.data()
            ?: return null

        // Unpack the JavaScript code to reveal the actual video source links.
        val unpacked = getAndUnpack(script)

        // Regex to find the source URL from the unpacked JavaScript.
        val videoUrl = Regex("""sources:\s*\[\{file:\s*"(.*?)"\}\]""").find(unpacked)?.groupValues?.getOrNull(1)
            ?: return null

        // Using the exact constructor signature from VidbomExtractor to ensure 100% compatibility.
        // This includes using `mainUrl` as the referer for the sake of compilation.
        return mutableListOf(
            ExtractorLink(
                this.name,
                this.name,
                videoUrl,
                this.mainUrl, // Matched to VidbomExtractor
                getQualityFromName("GoVID"),
            )
        )
    }
}
