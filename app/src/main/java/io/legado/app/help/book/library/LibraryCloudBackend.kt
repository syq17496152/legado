package io.legado.app.help.book.library

import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.http.okHttpClient
import io.legado.app.help.http.text
import io.legado.app.lib.cloud.CloudStorageFile
import io.legado.app.lib.cloud.S3Config
import io.legado.app.lib.cloud.S3Container
import io.legado.app.lib.cloud.S3Signer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

class LibraryCloudBackend(private val config: LibraryContainerConfig) {

    private val container: S3Container = config.container.normalized()

    suspend fun check() = withContext(Dispatchers.IO) {
        container.requireValid()
        request("GET", "", container.toConfig(), listBucket = true).use(::checkResult)
    }

    suspend fun upload(path: String, bytes: ByteArray, contentType: String = "application/json") = withContext(Dispatchers.IO) {
        container.requireValid()
        val body = bytes.toRequestBody(contentType.toMediaType())
        request("PUT", path, container.toConfig(), bodyRequest = body).use(::checkResult)
    }

    suspend fun download(path: String): ByteArray = withContext(Dispatchers.IO) {
        container.requireValid()
        request("GET", path, container.toConfig()).use { response ->
            checkResult(response)
            response.body.bytes()
        }
    }

    suspend fun downloadOrNull(path: String): ByteArray? = withContext(Dispatchers.IO) {
        container.requireValid()
        request("GET", path, container.toConfig()).use { response ->
            when {
                response.isSuccessful -> response.body.bytes()
                response.code == 404 -> null
                else -> {
                    checkResult(response)
                    null
                }
            }
        }
    }

    suspend fun exists(path: String): Boolean = withContext(Dispatchers.IO) {
        container.requireValid()
        request("HEAD", path, container.toConfig()).use { response ->
            when {
                response.isSuccessful -> true
                response.code == 404 -> false
                else -> {
                    checkResult(response)
                    false
                }
            }
        }
    }

    suspend fun delete(path: String): Boolean = withContext(Dispatchers.IO) {
        container.requireValid()
        request("DELETE", path, container.toConfig()).use { response ->
            when {
                response.isSuccessful || response.code == 404 -> true
                else -> {
                    checkResult(response)
                    false
                }
            }
        }
    }

    suspend fun list(prefix: String): List<CloudStorageFile> = withContext(Dispatchers.IO) {
        container.requireValid()
        listObjects(container, prefix)
    }

    suspend fun refreshUsage(): Long = withContext(Dispatchers.IO) {
        container.requireValid()
        listObjects(container, "").sumOf { it.size }
    }

    private fun listObjects(container: S3Container, relativePrefix: String): List<CloudStorageFile> {
        val cfg = container.toConfig()
        val rootPrefix = cfg.normalizedPrefix
        val queryPrefix = rootPrefix + relativePrefix.trimStart('/')
        val result = mutableListOf<CloudStorageFile>()
        var token: String? = null
        do {
            val response = request("GET", "", cfg, listBucket = true) {
                addQueryParameter("list-type", "2")
                addQueryParameter("prefix", queryPrefix)
                token?.let { addQueryParameter("continuation-token", it) }
            }
            val body = response.use {
                checkResult(it)
                it.body.text()
            }
            val parsed = parseListObjects(body, rootPrefix)
            result += parsed.files.filterNot { it.isDir }
            token = parsed.nextToken
        } while (!token.isNullOrBlank())
        return result
    }

    private fun request(
        method: String,
        path: String,
        cfg: S3Config,
        listBucket: Boolean = false,
        bodyRequest: RequestBody? = null,
        urlBlock: (HttpUrl.Builder.() -> Unit)? = null
    ): Response {
        val url = buildUrl(cfg, path, listBucket, urlBlock)
        val requestBuilder = Request.Builder().url(url)
        when (method) {
            "GET" -> requestBuilder.get()
            "HEAD" -> requestBuilder.head()
            "DELETE" -> requestBuilder.delete()
            "PUT" -> requestBuilder.put(bodyRequest ?: ByteArray(0).toRequestBody())
            else -> requestBuilder.method(method, bodyRequest)
        }
        val unsigned = requestBuilder.build()
        val payloadHash = if (method == "GET" || method == "HEAD" || method == "DELETE") {
            S3Signer.sha256Hex(ByteArray(0))
        } else {
            S3Signer.payloadHash(unsigned)
        }
        S3Signer.sign(requestBuilder, cfg, method, url, payloadHash)
        return okHttpClient.newCall(requestBuilder.build()).execute()
    }

    private fun buildUrl(
        cfg: S3Config,
        path: String,
        listBucket: Boolean,
        urlBlock: (HttpUrl.Builder.() -> Unit)?
    ): HttpUrl {
        val base = cfg.normalizedEndpoint.toHttpUrl()
        val builder = if (cfg.usePathStyle) {
            base.newBuilder().addPathSegment(cfg.normalizedBucket)
        } else {
            base.newBuilder().host("${cfg.normalizedBucket}.${base.host}")
        }
        if (!listBucket) {
            cfg.remoteKey(path).split('/').filter { it.isNotEmpty() }.forEach(builder::addPathSegment)
        }
        urlBlock?.invoke(builder)
        return builder.build()
    }

    private fun S3Config.remoteKey(path: String): String {
        return normalizedPrefix + path.trimStart('/')
    }

    private fun parseListObjects(xml: String, prefix: String): ListObjectsResult {
        val document = Jsoup.parse(xml, Parser.xmlParser())
        val files = document.getElementsByTag("Contents").mapNotNull { element ->
            val key = element.getElementsByTag("Key").firstOrNull()?.text().orEmpty()
            if (key.isBlank()) return@mapNotNull null
            val relative = key.removePrefix(prefix).trimStart('/')
            if (relative.isBlank()) return@mapNotNull null
            CloudStorageFile(
                path = relative,
                displayName = relative.substringAfterLast('/'),
                size = element.getElementsByTag("Size").firstOrNull()?.text()?.toLongOrNull() ?: 0L,
                lastModify = parseS3Time(element.getElementsByTag("LastModified").firstOrNull()?.text()),
                isDir = false
            )
        }
        val nextToken = document.getElementsByTag("NextContinuationToken").firstOrNull()?.text()
        return ListObjectsResult(files, nextToken)
    }

    private fun parseS3Time(value: String?): Long {
        if (value.isNullOrBlank()) return 0L
        return runCatching {
            ZonedDateTime.parse(value, DateTimeFormatter.ISO_DATE_TIME).toInstant().toEpochMilli()
        }.getOrDefault(0L)
    }

    private fun checkResult(response: Response) {
        if (!response.isSuccessful) {
            throw NoStackTraceException("S3 ${response.code}: ${response.message}")
        }
    }

    private data class ListObjectsResult(
        val files: List<CloudStorageFile>,
        val nextToken: String?
    )
}
