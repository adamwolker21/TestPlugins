package com.example.extractors

import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.*
import org.json.JSONObject

// V17: Reverting to YOUR successful build code. This was my mistake.
// This correctly packs headers into the URL.
class GovidExtractor : ExtractorApi() {
    override var name = "GoVID"
    override var mainUrl = "goveed1.space"
    override val requiresReferer = true

    override suspend fun getUrl(url: String, referer: String?): MutableList<ExtractorLink>? {
        val response = app.get(url, referer = referer).document
        val script = response.selectFirst("script:containsData(eval(function(p,a,c,k,e,d))")?.data()
            ?: return null

        val unpacked = getAndUnpack(script)
        val videoUrl = Regex("""sources:\s*\[\{file:\s*"(.*?)"\}\]""").find(unpacked)?.groupValues?.getOrNull(1)
            ?: return null

        // This is the correct way to handle headers for your CloudStream version.
        val playerHeaders = mapOf(
            "Referer" to url,
            "User-Agent" to USER_AGENT
        )
        val headersJson = JSONObject(playerHeaders).toString()
        val finalUrl = "$videoUrl#headers=$headersJson"

        return mutableListOf(
            newExtractorLink(
                this.name,
                this.name,
                finalUrl
            )
        )
    }
}
