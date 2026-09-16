package io.legado.app.ui.book.read.config

import android.content.Context
import io.legado.app.constant.AppLog
import io.legado.app.constant.PreferKey
import io.legado.app.help.HighlightPalette
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonArray
import io.legado.app.utils.getPrefBoolean
import io.legado.app.utils.getPrefString
import io.legado.app.utils.putPrefBoolean
import io.legado.app.utils.putPrefString
import java.io.File

/**
 * F-P0-2 备份选择器（借鉴蛋蛋Max）
 * 高亮规则存储类
 *
 * 简化说明：去掉蛋蛋Max 的 TextLine.cleanupUnusedBgImages/copyBgImageToInternal 调用
 * 已知上限：不支持背景图迁移到内部目录
 * 升级路径：后续移植 TextLine 扩展后可恢复背景图迁移逻辑
 */
/**
 * 恢复默认模式
 * - MERGE：保留用户自定义规则，补充缺失的内置规则
 * - OVERWRITE：删除所有规则，重置为内置规则
 */
enum class RestoreMode { MERGE, OVERWRITE }

object HighlightRuleStore {

    const val backupFileName = "highlightRule.json"
    const val backupBgDirName = "highlightRuleBg"

    data class BackupData(
        val rules: List<HighlightRule> = emptyList(),
        val groups: List<String> = emptyList(),
        val currentGroup: String = "",
        val dialogEnabled: Boolean = true,
        val bookTitleEnabled: Boolean = true,
        val bracketNoteEnabled: Boolean = true,
    )

    @Volatile
    private var cachedRules: List<HighlightRule>? = null

    fun defaultPresetRules(context: Context): List<HighlightRule> {
        return createDefaultRules(context)
    }

    fun load(context: Context): MutableList<HighlightRule> {
        cachedRules?.let { return it.toMutableList() }
        val stored = context.getPrefString(PreferKey.highlightRuleItems)
        // T-B3: 空值或空数组"[]"都走 reset 恢复12条内置规则
        // 修复 T-B2 一次性标志位缺陷：用户清空规则或覆盖安装后规则丢失时应恢复内置规则
        if (stored.isNullOrBlank() || stored.trim() == "[]") {
            return reset(context)
        }
        val rules = GSON.fromJsonArray<HighlightRule>(stored).getOrNull()?.toMutableList()
        if (rules != null && rules.isNotEmpty()) {
            // H-1: 全部规则 name+pattern 均空 = 损坏数据，自动恢复内置规则
            // 真机实测：用户设备 JSON 12 条 name/pattern 全空导致列表空+编辑空+不生效
            if (rules.all { it.name.isNullOrBlank() && it.pattern.isNullOrBlank() }) {
                AppLog.put("高亮规则：检测到全部规则为空数据，已自动恢复内置规则")
                return reset(context)
            }
            // F4/4.4：版本旗标 MERGE——新增内置规则推送老用户（只追加缺失 id 的 delta，
            // 不触碰用户已有项；SP 链无删除墓碑，禁全量 defaults 重置）
            var merged: List<HighlightRule> = rules
            if (runCatching { io.legado.app.help.config.LocalConfig.needUpHighlightRules }.getOrDefault(false)) {
                val existingIds = merged.map { it.id }.toSet()
                val toAdd = createDefaultRules(context).filter { it.id !in existingIds }
                if (toAdd.isNotEmpty()) {
                    merged = merged + toAdd
                    AppLog.put("高亮规则：版本升级推送新内置规则 ${toAdd.size} 条（仅追加缺失 id）")
                }
            }
            // R12.3(b)：已退役内置条目的**幂等**处置（未个性化移除 / 已个性化保留并标注「(已退役)」）。
            // 不引入版本旗标：`isLastVersion` 是「读后即写回」语义，存在评估与执行非原子的机会丢失窗口
            // （builtin-replace-id-fix 既有教训），本处置本身幂等且仅在**实际变更时**留痕，重复执行零副作用。
            val retired = retireBuiltinRules(merged)
            if (retired.changed) {
                AppLog.put(
                    "高亮规则：退役内置条目处置 移除${retired.removed} 标注${retired.renamed} " +
                        "剩余${retired.rules.size}条"
                )
            }
            merged = retired.rules
            val normalized = normalizeRules(merged, context)
            save(context, normalized)
            cachedRules = normalized
            return normalized.toMutableList()
        }
        // T-B3: 解析失败或空列表也走 reset（防止损坏 JSON 或空列表导致内置规则缺失）
        return reset(context)
    }

    fun loadEnabled(context: Context): List<HighlightRule> {
        return load(context).filter { it.enabled && it.pattern.isNotBlank() }
    }

    fun save(context: Context, rules: List<HighlightRule>) {
        val normalized = rules.map {
            sanitizeRule(it).copy(
                targetScope = normalizeTargetScope(it.targetScope)
            )
        }
        cachedRules = normalized
        context.putPrefString(PreferKey.highlightRuleItems, GSON.toJson(normalized))
        HighlightRuleGroupStore.ensureFromRules(context, normalized)
        // 简化说明：跳过 TextLine.cleanupUnusedBgImages 调用，当前项目 TextLine 无此方法
    }

