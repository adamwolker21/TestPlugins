package com.example

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context
import com.example.extractors.WeCimaExtractor
import com.example.extractors.VidbomExtractor
import com.example.extractors.GeneralPackedExtractor

@CloudstreamPlugin
class WeCimaPlugin: Plugin() {
    override fun load(context: Context) {
        // Register the main provider
        registerMainAPI(WeCimaProvider())
        
        // Register all our custom extractors
        registerExtractorAPI(WeCimaExtractor())
        registerExtractorAPI(VidbomExtractor())
        registerExtractorAPI(GeneralPackedExtractor())
    }
}
