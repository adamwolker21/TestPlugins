package com.asia2tv

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class Asia2TvPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(Asia2Tv())
        
        // تسجيل المستخرجات
        registerExtractorAPI(Morencius())
        registerExtractorAPI(StreamHG())
        registerExtractorAPI(MoonServer())
        registerExtractorAPI(LuluServer())
        registerExtractorAPI(VidmolyAsia()) // تمت إضافته هنا
    }
}