    fun reset(context: Context): MutableList<HighlightRule> {
        cachedRules = null
        val defaults = createDefaultRules(context)
        save(context, defaults)
        return defaults.toMutableList()
    }

    /**
     * 恢复默认规则
     * - MERGE：保留用户自定义规则，补充缺失的内置规则
     * - OVERWRITE：删除所有规则，重置为内置规则
     */
    fun restoreDefaults(context: Context, mode: RestoreMode): List<HighlightRule> {
        val defaults = createDefaultRules(context)
        return when (mode) {
            RestoreMode.MERGE -> {
                val current = load(context)
                val existingIds = current.map { it.id }.toSet()
                val toAdd = defaults.filter { it.id !in existingIds }
                (current + toAdd).also {
                    save(context, it)
                    AppLog.put("高亮规则：恢复默认（合并模式），新增 ${toAdd.size} 条内置规则")
                }
            }
            RestoreMode.OVERWRITE -> {
                save(context, defaults)
                AppLog.put("高亮规则：恢复默认（覆盖模式），重置为 ${defaults.size} 条内置规则")
                defaults
            }
        }
    }

    fun createBackupData(context: Context): BackupData {
        return BackupData(
            rules = load(context),
            groups = HighlightRuleGroupStore.load(context),
            currentGroup = context.getPrefString(PreferKey.highlightRuleCurrentGroup).orEmpty(),
            dialogEnabled = context.getPrefBoolean(PreferKey.highlightRuleDialog, true),
            bookTitleEnabled = context.getPrefBoolean(PreferKey.highlightRuleBookTitle, true),
            bracketNoteEnabled = context.getPrefBoolean(PreferKey.highlightRuleBracketNote, true),
        )
    }

    fun restoreBackupData(
        context: Context,
        backupData: BackupData,
        backupRootPath: String? = null,
    ) {
        HighlightRuleGroupStore.save(context, backupData.groups)
        val rules = backupData.rules.map { rule ->
            val safeRule = sanitizeRule(rule)
            val restoredBgImage = restoreRuleBgImage(context, backupRootPath, safeRule.bgImage)
            safeRule.copy(bgImage = restoredBgImage)
        }
        save(context, rules)
        context.putPrefBoolean(PreferKey.highlightRuleDialog, backupData.dialogEnabled)
        context.putPrefBoolean(PreferKey.highlightRuleBookTitle, backupData.bookTitleEnabled)
        context.putPrefBoolean(PreferKey.highlightRuleBracketNote, backupData.bracketNoteEnabled)
        val groups = HighlightRuleGroupStore.load(context)
        context.putPrefString(
            PreferKey.highlightRuleCurrentGroup,
            backupData.currentGroup.takeIf { groups.contains(it) }.orEmpty()
        )
    }

    fun getUsedBgImageFiles(context: Context): List<File> {
        return load(context)
            .mapNotNull { it.bgImage }
            .asSequence()
            .filter { it.isNotBlank() && !it.startsWith("assets://") }
            .map(::File)
            .filter { it.exists() && it.isFile }
            .distinctBy { it.absolutePath }
            .toList()
    }

    /**
     * R12.4：内置规则 id → 语义色板槽位。
     * 命中者颜色由 [HighlightPalette] 按**当前阅读器态**（白天/夜间/墨水屏）解析，
     * 修复「默认规则在夜间不可见」；用户已自定义样式（`styleJson` 存在且不含槽位）时不受影响。
     */
    private val builtinPaletteSlots: Map<String, String> = mapOf(
        "dialog_default" to HighlightPalette.Slot.DIALOGUE,
        "dialogue_speaker_default" to HighlightPalette.Slot.DIALOGUE,
        "dialogue_para_default" to HighlightPalette.Slot.DIALOGUE,
        "dash_dialogue_default" to HighlightPalette.Slot.DASH_DIALOGUE,
        "book_title_default" to HighlightPalette.Slot.BOOK_TITLE,
        "bracket_note_default" to HighlightPalette.Slot.NOTE,
        "title_emphasis_default" to HighlightPalette.Slot.TITLE_EMPHASIS,
        "thought_default" to HighlightPalette.Slot.THOUGHT,
        "narrator_default" to HighlightPalette.Slot.NARRATOR,
        "emphasis_default" to HighlightPalette.Slot.EMPHASIS,
        "poetry_default" to HighlightPalette.Slot.POETRY,
        "ellipsis_default" to HighlightPalette.Slot.ELLIPSIS,
        "number_default" to HighlightPalette.Slot.NUMBER,
        "english_default" to HighlightPalette.Slot.ENGLISH,
        "date_time_default" to HighlightPalette.Slot.DATE_TIME,
        "system_panel_default" to HighlightPalette.Slot.SYSTEM_PANEL,
        "onomatopoeia_default" to HighlightPalette.Slot.ONOMATOPOEIA,
        "url_muted_default" to HighlightPalette.Slot.URL
    )

