package com.example

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context
import com.example.extractors.GeneralPackedExtractor
import com.example.extractors.VidbomExtractor
import com.example.extractors.WeCimaExtractor

@CloudstreamPlugin
class WeCimaPlugin: Plugin() {
    override fun load(context: Context) {
        registerMainAPI(WeCimaProvider())
        // Register our working custom extractors
        registerExtractorAPI(WeCimaExtractor())
        registerExtractorAPI(VidbomExtractor())
        registerExtractorAPI(GeneralPackedExtractor())
    }
}
