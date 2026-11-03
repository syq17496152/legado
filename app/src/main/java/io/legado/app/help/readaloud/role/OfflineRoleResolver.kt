package io.legado.app.help.readaloud.role

import io.legado.app.constant.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max
import kotlin.math.min

/**
 * 端侧离线归因（AD-13 / AD-15 / AD-17 / AD-21）：
 * 切分（`ReadAloudRolePreprocessor` 的引号簇）→ cue 命中 → 别名/固定绑定 → NER 邻接 → 对话锁 → 轮转 → 未识别。
 *
 * 硬规则（AD-15）：
 * - 置信 <0.50 **一律未识别**（不猜）
 * - 未识别**不静默归旁白**（保留 dialogue/thought 类型，由播放期走模板兜底并在 UI 标红）
 * - 同段连续对白无新 cue → 对话锁（继承上一句角色）
 * - 结果按章缓存并经 [OfflineRoleCacheStore] 落库
 */
object OfflineRoleResolver {

    data class RoleCharacter(
        val id: Long,
        val name: String,
        val aliases: List<String> = emptyList(),
        val gender: String = "",
        val roleLevel: Int = 0
    )

    data class RoleHit(
        val characterName: String,
        val characterId: Long,
        val confidence: Double,
        val reason: String
    )

    data class Result(
        val segments: List<OfflineRoleSegment>,
        val narration: Int,
        val recognized: Int,
        val unknown: Int,
        /** 本章模型 cue 候选总数（诊断用：>0 说明模型确实产出了信号，0 说明模型没识别到 cue） */
        val modelCueSpans: Int = 0
    )

    private const val MIN_ACCEPT = 0.50
    private const val CONF_ALIAS = 0.95
    private const val CONF_CUE = 0.90
    private const val CONF_LEARNED_CUE = 0.92
    private const val CONF_NER_CUE = 0.90
    private const val CONF_NER = 0.78
    /** 跨段近场 PER：证据比同段弱（名字可能在相邻段落里只是被提到），但强于对话锁 */
    private const val CONF_NER_WINDOW = 0.72
    private const val CONF_LOCK = 0.70
    private const val CONF_TURN = 0.60
    private const val OVERLAP_THRESHOLD = 0.5

    private val cueVerbRegex = Regex(
        "([\\u4e00-\\u9fa5]{1,4})(?:低声|沉声|冷声|轻声|大声|怒声|高声|柔声)?(?:说道|问道|答道|笑道|冷声道|沉声道|低声道|怒道|喝道|喊道|叫道|开口道|喃喃道|说|道|问|答|笑|喊|叫|喝|叹)"
    )

    private val memory = ConcurrentHashMap<String, List<OfflineRoleSegment>>()

    private fun memoryKey(bookKey: String, chapterIndex: Int) = "$bookKey|$chapterIndex"