    /**
     * 给内置规则挂上色板槽位（仅当：内置 id 命中槽位表 + 该规则尚无自定义 `styleJson` + 确实使用颜色通道）。
     *
     * - 新装/重置：`createDefaultRules()` 路径挂槽位；
     * - 存量用户：`normalizeRules()` 路径挂槽位（未被个性化者）；
     * - 已自定义样式（`styleJson` 非空）→ **尊重用户**，不挂色板、原样保留。
     */
    private fun attachPaletteSlot(rule: HighlightRule): HighlightRule {
        val slot = builtinPaletteSlots[rule.id] ?: return rule
        if (rule.styleJson != null) return rule
        val style = rule.toHighlightStyle()
        val usesColor = style.textColor != 0 || style.underline != null ||
            style.strike != null || style.box != null || style.emphasis != null
        if (!usesColor) return rule
        return rule.copy(styleJson = GSON.toJson(style.copy(paletteSlot = slot)))
    }

    private fun createDefaultRules(context: Context): List<HighlightRule> = defaultRules(
        dialogEnabled = context.getPrefBoolean(PreferKey.highlightRuleDialog, true),
        bookTitleEnabled = context.getPrefBoolean(PreferKey.highlightRuleBookTitle, true),
        bracketNoteEnabled = context.getPrefBoolean(PreferKey.highlightRuleBracketNote, true)
    )

