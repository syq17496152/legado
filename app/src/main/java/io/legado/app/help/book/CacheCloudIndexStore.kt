package io.legado.app.help.book

import io.legado.app.data.entities.Book
import io.legado.app.utils.GSON
import io.legado.app.utils.MD5Utils
import io.legado.app.utils.externalFiles
import io.legado.app.utils.fromJsonObject
import io.legado.app.utils.getFile
import splitties.init.appCtx
import java.io.File

data class CacheCloudIndex(
    val version: Int = 1,
    val items: List<CacheCloudIndexItem> = emptyList()
)

data class CacheCloudIndexItem(
    val cacheKey: String = "",
    val groupKey: String = "",
    val sourceKey: String = "",
    val mode: String = "",
    val bookUrl: String = "",
    val origin: String = "",
    val originName: String = "",
    val name: String = "",
    val author: String = "",
    val coverUrl: String? = null,
    val intro: String? = null,
    val latestChapterTitle: String? = null,
    val type: Int = 0,
    val totalChapterCount: Int = 0,
    val cachedChapterCount: Int = 0,
    val zipFileName: String = "",
    val updatedAt: Long = 0L
)

object CacheCloudIndexStore {

    private const val REMOTE_FILE_NAME = "cache_index.json"
    private const val LOCAL_FILE_PREFIX = "cache_index_local_"

    private val rootDir: File
        get() = appCtx.externalFiles.getFile("cachePackages")

    fun remoteFileName(): String = REMOTE_FILE_NAME

    fun readLocal(storageKey: String): List<CacheCloudIndexItem> {
        val file = localFile(storageKey)
        if (!file.exists()) return emptyList()
        return GSON.fromJsonObject<CacheCloudIndex>(file.readText()).getOrNull()?.items.orEmpty()
    }

    fun writeLocal(items: List<CacheCloudIndexItem>, storageKey: String) {
        rootDir.mkdirs()
        localFile(storageKey).writeText(
            GSON.toJson(
                CacheCloudIndex(items = items.sortedByDescending { it.updatedAt })
            )
        )
    }

    fun upsertLocal(item: CacheCloudIndexItem, storageKey: String) {
        val merged = linkedMapOf<String, CacheCloudIndexItem>()
        readLocal(storageKey).forEach { merged[it.cacheKey] = it }
        val current = merged[item.cacheKey]
        if (current == null || item.updatedAt >= current.updatedAt) {
            merged[item.cacheKey] = item
        }
        writeLocal(merged.values.toList(), storageKey)
    }

    fun removeLocal(cacheKey: String, storageKey: String) {
        writeLocal(readLocal(storageKey).filterNot { it.cacheKey == cacheKey }, storageKey)
    }

    private fun localFile(storageKey: String): File {
        val digest = MD5Utils.md5Encode(storageKey.ifBlank { "default" })
        return rootDir.getFile("$LOCAL_FILE_PREFIX$digest.json")
    }
}

fun Book.cacheGroupKey(mode: String): String {
    return listOf(mode, name.trim(), getRealAuthor().trim()).joinToString(separator = "\u001F")
}

fun Book.cacheSourceKey(): String {
    return listOf(origin.ifBlank { originName }, bookUrl).joinToString(separator = "\u001F")
}

fun Book.cacheRemoteKey(mode: String): String {
    return listOf(mode, cacheSourceKey()).joinToString(separator = "\u001F")
}

fun Book.cacheSourceName(): String {
    return when {
        isLocal -> appCtx.getString(io.legado.app.R.string.local)
        originName.isNotBlank() -> originName
        origin.isNotBlank() -> origin
        else -> appCtx.getString(io.legado.app.R.string.unknown)
    }
}
