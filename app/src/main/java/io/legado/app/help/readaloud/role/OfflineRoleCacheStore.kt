package io.legado.app.help.readaloud.role

import androidx.annotation.Keep
import com.google.gson.Gson
import io.legado.app.data.appDb
import io.legado.app.data.entities.AiReadAloudRoleCache
import io.legado.app.help.tts.builtin.BuiltinHash
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 离线归因产物（C-9）：字段名与既有 `segmentsFromJson` 解析器完全兼容 */
@Keep
data class OfflineRoleSegment(
    val paragraphIndex: Int = 0,
    val start: Int = 0,
    val end: Int = 0,
    /** narrator / character / thought / other */
    val roleType: String = "other",
    val characterName: String = "",
    val characterId: Long = 0L,
    val confidence: Double = 0.0,
    /** alias / learned-cue / ner-cue / ner / cue-hit / lock / turn-taking / unknown */
    val reason: String = "unknown"
) {
    val recognized: Boolean get() = characterId > 0L || characterName.isNotBlank()
}

@Keep
private data class OfflineSegmentsJson(
    val version: Int = 1,
    val segments: List<OfflineRoleSegment> = emptyList()
)

/**
 * 离线归因缓存（AD-23）：**不走** `AiReadAloudRoleService`（其写入口依赖公网 AI 模型配置，
 * 未配置时恒返 null）。这里自建键 + 直写既有表，字段口径与既有解析器同源。
 */
object OfflineRoleCacheStore {

    /**
     * 归因算法版本（缓存命名空间）。AD-16 主路径改为「端侧 NER cue/PER」后必须升版：
     * 旧版（纯正则 cue）产出的分镜缓存不可复用，否则用户看到的仍是旧判定。
     * v3：字典 cue 收紧为「仅本段名字」（跨段名字交模型窗口判定）⇒ 同输入旧缓存会得到不同结果。
     * v4：新增「跨段近场模型 PER」判定（ner-window）⇒ 无 cue 对白的归属结果不同，必须重算。
     * v5：模型 cue 回看窗口 3→5 词元 + 复杂词性不阻断 ⇒ 更多段落由模型 cue 判定，结果不同。
     * v6：跨段近场 PER 增加"窗口内仅一个已登记角色"稳定闸 ⇒ 多角色场景不再按最近名字乱切。
     * v7：模型 cue 增加"人名+说话动词紧邻"宽松分支（引号紧贴动词时不再退回字典正则）。
     */
    const val MODE_PREFIX = "offline-ner-v7"
    private const val SCHEMA = "playback-v1"

    fun mode(): String = "$MODE_PREFIX|${ReadAloudRolePreprocessor.VERSION}"

    fun contentHash(paragraphs: List<String>): String =
        BuiltinHash.md5Hex(paragraphs.joinToString("\n"))

    fun key(bookKey: String, chapterIndex: Int, paragraphs: List<String>): String =
        BuiltinHash.md5Hex(
            "$MODE_PREFIX|$bookKey|$chapterIndex|${contentHash(paragraphs)}|$SCHEMA"
        )

    fun serialize(segments: List<OfflineRoleSegment>): String =
        Gson().toJson(OfflineSegmentsJson(1, segments))

    fun parse(json: String): List<OfflineRoleSegment> {
        if (json.isBlank()) return emptyList()
        return runCatching {
            Gson().fromJson(json, OfflineSegmentsJson::class.java)?.segments.orEmpty()
        }.getOrDefault(emptyList())
    }

    /** 读取：自有键优先，其次同章节任意可用缓存（`latestUsableByChapter` 无 mode 过滤） */
    suspend fun read(
        bookKey: String,
        chapterIndex: Int,
        paragraphs: List<String>
    ): List<OfflineRoleSegment>? = withContext(Dispatchers.IO) {
        val ownKey = key(bookKey, chapterIndex, paragraphs)
        appDb.aiReadAloudRoleCacheDao.get(ownKey)?.segmentsJson?.let { json ->
            parse(json).takeIf { it.isNotEmpty() }?.let { return@withContext it }
        }
        appDb.aiReadAloudRoleCacheDao.latestUsableByChapter(bookKey, chapterIndex)
            // 只认当前算法版本的缓存：旧版本（正则 cue）结果不得顶替，否则升级后仍显示旧判定
            ?.takeIf { it.mode.startsWith(MODE_PREFIX) }
            ?.segmentsJson
            ?.let { json -> parse(json).takeIf { it.isNotEmpty() } }
    }

    suspend fun write(
        bookKey: String,
        chapterIndex: Int,
        chapterTitle: String,
        paragraphs: List<String>,
        segments: List<OfflineRoleSegment>
    ): Boolean = withContext(Dispatchers.IO) {
        if (segments.isEmpty()) return@withContext false
        val now = System.currentTimeMillis()
        val cacheKey = key(bookKey, chapterIndex, paragraphs)
        val existing = appDb.aiReadAloudRoleCacheDao.get(cacheKey)
        val entity = AiReadAloudRoleCache(
            cacheKey = cacheKey,
            bookUrl = bookKey,
            chapterKey = chapterTitle,
            chapterIndex = chapterIndex,
            chapterTitle = chapterTitle,
            contentHash = contentHash(paragraphs),
            mode = mode(),
            paragraphCount = paragraphs.size,
            status = AiReadAloudRoleCache.STATUS_SUCCESS,
            retryCount = 0,
            lastError = "",
            segmentsJson = serialize(segments),
            createdAt = existing?.createdAt ?: now,
            updatedAt = now
        )
        runCatching { appDb.aiReadAloudRoleCacheDao.upsert(entity) }.isSuccess
    }

    /** 分镜页改段（离线模式）：就地改 + confidence=1.0 */
    suspend fun updateSegmentAssignment(
        bookKey: String,
        chapterIndex: Int,
        paragraphs: List<String>,
        paragraphIndex: Int,
        start: Int,
        end: Int,
        roleType: String,
        characterId: Long,
        characterName: String
    ): Boolean = withContext(Dispatchers.IO) {
        val cacheKey = key(bookKey, chapterIndex, paragraphs)
        val cache = runCatching { appDb.aiReadAloudRoleCacheDao.get(cacheKey) }.getOrNull()
            ?: return@withContext false
        val segments = parse(cache.segmentsJson).toMutableList()
        val index = segments.indexOfFirst {
            it.paragraphIndex == paragraphIndex && it.start == start && it.end == end
        }
        if (index < 0) return@withContext false
        val safeType = roleType.takeIf { it in setOf("narrator", "character", "thought", "other") }
            ?: "character"
        segments[index] = segments[index].copy(
            roleType = safeType,
            characterName = if (safeType == "narrator") "" else characterName.trim(),
            characterId = if (safeType == "narrator") 0L else characterId,
            confidence = 1.0,
            reason = "manual"
        )
        runCatching {
            appDb.aiReadAloudRoleCacheDao.upsert(
                cache.copy(
                    status = AiReadAloudRoleCache.STATUS_SUCCESS,
                    segmentsJson = serialize(segments),
                    mode = mode(),
                    updatedAt = System.currentTimeMillis()
                )
            )
        }.isSuccess
    }

    suspend fun deleteBook(bookKey: String) = withContext(Dispatchers.IO) {
        runCatching { appDb.aiReadAloudRoleCacheDao.deleteByBook(bookKey) }
    }
}
