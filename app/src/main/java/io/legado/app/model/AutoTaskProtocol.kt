package io.legado.app.model

import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import io.legado.app.R
import io.legado.app.api.controller.BookController
import io.legado.app.constant.AppConst
import io.legado.app.constant.NotificationId
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.help.book.isLocal
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonArray
import io.legado.app.utils.fromJsonObject
import io.legado.app.utils.isJsonArray
import io.legado.app.utils.isJsonObject
import io.legado.app.utils.isTrue
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * F-P1-1 自动任务执行结果协议处理
 * 借鉴自阅读T (skybbk1001/legadoT)
 *
 * 解析 JS 脚本返回的 actions JSON，分发到 refreshToc/notify 等动作。
 */
object AutoTaskProtocol {

    data class HandleResult(
        val handled: Boolean,
        val summary: String? = null,
        val details: List<String> = emptyList()
    )

    suspend fun handle(
        result: Any?,
        context: Context,
        taskName: String? = null,
        logger: ((String) -> Unit)? = null
    ): HandleResult {
        val actions = parseActions(result) ?: return HandleResult(false)
        val summaries = mutableListOf<String>()
        for (action in actions) {
            val summary = handleAction(action, context, taskName)
            if (summary.isNotBlank()) {
                summaries.add(summary)
                logger?.invoke(summary)
            }
        }
        val merged = summaries.joinToString(" | ").ifBlank { null }
        return HandleResult(true, merged, summaries)
    }

    private fun handleAction(
        action: Map<String, Any?>,
        context: Context,
        taskName: String?
    ): String {
        val type = getString(action, "type")?.lowercase(Locale.ROOT).orEmpty()
        return when (type) {
            "refreshtoc" -> handleRefreshToc(action, context)
            "notify" -> handleNotify(action, context, taskName)
            else -> throw IllegalArgumentException("未知动作: $type")
        }
    }

    private fun handleRefreshToc(action: Map<String, Any?>, context: Context): String {
        val bookUrl = getString(action, "bookUrl")?.trim().orEmpty()
        if (bookUrl.isBlank()) return "refreshToc 缺少 bookUrl"
        val book = appDb.bookDao.getBook(bookUrl) ?: return "refreshToc 未找到书籍"
        val beforeList = appDb.bookChapterDao.getChapterList(bookUrl)
        val beforeCount = beforeList.size
        val refresh = BookController.refreshToc(mapOf("url" to listOf(bookUrl)))
        if (!refresh.isSuccess) {
            return "《${book.name}》更新失败: ${refresh.errorMsg}"
        }
        val afterList = appDb.bookChapterDao.getChapterList(bookUrl)
        val afterCount = afterList.size
        val newCount = (afterCount - beforeCount).coerceAtLeast(0)
        val notifyObj = getMap(action, "notify")
        val notifyEnabled = notifyObj?.let { getBoolean(it, "enable", defaultValue = true) } ?: false
        val notifyMin = notifyObj?.let { getInt(it, "minCount") } ?: 1
        val cacheObj = getMap(action, "cache")
        val cacheEnabled = cacheObj?.let { getBoolean(it, "enable", defaultValue = false) } ?: false
        val shouldNotify = notifyEnabled && newCount >= notifyMin && newCount > 0
        val shouldCache = cacheEnabled && newCount > 0
        val latestTitle = getLatestChapterTitle(afterList)
        val titleTpl = notifyObj?.let { getString(it, "title") }
        val contentTpl = notifyObj?.let { getString(it, "content") }
        if (shouldNotify) {
            notifyBookUpdate(
                context = context,
                book = book,
                newCount = newCount,
                latestTitle = latestTitle,
                titleTpl = titleTpl,
                contentTpl = contentTpl
            )
        }
        var cacheCount = 0
        if (shouldCache) {
            val start = beforeCount
            val end = afterCount - 1
            if (start <= end && !book.isLocal) {
                CacheBook.start(context, book, start, end)
                cacheCount = end - start + 1
            }
        }
        return buildSummary(book, newCount, shouldNotify, cacheCount)
    }