    /** 解析整章（纯函数，便于 JVM 单测）。[signals] 可注入，默认走端侧 NER 模型（AD-16） */
    fun resolve(
        paragraphs: List<String>,
        units: List<ReadAloudRoleUnit>,
        characters: List<RoleCharacter>,
        learnedCues: Map<String, String> = emptyMap(),
        signals: (String) -> NerTextEngine.ParagraphSignals = NerTextEngine::signals
    ): Result {
        if (units.isEmpty()) {
            return Result(emptyList(), 0, 0, 0)
        }
        val nameMap = buildNameIndex(characters)
        val knownNames = characters.flatMap { listOf(it.name) + it.aliases }.filter { it.length >= 2 }
        val segments = ArrayList<OfflineRoleSegment>(units.size)
        var narration = 0
        var recognized = 0
        var unknown = 0
        var lastSpeaker: RoleCharacter? = null
        var lastParagraph = -1
        val rotation = ArrayDeque<RoleCharacter>()
        // 同段多句共用一次模型推理结果（章内逐段缓存，避免重复跑 LAC）
        val signalCache = HashMap<Int, NerTextEngine.ParagraphSignals>()
        // 模型 cue 候选总览（每段只计一次，供 roleResolve 日志判断"模型是否产出信号"）
        val countedParagraphs = HashSet<Int>()
        var modelCueSpans = 0

        units.sortedWith(compareBy({ it.firstParagraphIndex }, { it.firstStart })).forEach { unit ->
            val range = unit.ranges.firstOrNull()
            if (range == null) return@forEach
            val paragraph = paragraphs.getOrNull(range.paragraphIndex).orEmpty()

            if (unit.roleType == "narrator" || unit.kind == "narrator") {
                narration++
                segments.addAll(unit.ranges.map { r ->
                    OfflineRoleSegment(
                        paragraphIndex = r.paragraphIndex,
                        start = r.start,
                        end = r.end,
                        roleType = "narrator",
                        characterName = "",
                        characterId = 0L,
                        confidence = 1.0,
                        reason = "narration"
                    )
                })
                lastParagraph = range.paragraphIndex
                return@forEach
            }

            val paragraphSignal = signalCache.getOrPut(range.paragraphIndex) { signals(paragraph) }
            if (paragraphSignal.modelReady && countedParagraphs.add(range.paragraphIndex)) {
                modelCueSpans += paragraphSignal.cues.size
            }
            val assignment = assign(
                unit = unit,
                paragraph = paragraph,
                nameMap = nameMap,
                knownNames = knownNames,
                learnedCues = learnedCues,
                lastSpeaker = lastSpeaker,
                lastParagraph = lastParagraph,
                rotation = rotation,
                signal = paragraphSignal,
                signals = signals
            )
            if (assignment != null) {
                recognized++
                // 对话锁/轮转以 RoleCharacter 为状态载体（Assigned 仅承载本次结果）
                nameMap[normalizeName(assignment.name)]?.let { character ->
                    lastSpeaker = character
                    if (character.name !in rotation.map { it.name }) {
                        rotation.addLast(character)
                        if (rotation.size > 3) rotation.removeFirst()
                    }
                }
            } else {
                unknown++
            }
            segments.addAll(unit.ranges.map { r ->
                OfflineRoleSegment(
                    paragraphIndex = r.paragraphIndex,
                    start = r.start,
                    end = r.end,
                    roleType = unit.roleType.takeIf { it in setOf("narrator", "character", "thought", "other") }
                        ?: "other",
                    characterName = assignment?.name.orEmpty(),
                    characterId = assignment?.id ?: 0L,
                    confidence = assignment?.confidence ?: 0.0,
                    reason = assignment?.reason ?: "unknown"
                )
            })
            lastParagraph = range.paragraphIndex
        }
        return Result(segments, narration, recognized, unknown, modelCueSpans)
    }

    private data class Assigned(
        val name: String,
        val id: Long,
        val reason: String,
        val confidence: Double
    )

    /** 引号附近最近的角色名：前文取最靠后一次出现（离引号最近），后文取最靠前一次出现 */
    private fun nearestKnownName(
        cueBefore: String,
        cueAfter: String,
        knownNames: List<String>
    ): String? {
        val beforeHit = knownNames
            .mapNotNull { name -> cueBefore.lastIndexOf(name).takeIf { it >= 0 }?.let { name to it } }
            .maxByOrNull { it.second }
        if (beforeHit != null) return beforeHit.first
        return knownNames
            .mapNotNull { name -> cueAfter.indexOf(name).takeIf { it >= 0 }?.let { name to it } }
            .minByOrNull { it.second }
            ?.first
    }

    /**
     * 模型人名信号中离引号簇最近的一个（AD-16 ③）：
     * 前文只在引号前 40 字内、后文只在引号后 20 字内取，前文同距优先（说话人 cue 通常在前）。
     *
     * 2026-11-03：前文窗口 25 → 40 字（与跨段近场窗口口径一致）。长句里"人名+说话动词"常距引号
     * 25~40 字，旧窗口会让模型 cue 漏掉、退化到字典匹配（真机 ch=400 模型占比仅 30% 的主因之一）。
     */
    private fun nearestMention(
        mentions: List<NerTextEngine.Mention>,
        rangeStart: Int,
        rangeEnd: Int,
        maxBefore: Int = 40,
        maxAfter: Int = 20
    ): String? {
        return mentions.mapNotNull { mention ->
            val beforeGap = rangeStart - mention.end
            val afterGap = mention.start - rangeEnd
            when {
                beforeGap in 0..maxBefore -> mention.name to beforeGap
                afterGap in 0..maxAfter -> mention.name to (maxBefore + afterGap)
                else -> null
            }
        }.minByOrNull { it.second }?.first
    }

