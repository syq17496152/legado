package io.legado.app.ui.main.bookshelf

import androidx.annotation.Keep
import com.google.gson.reflect.TypeToken
import io.legado.app.constant.AppLog
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookGroup
import io.legado.app.help.config.AppConfig
import io.legado.app.utils.GSON
import io.legado.app.utils.getFile
import splitties.init.appCtx
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.security.MessageDigest

/**
 * 书架列表快照存储（R 批 §3.1.5 `Q6`，移植上游 `BookshelfSnapshotStore`）。
 *
 * 目的：切分组 / 冷启动首帧先用**上次渲染结果**填列表，避免「等 DB flow 发布前的白屏」；
 * DB flow 到达后照常覆盖真值并回写快照（**快照优先 + 失效回源**）。
 *
 * 失效口径：快照键由 `buildKey` 派生（版本 + 样式 + 分组 + 排序 + 标签筛选 + 布局/间距/卡片样式
 * + 数据类开关 + 分组的组标签/隐藏标签 + 分组签名），任一因素变化即得新键 ⇒ 旧快照自然失效，
 * 回落到 DB 查询（**不会出现「配置改了却渲染旧数据」**）。
 *
 * 与上游的差异（如实登记）：上游快照存 Compose item 投影（`display.copy(readConfig = null)`），
 * 本仓渲染单元是全实体 `Book` ⇒ 落盘用 [SnapshotBook] 投影（**只保留书架渲染字段，不含 readConfig**，
 * 原因与已知上限见该类 KDoc），读回时还原为 `Book`。键逐项对齐上游（`style/groupId/sort/tagFilter/
 * 分组签名` + `layout/margin/listStyle/showUnread/showLastUpdateTime`）。
 *
 * 已知上限：快照只保证「更快看到上一帧」，不保证与 DB 实时一致——DB flow 恒在毫秒级内覆盖真值；
 * 若进程被强杀，磁盘快照最多残留 [MAX_DISK_SNAPSHOTS] 份，由 [trimDiskSnapshots] 收口。
 */
object BookshelfSnapshotStore {

    private const val VERSION = 1
    private const val MAX_MEMORY_SNAPSHOTS = 12
    private const val MAX_DISK_SNAPSHOTS = 24

    private val snapshotType = object : TypeToken<Snapshot>() {}.type

    private val rootDir: File
        get() = appCtx.filesDir.getFile("bookshelfSnapshots").apply { mkdirs() }