    /**
     * 内置规则集（**context-free** 版本，供 JVM 单测断言「无重复 pattern / 无重复语义色值」等 §9.5.1 门禁）。
     *
     * R12.3(b) 语义收敛结果：等价项合并（emphasis ⊃ markdown_bold）、宽窄版并集（4 条宽版并入主形态）、
     * 语义包含项（括号标注 ⊃ 系统面板/心理活动/旁白）保留为**变体**（默认关闭，靠整体优先级裁决，不再并列争抢）。
     */
    internal fun defaultRules(
        dialogEnabled: Boolean = true,
        bookTitleEnabled: Boolean = true,
        bracketNoteEnabled: Boolean = true,
    ): List<HighlightRule> {
        return listOf(
            HighlightRule(
                id = "dialog_default",
                name = "对话高亮",
                pattern = "“[^”\n]{1,400}”|\"[^\"\n]{1,400}\"|「[^」\n]{1,400}」|『[^』\n]{1,400}』",
                sampleText = "她轻声说：“今晚就出发。”",
                group = HighlightRuleGroupStore.DEFAULT_GROUP,
                isRegex = true,
                enabled = dialogEnabled,
                textColor = 0xFFFF8C00.toInt()
            ),
            HighlightRule(
                id = "book_title_default",
                name = "书名号高亮",
                pattern = "《[^》\n]{1,80}》",
                sampleText = "最近在重读《百年孤独》，节奏依然很稳。",
                group = HighlightRuleGroupStore.DEFAULT_GROUP,
                isRegex = true,
                enabled = bookTitleEnabled,
                underlineMode = 3,
                underlineWidth = 0.5f,
                underlineColor = 0xFF63C37D.toInt()
            ),
            HighlightRule(
                id = "bracket_note_default",
                name = "括号标注高亮",
                pattern = "（[^（）\n]{1,80}）|\\([^()\n]{1,80}\\)|【[^】\n]{1,80}】|\\[[^\\]\n]{1,80}]",
                sampleText = "他停了一下（像是忽然想起了什么）。",
                group = HighlightRuleGroupStore.DEFAULT_GROUP,
                isRegex = true,
                enabled = bracketNoteEnabled,
                textColor = 0xFF8F959E.toInt(),
                underlineMode = 2,
                underlineWidth = 0.5f,
                underlineColor = 0xFF5A8DEE.toInt()
            ),
            HighlightRule(
                id = "title_emphasis_default",
                name = "标题强调",
                pattern = "(?m)^\\s{0,2}(?:第[0-9零〇一二两三四五六七八九十百千万IVXLCDMivxlcdm]{1,12}[章节卷回部篇集幕]|序章|楔子|引子|终章|尾声|后记|番外)[^\n]{0,40}$",
                sampleText = "第一章 雨夜来客",
                group = HighlightRuleGroupStore.DEFAULT_GROUP,
                isRegex = true,
                targetScope = HighlightRule.TARGET_TITLE,
                enabled = true,
                textColor = 0xFF333333.toInt(),
                underlineMode = 4,
                underlineColor = 0xFF7C5634.toInt()
            ),
            HighlightRule(
                id = "thought_default",
                name = "心理活动",
                // R12.3(b)：宽窄版并集（原 thought_wide_default 关键词并入主形态，宽版 id 退役）——
                // 「宽窄版不并列共存」：同一语义只保留一条规则，避免两规则抢同一区间
                pattern = "（[^）\n]{0,60}(?:心想|暗道|心道|想到|寻思着|琢磨|嘀咕|想道|心里|思量|思忖|盘算)[^）\n]{0,60}）",
                sampleText = "她心中一紧（暗道不对，这里一定有问题）。",
                group = HighlightRuleGroupStore.DEFAULT_GROUP,
                isRegex = true,
                enabled = false,
                textColor = 0xFF9370DB.toInt(),
                underlineMode = 1,
                underlineWidth = 0.5f,
                underlineColor = 0xFF9370DB.toInt()
            ),
            HighlightRule(
                id = "narrator_default",
                name = "旁白说明",
                // R12.3(b)：宽窄版并集（原 narrator_wide_default 分支并入，「不再赘述/省略」类同义）
                pattern = "(?:未完待续|待续|下文再表|按：?|注：?)[^\n]{0,40}|（(?:注|旁白|作者有话说)[:：][^）\n]{0,40}）|（(?:以下[^\n]{0,20}省略)[^\n]{0,40}）|[^\n]{0,20}(?:不再赘述|不再多说)",
                sampleText = "（注：此处时间线与前文同步）",
                group = HighlightRuleGroupStore.DEFAULT_GROUP,
                isRegex = true,
                enabled = false,
                textColor = 0xFF708090.toInt()
            ),
            HighlightRule(
                id = "emphasis_default",
                name = "重点强调",
                pattern = "(?:\\*\\*|__)[^\n*_]{1,40}(?:\\*\\*|__)|(?:!!!|！？|\\?!)[^\n]{0,20}",
                sampleText = "**这是重点内容**，需要特别注意。",
                group = HighlightRuleGroupStore.DEFAULT_GROUP,
                isRegex = true,
                enabled = false,
                textColor = 0xFFDC143C.toInt(),
                underlineMode = 1,
                underlineColor = 0xFFDC143C.toInt()
            ),
            HighlightRule(
                id = "poetry_default",
                name = "诗词引用",
                // R12.3(b)：宽窄版并集（原 poetry_wide_default 的「诗词题头」分支并入）
                pattern = "(?m)^[\\p{IsHan}，。！？；：、]{5,24}$|[\\n][七五言绝句律诗词牌曲牌][^\\n]{0,60}[^\\n]{10,50}[^\\n]{0,20}[，。！？]",
                sampleText = "床前明月光，\n疑是地上霜。",
                group = HighlightRuleGroupStore.DEFAULT_GROUP,
                isRegex = true,
                enabled = false,
                textColor = 0xFF2F4F4F.toInt(),
                underlineMode = 3,
                underlineWidth = 0.5f,
                underlineColor = 0xFF2F4F4F.toInt()
            ),
            HighlightRule(
                id = "ellipsis_default",
                name = "省略停顿",
                pattern = "…{2,}|\\.{3,}|—{2,}|-{3,}",
                sampleText = "他沉默了很久……最后还是点了头。",
                group = HighlightRuleGroupStore.DEFAULT_GROUP,
                isRegex = true,
                enabled = false,
                textColor = 0xFF8B8B8B.toInt()
            ),
            HighlightRule(
                id = "number_default",
                name = "数字金额",
                // R12.3(b)：宽窄版并集（原 number_wide_default 的「美元/英镑」并入）
                pattern = "(?:¥|￥)?\\d+(?:\\.\\d+)?(?:元|块|万|千|百|亿|%|％)|[零〇一二两三四五六七八九十百千万亿]+(?:元|块|万|千|百|亿)|[0-9零一二三四五六七八九十百千万亿]+(?:美元|英镑)",
                sampleText = "原价100元，现在只要50元。",
                group = HighlightRuleGroupStore.DEFAULT_GROUP,
                isRegex = true,
                enabled = false,
                textColor = 0xFF4169E1.toInt()
            ),
            HighlightRule(
                id = "english_default",
                name = "英文单词",
                pattern = "\\b[A-Za-z]{2,}[A-Za-z0-9'-]*\\b",
                sampleText = "Hello World，你好世界。",
                group = HighlightRuleGroupStore.DEFAULT_GROUP,
                isRegex = true,
                enabled = false,
                textColor = 0xFF4169E1.toInt()
            ),
            HighlightRule(
                id = "date_time_default",
                name = "时间日期",
                pattern = "(?:\\d{2,4}|[零〇一二两三四五六七八九十]{2,4})年(?:\\d{1,2}|[正一二三四五六七八九十冬腊])月(?:\\d{1,2}|[一二三四五六七八九十廿三])?[日号]?|\\b\\d{1,2}:\\d{2}\\b|(?:[0-1]?\\d|2[0-3])点(?:[0-5]?\\d分?)?",
                sampleText = "2024年8月12日，上午10:30出发。",
                group = HighlightRuleGroupStore.DEFAULT_GROUP,
                isRegex = true,
                enabled = false,
                textColor = 0xFF20B2AA.toInt()
            ),
            // ---------------- F4/4.5：v2 新增 12 条内置规则（默认开关策略：确定性开/泛化关） ----------------
            HighlightRule(
                id = "dialogue_speaker_default",
                name = "说话人标签对话",
                pattern = "[一-龥]{1,12}[说道问答喊骂叫叹喝笑]道?[：:][“][^”\\n]{1,200}[”]",
                sampleText = "她沉声道：“退下。”",
                group = HighlightRuleGroupStore.DEFAULT_GROUP,
                isRegex = true,
                enabled = true,
                textColor = 0xFFFFA726.toInt()
            ),
            HighlightRule(
                id = "dialogue_para_default",
                name = "段首长对白（跨段变体）",
                pattern = "(?m)^[ 　\\t]{0,4}[“][^”\\n]{1,200}$",
                sampleText = "“这段对白没有后引号，\n一直延续到段末。",
                group = HighlightRuleGroupStore.DEFAULT_GROUP,
                isRegex = true,
                enabled = false,
                textColor = 0xFFFFB74D.toInt()
            ),
            HighlightRule(
                id = "dash_dialogue_default",
                name = "破折号对白行",
                pattern = "(?m)^[ 　\\t]{0,4}——[^\\n]{1,80}$",
                sampleText = "——我们走吧。",
                group = HighlightRuleGroupStore.DEFAULT_GROUP,
                isRegex = true,
                enabled = false,
                textColor = 0xFFFFCC80.toInt()
            ),
            HighlightRule(
                id = "system_panel_default",
                name = "系统面板文本",
                pattern = "\\[[^\\]\\n]{1,40}\\]",
                sampleText = "[叮！检测到宿主情绪波动]",
                group = HighlightRuleGroupStore.DEFAULT_GROUP,
                isRegex = true,
                enabled = false,
                textColor = 0xFF4DD0E1.toInt()
            ),
            HighlightRule(
                id = "chapter_en_default",
                name = "英文章节行",
                pattern = "(?m)^[ 　\\t]{0,4}[Cc]hapter\\s+\\d+.{0,40}$",
                sampleText = "Chapter 1 The Beginning",
                group = HighlightRuleGroupStore.DEFAULT_GROUP,
                isRegex = true,
                enabled = false,
                textColor = 0xFF90A4AE.toInt()
            ),
            HighlightRule(
                id = "onomatopoeia_default",
                name = "拟声词",
                pattern = "(?:轰|哗|砰|咔|嗖|嗡|咻|咚){1,3}(?:——|—|~){1,2}",
                sampleText = "轰——一声巨响传来。",
                group = HighlightRuleGroupStore.DEFAULT_GROUP,
                isRegex = true,
                enabled = false,
                textColor = 0xFFFF8A65.toInt()
            ),
            // R12.3(b)：`markdown_bold_default` 已退役（其 `**…**` 被 emphasis_default 首分支完全包含，
            // 两条「强调」属语义等价 → 合并为一条），存量条目由 retireBuiltinRules() 幂等处置。
            HighlightRule(
                id = "url_muted_default",
                name = "网址/邮箱弱化",
                pattern = "https?://\\S+|[\\w.+-]+@[\\w-]+\\.\\w+",
                sampleText = "详情见 https://example.com 或邮件联系。",
                group = HighlightRuleGroupStore.DEFAULT_GROUP,
                isRegex = true,
                enabled = false,
                textColor = 0xFF78909C.toInt()
            )
            // R12.3(b)：以下 4 条「宽版」规则已退役——关键词/分支已并入对应主形态的并集 pattern，
            // 「宽窄版不并列共存」；存量条目由 retireBuiltinRules() 幂等处置（未个性化移除、已个性化保留并标注）：
            //   thought_wide_default → thought_default ｜ narrator_wide_default → narrator_default
            //   number_wide_default  → number_default  ｜ poetry_wide_default  → poetry_default
        ).map { attachPaletteSlot(it) }
    }