    private fun assign(
        unit: ReadAloudRoleUnit,
        paragraph: String,
        nameMap: Map<String, RoleCharacter>,
        knownNames: List<String>,
        learnedCues: Map<String, String>,
        lastSpeaker: RoleCharacter?,
        lastParagraph: Int,
        rotation: ArrayDeque<RoleCharacter>,
        signal: NerTextEngine.ParagraphSignals,
        signals: (String) -> NerTextEngine.ParagraphSignals
    ): Assigned? {
        val range = unit.ranges.first()
        val cueText = buildString {
            append(unit.cueBefore)
            append(' ')
            append(unit.cueAfter)
        }.trim()
        // 近场 cue：引号两侧各 40 字（含"深吸一口气，缓缓说道："这类长引导语；旧 24 字会把人名截掉）
        val nearCue = unit.cueBefore.takeLast(40) + " " + unit.cueAfter.take(40)

        // ① 显式 speakerHint（若上游提供）
        matchName(unit.speakerHint, nameMap)?.let { return Assigned(it.name, it.id, "alias", CONF_ALIAS) }

        // ② 学习到的 cue 模式（用户改段后自动命中）
        if (cueText.isNotBlank()) {
            learnedCues.entries.firstOrNull { (cue, _) ->
                cue.isNotBlank() && cueText.contains(cue)
            }?.let { (_, roleName) ->
                matchName(roleName, nameMap)?.let {
                    return Assigned(it.name, it.id, "learned-cue", CONF_LEARNED_CUE)
                }
            }
        }

        // 跨段近场窗口（引号两侧各 40 字）的模型推理结果：③ cue 与 ④.5 PER 复用同一次推理。
        // 延迟计算：同段 cue 已命中时无需再跑一次短推理（低配机每章几十次推理会拖慢章开始）。
        val windowSignal by lazy {
            if (signal.modelReady) {
                runCatching { signals(nearCue) }.getOrNull()
            } else null
        }
        // 引号在窗口中的位置（窗口 = cueBefore 末 40 字 + 空格 + cueAfter 前 40 字）
        val windowQuoteIndex = unit.cueBefore.takeLast(40).length + 1

        // ③ 端侧 NER cue（AD-16 主路径）：模型分词+词性判出的"名词性词元 + 说话动词 + 冒号/引号"，
        //    取离引号最近的角色名；这是模型判定，不是正则穷举。
        //    模型明确给出了"人名 cue"却不属于已登记角色（张三丰 vs 张三）⇒ 必须判未识别，
        //    禁止回落到字典子串匹配（2026-11-03：这正是"近似长名被错配"的来源）。
        var modelCueRejected = false
        if (signal.modelReady) {
            nearestMention(signal.cues, range.start, range.end)?.let { name ->
                val matched = matchName(name, nameMap)
                if (matched != null) {
                    return Assigned(matched.name, matched.id, "ner-cue", CONF_NER_CUE)
                }
                modelCueRejected = true
            }
            // 跨段 cue：preprocessor 的 cueBefore 会带上上一段结尾（"…张三说："），短片段的模型推理很便宜，
            // 用近场窗口再判一次，覆盖"本段没有 cue、cue 落在上一段"的对白（真机 ch=67 这类模型 cue=0 的主因）
            windowSignal?.takeIf { it.modelReady }?.cues?.asReversed()?.firstOrNull()?.name?.let { name ->
                val matched = matchName(name, nameMap)
                if (matched != null) {
                    return Assigned(matched.name, matched.id, "ner-cue", CONF_NER_CUE)
                }
                modelCueRejected = true
            }
        }

        // ③.5 模型 PER 紧邻（引号前 12 / 后 10 字）：引号紧挨人名时，模型实体比字典字符串匹配更可信，
        //     必须优先于 ④ 的字典命中（否则"模型判定占比"会被字典吃掉，观感仍是规则匹配）。
        if (signal.modelReady) {
            nearestMention(signal.persons, range.start, range.end, maxBefore = 12, maxAfter = 10)
                ?.let { name ->
                    matchName(name, nameMap)?.let {
                        return Assigned(it.name, it.id, "ner", CONF_NER)
                    }
                }
        }

        // ④ 字典 cue 命中：只认**本段**前缀/后缀里离引号最近的角色名，且必须存在"人名+说话动词"结构。
        // 跨段前缀（上一段末尾 40 字）里的名字交给 ③ 的模型窗口判定 —— 模型能判"人名+说话动词"结构，
        // 字典只做同段精确匹配；否则上一段旁白提到谁，下一段对白就判给谁（真机 ch=120 回归：16 处引号中
        // 有 4 处最近名来自上一段，观感就是"张冠李戴"）。
        // 2026-11-03 二次收紧：仅有名字、没有说话动词的"邻近猜测"一律不判（那是正则时代的猜法）；
        // 这样字典命中只会落在真正有归属语义的段落，模型的 ner-cue 才能成为主路径。
        val sameParagraphBefore = unit.cueBefore.substringAfterLast('\n')
        val sameParagraphAfter = unit.cueAfter.substringBefore('\n')
        val sameParagraphHasCue = cueNames(sameParagraphBefore + " " + sameParagraphAfter).isNotEmpty()
        if (cueText.isNotBlank() && !modelCueRejected && sameParagraphHasCue) {
            nearestKnownName(sameParagraphBefore, sameParagraphAfter, knownNames)?.let { name ->
                matchName(name, nameMap)?.let {
                    return Assigned(it.name, it.id, "cue-hit", CONF_CUE)
                }
            }
        }
        // 无模型时保留旧的跨段兜底（老设备/未下载模型不得功能断档）
        if (cueText.isNotBlank() && !signal.modelReady) {
            nearestKnownName(unit.cueBefore, unit.cueAfter, knownNames)?.let { name ->
                matchName(name, nameMap)?.let {
                    return Assigned(it.name, it.id, "cue-hit", CONF_CUE)
                }
            }
        }
        // 无模型时才做"任意 1~4 字 + 说话动词"的正则抽取（兜底路径；有模型时不走，避免规则瞎猜）
        if (!signal.modelReady) {
            cueNames(nearCue).forEach { candidate ->
                matchName(candidate, nameMap)?.let {
                    return Assigned(it.name, it.id, "cue-hit", CONF_CUE)
                }
            }
        }

        // ⑤ NER 邻接（引号簇 ±25 字）：有模型用 PER 实体，无模型回落字典精确匹配
        if (signal.modelReady) {
            nearestMention(signal.persons, range.start, range.end)?.let { name ->
                matchName(name, nameMap)?.let { return Assigned(it.name, it.id, "ner", CONF_NER) }
            }
        } else {
            val mentions = NerTextEngine.findMentions(paragraph, knownNames, detectUnknown = false)
            val near = NerTextEngine.mentionsNear(paragraph, range.start, range.end, mentions)
            near.firstOrNull()?.name?.let { name ->
                matchName(name, nameMap)?.let { return Assigned(it.name, it.id, "ner", CONF_NER) }
            }
        }

        // ⑥ 对话锁：同段连续对白且无新 cue → 继承上一句
        val sameParagraph = range.paragraphIndex == lastParagraph
        val hasExplicitCue = cueNames(cueText).isNotEmpty()
        if (sameParagraph && !hasExplicitCue && lastSpeaker != null && unit.roleType == "character") {
            return Assigned(lastSpeaker.name, lastSpeaker.id, "lock", CONF_LOCK)
        }

        // ⑥.5 模型 PER 跨段近场（引号前 40 / 后 20 字，模型实体而非字典猜名）：
        //      对白段常常整段只有引号、没有说话动词，归属线索落在相邻旁白段落（"张三靠墙站着。"）。
        //      该步只在同段没有任何显式归属时启用；模型若已明确给出"非登记角色"名字则不得猜测。
        val window = windowSignal
        if (window != null && window.modelReady && !modelCueRejected) {
            // 稳定闸（2026-11-03 用户反馈"音色乱跳"）：窗口里出现**多个已登记角色**时，
            // 最近的 PER 很可能只是"被提到的人"而不是说话人（如"张三看着李四。"后接李四对白）。
            // 此时宁可不判（交给对话锁/轮转保持对话连贯），也不要在一段对话里来回切换两个音色。
            val registeredIds = window.persons
                .mapNotNull { matchName(it.name, nameMap)?.id }
                .distinct()
            if (registeredIds.size == 1) {
                nearestMention(window.persons, windowQuoteIndex, windowQuoteIndex)
                    ?.let { name ->
                        matchName(name, nameMap)?.let {
                            return Assigned(it.name, it.id, "ner-window", CONF_NER_WINDOW)
                        }
                    }
            }
        }

        // ⑦ 对话轮转（换段后的无名对白：在 ≤3 已知角色中取与上一句不同的一个）
        if (unit.roleType == "character" || unit.roleType == "thought") {
            val pronounGender = pronounGender(paragraph, range)
            val candidates = rotation.filter { it.name != lastSpeaker?.name }
            // 无历史角色且无性别线索 → 宁标未识别，不猜（AD-15）
            if (candidates.isEmpty() && pronounGender.isBlank()) return null
            val preferred = when {
                pronounGender.isNotBlank() -> candidates.filter { genderOf(it) == pronounGender }
                else -> candidates
            }
            // 代词已指明性别但候选里没有同性别角色 → 不猜（避免女声配男角）
            if (pronounGender.isNotBlank() && preferred.isEmpty()) return null
            val pick = preferred.firstOrNull() ?: candidates.firstOrNull()
            if (pick != null && pick.name != lastSpeaker?.name) {
                return Assigned(pick.name, pick.id, "turn-taking", CONF_TURN)
            }
        }
        return null
    }