    /** 内存 LRU（accessOrder=true）：同会话切分组命中内存、零磁盘 I/O。 */
    private val memorySnapshots = object : LinkedHashMap<String, Snapshot>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Snapshot>?): Boolean {
            return size > MAX_MEMORY_SNAPSHOTS
        }
    }

    /**
     * 派生快照键：`{style}-{sha256(原始键).take(32)}`。
     * 原始键含全部影响「列表内容」的因素 ⇒ 任一变更即视为快照失效（回源 DB）。
     */
    fun buildKey(
        style: String,
        groupId: Long,
        sort: Int,
        tagFilter: String,
        groups: List<BookGroup>
    ): String {
        val groupSignature = groups
            .sortedWith(compareBy<BookGroup> { it.order }.thenBy { it.groupId })
            .joinToString(separator = "|") {
                "${it.groupId},${it.groupName},${it.cover.orEmpty()},${it.order}," +
                    "${it.enableRefresh},${it.show},${it.bookSort},${it.onlyUpdateRead}"
            }
        val groupTags = AppConfig.bookshelfGroupTags[groupId]
            .orEmpty()
            .joinToString(separator = "|")
        val hiddenTags = AppConfig.bookshelfHiddenTags[groupId]
            .orEmpty()
            .sorted()
            .joinToString(separator = "|")
        val rawKey = listOf(
            "version=$VERSION",
            "style=$style",
            "groupId=$groupId",
            "sort=$sort",
            "tagFilter=${tagFilter.trim()}",
            "layout=${AppConfig.bookshelfLayout}",
            "margin=${AppConfig.bookshelfMargin}",
            "listStyle=${AppConfig.bookshelfListItemStyle}",
            "showUnread=${AppConfig.showUnread}",
            "showLastUpdateTime=${AppConfig.showLastUpdateTime}",
            "groupTags=$groupTags",
            "hiddenTags=$hiddenTags",
            "groups=$groupSignature"
        ).joinToString(separator = "\n")
        return "$style-${sha256(rawKey).take(32)}"
    }

    /** 读快照（内存优先 → 磁盘）；键不匹配（版本/键漂移）或数据损坏一律返回 null（回源 DB）。 */
    fun read(key: String): List<Book>? {
        getMemory(key)?.let { return it }
        return kotlin.runCatching {
            val file = snapshotFile(key)
            if (!file.exists()) return@runCatching null
            InputStreamReader(file.inputStream(), Charsets.UTF_8).use { reader ->
                (GSON.fromJson(reader, snapshotType) as? Snapshot)
                    ?.takeIf { it.isValid(key) }
                    ?.also { snapshot ->
                        synchronized(memorySnapshots) { memorySnapshots[key] = snapshot }
                    }
                    ?.books
                    ?.map { it.toBook() }
            }
        }.onFailure {
            AppLog.putDebugWithTag(AppLog.TAG_DATA, "书架快照读取失败", it)
        }.getOrNull()
    }

    /** 写快照：内存 LRU 立即更新 + 磁盘 tmp→rename 原子写；空列表视为失效，直接清除。 */
    fun save(key: String, books: List<Book>) {
        if (books.isEmpty()) {
            remove(key)
            return
        }
        val snapshot = Snapshot(
            version = VERSION,
            key = key,
            savedAt = System.currentTimeMillis(),
            books = books.map { it.toSnapshotBook() }
        )
        synchronized(memorySnapshots) {
            memorySnapshots[key] = snapshot
        }
        kotlin.runCatching {
            val target = snapshotFile(key)
            val temp = snapshotFile("$key.tmp")
            OutputStreamWriter(temp.outputStream(), Charsets.UTF_8).use { writer ->
                GSON.toJson(snapshot, snapshotType, writer)
            }
            // 原子替换：先删旧再改名；改名失败（跨卷/占用）退化为覆盖拷贝
            if (target.exists()) {
                target.delete()
            }
            if (!temp.renameTo(target)) {
                temp.copyTo(target, overwrite = true)
                temp.delete()
            }
            trimDiskSnapshots()
        }.onFailure {
            runCatching { snapshotFile("$key.tmp").delete() }
            AppLog.putDebugWithTag(AppLog.TAG_DATA, "书架快照写入失败", it)
        }
    }

    private fun getMemory(key: String): List<Book>? {
        return synchronized(memorySnapshots) {
            memorySnapshots[key]?.takeIf { it.isValid(key) }?.books?.map { it.toBook() }
        }
    }

    private fun remove(key: String) {
        synchronized(memorySnapshots) {
            memorySnapshots.remove(key)
        }
        kotlin.runCatching {
            snapshotFile(key).delete()
            snapshotFile("$key.tmp").delete()
        }
    }

    private fun snapshotFile(key: String): File {
        return rootDir.getFile("${key.replace(Regex("[^A-Za-z0-9_.-]"), "_")}.json")
    }

    /** 磁盘保留最近 [MAX_DISK_SNAPSHOTS] 份（按修改时间倒序），其余删除。 */
    private fun trimDiskSnapshots() {
        rootDir.listFiles()
            ?.filter { it.isFile && it.extension == "json" && !it.name.endsWith(".tmp.json") }
            ?.sortedByDescending { it.lastModified() }
            ?.drop(MAX_DISK_SNAPSHOTS)
            ?.forEach { it.delete() }
    }

    private fun sha256(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
        return digest.joinToString(separator = "") { "%02x".format(it.toInt() and 0xff) }
    }

    /** 仅供测试：清空内存 LRU 与磁盘快照目录（避免用例间互相污染）。 */
    internal fun resetForTest() {
        synchronized(memorySnapshots) {
            memorySnapshots.clear()
        }
        kotlin.runCatching {
            rootDir.listFiles()?.forEach { it.delete() }
        }
    }

    private fun Snapshot.isValid(expectedKey: String): Boolean {
        return version == VERSION && key == expectedKey
    }

    /**
     * 快照载荷。
     * `books: List<SnapshotBook>` 属 Gson 泛型集合字段 ⇒ 必须 `@Keep`（否则 R8 剥离 `Signature`，
     * 反序列化元素类型回落 `Object` ⇒ `LinkedTreeMap` + `ClassCastException`，见 AGENTS 规则 7）。
     */
    @Keep
    private data class Snapshot(
        val version: Int = VERSION,
        val key: String = "",
        val savedAt: Long = 0L,
        val books: List<SnapshotBook> = emptyList()
    )

    /**
     * 书架渲染所需的最小书投影（移植上游「display 投影」口径）。
     *
     * **为什么不能直接存 `Book` 实体**：`Book.readConfig: ReadConfig` 内含 `java.time.LocalDate`，
     * Gson 会为该声明类型解析 `LocalDate` 反射适配器 ⇒ 字段值为 null 也会在
     * `toJson` 阶段抛 `JsonIOException: Failed making field 'java.time.LocalDate#year' accessible`
     * （2026-09-26 单测实证；Android/ART 上因无模块限制可绕过，属「平台侥幸」，release 经 R8 后不可依赖）。
     * ⇒ 快照显式只保留渲染字段，`readConfig` 不入快照。
     *
     * **已知上限（如实披露）**：丢弃 `readConfig` 后，对开启「阅读模拟」的书，首帧未读角标按
     * `totalChapterNum` 计算（不做模拟递减）——自带缓存的瞬态差异，DB flow 到达即纠正。
     */
    @Keep
    private data class SnapshotBook(
        val bookUrl: String = "",
        val origin: String = "",
        val originName: String = "",
        val name: String = "",
        val author: String = "",
        val intro: String? = null,
        val customIntro: String? = null,
        val customTag: String? = null,
        val coverUrl: String? = null,
        val customCoverUrl: String? = null,
        val type: Int = 0,
        val group: Long = 0L,
        val latestChapterTitle: String? = null,
        val latestChapterTime: Long = 0L,
        val lastCheckCount: Int = 0,
        val totalChapterNum: Int = 0,
        val durChapterTitle: String? = null,
        val durChapterIndex: Int = 0,
        val durChapterTime: Long = 0L,
        val canUpdate: Boolean = true,
        val order: Int = 0
    ) {
        fun toBook(): Book = Book(
            bookUrl = bookUrl,
            origin = origin,
            originName = originName,
            name = name,
            author = author,
            intro = intro,
            customIntro = customIntro,
            customTag = customTag,
            coverUrl = coverUrl,
            customCoverUrl = customCoverUrl,
            type = type,
            group = group,
            latestChapterTitle = latestChapterTitle,
            latestChapterTime = latestChapterTime,
            lastCheckCount = lastCheckCount,
            totalChapterNum = totalChapterNum,
            durChapterTitle = durChapterTitle,
            durChapterIndex = durChapterIndex,
            durChapterTime = durChapterTime,
            canUpdate = canUpdate,
            order = order
        )
    }

    private fun Book.toSnapshotBook(): SnapshotBook = SnapshotBook(
        bookUrl = bookUrl,
        origin = origin,
        originName = originName,
        name = name,
        author = author,
        intro = intro,
        customIntro = customIntro,
        customTag = customTag,
        coverUrl = coverUrl,
        customCoverUrl = customCoverUrl,
        type = type,
        group = group,
        latestChapterTitle = latestChapterTitle,
        latestChapterTime = latestChapterTime,
        lastCheckCount = lastCheckCount,
        totalChapterNum = totalChapterNum,
        durChapterTitle = durChapterTitle,
        durChapterIndex = durChapterIndex,
        durChapterTime = durChapterTime,
        canUpdate = canUpdate,
        order = order
    )
}