    private fun normalizeRules(
        rules: List<HighlightRule>,
        context: Context,
    ): List<HighlightRule> {
        val builtins = createDefaultRules(context).associateBy { it.id }
        return rules.map { rule ->
            val safeRule = sanitizeRule(rule)
            val normalizedGroup = safeRule.group
            val builtin = builtins[safeRule.id]
            val base = if (builtin != null && shouldRefreshBuiltin(safeRule, builtin)) {
                // F3/2.5 演进覆盖：用户 pattern 命中历史版本登记值（未做个性化修改）→ 升级到新版内置 pattern
                val patternIsLegacy =
                    legacyBuiltinPatterns[safeRule.id]?.contains(safeRule.pattern) == true
                healBuiltin(safeRule, builtin, patternIsLegacy, normalizedGroup)
            } else {
                // F3/2.8：愈合跳过留痕——用户修改被保留时输出诊断（真机排查"改了内置正则却被还原"疑云）
                if (builtin != null && safeRule.pattern != builtin.pattern) {
                    runCatching {
                        AppLog.putDebugWithTag(
                            AppLog.TAG_HIGHLIGHT_STYLE,
                            "内置规则${safeRule.id} 用户修改保留（pattern 未命中历史登记值，不愈合）",
                            level = AppLog.Level.INFO
                        )
                    }
                }
                safeRule.copy(
                    targetScope = normalizeTargetScope(safeRule.targetScope)
                )
            }
            // 简化说明：跳过 migrateBgImage（依赖 TextLine.copyBgImageToInternal）
            // 已知上限：旧数据背景图文件不迁移，升级后规则背景图可能丢失
            // 升级路径：fork 内实现 TextLine.copyBgImageToInternal 后补迁移链
            // R12.4：存量用户的内置规则补挂语义色板槽位（已自定义 styleJson 者不动）
            attachPaletteSlot(base)
        }
    }

