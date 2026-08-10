package com.asia2tv // أو المسار الخاص بك
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class Asia2TvPlugin: Plugin() {
    override fun load(context: Context) {
        registerMainAPI(Asia2Tv())
    }
}