    /** cue 文本里抽人名（取最后一个命中，越靠近引号越可信） */
    fun cueNames(cue: String): List<String> {
        if (cue.isBlank()) return emptyList()
        return cueVerbRegex.findAll(cue).mapNotNull { match ->
            val raw = match.groupValues.getOrNull(1).orEmpty()
            raw.takeIf { it.length in 2..4 && it !in PRONOUNS }
        }.toList().reversed()
    }

    private val PRONOUNS = setOf("他", "她", "它", "我", "你", "您")

    private fun pronounGender(paragraph: String, range: ReadAloudRoleRange): String {
        val window = paragraph.substring(
            max(0, range.start - 12).coerceAtMost(range.start),
            min(paragraph.length, range.end + 12)
        )
        return when {
            window.contains('她') -> "female"
            window.contains('他') -> "male"
            else -> ""
        }
    }

    private fun genderOf(character: RoleCharacter): String {
        val raw = character.gender
        return when {
            raw.contains("女") || raw.equals("female", true) -> "female"
            raw.contains("男") || raw.equals("male", true) -> "male"
            else -> ""
        }
    }

    /**
     * 名字匹配（模型 cue / 字典命中统一入口）：
     * ① 正名/别名精确命中；
     * ② 剥掉常见称谓后缀再命中（"张三大人/李四姐姐/王五前辈" ⇒ 张三/李四/王五）——
     *    模型常把"人名+称谓"并成一个词元，不剥就永远匹配不到已登记角色（模型占比上不去的主因之一）；
     * ③ 昵称前缀（"小张三/老李四"）只在剥离后仍能精确命中时接受。
     * 不做"任意包含"：避免 `张三丰` 被错配给 `张三`。
     */
    private fun matchName(value: String, nameMap: Map<String, RoleCharacter>): RoleCharacter? {
        val key = normalizeName(value)
        if (key.isBlank()) return null
        nameMap[key]?.let { return it }
        if (key.length <= 2) return null
        ADDRESS_SUFFIXES.firstNotNullOfOrNull { suffix ->
            if (key.endsWith(suffix)) key.removeSuffix(suffix).takeIf { it.length >= 2 } else null
        }?.let { trimmed -> nameMap[trimmed]?.let { return it } }
        NICKNAME_PREFIXES.firstNotNullOfOrNull { prefix ->
            if (key.startsWith(prefix)) key.removePrefix(prefix).takeIf { it.length >= 2 } else null
        }?.let { trimmed -> nameMap[trimmed]?.let { return it } }
        return null
    }

