package io.legado.app.help.readaloud

import io.legado.app.data.appDb
import io.legado.app.data.entities.BookCharacter
import io.legado.app.help.ai.AiReadAloudRoleService
import io.legado.app.help.readaloud.role.ChapterScript
import io.legado.app.help.readaloud.role.ChapterScriptPlayback
import io.legado.app.help.readaloud.role.ScriptSegment
import io.legado.app.help.readaloud.speech.SpeechRoute
import io.legado.app.help.readaloud.speech.SpeechRouteSanitizer
import io.legado.app.ui.book.read.page.entities.ReadAloudCue
import io.legado.app.ui.book.read.page.entities.TextChapter

data class ReadAloudSpeechPlan(
    val cues: List<ReadAloudCue>,
    val routes: List<SpeechRoute?>,
    val items: List<ReadAloudSpeechPlanItem>
)

data class ReadAloudSpeechPlanItem(
    val index: Int,
    val cue: ReadAloudCue,
    val route: SpeechRoute?,
    val roleType: String,
    val characterId: Long,
    val characterName: String,
    val avatar: String,
    val emotionName: String,
    val roleLevel: Int = BookCharacter.ROLE_NORMAL,
    val leftSide: Boolean,
    val narrator: Boolean,
    val sourceCueIndex: Int,
    val sourceStart: Int,
    val sourceEnd: Int
)

object ReadAloudSpeechPlanner {

    private const val LOCAL_DIALOGUE_CUE_GAP = 2

    fun planKey(
        bookUrl: String?,
        chapter: TextChapter?,
        cues: List<ReadAloudCue>,
        roleCacheKey: String? = null,
        scriptKey: String? = null
    ): String {
        return buildString {
            append(bookUrl.orEmpty())
            append('|')
            append(chapter?.chapter?.index ?: -1)
            append('|')
            append(chapter?.chapter?.url.orEmpty())
            append('|')
            append(cues.size)
            append('|')
            append(cues.firstOrNull()?.key.orEmpty())
            append('|')
            append(cues.lastOrNull()?.key.orEmpty())
            append('|')
            append(cues.sumOf { it.text.length })
            append('|')
            append(roleCacheKey.orEmpty())
            append('|')
            append(scriptKey.orEmpty())
        }
    }

