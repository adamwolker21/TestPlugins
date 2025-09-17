package com.wolker.asia2tv

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class Asia2TvPlugin: Plugin() {
    override fun load(context: Context) {
        // لا حاجة لـ import لأن كلا الملفين في نفس الحزمة الآن
        registerMainAPI(Asia2Tv())
    }
}
