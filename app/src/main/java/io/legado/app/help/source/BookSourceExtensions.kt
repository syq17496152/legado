package io.legado.app.help.source

import com.script.rhino.runScriptWithContext
import io.legado.app.constant.BookSourceType
import io.legado.app.constant.BookType
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.BookSourcePart
import io.legado.app.data.entities.rule.ExploreKind
import io.legado.app.ui.main.explore.ExploreAdapter.Companion.exploreInfoMapList
import io.legado.app.utils.ACache
import io.legado.app.utils.GSON
import io.legado.app.utils.InfoMap
import io.legado.app.utils.MD5Utils
import io.legado.app.utils.fromJsonArray
import io.legado.app.utils.isJsonArray
import io.legado.app.utils.printOnDebug
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * 采用md5作为key可以在分类修改后自动重新计算,不需要手动刷新
 */

private val mutexMap by lazy { ConcurrentHashMap<String, Mutex>() }
private val exploreKindsMap by lazy { ConcurrentHashMap<String, List<ExploreKind>>() }
private val aCache by lazy { ACache.get("explore") }

private fun BookSource.getExploreKindsKey(): String {
    val sourceState = listOf(
        MD5Utils.md5Encode16(getVariable()),
        get("type"),
        get("order"),
        get("hostIndex"),
        get("host"),
        lastHost.orEmpty()
    ).joinToString("|")
    return MD5Utils.md5Encode(
        listOf(
            bookSourceUrl,
            exploreUrl.orEmpty(),
            jsLib.orEmpty(),
            lastUpdateTime.toString(),
            sourceState
        ).joinToString("\n")
    )
}

private fun BookSourcePart.getExploreKindsKey(): String {
    return getBookSource()!!.getExploreKindsKey()
}

/**
 * 校验发现分类规则串是否可用：trim 后非空且不为 null/undefined（忽略大小写）
 */
private fun String.isValidExploreKindsRule(): Boolean {
    val trimmed = trim()
    if (trimmed.isEmpty()) {
        return false
    }
    return when (trimmed.lowercase()) {
        "null", "undefined" -> false
        else -> true
    }
}

suspend fun BookSourcePart.exploreKinds(): List<ExploreKind> {
    return getBookSource()!!.exploreKinds()
}

suspend fun BookSource.exploreKinds(): List<ExploreKind> {
    val exploreKindsKey = getExploreKindsKey()
    exploreKindsMap[exploreKindsKey]?.let { return it }
    val exploreUrl = exploreUrl
    if (exploreUrl.isNullOrBlank()) {
        return emptyList()
    }
    val mutex = mutexMap.computeIfAbsent(bookSourceUrl) { Mutex() }
    mutex.withLock {
        exploreKindsMap[exploreKindsKey]?.let { return it }
        val kinds = arrayListOf<ExploreKind>()
        withContext(Dispatchers.IO) {
            kotlin.runCatching {
                val ruleStr = when {
                    exploreUrl.startsWith("@js:", true) -> {
                        aCache.getAsString(exploreKindsKey)?.takeIf { it.isNotBlank() } ?: run {
                            val exploreInfoMap = exploreInfoMapList[bookSourceUrl] ?: InfoMap(bookSourceUrl).also {
                                exploreInfoMapList.put(bookSourceUrl, it)
                            }
                            runScriptWithContext {
                                evalJS(exploreUrl.substring(4)) {
                                    put("infoMap", exploreInfoMap)
                                }.toString().trim()
                            }.also {
                                if (it.isValidExploreKindsRule()) {
                                    aCache.put(exploreKindsKey, it)
                                }
                            }
                        }
                    }
                    exploreUrl.startsWith("<js>", true) -> {
                        aCache.getAsString(exploreKindsKey)?.takeIf { it.isNotBlank() } ?: run {
                            val exploreInfoMap = exploreInfoMapList[bookSourceUrl] ?: InfoMap(bookSourceUrl).also {
                                exploreInfoMapList.put(bookSourceUrl, it)
                            }
                            runScriptWithContext {
                                evalJS(exploreUrl.substring(4, exploreUrl.lastIndexOf("<"))) {
                                    put("infoMap", exploreInfoMap)
                                }.toString().trim()
                            }.also {
                                if (it.isValidExploreKindsRule()) {
                                    aCache.put(exploreKindsKey, it)
                                }
                            }
                        }
                    }
                    else -> exploreUrl
                }
                if (!ruleStr.isValidExploreKindsRule()) {
                    return@runCatching
                }
                if (ruleStr.isJsonArray()) {
                    GSON.fromJsonArray<ExploreKind>(ruleStr).getOrThrow().let {
                        kinds.addAll(it)
                    }
                } else {
                    ruleStr.split("(&&|\n)+".toRegex()).forEach { kindStr ->
                        val kindCfg = kindStr.split("::")
                        kinds.add(ExploreKind(kindCfg.first(), kindCfg.getOrNull(1)))
                    }
                }
            }.onFailure {
                kinds.add(ExploreKind("ERROR:${it.localizedMessage}", it.stackTraceToString()))
                it.printOnDebug()
            }
        }
        exploreKindsMap[exploreKindsKey] = kinds
        return kinds
    }
}

suspend fun BookSourcePart.clearExploreKindsCache() {
    withContext(Dispatchers.IO) {
        val exploreKindsKey = getExploreKindsKey()
        aCache.remove(exploreKindsKey)
        exploreKindsMap.remove(exploreKindsKey)
    }
}

suspend fun BookSource.clearExploreKindsCache() {
    withContext(Dispatchers.IO) {
        val exploreKindsKey = getExploreKindsKey()
        aCache.remove(exploreKindsKey)
        exploreKindsMap.remove(exploreKindsKey)
    }
}

fun BookSource.exploreKindsJson(): String {
    val exploreKindsKey = getExploreKindsKey()
    return aCache.getAsString(exploreKindsKey)?.takeIf { it.isJsonArray() }
        ?: exploreUrl.takeIf { it.isJsonArray() }
        ?: ""
}

fun BookSource.getBookType(): Int {
    return when (bookSourceType) {
        BookSourceType.file -> BookType.text or BookType.webFile
        BookSourceType.image -> BookType.image
        BookSourceType.audio -> BookType.audio
        BookSourceType.video -> BookType.video
        else -> BookType.text
    }
}

/**
 * 视频身份统一判定（video-source-dual-track AD-02 / AD-05）
 *
 * 语义：**任一侧表态 video 即真**——运行时书籍类型的 video 位，或源静态声明的视频类型；
 * 两侧均不表态返回 false（回落非视频链路，文本/音频/图片/文件/本地书零误判）。
 *
 * 为什么不能只看静态声明：部分自定义源（以卷名为线路的写法）`bookSourceType = 0`，
 * 其视频身份由目录规则 JS 在运行时写入 `book.type = BookType.video`；若只看静态声明，
 * 判定会整链落空（目录不即时加载 → 线路不建立 → 播放报"未找到章节"）。
 *
 * 注意：`BookType` 是位标志、`BookSourceType` 是独立枚举——两者 video 数值同为 4 但语义不同，
 * 故位侧必须用位与、静态侧用等值比较，不可互换。
 *
 * @param bookType 调用方取到的运行时书籍类型（`Book.type` / `SearchBook.type`）；
 *                 取不到时传 null，退化为静态判定（不放大也不缩小语义）
 */
fun BookSource?.isVideoSource(bookType: Int?): Boolean {
    return ((bookType ?: 0) and BookType.video) > 0 ||
        this?.bookSourceType == BookSourceType.video
}