    /**
     * 朗读计划（3.6）：段来源按「ChapterScript → OfflineRoleCacheStore → 规则」读取。
     *
     * - [chapterScript] 由调用方从磁盘读取后注入（计划层不碰文件，便于 JVM 单测锁定映射）；
     * - 剧本命中且与正文对齐 ⇒ 用剧本段；未命中/不对齐 ⇒ 原缓存链路；两者都没有 ⇒ 规则兜底（整段旁白）。
     * - 对外结构 [ReadAloudSpeechPlan] 不变。
     */
    fun build(
        bookUrl: String?,
        chapter: TextChapter,
        baseCues: List<ReadAloudCue>,
        multiRoleEnabled: Boolean,
        roleCacheKey: String? = null,
        chapterScript: ChapterScript? = null
    ): ReadAloudSpeechPlan {
        if (baseCues.isEmpty()) {
            return ReadAloudSpeechPlan(emptyList(), emptyList(), emptyList())
        }
        if (!multiRoleEnabled || bookUrl.isNullOrBlank()) {
            val items = baseCues.mapIndexed { index, cue ->
                ReadAloudSpeechPlanItem(
                    index = index,
                    cue = cue.copy(index = index),
                    route = null,
                    roleType = "narrator",
                    characterId = 0L,
                    characterName = "旁白",
                    avatar = "",
                    emotionName = "",
                    roleLevel = BookCharacter.ROLE_NORMAL,
                    leftSide = false,
                    narrator = true,
                    sourceCueIndex = cue.index,
                    sourceStart = 0,
                    sourceEnd = cue.text.length
                )
            }
            return ReadAloudSpeechPlan(
                cues = items.map { it.cue },
                routes = List(items.size) { null },
                items = items
            )
        }

        val characters = appDb.bookCharacterDao.characters(bookUrl)
        val byId = characters.associateBy { it.id }
        val byName = charactersByNormalizedName(characters)
        // 3.6 读取顺序第一层：章节剧本（与正文对齐后才采用）；不对齐/未命中 ⇒ 第二层缓存
        val scriptGroups = chapterScript
            ?.let { ChapterScriptPlayback.align(it, baseCues.map { cue -> cue.text }) }
            ?.let { ChapterScriptPlayback.groupsByParagraph(it) }
        val assignedSegmentsByCue = if (scriptGroups == null) {
            AiReadAloudRoleService.assignedSegmentsByCue(
                bookUrl = bookUrl,
                chapterIndex = chapter.chapter.index,
                cacheKey = roleCacheKey
            )
        } else {
            emptyMap()
        }
        val plannedItems = arrayListOf<ReadAloudSpeechPlanItem>()
        baseCues.forEachIndexed { cueIndex, cue ->
            val beforeSize = plannedItems.size
            // 剧本段优先；该段无剧本条目时由 completeCueSegments 用规则补旁白，与旧链路同语义
            val rawSegments = scriptGroups?.get(cueIndex)?.map { it.toPlannerSegment() }
                ?: assignedSegmentsByCue[cueIndex].orEmpty()
            val segments = completeCueSegments(
                cueIndex = cueIndex,
                cueText = cue.text,
                rawSegments = rawSegments
            )
            segments.forEach { segment ->
                val text = cue.text.substring(segment.start, segment.end)
                if (text.isEmpty()) return@forEach
                val chapterPosition = cue.chapterPosition + segment.start
                val pageIndex = chapter.getPageIndexByCharIndex(chapterPosition)
                    .takeIf { it >= 0 }
                    ?: cue.pageIndex
                val pageStartPos = (chapterPosition - chapter.getReadLength(pageIndex)).coerceAtLeast(0)
                val character = resolveCharacter(segment, byId, byName)
                val displayName = when {
                    segment.roleType == "narrator" -> "旁白"
                    character != null -> character.displayName()
                    segment.characterName.isNotBlank() -> segment.characterName
                    segment.roleType == "thought" -> "心理"
                    else -> "角色"
                }
                val characterId = character?.id ?: segment.characterId
                val route = routeForSegment(character, segment)
                val emotionSupported = route?.let {
                    SpeechRoute.engineSupportsEmotion(it.engineType)
                } == true
                val plannedCue = ReadAloudCue(
                    index = plannedItems.size,
                    text = text,
                    chapterPosition = chapterPosition,
                    pageIndex = pageIndex,
                    pageStartPos = pageStartPos,
                    key = "${cue.key}:role:${segment.start}:${segment.end}:${text.hashCode()}"
                )
                plannedItems += ReadAloudSpeechPlanItem(
                    index = plannedItems.size,
                    cue = plannedCue,
                    route = route,
                    roleType = segment.roleType,
                    characterId = characterId,
                    characterName = displayName,
                    avatar = character?.avatar.orEmpty(),
                    // A7 情绪诚实：内置/系统引擎不支持情绪 token ⇒ 计划里不携带，UI 也就不会展示无效情绪
                    emotionName = if (emotionSupported) {
                        segment.emotionName.ifBlank { route?.emotionName.orEmpty() }
                    } else {
                        ""
                    },
                    roleLevel = character?.roleLevel ?: BookCharacter.ROLE_NORMAL,
                    leftSide = true,
                    narrator = segment.roleType == "narrator" || displayName == "旁白",
                    sourceCueIndex = cueIndex,
                    sourceStart = segment.start,
                    sourceEnd = segment.end
                )
            }
            if (plannedItems.size == beforeSize) {
                val plannedCue = cue.copy(index = plannedItems.size)
                plannedItems += ReadAloudSpeechPlanItem(
                    index = plannedItems.size,
                    cue = plannedCue,
                    route = null,
                    roleType = "narrator",
                    characterId = 0L,
                    characterName = "旁白",
                    avatar = "",
                    emotionName = "",
                    roleLevel = BookCharacter.ROLE_NORMAL,
                    leftSide = false,
                    narrator = true,
                    sourceCueIndex = cueIndex,
                    sourceStart = 0,
                    sourceEnd = cue.text.length
                )
            }
        }
        val displayItems = assignConversationSides(plannedItems)
        return ReadAloudSpeechPlan(
            cues = displayItems.map { it.cue },
            routes = displayItems.map { it.route },
            items = displayItems
        )
    }

