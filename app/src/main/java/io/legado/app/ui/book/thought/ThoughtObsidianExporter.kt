package io.legado.app.ui.book.thought

import android.net.Uri
import io.legado.app.constant.AppLog
import io.legado.app.data.appDb
import io.legado.app.data.entities.BookHighlight
import io.legado.app.help.config.AppConfig
import io.legado.app.utils.FileDoc
import io.legado.app.utils.createFileIfNotExist
import io.legado.app.utils.exists
import io.legado.app.utils.writeText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * B16 批注导出 Obsidian：将书籍划线/批注导出为 .md
 * 数据源：BookHighlight（bookText≈selectedText/note≈thought/time≈createTime）
 * 双模式：0=REST API（Obsidian Local REST API 插件），1=本地 vault 文件（SAF 目录）
 */
object ThoughtObsidianExporter {

    private val timestampFormat = SimpleDateFormat("yyyyMMddHHmmss", Locale.getDefault())

    suspend fun exportBook(
        bookName: String,
        bookAuthor: String,
        highlights: List<BookHighlight>? = null
    ): Result<Unit> = kotlin.runCatching {
        val book = appDb.bookDao.getBook(bookName, bookAuthor)
        val intro = book?.getDisplayIntro()
        val cover = book?.getDisplayCover()
        val bookHighlights = highlights ?: appDb.bookHighlightDao.getByBook(bookName, bookAuthor)
        if (bookHighlights.isEmpty()) {
            return Result.success(Unit)
        }
        val markdown = ThoughtMarkdownGenerator.generate(bookName, bookAuthor, cover, intro, bookHighlights)
        kotlin.runCatching {
            AppLog.putDebugWithTag(
                AppLog.TAG_THOUGHT_EXPORT,
                "Markdown 生成 $bookName ${bookHighlights.size}条",
                level = AppLog.Level.INFO
            )
        }
        val fileName = generateUniqueFileName(bookName)

        when (AppConfig.obsidianExportMethod) {
            0 -> exportViaApi(fileName, markdown)
            1 -> exportViaLocalFile(fileName, markdown)
            else -> throw IllegalArgumentException("Unknown export method")
        }
    }

    /**
     * 在后台协程中静默自动导出，仅在 obsidianAutoExport 开启时执行。
     * 失败留日志（参考源静默，本项目必须留 WARN）。
     */
    fun exportBookAsync(bookName: String, bookAuthor: String) {
        if (!AppConfig.obsidianAutoExport) return
        CoroutineScope(Dispatchers.IO).launch {
            runCatching { exportBook(bookName, bookAuthor) }.onFailure {
                kotlin.runCatching {
                    AppLog.putDebugWithTag(
                        AppLog.TAG_THOUGHT_EXPORT,
                        "自动导出失败 $bookName\n${it.localizedMessage}",
                        it,
                        AppLog.Level.WARN
                    )
                }
            }
        }
    }

    suspend fun exportAll(): Result<Pair<Int, Int>> = kotlin.runCatching {
        val allHighlights = appDb.bookHighlightDao.all
        val grouped = allHighlights.groupBy { it.bookName to it.bookAuthor }
        var successCount = 0
        grouped.forEach { (key, highlights) ->
            val (name, author) = key
            exportBook(name, author, highlights).onSuccess { successCount++ }
        }
        kotlin.runCatching {
            AppLog.putDebugWithTag(
                AppLog.TAG_THOUGHT_EXPORT,
                "全量导出 成功$successCount/${grouped.size}",
                level = AppLog.Level.INFO
            )
        }
        successCount to grouped.size
    }

    private suspend fun exportViaApi(fileName: String, content: String) {
        val apiUrl = AppConfig.obsidianApiUrl
        val apiKey = AppConfig.obsidianApiKey
        val subPath = AppConfig.obsidianVaultSubPath.trim('/')
        val filePath = if (subPath.isEmpty()) fileName else "$subPath/$fileName"
        ObsidianApi.putFile(apiUrl, apiKey, filePath, content).getOrThrow()
        kotlin.runCatching {
            AppLog.putDebugWithTag(
                AppLog.TAG_THOUGHT_EXPORT,
                "API 导出成功 $filePath",
                level = AppLog.Level.INFO
            )
        }
    }

    private fun exportViaLocalFile(fileName: String, content: String) {
        val dirUri = AppConfig.obsidianLocalDirUri
            ?: throw IllegalStateException("Obsidian local directory not configured")
        val dirDoc = FileDoc.fromUri(Uri.parse(dirUri), true)
        val subPath = AppConfig.obsidianVaultSubPath.trim('/')
        val subDirs = if (subPath.isEmpty()) emptyArray() else subPath.split('/').toTypedArray()
        val fileDoc = dirDoc.createFileIfNotExist(fileName, *subDirs)
        fileDoc.writeText(content)
        kotlin.runCatching {
            AppLog.putDebugWithTag(
                AppLog.TAG_THOUGHT_EXPORT,
                "本地导出 $fileName",
                level = AppLog.Level.INFO
            )
        }
    }

    private fun generateUniqueFileName(bookName: String): String {
        val sanitized = bookName.replace(Regex("[/\\\\:*?\"<>|]"), "_")
        val timestamp = timestampFormat.format(Date())
        val baseName = "${sanitized}_${timestamp}"
        var fileName = "$baseName.md"

        if (AppConfig.obsidianExportMethod == 1) {
            val dirUri = AppConfig.obsidianLocalDirUri ?: return fileName
            val dirDoc = FileDoc.fromUri(Uri.parse(dirUri), true)
            val subPath = AppConfig.obsidianVaultSubPath.trim('/')
            val subDirs = if (subPath.isEmpty()) emptyArray() else subPath.split('/').toTypedArray()
            var suffix = 0
            while (true) {
                val checkName = if (suffix == 0) fileName else "$baseName-$suffix.md"
                val exists = dirDoc.exists(checkName, *subDirs)
                if (!exists) {
                    fileName = checkName
                    break
                }
                suffix++
            }
        }
        return fileName
    }
}