    /**
     * R12.2：内置规则「愈合」合并（内置演进 vs 用户修改的裁决）。
     *
     * 关键修复：原实现用 `builtin.copy(...)` 时**未透传 `styleJson`**，导致命中愈合条件的用户自定义样式
     * 被静默清空（表现为「改过的颜色/线型被还原」）。此处显式保留用户样式（`styleJson` 优先），
     * 并补齐此前同样被丢弃的 `underlineOffset`。
     *
     * 抽为纯函数（不依赖 Context）以便单测锁定「愈合不丢用户样式」。
     */
    internal fun healBuiltin(
        safeRule: HighlightRule,
        builtin: HighlightRule,
        patternIsLegacy: Boolean,
        normalizedGroup: String,
    ): HighlightRule = builtin.copy(
        enabled = safeRule.enabled,
        group = normalizedGroup,
        // R-1 修复：保留用户改过的 pattern/sampleText/name（仅当用户改过时）
        pattern = if (patternIsLegacy) {
            builtin.pattern
        } else {
            safeRule.pattern.takeIf { it != builtin.pattern } ?: builtin.pattern
        },
        sampleText = safeRule.sampleText.takeIf { it.isNotBlank() } ?: builtin.sampleText,
        name = safeRule.name.takeIf { it.isNotBlank() } ?: builtin.name,
        targetScope = normalizeTargetScope(safeRule.targetScope, builtin.targetScope),
        textColor = safeRule.textColor ?: builtin.textColor,
        underlineMode = safeRule.underlineMode.takeIf { it != 0 } ?: builtin.underlineMode,
        underlineColor = safeRule.underlineColor ?: builtin.underlineColor,
        underlineWidth = safeRule.underlineWidth.takeIf { it != 1f } ?: builtin.underlineWidth,
        underlineOffset = safeRule.underlineOffset.takeIf { it != 2f } ?: builtin.underlineOffset,
        underlineSvgPath = safeRule.underlineSvgPath ?: builtin.underlineSvgPath,
        bgImage = safeRule.bgImage ?: builtin.bgImage,
        bgImageFit = safeRule.bgImageFit.takeIf { it != 0 } ?: builtin.bgImageFit,
        bgImageScale = safeRule.bgImageScale.takeIf { it != 1f } ?: builtin.bgImageScale,
        // R12.2：用户已保存的完整样式必须原样保留（此前缺失 → 静默清空）
        styleJson = safeRule.styleJson ?: builtin.styleJson,
        // B15: 保留用户的捕获组模板与 dotAll
        replacement = safeRule.replacement.ifBlank { builtin.replacement },
        isDotAll = safeRule.isDotAll || builtin.isDotAll
    )

    fun sanitizeRule(
        rule: HighlightRule,
        fallbackGroup: String = HighlightRuleGroupStore.DEFAULT_GROUP,
    ): HighlightRule {
        val name = runCatching { rule.name }.getOrNull().orEmpty()
        val pattern = runCatching { rule.pattern }.getOrNull().orEmpty()
        val sampleText = runCatching { rule.sampleText }.getOrNull().orEmpty()
        val group = runCatching { rule.group }.getOrNull().orEmpty().ifBlank { fallbackGroup }
        val id = runCatching { rule.id }.getOrNull().orEmpty().ifBlank {
            buildSanitizedRuleId(name, pattern, sampleText, group)
        }
        val underlineSvgPath = runCatching { rule.underlineSvgPath }.getOrNull()
        val bgImage = runCatching { rule.bgImage }.getOrNull()?.takeIf { it.isNotBlank() }
        return HighlightRule(
            id = id,
            name = name,
            pattern = pattern,
            sampleText = sampleText,
            group = group,
            targetScope = normalizeTargetScope(runCatching { rule.targetScope }.getOrDefault(HighlightRule.TARGET_ALL)),
            enabled = runCatching { rule.enabled }.getOrDefault(true),
            textColor = runCatching { rule.textColor }.getOrNull(),
            underlineMode = runCatching { rule.underlineMode }.getOrDefault(0).coerceIn(0, 5),
            underlineColor = runCatching { rule.underlineColor }.getOrNull(),
            underlineWidth = runCatching { rule.underlineWidth }.getOrDefault(1f).coerceIn(0.1f, 10f),
            underlineOffset = runCatching { rule.underlineOffset }.getOrDefault(2f).coerceIn(0f, 20f),
            underlineSvgPath = underlineSvgPath,
            bgImage = bgImage,
            bgImageFit = runCatching { rule.bgImageFit }.getOrDefault(0).coerceIn(0, 2),
            bgImageScale = runCatching { rule.bgImageScale }.getOrDefault(1f).coerceIn(0.1f, 5f),
            // 修复：补齐 F-P1-2 新增字段（isRegex/styleJson/timeoutMillisecond），否则保存后样式丢失
            isRegex = runCatching { rule.isRegex }.getOrDefault(false),
            styleJson = runCatching { rule.styleJson }.getOrNull(),
            timeoutMillisecond = runCatching { rule.timeoutMillisecond }.getOrDefault(3000L),
            // B15: 保留捕获组样式模板与 dotAll 开关
            replacement = runCatching { rule.replacement }.getOrNull().orEmpty(),
            isDotAll = runCatching { rule.isDotAll }.getOrDefault(false),
        )
    }

    private fun buildSanitizedRuleId(
        name: String,
        pattern: String,
        sampleText: String,
        group: String,
    ): String {
        val seed = listOf(name, pattern, sampleText, group).joinToString("|")
        return "${System.currentTimeMillis()}_${seed.hashCode().toUInt().toString(16)}"
    }

    private fun normalizeTargetScope(value: Int, fallback: Int = HighlightRule.TARGET_ALL): Int {
        return when (value) {
            HighlightRule.TARGET_ALL,
            HighlightRule.TARGET_TITLE,
            HighlightRule.TARGET_BODY -> value
            else -> fallback
        }
    }