    private fun resolveCharacter(
        segment: AiReadAloudRoleService.Segment,
        byId: Map<Long, BookCharacter>,
        byName: Map<String, BookCharacter>
    ): BookCharacter? {
        return when {
            segment.characterId > 0L -> byId[segment.characterId]
            segment.characterName.isNotBlank() -> byName[characterNameKey(segment.characterName)]
            else -> null
        }
    }

    private fun charactersByNormalizedName(characters: List<BookCharacter>): Map<String, BookCharacter> {
        val result = linkedMapOf<String, BookCharacter>()
        characters.forEach { character ->
            listOf(character.name, character.displayName())
                .map(::characterNameKey)
                .filter { it.isNotBlank() }
                .forEach { key -> result.putIfAbsent(key, character) }
        }
        return result
    }

    private fun characterNameKey(value: String): String {
        return value.trim()
            .replace(Regex("\\s+"), "")
            .trim('《', '》', '“', '”', '"', '\'', '：', ':')
            .lowercase()
    }

    private fun routeForSegment(
        character: BookCharacter?,
        segment: AiReadAloudRoleService.Segment
    ): SpeechRoute? {
        val route = SpeechRouteSanitizer.validOrNull(SpeechRoute.fromJson(character?.speechRouteJson))
            ?: return null
        if (!route.isConfigured) return null
        val emotionName = segment.emotionName.trim()
        val emotionTag = segment.emotionTag.trim()
        // A7：引擎不支持情绪时，不把脚本情绪写进路由（否则 UI 显示"有情绪"但听到的没有任何变化）
        if (!SpeechRoute.engineSupportsEmotion(route.engineType)) return route
        return if (emotionName.isNotBlank() || emotionTag.isNotBlank()) {
            route.copy(emotionName = emotionName, emotionTag = emotionTag)
        } else {
            route
        }
    }

    private fun assignConversationSides(
        items: List<ReadAloudSpeechPlanItem>
    ): List<ReadAloudSpeechPlanItem> {
        if (items.none { !it.narrator }) return items
        val result = items.toMutableList()
        val block = mutableListOf<Int>()
        var lastSpeechCue = -1

        fun flushBlock() {
            if (block.isEmpty()) return
            val speechItems = block.map { result[it] }
            val rightRoleLevel = when {
                speechItems.any { it.roleLevel == BookCharacter.ROLE_MAIN } -> BookCharacter.ROLE_MAIN
                speechItems.any { it.roleLevel == BookCharacter.ROLE_IMPORTANT } -> BookCharacter.ROLE_IMPORTANT
                else -> BookCharacter.ROLE_NORMAL
            }
            val rightSpeakerByLevel = mutableMapOf<Int, String>()
            val sideBySpeaker = mutableMapOf<String, Boolean>()
            block.forEach { index ->
                val item = result[index]
                val speakerKey = item.speakerKey()
                val leftSide = sideBySpeaker.getOrPut(speakerKey) {
                    val roleLevel = item.roleLevel.coerceIn(
                        BookCharacter.ROLE_NORMAL,
                        BookCharacter.ROLE_MAIN
                    )
                    if (roleLevel != rightRoleLevel) {
                        true
                    } else {
                        val rightSpeaker = rightSpeakerByLevel[roleLevel]
                        if (rightSpeaker == null || rightSpeaker == speakerKey) {
                            rightSpeakerByLevel[roleLevel] = speakerKey
                            false
                        } else {
                            true
                        }
                    }
                }
                result[index] = item.copy(leftSide = leftSide)
            }
            block.clear()
        }

        items.forEachIndexed { index, item ->
            if (item.narrator) return@forEachIndexed
            if (block.isNotEmpty() && item.sourceCueIndex - lastSpeechCue > LOCAL_DIALOGUE_CUE_GAP) {
                flushBlock()
            }
            block += index
            lastSpeechCue = item.sourceCueIndex
        }
        flushBlock()
        return result
    }

