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

    /** 无模型时的姓氏启发式兜底来源（旧标记，保留兼容） */
    const val SOURCE_OFFLINE = "offline-rule-v1"

    /** AD-16：端侧 LAC-ONNX NER 模型识别来源（有模型时落库的真实来源，避免把模型结果标成"规则"） */
    const val SOURCE_OFFLINE_NER = "offline-ner-lac-v1"

    /** 自动发现来源集合（碎片清理只动这两类，绝不碰用户手建角色） */
    private val AUTO_SOURCES = setOf(SOURCE_OFFLINE, SOURCE_OFFLINE_NER)

    /** 本次发现的真实来源：有模型=N ER，无模型=规则兜底（可单测，避免把模型结果标成"规则"） */
    internal fun autoSource(trustModel: Boolean): String =
        if (trustModel) SOURCE_OFFLINE_NER else SOURCE_OFFLINE

    /**
     * 章开始是否自动累积角色（可单测的门槛）：
     * 仅在内置多角色链路生效、书标识有效、本章有正文时执行；避免旁白/空章做无谓的模型推理。
     */
    fun shouldDiscoverAtChapterStart(
        builtinActive: Boolean,
        bookKey: String,
        paragraphs: List<String>
    ): Boolean = builtinActive && bookKey.isNotBlank() && paragraphs.isNotEmpty()

    /**
     * 单章候选上限（2026-11-10 用户反馈「角色获取太少」后上调 12 → 30）：
     * 网文一章常同时出现主角团 + 反派 + 路人（说话 cue 齐全），12 个会把后半章角色挤掉；
     * 30 覆盖典型单章出场规模，同时仍防「整章误报几十个」污染角色表。
     * 用户仍可在角色管理里手动新增/修正（补录不会被自动清理）。
     */
    const val DEFAULT_LIMIT = 30

    /** 单姓三字名至少被 cue 命中两次才接纳（剔除"郑吒嗤"式一次性碎片） */
    private const val MIN_LONG_NAME_CUES = 2

    /** 与独立出现的短名并存时，长名需 ≥3 次才判定为真名，否则视为"短名+动词"碎片 */
    private const val CONFIRMED_LONG_NAME_CUES = 3

    /** 模型路径：非姓氏起头的实体至少出现 2 次才收录（拦截"法西斯"这类一次性误报） */
    private const val MIN_MODEL_UNSURNAMED_CUES = 2

    /**
     * 规则路径：非姓氏起头的昵称（阿明/小美/老张）证据更弱，同样要求 ≥2 次 cue，
     * 避免把"小了/老说"这类片段词收成角色（2026-11-05 E2E 修复配套）。
     */
    private const val MIN_RULE_UNSURNAMED_CUES = 2

    /** 昵称式人名（前缀 1 字 + 1 字）：小美 / 老张 / 阿明。只在"无 NER 模型"的规则路径补扫 */
    private val NICKNAME_TOKEN = Regex("(?:小|老|阿)[\\u4e00-\\u9fa5]")

    /**
     * 无 NER 模型时的「对话 cue 人名」：`艾伦说道：` / `徐福道：` 这类**不以常见姓氏开头**的名字。
     *
     * 2026-11-10 用户反馈「角色获取太少」：规则路径原先只认百家姓 + 小/老/阿昵称，
     * 西式/日式/自造名（艾伦、路明非式无姓设定）整类漏掉。这里用「人名 + 说话动词」
     * 的强结构信号补扫，仍要求 ≥2 次出现（挡掉"注意："这类误报）。
     */
    private val CUE_NAME_REGEX = Regex(
        "([\\u4e00-\\u9fa5]{2,3}?)(说道|问道|答道|笑道|喊道|叫道|沉声道|低声道|轻声道|开口道|说|道|问|答|喊|叫|叹)"
    )

    /** 段落起始的剧本式写法：`艾伦：你好。` */
    private val COLON_NAME_REGEX = Regex("(?:^|[\\n。！？；])[ \\t\\u3000]*([\\u4e00-\\u9fa5]{2,4})[：:]")

    /** 结构信号无法排除的代词/泛指（cue 路径专用；姓氏路径由 NerTextEngine 内部表兜住） */
    private val CUE_STOP_NAMES = setOf(
        "我们", "你们", "他们", "她们", "咱们", "大家", "众人", "两人", "三人", "一人",
        "此人", "那人", "有人", "旁人", "对方", "自己", "这时", "此时", "闻言", "见状",
        "接着", "然后", "于是", "只见", "只听", "注意", "所有", "全部", "任何", "其他"
    )

    /** cue 人名是否可信：长度 2~4、非伪名、非代词/泛指、不含副词/虚词用字 */
    internal fun isPlausibleCueName(name: String): Boolean {
        if (name.length !in 2..4) return false
        if (name in CUE_STOP_NAMES) return false
        if (NerTextEngine.isImplausibleName(name)) return false
        return name.none { it in CUE_IMPLAUSIBLE_CHARS }
    }

    private val CUE_IMPLAUSIBLE_CHARS = setOf(
        '忽', '然', '又', '便', '才', '就', '也', '都', '正', '在', '的', '是', '了', '着',
        '把', '被', '和', '与', '跟', '对', '向', '从', '到', '要', '会', '能', '可', '却', '还'
    )

    /**
     * 纯函数：无模型路径的 cue 人名候选（名字 → 出现次数）。
     * 只统计「人名 + 说话动词」与「段首 名字：」两种强结构，避免泛化误报。
     */
    internal fun cueNameCandidates(paragraphs: List<String>): Map<String, Int> {
        val counts = LinkedHashMap<String, Int>()
        paragraphs.forEach { paragraph ->
            CUE_NAME_REGEX.findAll(paragraph).forEach { match ->
                val prev = paragraph.getOrNull(match.range.first - 1)
                // 名字必须从词边界开始（前一字不是汉字），否则会把长词尾部当人名
                if (prev != null && prev in '\u4e00'..'\u9fa5') return@forEach
                val name = match.groupValues[1]
                if (isPlausibleCueName(name)) counts[name] = (counts[name] ?: 0) + 1
            }
            COLON_NAME_REGEX.findAll(paragraph).forEach { match ->
                val name = match.groupValues[1]
                if (isPlausibleCueName(name)) counts[name] = (counts[name] ?: 0) + 1
            }
        }
        return counts.filterValues { it >= MIN_RULE_UNSURNAMED_CUES }
    }

    /** 昵称右侧 12 字内出现对话冒号/说话动词 ⇒ 视为有对话证据（昵称名常以"X：xxx"出现，无动词） */
    private fun hasDialogueCueNear(paragraph: String, start: Int, end: Int): Boolean {
        val window = paragraph.substring(
            end,
            (end + NICKNAME_CUE_WINDOW).coerceAtMost(paragraph.length)
        )
        return window.contains('：') || window.contains(':') || window.contains('“') ||
            window.contains('"') || SPEECH_VERBS.any { window.contains(it) }
    }

    private const val NICKNAME_CUE_WINDOW = 12

    private val SPEECH_VERBS = listOf(
        "说道", "问道", "答道", "笑道", "低声", "沉声", "轻声", "大声", "开口", "喃喃",
        "说", "道", "问", "答", "喊", "叫", "叹"
    )

    /**
     * 无 NER 模型时的昵称候选：**全章出现 ≥2 次且至少一次有对话证据**才收。
     * 单次出现的"小美"可能只是词片段；两次以上并伴随对话，大概率是角色。
     */
    internal fun nicknameCandidates(paragraphs: List<String>): Map<String, Int> {
        val counts = LinkedHashMap<String, Int>()
        val cueSeen = HashSet<String>()
        paragraphs.forEach { paragraph ->
            NICKNAME_TOKEN.findAll(paragraph).forEach { match ->
                val name = match.value
                if (NerTextEngine.isImplausibleName(name)) return@forEach
                counts[name] = (counts[name] ?: 0) + 1
                if (name !in cueSeen && hasDialogueCueNear(paragraph, match.range.first, match.range.last + 1)) {
                    cueSeen.add(name)
                }
            }
        }
        return counts.filter { (name, count) -> count >= MIN_RULE_UNSURNAMED_CUES && name in cueSeen }
    }

    data class Candidate(
        val name: String,
        val gender: String,
        val cueCount: Int
    )

    /** 角色发现结果：新增 / 清理碎片都意味着旧分镜缓存必须重算 */
    data class DiscoveryResult(val added: Int, val pruned: Int) {
        val changed: Int get() = added + pruned
    }

    /** 一次扫描的产物：各候选名的 cue 命中次数 + 首次出现段落（用于弱性别推断） */
    private data class CueScan(
        val counts: LinkedHashMap<String, Int>,
        val firstContext: LinkedHashMap<String, String>
    )

    /**
     * 纯函数：从章节段落抽取角色候选（按 cue 出现次数降序，最多 [limit] 个）。
     * 不读库、不联网，可直接单测。
     */
    fun candidates(paragraphs: List<String>, limit: Int = DEFAULT_LIMIT): List<Candidate> {
        if (paragraphs.isEmpty() || limit <= 0) return emptyList()
        val trustModel = NerTextEngine.isModelReady()
        return toCandidates(scanCues(paragraphs, trustModel), limit, trustModel)
    }

    /** 人名信号 → 频次统计，产出最终候选（有 NER 模型时信任模型信号） */
    private fun toCandidates(scan: CueScan, limit: Int, trustModel: Boolean): List<Candidate> =
        selectNames(scan.counts, trustModel).take(limit).map { (name, count) ->
            // 性别只做弱推断（人名 + 首次出现段落代词/称谓），识别不到就留空交给配声按音色挑
            Candidate(name, BookCharacter.inferGender(name + scan.firstContext[name].orEmpty()), count)
        }

    /**
     * 模型路径计数门槛（可单测）：姓氏起头的实体直接收；非姓氏实体需有说话 cue 邻接，
     * 用来拦截模型把普通名词/组织名（法西斯）标成 PER 的误报。
     */
    internal fun shouldCountModelMention(name: String, cueAdjacent: Boolean): Boolean =
        NerTextEngine.startsWithKnownSurname(name) || cueAdjacent

    /**
     * 逐段收集人名信号次数：
     * - 有 NER 模型：姓氏起头的 PER 实体直接计数（旁白里的角色不漏）；非姓氏实体需 cue 邻接；
     * - 无模型：退回"姓氏候选 + cue 邻接"规则。
     */
    private fun scanCues(paragraphs: List<String>, trustModel: Boolean): CueScan {
        val cueCounts = LinkedHashMap<String, Int>()
        val firstContext = LinkedHashMap<String, String>()
        paragraphs.forEach { paragraph ->
            if (paragraph.isBlank()) return@forEach
            NerTextEngine.findMentions(paragraph, detectUnknown = true).forEach { mention ->
                val cueAdjacent = hasSpeechCue(paragraph, mention.end)
                val counted = if (trustModel) {
                    // 模型路径：姓氏起头的实体直接计数；非姓氏实体必须有说话 cue 邻接，
                    // 否则多是普通名词/组织名（法西斯），不是角色
                    shouldCountModelMention(mention.name, cueAdjacent)
                } else {
                    cueAdjacent
                }
                if (counted) {
                    cueCounts[mention.name] = (cueCounts[mention.name] ?: 0) + 1
                    firstContext.putIfAbsent(mention.name, paragraph)
                }
            }
        }
        // 无 NER 模型时姓氏启发式覆盖不到「阿明/小美/老张」这类昵称式名字
        // （2026-11-05 模拟器 E2E 实证：整章被判"未识别角色"⇒ 剧本生成被静默跳过）
        if (!trustModel) {
            nicknameCandidates(paragraphs).forEach { (name, count) ->
                cueCounts[name] = (cueCounts[name] ?: 0) + count
                firstContext.putIfAbsent(name, paragraphs.firstOrNull { it.contains(name) }.orEmpty())
            }
            // 2026-11-10：补扫「非姓氏起头的对话人名」（艾伦说道 / 段首「艾伦：」），
            // 只补新名字，避免与姓氏路径重复计数（既有排序/门槛口径不变）
            cueNameCandidates(paragraphs).forEach { (name, count) ->
                if (!cueCounts.containsKey(name)) {
                    cueCounts[name] = count
                    firstContext.putIfAbsent(name, paragraphs.firstOrNull { it.contains(name) }.orEmpty())
                }
            }
        }
        return CueScan(cueCounts, firstContext)
    }

    /**
     * 纯函数：全章频次消歧（2026-11-02 真机"角色识别不准"根因）。
     *
     * 姓氏启发式无法只凭单次出现判断三字名的边界：
     * - `萧宏律说` / `赵樱空道` 是真名，截成 2 字就成了"萧宏/赵樱"这类碎片；
     * - `郑吒嗤笑道` 是"人名 + 动作字"，扩成 3 字就成了"郑吒嗤"。
     *
     * 规则：① 单姓三字名至少 cue 命中 2 次；② 短名被长名完全覆盖（差值 ≤0）⇒ 短名是碎片；
     * ③ 短名独立出现且长名不足 3 次 ⇒ 长名是"短名+尾巴"碎片。复姓名不受 ① 约束。
     */
    internal fun selectNames(
        cueCounts: Map<String, Int>,
        trustModel: Boolean = false
    ): List<Pair<String, Int>> {
        val candidates = cueCounts.filterValues { it > 0 }.toMutableMap()
        candidates.keys.toList().forEach { name ->
            // 伪人名（姓+高频动词）在任何路径都剔除
            if (NerTextEngine.isImplausibleName(name)) {
                candidates.remove(name)
                return@forEach
            }
            if (trustModel) {
                // 模型 PER 也可能是普通名词（法西斯/某组织名）：非姓氏起头且只出现一次的一律不收；
                // 真昵称（零点）在章内通常反复出现，仍可保留。
                if (!NerTextEngine.startsWithKnownSurname(name) &&
                    (candidates[name] ?: 0) < MIN_MODEL_UNSURNAMED_CUES
                ) {
                    candidates.remove(name)
                }
                return@forEach
            }
            if (name.length >= 3 &&
                !NerTextEngine.startsWithCompoundSurname(name) &&
                (candidates[name] ?: 0) < MIN_LONG_NAME_CUES
            ) {
                candidates.remove(name)
                return@forEach
            }
            // 昵称式名字（阿明/小美/老张）不在姓氏表里，必须有 ≥2 次 cue 才算稳定角色
            if (!NerTextEngine.startsWithKnownSurname(name) &&
                (candidates[name] ?: 0) < MIN_RULE_UNSURNAMED_CUES
            ) {
                candidates.remove(name)
            }
        }
        // 前缀合并：无论有无模型都要做（否则"李帅"会和"李帅西"并存）
        candidates.keys.sortedBy { it.length }.forEach { short ->
            val shortCount = candidates[short] ?: return@forEach
            val longer = candidates.keys.firstOrNull { it.length > short.length && it.startsWith(short) }
                ?: return@forEach
            val longCount = candidates[longer] ?: return@forEach
            when {
                shortCount - longCount <= 0 -> candidates.remove(short)
                // 规则路径才把"短名+尾巴"当碎片删长名；模型可信时长名保留
                !trustModel && longCount < CONFIRMED_LONG_NAME_CUES -> candidates.remove(longer)
            }
        }
        // 稳定排序：同次数同长度时保持"首次出现"顺序，保证结果可复现
        return candidates.entries
            .sortedByDescending { it.value }
            .map { it.key to it.value }
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
     * 落库候选角色（幂等：已存在同名角色不覆盖），并清理旧版本落库的碎片名。
     * 返回新增 / 清理条数；[DiscoveryResult.changed] > 0 时调用方必须重算分镜缓存。
     * [bookKey] 用 [io.legado.app.help.book.BookIdentity] 口径（与既有角色表一致）。
     */
    suspend fun ensureCharacters(
        bookKey: String,
        paragraphs: List<String>,
        limit: Int = DEFAULT_LIMIT
    ): DiscoveryResult = withContext(Dispatchers.IO) {
        if (bookKey.isBlank()) return@withContext DiscoveryResult(0, 0)
        val trustModel = NerTextEngine.isModelReady()
        val scan = scanCues(paragraphs, trustModel)
        val found = toCandidates(scan, limit, trustModel)
        val source = autoSource(trustModel)
        if (trustModel) migrateProvenance(bookKey)
        // 先清理旧碎片（萧宏/郑吒嗤），否则旧角色继续占位并污染归因（2026-11-02 用户反馈）
        val pruned = pruneFragments(bookKey, found.map { it.name }.toSet(), scan.counts)
        if (found.isEmpty()) return@withContext DiscoveryResult(0, pruned)
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
                        source = source,
                        lastDetectedAt = now,
                        createdAt = now,
                        updatedAt = now
                    )
                )
                added++
            }.onFailure { AppLog.put("离线角色落库失败（${candidate.name}）：${it.message}") }
        }
        AppLog.put("离线角色发现：候选 ${found.size} / 新增 $added / 清理碎片 $pruned")
        DiscoveryResult(added, pruned)
    }

    /**
     * 来源标记迁移：模型就绪时，把历史「规则」标记回写为 NER 来源。
     * 只改 `source` 与 `updatedAt`，不动名字/性别/用户编辑内容；用于纠正旧版本把模型结果标成规则的误导。
     */
    private fun migrateProvenance(bookKey: String) {
        val existing = runCatching { appDb.bookCharacterDao.characters(bookKey) }
            .getOrDefault(emptyList())
        val now = System.currentTimeMillis()
        existing.forEach { character ->
            if (!character.autoCreated || character.source != SOURCE_OFFLINE) return@forEach
            runCatching {
                appDb.bookCharacterDao.updateCharacter(
                    character.copy(source = SOURCE_OFFLINE_NER, updatedAt = now)
                )
            }.onFailure {
                AppLog.put("离线角色来源标记迁移失败（${character.name}）：${it.message}")
            }
        }
    }

    /**
     * 清理旧版本落库的自动角色碎片（只动 `autoCreated` + 离线来源，绝不碰用户手建角色）：
     * - 旧名是新角色的严格前缀（萧宏 → 萧宏律）：无条件清理；
     * - 旧名比新角色长且以它开头（郑吒嗤 → 郑吒）：仅当本章该旧名 cue 次数不足 3（一次性尾巴碎片）。
     */
    private fun pruneFragments(
        bookKey: String,
        discoveredNames: Set<String>,
        rawCueCounts: Map<String, Int>
    ): Int {
        if (discoveredNames.isEmpty()) return 0
        val existing = runCatching { appDb.bookCharacterDao.characters(bookKey) }
            .getOrDefault(emptyList())
        var pruned = 0
        existing.forEach { character ->
            if (!character.autoCreated || character.source !in AUTO_SOURCES) return@forEach
            val name = character.name.trim()
            if (name.isBlank() || name in discoveredNames) return@forEach
            // 旧版本落库的"人名+笑声叠字"碎片（张杰哈哈）：当前章已发现其基名（张杰）⇒ 清掉
            val reduplicatedTail = name.length > 2 && name.last() == name[name.length - 2] &&
                    discoveredNames.any { it.length < name.length && name.startsWith(it) }
            if (reduplicatedTail) {
                runCatching { appDb.bookCharacterDao.deleteCharacterWithRelations(character) }
                    .onSuccess { pruned++ }
                    .onFailure { AppLog.put("离线角色叠字碎片清理失败（$name）：${it.message}") }
                return@forEach
            }
            // 旧版本落库的伪人名（解释/却冷/都知…）直接用同一"名字用字"口径清掉
            if (NerTextEngine.isImplausibleName(name)) {
                runCatching { appDb.bookCharacterDao.deleteCharacterWithRelations(character) }
                    .onSuccess { pruned++ }
                    .onFailure { AppLog.put("离线角色伪名清理失败（$name）：${it.message}") }
                return@forEach
            }
            // 短碎片：它的全部出现都被新长名覆盖（次数差值 ≤0）才算碎片；独立出现则保留
            val prefixLonger = discoveredNames.firstOrNull { it.length > name.length && it.startsWith(name) }
            val prefixOfNew = prefixLonger != null &&
                    (rawCueCounts[name] ?: 0) <= (rawCueCounts[prefixLonger] ?: 0)
            val overlongOld = discoveredNames.any { name.length > it.length && name.startsWith(it) } &&
                    (rawCueCounts[name] ?: 0) < CONFIRMED_LONG_NAME_CUES
            if (!prefixOfNew && !overlongOld) return@forEach
            runCatching { appDb.bookCharacterDao.deleteCharacterWithRelations(character) }
                .onSuccess { pruned++ }
                .onFailure { AppLog.put("离线角色碎片清理失败（$name）：${it.message}") }
        }
        return pruned
    }
}
