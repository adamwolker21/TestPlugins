package com.example

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class Asia2TvPlugin: Plugin() {
    override fun load(context: Context) {
        // All providers should be added in this manner.
        // Please don't edit the names.
        registerMainAPI(Asia2Tv())
    }
}
