package io.legado.app.help.http

import io.legado.app.utils.EncodingDetect
import io.legado.app.utils.GSON
import io.legado.app.utils.Utf8BomUtils
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.asResponseBody
import okio.buffer
import okio.source
import java.io.File
import java.io.IOException
import java.nio.charset.Charset
import java.util.zip.ZipInputStream
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

suspend fun OkHttpClient.newCallResponse(
    retry: Int = 0,
    builder: Request.Builder.() -> Unit
): Response {
    val requestBuilder = Request.Builder()
    requestBuilder.apply(builder)
    var response: Response? = null
    var currentRequest = requestBuilder.build()
    for (i in 0..retry) {
        response = newCall(currentRequest).await()
        if (response.isSuccessful) {
            return response
        }
        // 307/308 兜底：OkHttp 自动重定向未跟随时（如 body 一次性流），手动保持 method+body 跟随
        if (response.code == 307 || response.code == 308) {
            response.header("Location")?.let { location ->
                val redirectRequest = currentRequest.newBuilder()
                    .url(location)
                    .method(currentRequest.method, currentRequest.body)
                    .headers(currentRequest.headers)
                    .build()
                response.close()
                response = newCall(redirectRequest).await()
                if (response.isSuccessful) {
                    return response
                }
                currentRequest = redirectRequest
            }
        }
    }
    return response!!
}

suspend fun OkHttpClient.newCallResponseBody(
    retry: Int = 0,
    builder: Request.Builder.() -> Unit
): ResponseBody {
    return newCallResponse(retry, builder).body
}

suspend fun OkHttpClient.newCallStrResponse(
    retry: Int = 0,
    builder: Request.Builder.() -> Unit
): StrResponse {
    return newCallResponse(retry, builder).let {
        StrResponse(it, it.body.text())
    }
}

suspend fun Call.await(): Response = suspendCancellableCoroutine { block ->

    block.invokeOnCancellation {
        cancel()
    }

    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            block.resumeWithException(e)
        }

        override fun onResponse(call: Call, response: Response) {
            block.resume(response)
        }
    })

}

fun ResponseBody.text(encode: String? = null): String {
    val responseBytes = Utf8BomUtils.removeUTF8BOM(bytes())
    var charsetName: String? = encode

    charsetName?.let {
        return String(responseBytes, Charset.forName(charsetName))
    }

    //根据http头判断
    contentType()?.charset()?.let { charset ->
        return String(responseBytes, charset)
    }

    //根据内容判断
    charsetName = EncodingDetect.getHtmlEncode(responseBytes)
    return String(responseBytes, Charset.forName(charsetName))
}

fun ResponseBody.decompressed(): ResponseBody {
    val contentType = contentType()?.toString()
    if (contentType != "application/zip") {
        return this
    }
    val source = ZipInputStream(byteStream()).apply {
        try {
            nextEntry
        } catch (e: Exception) {
            close()
            throw e
        }
    }.source().buffer()
    return source.asResponseBody(null, -1)
}

fun Request.Builder.addHeaders(headers: Map<String, String>) {
    headers.forEach {
        addHeader(it.key, it.value)
    }
}

fun Request.Builder.get(url: String, queryMap: Map<String, String>, encoded: Boolean = false) {
    val httpBuilder = url.toHttpUrl().newBuilder()
    queryMap.forEach {
        if (encoded) {
            httpBuilder.addEncodedQueryParameter(it.key, it.value)
        } else {
            httpBuilder.addQueryParameter(it.key, it.value)
        }
    }
    url(httpBuilder.build())
}

fun Request.Builder.get(url: String, encodedQuery: String?) {
    val httpBuilder = url.toHttpUrl().newBuilder()
    httpBuilder.encodedQuery(encodedQuery)
    url(httpBuilder.build())
}

private val formContentType = "application/x-www-form-urlencoded".toMediaType()

/**
 * 提交表单（REQ-09 / AD-14：新增可选 [charset]，默认 UTF-8 ⇒ 既有调用点行为零变化）。
 * 指定非 UTF-8 时同步改写 MediaType 的 charset 与请求体字节编码，使目标站能正确识别。
 */
fun Request.Builder.postForm(encodedForm: String, charset: Charset = Charsets.UTF_8) {
    val mediaType = if (charset == Charsets.UTF_8) {
        formContentType
    } else {
        "application/x-www-form-urlencoded; charset=${charset.name()}".toMediaType()
    }
    post(encodedForm.toByteArray(charset).toRequestBody(mediaType))
}

@Suppress("unused")
fun Request.Builder.postForm(
    form: Map<String, String>,
    encoded: Boolean = false,
    charset: Charset = Charsets.UTF_8
) {
    if (charset == Charsets.UTF_8) {
        // 既有默认路径（UTF-8）保持原实现，确保零变化
        val formBody = FormBody.Builder()
        form.forEach {
            if (encoded) {
                formBody.addEncoded(it.key, it.value)
            } else {
                formBody.add(it.key, it.value)
            }
        }
        post(formBody.build())
        return
    }
    // 非 UTF-8：FormBody 固定以 UTF-8 编码 ⇒ 按 charset 自建表单体（form-urlencode）
    val body = form.entries.joinToString("&") { (key, value) ->
        if (encoded) "$key=$value" else "${formEncode(key, charset)}=${formEncode(value, charset)}"
    }
    postForm(body, charset)
}

/** form-urlencode（与 `FormBody` 同语义：空格编成 `+`）。 */
private fun formEncode(value: String, charset: Charset): String =
    java.net.URLEncoder.encode(value, charset.name())

fun Request.Builder.postMultipart(type: String?, form: Map<String, Any>) {
    val multipartBody = MultipartBody.Builder()
    type?.let {
        multipartBody.setType(type.toMediaType())
    }
    form.forEach {
        when (val value = it.value) {
            is Map<*, *> -> {
                val fileName = value["fileName"] as String
                val file = value["file"]
                val mediaType = (value["contentType"] as? String)?.toMediaType()
                val requestBody = when (file) {
                    is File -> {
                        file.asRequestBody(mediaType)
                    }

                    is ByteArray -> {
                        file.toRequestBody(mediaType)
                    }

                    is String -> {
                        file.toRequestBody(mediaType)
                    }

                    else -> {
                        GSON.toJson(file).toRequestBody(mediaType)
                    }
                }
                multipartBody.addFormDataPart(it.key, fileName, requestBody)
            }

            else -> multipartBody.addFormDataPart(it.key, it.value.toString())
        }
    }
    post(multipartBody.build())
}

fun Request.Builder.postJson(json: String?) {
    json?.let {
        val requestBody = json.toRequestBody("application/json; charset=UTF-8".toMediaType())
        post(requestBody)
    }
}