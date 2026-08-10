package com.asia2tv 
// تأكد أن المسار (package) يطابق المسار الموجود في ملفاتك، مثلاً: com.adamwolker21.asia2tv

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class Asia2TvPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(Asia2Tv())
    }
}
