package io.legado.app.ui.book.thought

import io.legado.app.help.http.newCallResponse
import io.legado.app.help.http.okHttpClient
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URLEncoder

/**
 * B16 批注导出 Obsidian REST API 客户端
 * 对应 Obsidian Local REST API 插件：PUT /vault/{path}
 */
object ObsidianApi {

    private val markdownType = "text/markdown; charset=utf-8".toMediaType()

    /** 逐段 URL 编码（保留分隔符），供 putFile 与单测使用 */
    fun encodePath(filePath: String): String {
        return filePath.split("/").joinToString("/") {
            URLEncoder.encode(it, "UTF-8").replace("+", "%20")
        }
    }

    suspend fun putFile(
        apiUrl: String,
        apiKey: String,
        filePath: String,
        content: String
    ): Result<Unit> = kotlin.runCatching {
        val encodedPath = encodePath(filePath)
        val url = "${apiUrl.trimEnd('/')}/vault/${encodedPath.trimStart('/')}"
        val body = content.toRequestBody(markdownType)
        okHttpClient.newCallResponse {
            url(url)
            put(body)
            addHeader("Authorization", "Bearer $apiKey")
            addHeader("Content-Type", "text/markdown")
        }
    }

    suspend fun checkConnection(
        apiUrl: String,
        apiKey: String
    ): Result<Boolean> = kotlin.runCatching {
        val url = "${apiUrl.trimEnd('/')}/"
        val response = okHttpClient.newCallResponse {
            url(url)
            get()
            addHeader("Authorization", "Bearer $apiKey")
        }
        response.isSuccessful
    }
}
