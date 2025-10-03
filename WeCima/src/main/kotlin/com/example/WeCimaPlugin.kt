package com.example

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context
import com.example.extractors.WeCimaExtractor

@CloudstreamPlugin
class WeCimaPlugin: Plugin() {
    override fun load(context: Context) {
        registerMainAPI(WeCimaProvider())
        // v16: Register the new extractor
        registerExtractorAPI(WeCimaExtractor())
    }
}