    private fun ReadAloudSpeechPlanItem.speakerKey(): String {
        return if (characterId > 0L) {
            "id:$characterId"
        } else {
            "name:$roleType:$characterName"
        }
    }

    /**
     * 剧本段 → 计划段：只做字段映射，不改区间（区间已由 [ChapterScriptPlayback.align] 校验）。
     * 情绪走 `emotionName`（`emotionTag` 保留给后续情绪档位，不在本层推断）。
     */
    private fun ScriptSegment.toPlannerSegment(): AiReadAloudRoleService.Segment {
        return AiReadAloudRoleService.Segment(
            paragraphIndex = paragraphIndex,
            start = start,
            end = end,
            roleType = when (roleType) {
                "narration", "narrator" -> "narrator"
                "thought" -> "thought"
                else -> "character"
            },
            characterName = characterName,
            characterId = characterId,
            confidence = confidence,
            emotionName = emotion,
            emotionTag = ""
        )
    }

    private fun completeCueSegments(
        cueIndex: Int,
        cueText: String,
        rawSegments: List<AiReadAloudRoleService.Segment>
    ): List<AiReadAloudRoleService.Segment> {
        if (cueText.isEmpty()) return emptyList()
        val normalized = rawSegments
            .mapNotNull { segment ->
                val start = segment.start.coerceIn(0, cueText.length)
                val end = segment.end.coerceIn(start, cueText.length)
                if (start >= end) null else segment.copy(start = start, end = end)
            }
            .sortedWith(compareBy<AiReadAloudRoleService.Segment> { it.start }.thenBy { it.end })
        val result = mutableListOf<AiReadAloudRoleService.Segment>()
        var cursor = 0
        normalized.forEach { segment ->
            if (segment.end <= cursor) return@forEach
            if (segment.start > cursor) {
                result += narratorSegment(cueIndex, cursor, segment.start)
            }
            val start = segment.start.coerceAtLeast(cursor)
            if (start < segment.end) {
                result += segment.copy(start = start)
                cursor = segment.end
            }
        }
        if (cursor < cueText.length) {
            result += narratorSegment(cueIndex, cursor, cueText.length)
        }
        return mergeAdjacentSegments(result)
    }

    private fun narratorSegment(
        cueIndex: Int,
        start: Int,
        end: Int
    ): AiReadAloudRoleService.Segment {
        return AiReadAloudRoleService.Segment(
            paragraphIndex = cueIndex,
            start = start,
            end = end,
            roleType = "narrator",
            characterName = "旁白",
            confidence = 0.5
        )
    }

    private fun mergeAdjacentSegments(
        segments: List<AiReadAloudRoleService.Segment>
    ): List<AiReadAloudRoleService.Segment> {
        if (segments.size <= 1) return segments
        val result = mutableListOf<AiReadAloudRoleService.Segment>()
        segments.forEach { segment ->
            val last = result.lastOrNull()
            if (last != null &&
                last.end == segment.start &&
                last.roleType == segment.roleType &&
                last.characterId == segment.characterId &&
                last.characterName == segment.characterName &&
                last.emotionName == segment.emotionName &&
                last.emotionTag == segment.emotionTag
            ) {
                result[result.lastIndex] = last.copy(end = segment.end)
            } else {
                result += segment
            }
        }
        return result
    }
}