    /** 常见称谓后缀（长的在前，避免"姐姐"被"姐"抢先剥成单字） */
    private val ADDRESS_SUFFIXES = listOf(
        "大人", "先生", "小姐", "前辈", "老师", "师傅", "同学", "将军", "少爷", "姑娘",
        "姐姐", "妹妹", "哥哥", "弟弟", "叔叔", "阿姨", "婶婶", "爷爷", "奶奶", "夫人",
        "姐", "妹", "哥", "弟", "叔", "姨", "婶", "爷", "奶", "兄", "总"
    )

    /** 昵称前缀（中文口语常见"小X/老X/阿X"） */
    private val NICKNAME_PREFIXES = listOf("小", "老", "阿")

    /** 构建归一化名索引：正名 + 书名号剥离 + 别名（显式循环，避免运算符优先级歧义） */
    fun buildNameIndex(characters: List<RoleCharacter>): Map<String, RoleCharacter> {
        val map = LinkedHashMap<String, RoleCharacter>()
        characters.forEach { character ->
            val names = ArrayList<String>(2 + character.aliases.size)
            names.add(character.name)
            names.add(character.name.trim('《', '》', '“', '”'))
            names.addAll(character.aliases)
            names.forEach { alias ->
                val key = normalizeName(alias)
                if (key.isNotBlank()) map.putIfAbsent(key, character)
            }
        }
        return map
    }

    fun normalizeName(value: String): String = value.trim()
        .replace(Regex("\\s+"), "")
        .trim('《', '》', '“', '”', '"', '\'', '：', ':', '，', ',', '。', '.', '、')

