package io.legado.app.lib.cloud

import android.net.Uri
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.config.AppConfig
import io.legado.app.help.http.okHttpClient
import io.legado.app.help.http.text
import io.legado.app.utils.toRequestBody
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import java.io.File
import java.io.FileOutputStream
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

class S3CloudStorageBackend(
    private val scope: S3ContainerScope = S3ContainerScope.DEFAULT,
    private val fixedContainerId: String? = null
) : CloudStorageBackend {

    private var currentContainerId: String? = null
    private val uploadMutex = Mutex()

    override val isOk: Boolean
        get() = currentContainer() != null

    override suspend fun upConfig() {
        currentContainerId = resolveContainer()?.takeIf { container ->
            container.enabled && runCatching { container.requireValid() }.isSuccess
        }?.id
    }

    override suspend fun check() {
        val container = currentContainer() ?: resolveContainer() ?: throw NoStackTraceException("S3 not configured")
        container.requireValid()
        request("GET", "", container.toConfig(), listBucket = true).use {
            checkResult(it)
        }
    }

    override suspend fun makeDir(path: String): Boolean = true

    override suspend fun listFiles(path: String): List<CloudStorageFile> {
        val container = requireContainer()
        return listFiles(container, path)
    }

    override suspend fun exists(path: String): Boolean {
        return runCatching {
            request("HEAD", path, requireContainer().toConfig()).use { it.isSuccessful }
        }.getOrDefault(false)
    }

    override suspend fun upload(path: String, localPath: String, contentType: String) {
        upload(path, File(localPath), contentType)
    }

    override suspend fun upload(path: String, file: File, contentType: String) {
        withContext(IO) {
            if (!file.exists()) throw NoStackTraceException("file not found")
            guardedUpload(path, file.length()) { container ->
                val body = file.asRequestBody(contentType.toMediaType())
                request("PUT", path, container.toConfig(), bodyRequest = body).use { checkResult(it) }
            }
        }
    }

    override suspend fun upload(path: String, byteArray: ByteArray, contentType: String) {
        withContext(IO) {
            guardedUpload(path, byteArray.size.toLong()) { container ->
                val body = byteArray.toRequestBody(contentType.toMediaType())
                request("PUT", path, container.toConfig(), bodyRequest = body).use { checkResult(it) }
            }
        }
    }

    override suspend fun upload(path: String, uri: Uri, contentType: String) {
        withContext(IO) {
            val size = runCatching {
                splitties.init.appCtx.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length }
            }.getOrNull()?.takeIf { it >= 0 }
                ?: throw NoStackTraceException("S3 upload size unknown")
            guardedUpload(path, size) { container ->
                val body = uri.toRequestBody(contentType.toMediaType())
                request("PUT", path, container.toConfig(), bodyRequest = body).use { checkResult(it) }
            }
        }
    }

    override suspend fun download(path: String): ByteArray {
        return withContext(IO) {
            request("GET", path, requireContainer().toConfig()).use {
                checkResult(it)
                it.body.bytes()
            }
        }
    }

    override suspend fun downloadTo(path: String, file: File, replaceExisting: Boolean) {
        withContext(IO) {
            if (file.exists() && !replaceExisting) return@withContext
            file.parentFile?.mkdirs()
            request("GET", path, requireContainer().toConfig()).use { response ->
                checkResult(response)
                FileOutputStream(file).use { output ->
                    response.body.byteStream().copyTo(output)
                }
            }
        }
    }

    override suspend fun delete(path: String): Boolean {
        val container = requireContainer()
        val oldSize = remoteObjectSize(container, path)
        return request("DELETE", path, container.toConfig()).use {
            checkResult(it)
            it.isSuccessful.also { success ->
                if (success && oldSize > 0) {
                    S3ContainerManager.adjustContainerUsage(container.id, -oldSize)
                }
            }
        }
    }

    suspend fun refreshUsage(containerId: String): S3Container {
        val container = S3ContainerManager.container(containerId)
            ?: throw NoStackTraceException("S3 container not found")
        container.requireValid()
        val usedBytes = listAllObjects(container).sumOf { it.size }
        S3ContainerManager.saveContainerUsage(container.id, usedBytes)
        return S3ContainerManager.container(container.id) ?: container.copy(usedBytes = usedBytes)
    }

    suspend fun listFiles(containerId: String, path: String): List<CloudStorageFile> {
        val container = S3ContainerManager.container(containerId)
            ?: throw NoStackTraceException("S3 container not found")
        return listFiles(container, path)
    }

    suspend fun download(containerId: String, path: String): ByteArray {
        val container = S3ContainerManager.container(containerId)
            ?: throw NoStackTraceException("S3 container not found")
        return withContext(IO) {
            request("GET", path, container.toConfig()).use {
                checkResult(it)
                it.body.bytes()
            }
        }
    }

    suspend fun downloadTo(containerId: String, path: String, file: File, replaceExisting: Boolean = true) {
        val container = S3ContainerManager.container(containerId)
            ?: throw NoStackTraceException("S3 container not found")
        withContext(IO) {
            if (file.exists() && !replaceExisting) return@withContext
            file.parentFile?.mkdirs()
            request("GET", path, container.toConfig()).use { response ->
                checkResult(response)
                FileOutputStream(file).use { output ->
                    response.body.byteStream().copyTo(output)
                }
            }
        }
    }

    fun withScope(scope: S3ContainerScope): S3CloudStorageBackend {
        return S3CloudStorageBackend(scope)
    }

    private suspend fun guardedUpload(path: String, newSize: Long, upload: (S3Container) -> Unit) {
        uploadMutex.withLock {
            val candidates = uploadCandidates(path, newSize)
            var lastFull: S3CapacityFullException? = null
            candidates.forEach { container ->
                val oldSize = remoteObjectSize(container, path)
                val delta = newSize - oldSize
                if (!container.canFit(delta)) {
                    S3ContainerManager.markFull(container.id, true)
                    lastFull = S3CapacityFullException("S3 container is full: ${S3ContainerManager.displayLabel(container)}", scope)
                    return@forEach
                }
                upload(container)
                S3ContainerManager.adjustContainerUsage(container.id, delta)
                currentContainerId = container.id
                if (container.id != S3ContainerManager.selectedContainerId(scope)) {
                    S3ContainerManager.selectContainer(scope, container.id)
                }
                return
            }
            throw lastFull ?: S3CapacityFullException(scope = scope)
        }
    }

    private fun uploadCandidates(path: String, newSize: Long): List<S3Container> {
        val current = requireContainer()
        if (!fixedContainerId.isNullOrBlank()) return listOf(current)
        if (!AppConfig.autoSwitchS3Container) return listOf(current)
        val containers = S3ContainerManager.listContainers().filter { container ->
            container.enabled && runCatching { container.requireValid() }.isSuccess
        }
        if (containers.isEmpty()) return listOf(current)
        val startIndex = containers.indexOfFirst { it.id == current.id }.takeIf { it >= 0 } ?: 0
        return containers.drop(startIndex) + containers.take(startIndex)
    }

    private fun currentContainer(): S3Container? {
        return S3ContainerManager.container(currentContainerId) ?: resolveContainer()?.also {
            currentContainerId = it.id
        }
    }

    private fun resolveContainer(): S3Container? {
        return S3ContainerManager.container(fixedContainerId)
            ?: S3ContainerManager.selectedContainer(scope)
    }

    private fun requireContainer(): S3Container {
        val container = currentContainer() ?: throw NoStackTraceException("S3 not configured")
        if (!container.enabled) throw NoStackTraceException("S3 container disabled")
        container.requireValid()
        return container
    }

    private fun listFiles(container: S3Container, path: String): List<CloudStorageFile> {
        val cfg = container.toConfig()
        val prefix = cfg.remoteKey(path).ensureTrailingSlashIfNeeded(path)
        val result = mutableListOf<CloudStorageFile>()
        var token: String? = null
        do {
            val response = request("GET", "", cfg, listBucket = true) {
                addQueryParameter("list-type", "2")
                addQueryParameter("prefix", prefix)
                addQueryParameter("delimiter", "/")
                token?.let { addQueryParameter("continuation-token", it) }
            }
            val body = response.use {
                checkResult(it)
                it.body.text()
            }
            val parsed = parseListObjects(body, prefix)
            result += parsed.files
            token = parsed.nextToken
        } while (!token.isNullOrBlank())
        return result
    }

    private fun listAllObjects(container: S3Container): List<CloudStorageFile> {
        val cfg = container.toConfig()
        val prefix = cfg.normalizedPrefix
        val result = mutableListOf<CloudStorageFile>()
        var token: String? = null
        do {
            val response = request("GET", "", cfg, listBucket = true) {
                addQueryParameter("list-type", "2")
                addQueryParameter("prefix", prefix)
                token?.let { addQueryParameter("continuation-token", it) }
            }
            val body = response.use {
                checkResult(it)
                it.body.text()
            }
            val parsed = parseListObjects(body, prefix)
            result += parsed.files.filterNot { it.isDir }
            token = parsed.nextToken
        } while (!token.isNullOrBlank())
        return result
    }

    private fun remoteObjectSize(container: S3Container, path: String): Long {
        return request("HEAD", path, container.toConfig()).use { response ->
            when {
                response.isSuccessful -> response.header("Content-Length")?.toLongOrNull() ?: 0L
                response.code == 404 -> 0L
                else -> {
                    checkResult(response)
                    0L
                }
            }
        }
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

    private fun String.ensureTrailingSlashIfNeeded(path: String): String {
        return if (path.isBlank() || path.endsWith("/")) this else "$this/"
    }

    private fun parseListObjects(xml: String, prefix: String): ListObjectsResult {
        val document = Jsoup.parse(xml, Parser.xmlParser())
        val files = document.getElementsByTag("Contents").mapNotNull { element ->
            val key = element.getElementsByTag("Key").firstOrNull()?.text() ?: return@mapNotNull null
            if (key == prefix) return@mapNotNull null
            val displayName = key.removePrefix(prefix).trimEnd('/').substringAfterLast("/")
            if (displayName.isBlank()) return@mapNotNull null
            val size = element.getElementsByTag("Size").firstOrNull()?.text()?.toLongOrNull() ?: 0
            val lastModify = element.getElementsByTag("LastModified").firstOrNull()?.text()
                ?.let { runCatching { ZonedDateTime.parse(it, DateTimeFormatter.ISO_DATE_TIME).toInstant().toEpochMilli() }.getOrNull() }
                ?: 0
            CloudStorageFile(
                path = key,
                displayName = displayName,
                size = size,
                lastModify = lastModify,
                isDir = false
            )
        }
        val commonPrefixes = document.getElementsByTag("CommonPrefixes").mapNotNull { element ->
            val key = element.getElementsByTag("Prefix").firstOrNull()?.text() ?: return@mapNotNull null
            val displayName = key.removePrefix(prefix).trimEnd('/').substringAfterLast("/")
            if (displayName.isBlank()) return@mapNotNull null
            CloudStorageFile(path = key, displayName = displayName, isDir = true)
        }
        val nextToken = document.getElementsByTag("NextContinuationToken").firstOrNull()?.text()
        return ListObjectsResult(commonPrefixes + files, nextToken)
    }

    private fun checkResult(response: Response) {
        if (response.isSuccessful) return
        val body = runCatching { response.body.string() }.getOrDefault("")
        val message = when (response.code) {
            301, 400 -> "S3 endpoint, bucket or region error"
            403 -> "S3 authorization or signature error"
            404 -> "S3 object not found"
            else -> response.message.ifBlank { "S3 request failed" }
        }
        throw NoStackTraceException("$message (${response.code})${body.takeIf { it.isNotBlank() }?.let { "\n$it" }.orEmpty()}")
    }

    private data class ListObjectsResult(
        val files: List<CloudStorageFile>,
        val nextToken: String?
    )
}