    private fun restoreRuleBgImage(
        context: Context,
        backupRootPath: String?,
        bgImage: String?,
    ): String? {
        val path = bgImage ?: return null
        if (path.isBlank() || path.startsWith("assets://")) return path
        val rootPath = backupRootPath ?: return path
        val backupFile = File(rootPath, "$backupBgDirName${File.separator}${File(path).name}")
            .takeIf { it.exists() && it.isFile }
            ?: return path
        val dir = File(context.filesDir, "bg_images")
        if (!dir.exists()) {
            dir.mkdirs()
        }
        val targetFile = File(dir, backupFile.name)
        if (!targetFile.exists() || targetFile.length() != backupFile.length()) {
            backupFile.copyTo(targetFile, overwrite = true)
        }
        return targetFile.absolutePath
    }

    private fun shouldRefreshBuiltin(rule: HighlightRule, builtin: HighlightRule): Boolean {
        if (rule.id !in builtinIds) return false
        // R-1 修复：isRegex=false 仅在 pattern 与内置一致时才触发愈合（用户未改 pattern）
        // 用户改过 pattern 的内置规则不触发愈合，保留用户修改
        if (!rule.isRegex && rule.pattern == builtin.pattern) return true
        val inspectText = buildString {
            append(rule.name)
            append(rule.pattern)
            append(rule.sampleText)
        }
        return garbledMarkers.any { inspectText.contains(it) } ||
            // F3/2.5 演进覆盖：pattern 命中历史版本登记值（未做个性化修改）→ 允许升级
            legacyBuiltinPatterns[rule.id]?.contains(rule.pattern) == true
    }

    private val builtinIds = setOf(
        "dialog_default",
        "book_title_default",
        "bracket_note_default",
        "title_emphasis_default",
        "thought_default",
        "narrator_default",
        "emphasis_default",
        "poetry_default",
        "ellipsis_default",
        "number_default",
        "english_default",
        "date_time_default",
        // F4/4.5：v2 新增 12 条（同步扩 builtinIds 享受愈合保护）
        "dialogue_speaker_default",
        "dialogue_para_default",
        "dash_dialogue_default",
        "system_panel_default",
        "chapter_en_default",
        "onomatopoeia_default",
        "url_muted_default"
        // R12.3(b)：markdown_bold_default / thought_wide_default / narrator_wide_default /
        // number_wide_default / poetry_wide_default 已退役 → 移出 builtinIds（不再参与愈合）
    )

    /**
     * 历史内置正则登记表（F3/2.5 演进覆盖）：value 为该 id 全部历史版本 pattern 集合。
     * 用户 pattern 命中集合（=未做个性化修改，仅持有旧版本值）时允许内置新版覆盖升级；
     * 用户真改过（不在集合）则保留——SP 链无删除墓碑，此表即"版本演进 vs 用户修改"的判定边界。
     */
    private val legacyBuiltinPatterns: Map<String, Set<String>> = mapOf(
        "dialog_default" to setOf(
            // v0 捕获组版
            "[“\"]([^”\"\n]{1,120})[”\"]|「[^」\n]{1,120}」|『[^』\n]{1,120}』",
            // v1 限长 120 版（v2 放宽至 400，存量 120 用户升级后应得 400）
            "“[^”\n]{1,120}”|\"[^\"\n]{1,120}\"|「[^」\n]{1,120}」|『[^』\n]{1,120}』"
        ),
        "book_title_default" to setOf("《[^》\n]{1,80}》"),
        "bracket_note_default" to setOf("（[^）\n]{1,80}）|\\([^\\)\n]{1,80}\\)|【[^】\n]{1,80}】"),
        "title_emphasis_default" to setOf("(?m)^(第[0-9零一二三四五六七八九十百千两0123456789IVXLCDMivxlcdm]{1,12}[章节回卷部篇集幕]|序章|楔子|引子|终章|尾声|后记|番外)[^\n]{0,40}$"),
        "thought_default" to setOf(
            "（[^）]*?(想道|暗道|心道|心里|想着|思量|思忖|盘算|盘算着)[^）]*?）",
            // R12.3(b)：收敛前的 v2 窄版（未个性化用户愈合到「宽窄并集」新 pattern）
            "（[^）\\n]{0,40}(?:心想|暗道|心道|想到|寻思着|琢磨|嘀咕)[^）\\n]{0,40}）"
        ),
        "narrator_default" to setOf(
            "（以下\\S{0,20}省略|省略\\S{0,20}内容|[^\n]{0,20}的情景不再赘述|[^\n]{0,20}的情况不再多说）",
            // R12.3(b)：收敛前的 v2 窄版
            "(?:未完待续|待续|下文再表|按：?|注：?)[^\n]{0,40}|（(?:注|旁白|作者有话说)[:：][^）\n]{0,40}）"
        ),
        "emphasis_default" to setOf("[*！]{1,2}[^*\n]{1,50}[*！]{1,2}"),
        "poetry_default" to setOf(
            "[\n]([七五言绝句律诗词牌曲牌][^\n]{0,60}[^\n]{10,50}[^\n]{0,20}[，。！？])\n",
            // R12.3(b)：收敛前的 v2 窄版
            "(?m)^[\\p{IsHan}，。！？；：、]{5,24}$"
        ),
        "ellipsis_default" to setOf("x{2,}|\\*{2,}|\\.{2,}"),
        "number_default" to setOf(
            "[0-9零一二三四五六七八九十百千万亿]+[元块美元英镑]|[0-9]+[%％]",
            // R12.3(b)：收敛前的 v2 窄版
            "(?:¥|￥)?\\d+(?:\\.\\d+)?(?:元|块|万|千|百|亿|%|％)|[零〇一二两三四五六七八九十百千万亿]+(?:元|块|万|千|百|亿)"
        ),
        "english_default" to setOf("[a-zA-Z]{2,}[a-zA-Z0-9'-]*"),
        "date_time_default" to setOf("[0-9零一二三四五六七八九十]+年[0-9零一二三四五六七八九十]+月[0-9零一二三四五六七八九十]*日?|[0-9]+点[0-9零一二三四五六七八九十]*分?")
    )

