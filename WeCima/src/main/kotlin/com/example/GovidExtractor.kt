package com.example.extractors

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.*

// This extractor handles the GoVID server which uses packed JavaScript.
class GovidExtractor : ExtractorApi() {
    override var name = "GoVID" // Changed to var to match VidbomExtractor
    override var mainUrl = "goveed1.space" // Changed to var to match VidbomExtractor
    override val requiresReferer = false

    // V6 Fix: The function signature now returns a MutableList and uses the exact
    // same ExtractorLink constructor overload as the user's working VidbomExtractor.
    // This avoids the specific deprecation error that was causing the build to fail.
    override suspend fun getUrl(url: String, referer: String?): MutableList<ExtractorLink>? {
        val response = app.get(url, referer = referer).document
        val script = response.selectFirst("script:containsData(eval(function(p,a,c,k,e,d))")?.data()
            ?: return null

        // Unpack the JavaScript code to reveal the actual video source links.
        val unpacked = getAndUnpack(script)

        // Regex to find the source URL from the unpacked JavaScript.
        val videoUrl = Regex("""sources:\s*\[\{file:\s*"(.*?)"\}\]""").find(unpacked)?.groupValues?.getOrNull(1)
            ?: return null

        // Using the exact same constructor as VidbomExtractor to ensure compatibility.
        // It uses positional arguments and omits the 'isM3u8' parameter.
        return mutableListOf(
            ExtractorLink(
                this.name,                             // source
                this.name,                             // name
                videoUrl,                              // url
                url,                                   // referer
                getQualityFromName("GoVID"),       // quality
            )
        )
    }
}
