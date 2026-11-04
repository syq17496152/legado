package io.legado.app.help.readaloud.role

import androidx.annotation.Keep
import io.legado.app.help.readaloud.role.OfflineRoleSegment
import java.security.MessageDigest

/**
 * 章节剧本（AD-03）：段级角色 + 情绪 + 置信度 + 来源，作为朗读侧的**叠加层**，不替换既有
 * `OfflineRoleSegment` / `ReadAloudSpeechPlanItem`。
 *
 * 缓存键带 `contentHash + ruleVersion + schemaVersion`：作者改文、规则升级、schema 升级都必须能
 * 判定「旧结果不可复用」，否则会把旧判定污染到新正文上。
 */
@Keep
data class ChapterScript(
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val bookKey: String = "",
    val chapterIndex: Int = -1,
    /** 章节正文指纹（sha256 前 16 位） */
    val contentHash: String = "",
    /** 逐段指纹：用于判断「变化率 > 10%」，单章 contentHash 无法算变化率 */
    val paragraphHashes: List<String> = emptyList(),
    val ruleVersion: String = "",
    val generatedAt: Long = 0,
    /** rule | local-ai | online | mixed */
    val source: String = "rule",
    val segments: List<ScriptSegment> = emptyList()
) {

    companion object {
        /** 与剧本提示词/规则版本绑定；升级必须全量重算 */
        const val CURRENT_SCHEMA_VERSION = 3

        /** 正文变化率超过该比例才重算（作者小改错字不重跑，避免烧电） */
        const val REBUILD_RATE_THRESHOLD = 0.10

        /** 变化率 = 逐段指纹不同的段数 / 两版最大段数（0 段时视为 0） */
        fun changeRate(old: List<String>, new: List<String>): Double {
            val total = maxOf(old.size, new.size)
            if (total == 0) return 0.0
            var changed = 0
            for (index in 0 until total) {
                if (old.getOrNull(index) != new.getOrNull(index)) changed++
            }
            return changed.toDouble() / total
        }
    }

    /**
     * 旧剧本是否可复用（纯函数）：
     * - schemaVersion 变了 ⇒ 不可复用（规则/提示词已升级）
     * - 段指纹缺失（旧版文件）⇒ 不可复用（无法判断变化率，宁可重算）
     * - 变化率 > 10% ⇒ 不可复用
     */
    fun reusableFor(newParagraphHashes: List<String>): Boolean {
        if (schemaVersion != CURRENT_SCHEMA_VERSION) return false
        if (paragraphHashes.isEmpty()) return false
        return changeRate(paragraphHashes, newParagraphHashes) <= REBUILD_RATE_THRESHOLD
    }
}

@Keep
data class ScriptSegment(
    val paragraphIndex: Int = 0,
    val start: Int = 0,
    val end: Int = 0,
    /** narration / dialogue / thought */
    val roleType: String = "dialogue",
    val characterId: Long = 0L,
    val characterName: String = "",
    val emotion: String = "",
    val confidence: Double = 0.0,
    /** rule / alias / cue / ner / lock / turn / local-ai / online / user-frozen */
    val source: String = "rule",
    val frozen: Boolean = false
) {

    val recognized: Boolean get() = characterId > 0L || characterName.isNotBlank()

    /**
     * 是否需要模型仲裁（AD-04）：低置信 / 轮转推断 / 未知归属。
     * 阈值 0.70 是保守口径——高置信规则结果不送模型，省 token 且避免覆盖正确答案。
     */
    val needsArbitration: Boolean
        get() = !frozen && (confidence < 0.70 || source == "turn" || source == "unknown")
}

/**
 * 剧本构建（3.2，纯函数）：规则归因 → draft；用户手改（override）短路优先。
 * 不读写文件、不调模型，便于 JVM 单测锁定字段口径。
 */
object ChapterScriptBuilder {

    /** 规则 reason → 剧本 source（保留可诊断的细分来源） */
    fun sourceOf(reason: String): String = when (reason) {
        "alias" -> "alias"
        "learned-cue", "cue-hit" -> "cue"
        "ner-cue", "ner", "ner-window", "window" -> "ner"
        "lock" -> "lock"
        "turn-taking", "turn" -> "turn"
        "manual" -> "user-frozen"
        else -> "unknown"
    }

    /** 既有 roleType（narrator/character/thought/other）→ 剧本 roleType（narration/dialogue/thought） */
    fun roleTypeOf(raw: String): String = when (raw) {
        "narrator", "narration" -> "narration"
        "thought" -> "thought"
        else -> "dialogue"
    }

    /**
     * 构建草稿（未合并 override）。
     *
     * @param overrides 用户手改（键见 [ScriptOverrideStore.keyOf]）；命中即 `frozen=true, confidence=1.0`
     */
    fun build(
        bookKey: String,
        chapterIndex: Int,
        paragraphs: List<String>,
        segments: List<OfflineRoleSegment>,
        ruleVersion: String,
        overrides: Map<String, ScriptSegment> = emptyMap(),
        now: Long = System.currentTimeMillis()
    ): ChapterScript {
        val mapped = segments.map { segment ->
            val base = ScriptSegment(
                paragraphIndex = segment.paragraphIndex,
                start = segment.start,
                end = segment.end,
                roleType = roleTypeOf(segment.roleType),
                characterId = segment.characterId,
                characterName = segment.characterName,
                confidence = segment.confidence,
                source = sourceOf(segment.reason)
            )
            val override = overrides[ScriptOverrideStore.keyOf(segment)]
            if (override != null) {
                base.copy(
                    roleType = override.roleType,
                    characterId = override.characterId,
                    characterName = override.characterName,
                    confidence = 1.0,
                    source = "user-frozen",
                    frozen = true
                )
            } else {
                base
            }
        }
        return ChapterScript(
            schemaVersion = ChapterScript.CURRENT_SCHEMA_VERSION,
            bookKey = bookKey,
            chapterIndex = chapterIndex,
            contentHash = hashOf(paragraphs),
            paragraphHashes = paragraphs.map { hashOf(it) },
            ruleVersion = ruleVersion,
            generatedAt = now,
            source = "rule",
            segments = mapped
        )
    }

    /** 单段指纹：对空行也必须稳定（空串哈希固定，不能跳过 ⇒ 否则 idx 对不齐） */
    internal fun hashOf(text: String): String = ScriptHash.sha256Hex(text)

    /** 章节指纹：与逐段指纹同源，保证「单段没变 ⇒ 章节 hash 也没变」 */
    internal fun hashOf(paragraphs: List<String>): String =
        ScriptHash.sha256Hex(paragraphs.joinToString("\n"))
}

/** 剧本哈希（sha256 前 16 位：短、足够区分、便于日志） */
object ScriptHash {
    fun sha256Hex(text: String): String = runCatching {
        val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        digest.joinToString("") { "%02x".format(it) }.take(16)
    }.getOrDefault("")
}
