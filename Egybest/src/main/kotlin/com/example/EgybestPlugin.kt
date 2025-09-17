package com.example

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class EgybestPlugin: Plugin() {
    // This is the entry point of the plugin.
    // v2: No major changes needed here, this file primarily registers the provider.
    // The core logic is handled in EgybestProvider.
    override fun load(context: Context) {
        registerMainAPI(EgybestProvider())
    }
}
