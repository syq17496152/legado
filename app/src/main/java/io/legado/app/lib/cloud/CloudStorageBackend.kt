package io.legado.app.lib.cloud

import android.net.Uri
import java.io.File

interface CloudStorageBackend {
    val isOk: Boolean
    val isJianGuoYun: Boolean get() = false

    suspend fun upConfig()
    suspend fun check()
    suspend fun makeDir(path: String): Boolean
    suspend fun listFiles(path: String): List<CloudStorageFile>
    suspend fun exists(path: String): Boolean
    suspend fun upload(path: String, localPath: String, contentType: String = DEFAULT_CONTENT_TYPE)
    suspend fun upload(path: String, file: File, contentType: String = DEFAULT_CONTENT_TYPE)
    suspend fun upload(path: String, byteArray: ByteArray, contentType: String = DEFAULT_CONTENT_TYPE)
    suspend fun upload(path: String, uri: Uri, contentType: String = DEFAULT_CONTENT_TYPE)
    suspend fun download(path: String): ByteArray
    suspend fun downloadTo(path: String, file: File, replaceExisting: Boolean = true)
    suspend fun delete(path: String): Boolean

    companion object {
        const val DEFAULT_CONTENT_TYPE = "application/octet-stream"
    }
}

