package io.legado.app.utils

import io.legado.app.BuildConfig
import io.legado.app.constant.AppLog
import io.legado.app.constant.AppPattern.semicolonRegex
import io.legado.app.help.config.AppConfig
import io.legado.app.help.http.okHttpClient
import io.legado.app.model.analyzeRule.AnalyzeUrl
import io.legado.app.model.analyzeRule.CustomUrl
import java.net.URL
import java.net.URLDecoder

object UrlUtil {

    // 有时候文件名在query里，截取path会截到其他内容
    // https://www.example.com/download.php?filename=文件.txt
    // https://www.example.com/txt/文件.txt?token=123456
    private val unExpectFileSuffixs = arrayOf(
        "php", "html"
    )

    fun replaceReservedChar(text: String): String {
        return text.replace("%", "%25")
            .replace(" ", "%20")
            .replace("\"", "%22")
            .replace("#", "%23")
            .replace("&", "%26")
            .replace("(", "%28")
            .replace(")", "%29")
            .replace("+", "%2B")
            .replace(",", "%2C")
            .replace("/", "%2F")
            .replace(":", "%3A")
            .replace(";", "%3B")
            .replace("<", "%3C")
            .replace("=", "%3D")
            .replace(">", "%3E")
            .replace("?", "%3F")
            .replace("@", "%40")
            .replace("\\", "%5C")
            .replace("|", "%7C")
    }


    /* 阅读定义的url,{urlOption} */
    fun getFileName(analyzeUrl: AnalyzeUrl): String? {
        return getFileName(analyzeUrl.url, analyzeUrl.headerMap)
    }

    /**
     * 根据网络url获取文件信息 文件名
     */
    @Suppress("MemberVisibilityCanBePrivate")
    fun getFileName(fileUrl: String, headerMap: Map<String, String>? = null): String? {
        return kotlin.runCatching {
            val url = URL(fileUrl)
            var fileName: String? = getFileNameFromPath(url)
            if (fileName == null) {
                fileName = getFileNameFromResponseHeader(url, headerMap)
            }
            fileName
        }.getOrNull()
    }

    @Suppress("MemberVisibilityCanBePrivate")
    private fun getFileNameFromResponseHeader(
        url: URL,
        headerMap: Map<String, String>? = null
    ): String? {
        // P1-3 (2026-07-31): 使用 OkHttp API 替换 HttpURLConnection，走 CronetInterceptor 接入 Cronet
        // 获得 BoringSSL TLS 指纹 + QUIC 能力，提升反爬 CDN 场景文件名获取成功率
        val request = okhttp3.Request.Builder()
            .url(url.toString())
            .head()
            .apply {
                headerMap?.forEach { (key, value) -> addHeader(key, value) }
            }
            .build()
        // 禁用自动重定向，否则获取不到响应头返回的 Location
        val client = okHttpClient.newBuilder()
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
        val response = client.newCall(request).execute()
        response.use { resp ->
            if (AppConfig.recordLog || BuildConfig.DEBUG) {
                val headersString = buildString {
                    for (i in 0 until resp.headers.size) {
                        append(resp.headers.name(i))
                        append(": ")
                        append(resp.headers.value(i))
                        append("\n")
                    }
                }
                AppLog.put("$url response header:\n$headersString")
            }

            /** Content-Disposition 存在三种情况 文件名应该用引号 有些用空格
             * filename="filename"
             * filename*="charset''filename"
             */
            val raw: String? = resp.header("Content-Disposition")
            // Location跳转到实际链接
            val redirectUrl: String? = resp.header("Location")

            return if (raw != null) {
                val fileNames = raw.split(semicolonRegex).filter { it.contains("filename") }
                val names = hashSetOf<String>()
                fileNames.forEach {
                    val fileName = it.substringAfter("=")
                        .trim()
                        .replace("^\"".toRegex(), "")
                        .replace("\"$".toRegex(), "")
                    if (it.contains("filename*")) {
                        val data = fileName.split("''")
                        names.add(URLDecoder.decode(data[1], data[0]))
                    } else {
                        names.add(fileName)
                    }
                }
                names.firstOrNull()
            } else if (redirectUrl != null) {
                val newUrl = URL(URLDecoder.decode(redirectUrl, "UTF-8"))
                getFileNameFromPath(newUrl)
            } else {
                AppLog.put("Cannot obtain URL file name, enable recordLog for response header")
                null
            }
        }
    }
    
    private fun getFileNameFromPath(fileUrl: URL): String? {
        val path = fileUrl.path ?: return null
        val suffix = getSuffix(path, "")
        return if (
           suffix != "" && !unExpectFileSuffixs.contains(suffix)
        ) {
            path.substringAfterLast("/")
        } else {
            AppLog.put("getFileNameFromPath: Unexpected file suffix: $suffix")
            null
        }
    }

    private val fileSuffixRegex = Regex("^[a-z\\d]+$", RegexOption.IGNORE_CASE)

    /* 获取合法的文件后缀 */
    fun getSuffix(str: String, default: String? = null): String {
        val suffix = CustomUrl(str).getUrl()
            .substringAfterLast("/")
            .substringBefore("?")
            .substringBefore("#")
            .substringAfterLast(".", "")
        //检查截取的后缀字符是否合法 [a-zA-Z0-9]
        return if (suffix.length > 5 || !suffix.matches(fileSuffixRegex)) {
            if (default == null) {
                AppLog.put("Cannot find legal suffix:\n target: $str\n suffix: $suffix")
            }
            default ?: "ext"
        } else {
            suffix
        }
    }

}