    /**
     * 段 → 角色映射（C-11 / AD-21）：按**字符区间交集覆盖率 ≥0.5** 判定，不依赖起点相等。
     */
    fun lookup(
        segments: List<OfflineRoleSegment>,
        paragraphIndex: Int,
        offset: Int,
        length: Int,
        minConfidence: Double = MIN_ACCEPT
    ): RoleHit? {
        if (length <= 0 || segments.isEmpty()) return null
        val segEnd = offset + length
        val candidates = segments.asSequence()
            .filter { it.paragraphIndex == paragraphIndex && it.recognized && it.confidence >= minConfidence }
            .mapNotNull { seg ->
                val overlap = max(0, min(segEnd, seg.end) - max(offset, seg.start))
                val ratio = overlap.toDouble() / length
                if (ratio < OVERLAP_THRESHOLD) null else ratio to seg
            }
            .sortedByDescending { it.first }
            .toList()
        val best = candidates.firstOrNull() ?: return null
        val adjusted = if (candidates.size > 1) (best.second.confidence - 0.1) else best.second.confidence
        if (adjusted < minConfidence) return null
        return RoleHit(
            characterName = best.second.characterName,
            characterId = best.second.characterId,
            confidence = adjusted,
            reason = best.second.reason
        )
    }

    fun lookup(bookKey: String, chapterIndex: Int, paragraphIndex: Int, offset: Int, length: Int): RoleHit? {
        val segments = memory[memoryKey(bookKey, chapterIndex)] ?: return null
        return lookup(segments, paragraphIndex, offset, length)
    }

    fun cached(bookKey: String, chapterIndex: Int): List<OfflineRoleSegment>? =
        memory[memoryKey(bookKey, chapterIndex)]

    /**
     * 章开始准备：内存优先 → 落库缓存 → 现算并落库。
     * 零网络；失败不阻断播放（返回空表，播放期走模板兜底）。
     */
    suspend fun ensure(
        bookKey: String,
        chapterIndex: Int,
        chapterTitle: String,
        paragraphs: List<String>,
        characters: List<RoleCharacter>,
        aliasStore: BookAliasStore? = null,
        forceRecompute: Boolean = false
    ): List<OfflineRoleSegment> {
        val key = memoryKey(bookKey, chapterIndex)
        if (!forceRecompute) {
            memory[key]?.let { if (it.isNotEmpty()) return it }
            OfflineRoleCacheStore.read(bookKey, chapterIndex, paragraphs)?.let { cachedSegments ->
                memory[key] = cachedSegments
                return cachedSegments
            }
        }
        // AD-16：段落级归因会跑端侧 LAC 推理（逐段），必须离开主线程（调用方为 TTS 服务主线程作用域）
        return withContext(Dispatchers.IO) {
            runCatching {
                val units = ReadAloudRolePreprocessor.process(paragraphs, 0).units
                val learned = aliasStore?.cuesFor(bookKey).orEmpty()
                val result = resolve(paragraphs, units, characters, learned)
                if (result.segments.isNotEmpty()) {
                    // 正式诊断日志（保留）：区分"模型判定"与"字典/锁/轮转"的分布，用于验证 AD-16 主路径真实生效
                    val byReason = result.segments.groupingBy { it.reason }.eachCount()
                    AppLog.putDebugWithTag(
                        AppLog.TAG_TTS_TRACE,
                        "roleResolve ch=$chapterIndex nerCue=${byReason["ner-cue"] ?: 0} " +
                            "ner=${byReason["ner"] ?: 0} win=${byReason["ner-window"] ?: 0} " +
                            "cue=${byReason["cue-hit"] ?: 0} " +
                            "lock=${byReason["lock"] ?: 0} turn=${byReason["turn-taking"] ?: 0} " +
                            "unknown=${byReason["unknown"] ?: 0} modelCues=${result.modelCueSpans}",
                        level = AppLog.Level.INFO
                    )
                    memory[key] = result.segments
                    OfflineRoleCacheStore.write(
                        bookKey, chapterIndex, chapterTitle, paragraphs, result.segments
                    )
                }
                result.segments
            }.onFailure {
                AppLog.put("离线归因失败（章 $chapterIndex）：${it.message}")
            }.getOrDefault(emptyList())
        }
    }

    fun clearMemory(bookKey: String) {
        memory.keys.filter { it.startsWith("$bookKey|") }.forEach { memory.remove(it) }
    }

    fun resetMemory() {
        memory.clear()
    }
}