    private fun handleNotify(
        action: Map<String, Any?>,
        context: Context,
        taskName: String?
    ): String {
        val titleTpl = getString(action, "title")
        val contentTpl = getString(action, "content")
        val time = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date())
        val title = formatCommonTemplate(
            titleTpl ?: context.getString(R.string.auto_task_notify_title),
            taskName,
            time
        )
        val content = formatCommonTemplate(
            contentTpl ?: context.getString(R.string.auto_task_notify_content, taskName ?: ""),
            taskName,
            time
        )
        val level = getString(action, "level")?.lowercase(Locale.ROOT).orEmpty()
        val priority = when (level) {
            "high", "error", "fail", "failed" -> NotificationCompat.PRIORITY_HIGH
            "low" -> NotificationCompat.PRIORITY_LOW
            else -> NotificationCompat.PRIORITY_DEFAULT
        }
        val notifyId = getInt(action, "id")?.let {
            NotificationId.AutoTaskNotifyBase + (it and 0x7fff)
        } ?: run {
            val key = "${taskName.orEmpty()}|$title|$content"
            NotificationId.AutoTaskNotifyBase + (key.hashCode() and 0x7fff)
        }
        val notification = NotificationCompat.Builder(context, AppConst.channelIdWeb)
            .setSmallIcon(R.drawable.ic_web_service_noti)
            .setContentTitle(title)
            .setContentText(content)
            .setStyle(NotificationCompat.BigTextStyle().bigText(content))
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setPriority(priority)
            .build()
        NotificationManagerCompat.from(context).notify(notifyId, notification)
        return "通知: $title"
    }

    private fun notifyBookUpdate(
        context: Context,
        book: Book,
        newCount: Int,
        latestTitle: String?,
        titleTpl: String?,
        contentTpl: String?
    ) {
        val time = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date())
        val defaultTitle = context.getString(R.string.auto_task_book_update_title, book.name)
        val defaultContent = if (latestTitle.isNullOrBlank()) {
            context.getString(R.string.auto_task_book_update_content_count, newCount)
        } else {
            context.getString(R.string.auto_task_book_update_content, newCount, latestTitle)
        }
        val title = formatTemplate(titleTpl ?: defaultTitle, book, newCount, latestTitle, time)
        val content = formatTemplate(contentTpl ?: defaultContent, book, newCount, latestTitle, time)
        val notifyId = NotificationId.AutoTaskBookUpdateBase +
            (book.bookUrl.hashCode() and 0x7fffffff) % 10000
        val notification = NotificationCompat.Builder(context, AppConst.channelIdWeb)
            .setSmallIcon(R.drawable.ic_web_service_noti)
            .setContentTitle(title)
            .setContentText(content)
            .setStyle(NotificationCompat.BigTextStyle().bigText(content))
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .build()
        NotificationManagerCompat.from(context).notify(notifyId, notification)
    }

    private fun buildSummary(
        book: Book,
        newCount: Int,
        notified: Boolean,
        cacheCount: Int
    ): String {
        val name = if (book.name.isBlank()) book.bookUrl else book.name
        val parts = mutableListOf("《$name》")
        if (newCount > 0) {
            parts.add("+$newCount")
        } else {
            parts.add("无更新")
        }
        if (notified) parts.add("通知")
        if (cacheCount > 0) parts.add("缓存$cacheCount")
        return parts.joinToString(" ")
    }

    private fun getLatestChapterTitle(list: List<BookChapter>): String? {
        return list.lastOrNull { !it.isVolume }?.title ?: list.lastOrNull()?.title
    }

    private fun parseActions(result: Any?): List<Map<String, Any?>>? {
        if (result == null) return null
        return when (result) {
            is String -> parseActionsFromJson(result)
            else -> {
                val json = runCatching { GSON.toJson(result) }.getOrNull()
                if (json.isNullOrBlank()) null else parseActionsFromJson(json)
            }
        }
    }

    private fun parseActionsFromJson(text: String): List<Map<String, Any?>>? {
        val trimmed = text.trim()
        return when {
            trimmed.isJsonArray() -> {
                val list = GSON.fromJsonArray<Map<String, Any?>>(trimmed).getOrNull()
                list?.mapNotNull { ensureStringKeyMap(it) }
            }
            trimmed.isJsonObject() -> {
                val map = GSON.fromJsonObject<Map<String, Any?>>(trimmed).getOrNull()
                mapToActions(map)
            }
            else -> null
        }
    }

    private fun mapToActions(root: Map<String, Any?>?): List<Map<String, Any?>>? {
        if (root == null) return null
        val normalized = ensureStringKeyMap(root) ?: return null
        val actionsValue = normalized["actions"]
        return when (actionsValue) {
            is List<*> -> actionsValue.mapNotNull { ensureStringKeyMap(it) }
            else -> if (normalized.containsKey("type")) listOf(normalized) else null
        }
    }

    private fun ensureStringKeyMap(value: Any?): Map<String, Any?>? {
        return when (value) {
            is Map<*, *> -> {
                val out = LinkedHashMap<String, Any?>()
                value.forEach { (k, v) ->
                    if (k != null) out[k.toString()] = v
                }
                out
            }
            else -> null
        }
    }

    private fun getString(map: Map<String, Any?>, vararg keys: String): String? {
        for (key in keys) {
            val value = getValueIgnoreCase(map, key)
            val str = value?.toString()?.trim()
            if (!str.isNullOrBlank()) return str
        }
        return null
    }

    private fun getInt(map: Map<String, Any?>, vararg keys: String): Int? {
        for (key in keys) {
            val value = getValueIgnoreCase(map, key)
            when (value) {
                is Number -> return value.toInt()
                is String -> value.toIntOrNull()?.let { return it }
            }
        }
        return null
    }

    private fun getBoolean(
        map: Map<String, Any?>,
        key: String,
        defaultValue: Boolean
    ): Boolean {
        val value = getValueIgnoreCase(map, key)
        return when (value) {
            is Boolean -> value
            is Number -> value.toInt() != 0
            is String -> value.isTrue(defaultValue)
            else -> defaultValue
        }
    }

    private fun getMap(map: Map<String, Any?>, key: String): Map<String, Any?>? {
        val value = getValueIgnoreCase(map, key)
        return ensureStringKeyMap(value)
    }

    private fun getValueIgnoreCase(map: Map<String, Any?>, key: String): Any? {
        map[key]?.let { return it }
        return map.entries.firstOrNull { it.key.equals(key, true) }?.value
    }

    private fun formatTemplate(
        template: String,
        book: Book,
        newCount: Int,
        latestTitle: String?,
        time: String
    ): String {
        return template
            .replace("{book}", book.name)
            .replace("{author}", book.author)
            .replace("{newCount}", newCount.toString())
            .replace("{chapter}", latestTitle.orEmpty())
            .replace("{time}", time)
    }

    private fun formatCommonTemplate(
        template: String,
        taskName: String?,
        time: String
    ): String {
        return template
            .replace("{task}", taskName.orEmpty())
            .replace("{time}", time)
    }
}
