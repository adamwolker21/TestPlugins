package com.example.extractors

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.*

class GovidExtractor : ExtractorApi() {
    override var name = "GoVID"
    override var mainUrl = "goveed1.space"
    override val requiresReferer = false

    override suspend fun getUrl(url: String, referer: String?): MutableList<ExtractorLink>? {
        val response = app.get(url, referer = referer).document
        val script = response.selectFirst("script:containsData(eval(function(p,a,c,k,e,d))")?.data()
            ?: return null

        val unpacked = getAndUnpack(script)
        val videoUrl = Regex("""sources:\s*\[\{file:\s*"(.*?)"\}\]""").find(unpacked)?.groupValues?.getOrNull(1)
            ?: return null

        // استخدام newExtractorLink بدلاً من Constructor المُهمَل
        return mutableListOf(
            newExtractorLink(
                source = this.name,
                name = this.name,
                url = videoUrl,
                referer = this.mainUrl,
                quality = getQualityFromName("GoVID"),
            )
        )
    }
}
