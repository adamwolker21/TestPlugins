package com.example

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class EgybestPlugin: Plugin() {
    // v3: No changes needed in this file.
    // It correctly registers the provider.
    override fun load(context: Context) {
        registerMainAPI(EgybestProvider())
    }
}
