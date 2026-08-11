package com.asia2tv

import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.JsUnpacker
import org.json.JSONObject

private val cloudflareKiller by lazy { CloudflareKiller() }

private fun findUrlInUnpackedJs(unpackedJs: String): String? {
    Regex("""(?i)"hls2"\s*:\s*"([^"]+)"""").find(unpackedJs)?.groupValues?.get(1)?.let { return it }
    Regex("""(?i)(https?://[^\s'"]+\.(?:m3u8|mp4)[^\s'"]*)""").find(unpackedJs)?.groupValues?.get(1)?.let { return it }
    Regex("""(?i)file\s*:\s*["'](http[^"']+)""").find(unpackedJs)?.groupValues?.get(1)?.let { return it }
    return null
}

// 1. مستخرج Vidmoly
class VidmolyAsia : ExtractorApi() {
    override var name = "Vidmoly"
    override var mainUrl = "vidmoly.net"
    override val requiresReferer = true

    override suspend fun getUrl(url: String, referer: String?): List<ExtractorLink>? {
        try {
            val response = app.get(url, referer = referer ?: "https://asia2tv.com/")
            val document = response.text

            val m3u8Url = Regex("""sources:\s*\[\s*\{\s*file:\s*['"](http[^'"]+\.m3u8[^'"]*)['"]""").find(document)?.groupValues?.get(1)
            
            if (!m3u8Url.isNullOrBlank()) {
                return listOf(
                    newExtractorLink(
                        source = this.name,
                        name = this.name,
                        url = m3u8Url,
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.referer = response.url
                        this.quality = Qualities.P720.value
                    }
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return null
    }
}

// 2. مستخرج StreamHG (تم تصحيح الخطأ هنا)
class StreamHG : ExtractorApi() {
    override var name = "StreamHG"
    override var mainUrl = "hglink.to" 
    override val requiresReferer = true
    
    // دومينات احتياطية في حال فشل جلب الرابط من التوجيه
    private val knownHosts = listOf("https://vibuxer.com", "https://hanerix.com", "https://audinifer.com")

    override suspend fun getUrl(url: String, referer: String?): List<ExtractorLink>? {
        val videoId = url.substringAfterLast("/")
        if (videoId.isBlank()) return null
        
        var actualUrl = url
        val reqReferer = "https://hglink.to/"

        try {
            // الخطوة 1: طلب الرابط الأصلي مع إيقاف التوجيه التلقائي لسحب الدومين الجديد
            val initialResponse = app.get(url, allowRedirects = false)
            // استخراج الترويسة بشكل مباشر لتفادي خطأ البناء
            val location = initialResponse.headers["location"] ?: initialResponse.headers["Location"] ?: ""
            
            if (initialResponse.code in 300..399 && location.isNotBlank()) {
                actualUrl = location
            }
        } catch (e: Exception) {
            // صامت
        }

        val urlsToTry = mutableListOf(actualUrl)
        knownHosts.forEach { host ->
            val fallbackUrl = "$host/e/$videoId"
            if (!urlsToTry.contains(fallbackUrl)) urlsToTry.add(fallbackUrl)
        }

        // الخطوة 2: الدخول للرابط مع الـ Referer الإجباري وفك التشفير
        for (targetUrl in urlsToTry) {
            try {
                val response = app.get(targetUrl, referer = reqReferer, interceptor = cloudflareKiller)
                val playerPageContent = response.text

                if (playerPageContent.isBlank()) continue

                val unpackedJs = JsUnpacker(playerPageContent).unpack() ?: continue
                val videoLink = findUrlInUnpackedJs(unpackedJs) ?: continue

                val headers = mapOf("Referer" to targetUrl, "User-Agent" to USER_AGENT)
                val finalUrlWithHeaders = "$videoLink#headers=${JSONObject(headers)}"

                val isM3u8 = videoLink.contains(".m3u8")
                val linkType = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO

                return listOf(
                    newExtractorLink(
                        source = this.name,
                        name = this.name,
                        url = finalUrlWithHeaders,
                        type = linkType
                    ) {
                        this.referer = targetUrl
                        this.quality = Qualities.Unknown.value
                    }
                )
            } catch (e: Exception) {
                // استمرار المحاولة
            }
        }
        return null
    }
}

// 3. مستخرج Morencius
class Morencius : ExtractorApi() {
    override var name = "Morencius"
    override var mainUrl = "morencius.com" 
    override val requiresReferer = true
    private val potentialHosts = listOf("https://morencius.com", "https://earnvids.com")

    override suspend fun getUrl(url: String, referer: String?): List<ExtractorLink>? {
        val videoId = url.substringAfterLast("/")
        if (videoId.isBlank()) return null

        for (host in potentialHosts) {
            try {
                val finalPageUrl = if (url.contains("/e/")) "$host/e/$videoId" else "$host/v/$videoId"
                val playerPageContent = app.get(finalPageUrl, referer = referer ?: url, interceptor = cloudflareKiller).text
                if (playerPageContent.isBlank()) continue

                val unpackedJs = JsUnpacker(playerPageContent).unpack() ?: continue
                val videoLink = findUrlInUnpackedJs(unpackedJs) ?: continue

                val headers = mapOf("Referer" to finalPageUrl, "User-Agent" to USER_AGENT)
                val finalUrlWithHeaders = "$videoLink#headers=${JSONObject(headers)}"
                
                val isM3u8 = videoLink.contains(".m3u8")
                val linkType = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO

                return listOf(
                    newExtractorLink(
                        source = this.name,
                        name = this.name,
                        url = finalUrlWithHeaders,
                        type = linkType
                    ) {
                        this.referer = finalPageUrl
                        this.quality = Qualities.Unknown.value
                    }
                )
            } catch (e: Exception) {
                // المحاولة في النطاق التالي
            }
        }
        return null
    }
}

// 4. مستخرج MoonServer
class MoonServer : ExtractorApi() {
    override var name = "MoonServer"
    override var mainUrl = "bysefujedu.com"
    override val requiresReferer = true

    override suspend fun getUrl(url: String, referer: String?): List<ExtractorLink>? {
        try {
            val playerPageContent = app.get(url, referer = referer ?: url, interceptor = cloudflareKiller).text
            if (playerPageContent.isBlank()) return null

            val unpackedJs = JsUnpacker(playerPageContent).unpack() ?: return null
            val videoLink = findUrlInUnpackedJs(unpackedJs) ?: return null

            val headers = mapOf("Referer" to url, "User-Agent" to USER_AGENT)
            val finalUrlWithHeaders = "$videoLink#headers=${JSONObject(headers)}"

            return listOf(
                newExtractorLink(
                    source = this.name,
                    name = this.name,
                    url = finalUrlWithHeaders,
                    type = if (videoLink.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                ) {
                    this.referer = url
                    this.quality = Qualities.Unknown.value
                }
            )
        } catch (e: Exception) {
            return null
        }
    }
}

// 5. مستخرج LuluServer
class LuluServer : ExtractorApi() {
    override var name = "LuluServer"
    override var mainUrl = "luluvid.com"
    override val requiresReferer = true

    override suspend fun getUrl(url: String, referer: String?): List<ExtractorLink>? {
        try {
            val playerPageContent = app.get(url, referer = referer ?: url, interceptor = cloudflareKiller).text
            if (playerPageContent.isBlank()) return null

            val unpackedJs = JsUnpacker(playerPageContent).unpack() ?: return null
            val videoLink = findUrlInUnpackedJs(unpackedJs) ?: return null

            val headers = mapOf("Referer" to url, "User-Agent" to USER_AGENT)
            val finalUrlWithHeaders = "$videoLink#headers=${JSONObject(headers)}"

            return listOf(
                newExtractorLink(
                    source = this.name,
                    name = this.name,
                    url = finalUrlWithHeaders,
                    type = if (videoLink.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                ) {
                    this.referer = url
                    this.quality = Qualities.Unknown.value
                }
            )
        } catch (e: Exception) {
            return null
        }
    }
}
