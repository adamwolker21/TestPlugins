package com.example

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.getAndUnpack

open class GovidExtractor : ExtractorApi() {
    override val name = "GoVID"
    override val mainUrl = "goveed1.space" // استخدمنا النطاق الرئيسي هنا
    override val requiresReferer = false

    override suspend fun getUrl(url: String, referer: String?): List<ExtractorLink>? {
        // جلب محتوى صفحة الـ embed
        val doc = app.get(url).document
        
        // البحث عن السكريبت الذي يحتوي على الكود المخفي (Packed)
        val packedScript = doc.select("script").firstOrNull { script ->
            script.data().contains("eval(function(p,a,c,k,e,d)")
        }?.data()

        if (packedScript != null) {
            // فك الكود المخفي باستخدام الدالة الجاهزة
            val unpacked = getAndUnpack(packedScript)
            
            // بعد فك الشفرة، نبحث عن رابط المصدر بداخلها
            // عادة ما يكون بهذا الشكل: sources:[{file:"https://.../v.mp4"}]
            val videoUrl = Regex("""sources:\s*\[\{file:\s*"(.*?)"\}""").find(unpacked)?.groupValues?.get(1)
            
            if (videoUrl != null) {
                return listOf(
                    ExtractorLink(
                        source = this.name,
                        name = "GoVID", // يمكنك تعديل الاسم هنا
                        url = videoUrl,
                        referer = url, // نرسل رابط الـ embed كـ referer
                        quality = Qualities.Unknown.value // الجودة غير معروفة من الرابط
                    )
                )
            }
        }
        return null
    }
}
