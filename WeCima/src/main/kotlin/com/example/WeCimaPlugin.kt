package com.example

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context
import com.example.extractors.*

@CloudstreamPlugin
class WeCimaPlugin: Plugin() {
    override fun load(context: Context) {
        registerMainAPI(WeCimaProvider())
        // Register all our custom extractors
        registerExtractorAPI(WeCimaExtractor())
        registerExtractorAPI(VidbomExtractor())
        registerExtractorAPI(GeneralPackedExtractor())

        // Register all our GoVID test extractors
        registerExtractorAPI(GovidExtractor_Base())
        registerExtractorAPI(GovidExtractor_CF())
        registerExtractorAPI(GovidExtractor_Headers())
        registerExtractorAPI(GovidExtractor_Full())
    }
}
