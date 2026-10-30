package io.legado.app.help.readaloud.role

import io.legado.app.constant.AppLog
import io.legado.app.data.appDb
import io.legado.app.data.entities.BookCharacter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 端侧离线"角色发现"（T4.2 规则兜底 / AD-16 只产出候选）：
 * 用 [NerTextEngine] 的姓氏启发式找候选人名，再用"人名紧跟说话动词/冒号"这一 cue 邻接信号过滤，
 * 只把**命中 cue 的候选**落库成 [BookCharacter] 草稿（autoCreated=true），不猜说话人归属。
 *
 * 设计取舍：这里只负责"书里有哪些人"，段级归属仍由 [OfflineRoleResolver] 负责，职责不混。
 */
object OfflineCharacterDiscovery {

    /** 说话 cue 动词（人名后紧跟这些词视为"该角色开口"） */
    private val speechVerbs = listOf(
        "说", "道", "问", "答", "喊", "叫", "喝", "笑", "叹", "骂", "哼", "嚷", "吼", "叮嘱", "低语"
    )

    /** 冒号紧随（`张三：…` 的剧本式写法）也算强 cue */
    private const val COLON_CN = "："
    private const val COLON_EN = ":"

    const val SOURCE_OFFLINE = "offline-rule-v1"

    data class Candidate(
        val name: String,
        val gender: String,
        val cueCount: Int
    )

    /**
     * 纯函数：从章节段落抽取角色候选（按 cue 出现次数降序，最多 [limit] 个）。
     * 不读库、不联网，可直接单测。
     */
    fun candidates(paragraphs: List<String>, limit: Int = 8): List<Candidate> {
        if (paragraphs.isEmpty() || limit <= 0) return emptyList()
        val cueCounts = LinkedHashMap<String, Int>()
        val firstContext = LinkedHashMap<String, String>()
        paragraphs.forEach { paragraph ->
            if (paragraph.isBlank()) return@forEach
            val mentions = NerTextEngine.findMentions(paragraph, detectUnknown = true)
            mentions.forEach { mention ->
                if (hasSpeechCue(paragraph, mention.end)) {
                    cueCounts[mention.name] = (cueCounts[mention.name] ?: 0) + 1
                    firstContext.putIfAbsent(mention.name, paragraph)
                }
            }
        }
        return cueCounts.entries
            .sortedByDescending { it.value }
            .take(limit)
            .map { entry ->
                // 性别只做弱推断（人名 + 首次出现段落代词/称谓），识别不到就留空交给配声按音色挑
                val gender = BookCharacter.inferGender(entry.key + firstContext[entry.key].orEmpty())
                Candidate(entry.key, gender, entry.value)
            }
    }

    /**
     * 人名之后 0~6 字内是否出现说话 cue。
     * 两种形态：① 紧邻动词（`张三说`）；② 动词后紧跟引语冒号（`张三笑了笑：`）。
     */
    private fun hasSpeechCue(paragraph: String, nameEnd: Int): Boolean {
        if (nameEnd >= paragraph.length) return false
        val tail = paragraph.substring(nameEnd, minOf(paragraph.length, nameEnd + 6))
        if (tail.startsWith(COLON_CN) || tail.startsWith(COLON_EN)) return true
        val verb = speechVerbs
            .map { it to tail.indexOf(it) }
            .filter { it.second >= 0 }
            .minByOrNull { it.second } ?: return false
        if (tail.startsWith(verb.first)) return true
        val colon = tail.indexOfFirst { it == '：' || it == ':' }
        return colon >= 0 && colon > verb.second
    }

    /**
     * 落库候选角色（幂等：已存在同名角色不覆盖），返回新增条数。
     * [bookKey] 用 [io.legado.app.help.book.BookIdentity] 口径（与既有角色表一致）。
     */
    suspend fun ensureCharacters(
        bookKey: String,
        paragraphs: List<String>,
        limit: Int = 8
    ): Int = withContext(Dispatchers.IO) {
        if (bookKey.isBlank()) return@withContext 0
        val found = candidates(paragraphs, limit)
        if (found.isEmpty()) return@withContext 0
        var added = 0
        val now = System.currentTimeMillis()
        found.forEachIndexed { index, candidate ->
            if (runCatching { appDb.bookCharacterDao.getCharacter(bookKey, candidate.name) }
                    .getOrNull() != null
            ) {
                return@forEachIndexed
            }
            runCatching {
                appDb.bookCharacterDao.insertCharacter(
                    BookCharacter(
                        bookUrl = bookKey,
                        name = candidate.name,
                        gender = candidate.gender,
                        roleLevel = if (index == 0) BookCharacter.ROLE_MAIN else BookCharacter.ROLE_NORMAL,
                        sortOrder = index,
                        autoCreated = true,
                        source = SOURCE_OFFLINE,
                        lastDetectedAt = now,
                        createdAt = now,
                        updatedAt = now
                    )
                )
                added++
            }.onFailure { AppLog.put("离线角色落库失败（${candidate.name}）：${it.message}") }
        }
        AppLog.put("离线角色发现：候选 ${found.size} / 新增 $added")
        added
    }
}
