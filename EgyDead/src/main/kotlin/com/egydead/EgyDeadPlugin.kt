package com.egydead

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class EgyDeadPlugin: Plugin() {
    override fun load(context: Context) {
        // We only need to register the provider now.
        // The extractors are handled internally by the provider itself.
        registerMainAPI(EgyDeadProvider())
    }
}