    // 乱码标记（GBK↔UTF-8 误解码特征字），以 \u 转义书写以避免源码文件出现乱码字面量
    private val garbledMarkers = listOf(
        "\u950B", "\u9286", "\u9225", "\u7035", "\u6D94",
        "\u93CD", "\u942A", "\u93C8", "\u7ED7"
    )

    // ---------------- R12.3(b)：内置规则退役与存量孤儿处置 ----------------

    /** 已退役标记（追加到保留条目的 name 上，使「列表看不见却仍生效」的孤条目变为可见） */
    const val RETIRED_SUFFIX = "(已退役)"

    /**
     * **已退役内置 id 表**（id → 该 id 的全部历史 pattern 集合）。
     *
     * 退役原因（§9.5.1 语义收敛）：
     * - `markdown_bold_default`：`**…**` 被 `emphasis_default` 首分支完全包含 → **等价合并**；
     * - `thought_wide_default` / `narrator_wide_default` / `number_wide_default` / `poetry_wide_default`：
     *   宽窄版不并列共存，关键词/分支已并入主形态并集 pattern → **合并退役**。
     *
     * 用途：判定「是否被个性化」——条目 pattern 命中本表（=用户持有内置原值）且无自定义样式 → 可安全移除；
     * 否则保留（不清空用户数据）并标注 [RETIRED_SUFFIX]。
     */
    private val retiredBuiltinPatterns: Map<String, Set<String>> = mapOf(
        "markdown_bold_default" to setOf("\\*\\*[^\\n*]{1,40}\\*\\*"),
        "thought_wide_default" to setOf(
            "（[^）\\n]{0,60}(?:想道|暗道|心道|心里|思量|思忖|盘算)[^）\\n]{0,60}）"
        ),
        "narrator_wide_default" to setOf(
            "（(?:以下[^\\n]{0,20}省略|注[:：])[^\\n]{0,40}）|[^\\n]{0,20}(?:不再赘述|不再多说)"
        ),
        "number_wide_default" to setOf(
            "[0-9零一二三四五六七八九十百千万亿]+(?:元|块|美元|英镑)|[0-9]+[%％]"
        ),
        "poetry_wide_default" to setOf(
            "[\\n]([七五言绝句律诗词牌曲牌][^\\n]{0,60}[^\\n]{10,50}[^\\n]{0,20}[，。！？])"
        )
    )

    /** 退役处置结果 */
    internal data class RetireResult(
        val rules: List<HighlightRule>,
        /** 被移除的条目数（未个性化的退役条目） */
        val removed: Int,
        /** 被追加「(已退役)」标注的条目数（本次实际变更量） */
        val renamed: Int,
    ) {
        val changed: Boolean get() = removed > 0 || renamed > 0
    }

    /**
     * R12.3(b)：退役内置条目的**幂等**处置（纯函数，供 JVM 单测）。
     *
     * - 命中 [retiredBuiltinPatterns] 且**未被个性化**（pattern 为内置原值 + 无自定义 `styleJson`）→ **移除**；
     * - 已个性化（改过 pattern 或存过样式）→ **保留**并标注 [RETIRED_SUFFIX]（用户数据不丢，且在列表中可见）；
     * - 非退役 id → 原样返回。
     *
     * 幂等性：已移除者下次无此条目（no-op）；已标注者 name 已含标记 → 不重复追加（[RetireResult.renamed] 不再增长）。
     */
    internal fun retireBuiltinRules(
        rules: List<HighlightRule>,
        retiredPatterns: Map<String, Set<String>> = retiredBuiltinPatterns,
    ): RetireResult {
        if (retiredPatterns.isEmpty()) return RetireResult(rules, 0, 0)
        val out = ArrayList<HighlightRule>(rules.size)
        var removed = 0
        var renamed = 0
        for (rule in rules) {
            val historical = retiredPatterns[rule.id]
            if (historical == null) {
                out.add(rule)
                continue
            }
            val customized = rule.styleJson != null || rule.pattern !in historical
            if (!customized) {
                removed++
                continue
            }
            val name = rule.name.orEmpty()
            if (name.endsWith(RETIRED_SUFFIX)) {
                out.add(rule)
            } else {
                renamed++
                out.add(rule.copy(name = "$name$RETIRED_SUFFIX"))
            }
        }
        return RetireResult(out, removed, renamed)
    }
}
