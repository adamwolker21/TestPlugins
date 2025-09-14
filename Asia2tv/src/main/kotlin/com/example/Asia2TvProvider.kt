// v53: Using ExtractorLink directly instead of newExtractorLink with modification block
package com.wolker.asia2tv

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

// ... [الكود السابق بدون تغيير] ...

    // دالة خاصة لاستخراج روابط من vidmoly
    private suspend fun extractVidmolyLinks(
        iframeUrl: String,
        referer: String,
        serverName: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        try {
            println("DEBUG: Extracting from Vidmoly: $iframeUrl")
            val document = app.get(iframeUrl, headers = customHeaders + mapOf("Referer" to referer)).document
            
            // البحث في السكريبتات عن روابط m3u8
            val scripts = document.select("script")
            for (script in scripts) {
                val scriptContent = script.html()
                
                // regex محسن للعثور على روابط m3u8
                val m3u8Regex = """(https?://[^"'`\s]*\.m3u8[^"'`\s]*)""".toRegex()
                val matches = m3u8Regex.findAll(scriptContent)
                
                for (match in matches) {
                    val m3u8Url = match.value
                    if (m3u8Url.contains("m3u8")) {
                        println("DEBUG: Found m3u8 URL: $m3u8Url")
                        
                        // استخدام newExtractorLink بدلاً من ExtractorLink مباشرة
                        callback.invoke(newExtractorLink(
                            source = name,
                            name = serverName,
                            url = m3u8Url,
                            referer = iframeUrl,
                            quality = Qualities.Unknown.value,
                            isM3u8 = true
                        ))
                        return true
                    }
                }
            }
            
            // ... [الباقي بدون تغيير] ...
        } catch (e: Exception) {
            println("DEBUG: Vidmoly extraction failed: ${e.message}")
            e.printStackTrace()
        }
        return false
    }

    // دالة لاستخراج روابط من doodstream
    private suspend fun extractDoodLinks(
        iframeUrl: String,
        referer: String,
        serverName: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        try {
            println("DEBUG: Extracting from Doodstream: $iframeUrl")
            val document = app.get(iframeUrl, headers = customHeaders + mapOf("Referer" to referer)).document
            val scriptContent = document.select("script").html()
            
            // regex خاص بـ doodstream
            val doodRegex = """https?://[^/]+/e/[^"']+""".toRegex()
            val doodMatch = doodRegex.find(scriptContent)
            
            doodMatch?.value?.let { doodUrl ->
                println("DEBUG: Found Doodstream URL: $doodUrl")
                val response = app.get(doodUrl, referer = iframeUrl, headers = customHeaders).text
                val m3u8Regex = """(https?://[^"'`\s]*\.m3u8[^"'`\s]*)""".toRegex()
                val m3u8Match = m3u8Regex.find(response)
                
                m3u8Match?.value?.let { m3u8Url ->
                    println("DEBUG: Found m3u8 from Doodstream: $m3u8Url")
                    
                    // استخدام newExtractorLink بدلاً من ExtractorLink مباشرة
                    callback.invoke(newExtractorLink(
                        source = name,
                        name = serverName,
                        url = m3u8Url,
                        referer = doodUrl,
                        quality = Qualities.Unknown.value,
                        isM3u8 = true
                    ))
                    return true
                }
            }
        } catch (e: Exception) {
            println("DEBUG: Doodstream extraction failed: ${e.message}")
            e.printStackTrace()
        }
        return false
    }
}
