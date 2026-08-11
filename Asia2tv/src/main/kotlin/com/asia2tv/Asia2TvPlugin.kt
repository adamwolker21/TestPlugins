package com.asia2tv

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class Asia2TvPlugin : Plugin() {
    override fun load(context: Context) {
        // تسجيل المزود الأساسي
        registerMainAPI(Asia2Tv())
        
        // تسجيل المستخرجات (Extractors) الخاصة بالسيرفرات
        registerExtractorAPI(Morencius())
        registerExtractorAPI(StreamHG())
        registerExtractorAPI(MoonServer())
        registerExtractorAPI(LuluServer())
    }
}
