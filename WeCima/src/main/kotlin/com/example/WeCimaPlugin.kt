package com.example

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context
import com.example.extractors.GeneralPackedExtractor
import com.example.extractors.VidbomExtractor
import com.example.extractors.WeCimaExtractor
import com.example.extractors.GovidExtractor // V19: Import the new extractor

@CloudstreamPlugin
class WeCimaPlugin: Plugin() {
    override fun load(context: Context) {
        registerMainAPI(WeCimaProvider())
        // Register all our custom extractors
        registerExtractorAPI(WeCimaExtractor())
        registerExtractorAPI(VidbomExtractor())
        registerExtractorAPI(GeneralPackedExtractor())
        registerExtractorAPI(GovidExtractor()) // V19: Register the new extractor
    }
}
