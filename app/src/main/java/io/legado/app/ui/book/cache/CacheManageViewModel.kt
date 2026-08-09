package io.legado.app.ui.book.cache

import android.app.Application
import io.legado.app.R
import io.legado.app.base.BaseViewModel
import io.legado.app.constant.AppLog
import io.legado.app.help.book.BookHelp
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.utils.FileUtils
import io.legado.app.utils.externalCache
import kotlinx.coroutines.Dispatchers.IO
import splitties.init.appCtx
import java.io.File
import java.util.Locale
import kotlin.math.max

data class CacheStorageDetail(
    val nameRes: Int,
    val bytes: Long,
    val deletePaths: List<String>
)

class CacheManageViewModel(application: Application) : BaseViewModel(application) {

    /**
     * 构建存储分项统计（目录级），B11 缓存分项
     * 维度：书籍文本(book_cache)/视频(exoplayer)/音频(httpTTS)
     */
    fun buildStorageBreakdown(): Coroutine<List<CacheStorageDetail>> {
        return execute(context = IO) {
            val books = CacheStorageDetail(
                R.string.cache_stats_books,
                directorySize(File(BookHelp.cachePath)),
                listOf(BookHelp.cachePath)
            )
            val video = CacheStorageDetail(
                R.string.cache_stats_video,
                directorySize(File(appCtx.externalCache, "exoplayer")),
                listOf(File(appCtx.externalCache, "exoplayer").absolutePath)
            )
            val audio = CacheStorageDetail(
                R.string.cache_stats_audio,
                directorySize(File(appCtx.cacheDir, "httpTTS")),
                listOf(File(appCtx.cacheDir, "httpTTS").absolutePath)
            )
            val total = books.bytes + video.bytes + audio.bytes
            kotlin.runCatching {
                AppLog.putDebugWithTag(
                    AppLog.TAG_CACHE_STATS,
                    "分项统计: 书籍=${formatBytes(books.bytes)} 音频=${formatBytes(audio.bytes)} " +
                        "视频=${formatBytes(video.bytes)} 总计=${formatBytes(total)}",
                    level = AppLog.Level.INFO
                )
            }
            listOf(books, video, audio)
        }
    }

    /**
     * 删除分项缓存。视频目录在播放中时加锁拒绝删除
     */
    fun deleteStorageTarget(detail: CacheStorageDetail): Coroutine<Boolean> {
        return execute(context = IO) {
            if (detail.deletePaths.any { it.contains("exoplayer") }) {
                if (isVideoPlaying()) {
                    return@execute false
                }
            }
            val before = detail.deletePaths.sumOf { directorySize(File(it)) }
            detail.deletePaths.forEach { path ->
                FileUtils.delete(path, deleteRootDir = true)
            }
            kotlin.runCatching {
                AppLog.putDebugWithTag(
                    AppLog.TAG_CACHE_STATS,
                    "分项删除完成 target=${detail.nameRes} 释放=${formatBytes(before)}",
                    level = AppLog.Level.INFO
                )
            }
            true
        }.onError {
            kotlin.runCatching {
                AppLog.putDebugWithTag(
                    AppLog.TAG_CACHE_STATS,
                    "分项删除失败 target=${detail.nameRes}",
                    it,
                    AppLog.Level.ERROR
                )
            }
        }
    }

    private fun isVideoPlaying(): Boolean {
        return kotlin.runCatching {
            com.shuyu.gsyvideoplayer.GSYVideoManager.instance()?.isPlaying() == true
        }.getOrDefault(false)
    }
}

internal fun directorySize(file: File): Long {
    if (!file.exists()) return 0L
    if (file.isFile) return max(0L, file.length())
    return kotlin.runCatching {
        file.listFiles()?.sumOf { directorySize(it) } ?: 0L
    }.getOrDefault(0L)
}

internal fun formatBytes(bytes: Long): String {
    val kb = 1024.0
    val mb = kb * 1024.0
    val gb = mb * 1024.0
    return when {
        bytes >= gb -> String.format(Locale.getDefault(), "%.2f GB", bytes / gb)
        bytes >= mb -> String.format(Locale.getDefault(), "%.2f MB", bytes / mb)
        bytes >= kb -> String.format(Locale.getDefault(), "%.1f KB", bytes / kb)
        else -> "$bytes B"
    }
}
