package io.legado.app.ui.book.read.config

/**
 * 听书中心状态机（T5.1 / ui-design §2/§8）：纯函数，JVM 可单测。
 *
 * 三卡固定映射到已有内置模板（不新造第二套模板语义）：
 * - 普通朗读 → `builtin_mono`（单声，关闭多人）
 * - 双声对读 → `builtin_dual_voice`（旁白/对白两音色）
 * - 沉浸多角色 → `builtin_immersive_multi`（角色级配声，推荐）
 * - 自定义模板不再占卡位（4.4），只在状态行提示当前模板名
 *
 * 主按钮文案三态：未就绪→「启用离线朗读」；多角色未准备→「识别角色并开始」；其余→「开始听书」。
 */
data class SceneCardRow(
    val id: String,
    val title: String,
    val subtitle: String,
    val selected: Boolean,
    val recommended: Boolean = false
)

/** 三步引导的一步（spec R2：不阻塞 —— 缺失时只提示当前该做什么，不把资产当门槛） */
data class GuideStep(
    val title: String,
    val done: Boolean
)

/** 高级区一个入口（4.1 高级项可见性）：id 决定点击去向，由状态机按可用条件收敛 */
data class SceneAdvancedEntry(
    val id: String,
    val label: String
)

data class SceneSnapshot(
    val cards: List<SceneCardRow>,
    val statusLine: String,
    val primaryLabel: String,
    val primaryEnabled: Boolean,
    val engineReady: Boolean,
    val selectedCard: String,
    /** 本章准备度三数字（页面顶部统计卡用，缺省 0 便于既有调用方不受影响） */
    val characterCount: Int = 0,
    val assignedCount: Int = 0,
    val storyboardSegments: Int = 0,
    /** 三步引导（引擎 / 音色 / 开听） */
    val guideSteps: List<GuideStep> = emptyList(),
    /** 资源概览一行（音色 · 模板 · 配乐 · 占用），空串则不显示 */
    val resourceLine: String = "",
    /** 本章剧本状态行（3.7：未生成/生成中/已生成/降级），空串则不显示 */
    val scriptLine: String = "",
    /** 3.5：本地 AI 可用 ⇒ 显示「生成剧本」按钮（缺省 false，既有调用方不受影响） */
    val canGenerateScript: Boolean = false,
    /** 高级区可见入口（4.1）：按有无书/引擎是否就绪收敛，避免"点进去也做不了事" */
    val advancedEntries: List<SceneAdvancedEntry> = emptyList(),
    /** 1.7 顶部胶囊：引擎档位 + 剧本来源（空串 = 不展示） */
    val capsule: String = ""
)

object CastSceneState {

    const val CARD_MONO = "mono"
    const val CARD_DUAL = "dual"
    const val CARD_MULTI = "multi"
    const val CARD_CUSTOM = "custom"

    const val TEMPLATE_MONO = "builtin_mono"
    const val TEMPLATE_DUAL = "builtin_dual_voice"
    const val TEMPLATE_MULTI = "builtin_immersive_multi"

    /** 卡 → 模板 id（"自定义"不在此表，走模板管理） */
    fun templateOf(cardId: String): String? = when (cardId) {
        CARD_MONO -> TEMPLATE_MONO
        CARD_DUAL -> TEMPLATE_DUAL
        CARD_MULTI -> TEMPLATE_MULTI
        else -> null
    }

    /** 当前激活模板 → 选中的卡（未设置模板=默认单声 ⇒ 普通朗读；其它模板=自定义） */
    fun cardOf(templateId: String?): String = when (templateId) {
        null -> CARD_MONO
        TEMPLATE_MONO -> CARD_MONO
        TEMPLATE_DUAL -> CARD_DUAL
        TEMPLATE_MULTI -> CARD_MULTI
        else -> CARD_CUSTOM
    }

    /** 卡序号 → 视觉圆标（A/B/C/D，纯函数可单测）：窄屏两列网格靠它快速指认 */
    fun badgeOf(index: Int): String = ('A' + index.coerceIn(0, 25)).toString()

    /**
     * 一键编排（4.7）触发条件：多角色卡且角色还没识别/配声。
     * 抽成纯函数是为了让主按钮文案与点击分支**同源**，避免再出现"文案说识别、点击却直接起播"。
     */
    fun needsOneTapPrepare(cardId: String, characterCount: Int, assignedCount: Int): Boolean =
        cardId == CARD_MULTI && (characterCount <= 0 || assignedCount <= 0)

    /**
     * 三步引导（spec R2「可跳过、不阻塞」）：
     * 只表达「现在到哪一步」，不把资产配置变成听书前置门槛 —— 系统语音始终可听。
     */
    fun guideSteps(engineReady: Boolean, voiceCount: Int, hasBook: Boolean): List<GuideStep> = listOf(
        GuideStep("① 离线引擎就绪", engineReady),
        GuideStep("② 音色可用", voiceCount > 0),
        GuideStep("③ 打开一本书听书", hasBook && engineReady && voiceCount > 0)
    )

    /** 资源概览一行：让用户在听书中心就能看到"我有什么、占多少空间"，不必翻到设置里找 */
    fun resourceLine(
        voiceCount: Int,
        templateCount: Int,
        bgmCount: Int,
        storageBytes: Long
    ): String {
        val mb = storageBytes / (1024L * 1024L)
        return "音色 $voiceCount · 模板 $templateCount · 配乐 $bgmCount · 离线资产 ${mb}MB"
    }

    /**
     * 4.6 二级页职责收敛：制作/调试留在「引擎与音色」页，角色配音与开书向导的**入口**
     * 收敛到听书中心高级区；key 与 `PageTab.key` 单源（深链），避免两套字符串漂移。
     */
    val advancedPageEntries: List<Pair<String, String>> = listOf(
        "roles" to "角色配音",
        "wizard" to "开书向导"
    )

    /**
     * 1.7 顶部胶囊（纯函数可单测）：一次说完「现在用什么引擎、剧本到哪一步」。
     * 剧本三态与 `ScriptStatusBus` 对齐（未生成 / 已生成 / 降级），不额外造第四种说法。
     */
    fun capsuleText(
        engineTitle: String,
        engineReady: Boolean,
        scriptReady: Boolean,
        scriptDegraded: Boolean
    ): String {
        val engine = if (engineReady) {
            engineTitle.ifBlank { "离线引擎" }
        } else {
            "系统语音"
        }
        val script = when {
            !scriptReady -> "剧本未生成"
            scriptDegraded -> "剧本降级"
            else -> "剧本就绪"
        }
        return "$engine · $script"
    }

    /** 低端机门槛（README：「低端 4GB 三卡可用，本地 AI 入口不出现」）：RAM < 8GB 不开放端侧大模型 */
    const val LOW_END_RAM_MB = 8000L

    fun isLowEndDevice(totalRamMb: Long): Boolean = totalRamMb < LOW_END_RAM_MB

    /**
     * AI 入口可见性（4.1 高级项可见性 / 7.7 特性关闭交付）：
     * 低端机或用户没开本地 AI ⇒ **不显示**入口（不是灰色）；
     * 但已有 AI 数据（剧本草案/模型）必须仍可见 —— 否则用户无法清理遗留数据。
     */
    fun aiEntryVisible(lowEndDevice: Boolean, localAiEnabled: Boolean, hasAiData: Boolean): Boolean =
        hasAiData || (!lowEndDevice && localAiEnabled)

    /**
     * 高级区入口表（4.1）：只放"现在真能用"的项。
     * - 批量预合成需要离线引擎（系统语音无法预合成）且必须有书；
     * - 分镜编辑/角色配音/开书向导必须有书（无书时点进去只能看到空态）。
     */
    fun advancedEntriesOf(hasBook: Boolean, engineReady: Boolean): List<SceneAdvancedEntry> = buildList {
        add(SceneAdvancedEntry("voices", "引擎与音色"))
        add(SceneAdvancedEntry("templates", "选角模板"))
        if (hasBook && engineReady) add(SceneAdvancedEntry("prebuild", "批量预合成"))
        add(SceneAdvancedEntry("bgm", "配乐库"))
        if (hasBook) add(SceneAdvancedEntry("storyboard", "分镜编辑"))
        add(SceneAdvancedEntry("tuning", "语速音调"))
        add(SceneAdvancedEntry("diagnostics", "诊断"))
        if (hasBook) {
            advancedPageEntries.forEach { (tabKey, label) -> add(SceneAdvancedEntry(tabKey, label)) }
        }
    }

    /**
     * 使用提示（纯函数可单测）：首次进本页的人不用去翻文档，
     * 也顺带把"开听后怎么回来改"说清楚（页面内容偏短，空区靠它填得有意义）。
     */
    fun usageTips(): List<String> = listOf(
        "普通朗读：用当前引擎的默认音色，不逐段换声",
        "沉浸多角色：先识别角色，再按角色逐段换声",
        "开听后想换方式：回阅读页侧拉菜单再进本页"
    )

    /**
     * 场景卡配乐开关摘要（纯函数可单测）：关=未开启；开但配乐库为空=提示去添加；
     * 开且有配乐=给出可用数量。文案与真实播放条件（开关开 + 有已启用配乐）保持一致。
     */
    fun bgmSummary(enabled: Boolean, trackCount: Int): String = when {
        !enabled -> "未开启 · 打开后朗读时播放配乐"
        trackCount <= 0 -> "已开启 · 暂无配乐，点此去添加"
        else -> "已开启 · 可用配乐 $trackCount 首（与朗读混音）"
    }

    /** 打开配乐开关的前置条件：配乐库至少一首已启用配乐（否则引导去添加，不写开关） */
    fun canEnableBgm(trackCount: Int): Boolean = trackCount > 0

    /**
     * @param engineReady 内置引擎就绪（模型 READY + 有音色）
     * @param characterCount 本书已识别角色数
     * @param assignedCount 已配声角色数
     * @param storyboardSegments 当前章离线分镜段数（0 = 未产出）
     * @param activeTemplateId 当前激活模板
     * @param customName 当前模板显示名（自定义模板状态行用）
     * @param hasBook 是否已打开书（未开书时禁用主按钮）
     * @param voiceCount 可用音色数（三步引导第②步与资源概览）
     * @param templateCount 选角模板数（资源概览）
     * @param bgmCount 已启用配乐数（资源概览）
     * @param storageBytes 离线资产占用（模型+音色+缓存）
     * @param scriptStatusLabel 本章剧本状态文案（3.7；空串 = 不展示）
     * @param engineTitle 当前离线档位名（1.7 胶囊）
     * @param scriptReady 本章剧本已生成（1.7 胶囊）
     * @param scriptDegraded 本章剧本降级（1.7 胶囊）
     */
    fun evaluate(
        engineReady: Boolean,
        characterCount: Int,
        assignedCount: Int,
        storyboardSegments: Int,
        activeTemplateId: String?,
        customName: String = "",
        hasBook: Boolean = true,
        voiceCount: Int = 0,
        templateCount: Int = 0,
        bgmCount: Int = 0,
        storageBytes: Long = 0L,
        scriptStatusLabel: String = "",
        engineTitle: String = "",
        scriptReady: Boolean = false,
        scriptDegraded: Boolean = false,
        /** 3.5：本地 AI 可用（决定「生成剧本」按钮是否出现，不改变起播能力） */
        canGenerateScript: Boolean = false
    ): SceneSnapshot {
        val selected = cardOf(activeTemplateId)
        val multiSubtitle = if (characterCount <= 0) {
            "新书，未识别角色"
        } else {
            "已识别 $characterCount 个角色 · 音色已配 $assignedCount 个"
        }
        // 4.4：卡片收敛为 3 张（单声 / 双声 / 智能多角色）；「自定义模板」不再占卡位，
        // 移入高级区（选角模板 chip）。已激活自定义模板时用状态行说明，避免用户"看不到当前选择"。
        val cards = listOf(
            SceneCardRow(CARD_MONO, "普通朗读", "当前引擎 · 单声", selected == CARD_MONO),
            SceneCardRow(CARD_DUAL, "双声对读", "旁白 + 对白两个音色", selected == CARD_DUAL),
            SceneCardRow(
                CARD_MULTI, "沉浸多角色", multiSubtitle, selected == CARD_MULTI, recommended = true
            )
        )
        val statusLine = when {
            !hasBook -> "请先打开一本书（在阅读页侧拉菜单打开「听书」）"
            !engineReady -> "离线引擎未就绪：先下载/导入语音包，再回来一键启用"
            selected == CARD_CUSTOM ->
                "当前为自定义模板「${customName.ifBlank { "未命名" }}」· 可在「选角模板」里更换"
            selected == CARD_MULTI && storyboardSegments <= 0 ->
                if (characterCount <= 0) {
                    "角色识别：未开始"
                } else {
                    "角色识别：已完成 · 本章还没有分镜" + scriptStatusHint(scriptStatusLabel)
                }
            selected == CARD_MULTI ->
                "角色识别：已完成 · 本章 $storyboardSegments 段分镜" + scriptStatusHint(scriptStatusLabel)
            else -> "当前为「${cards.first { it.selected }.title}」；需要更细的模板可在「选角模板」里改"
        }
        // 4.11 开箱即用（spec R2）：离线引擎未就绪也**不阻塞** —— 主按钮直接可听（回落系统语音），
        // 「启用离线」变成状态行里的提示与「引擎与音色」入口，而不是挡在用户前面的门槛。
        val primaryLabel = when {
            !hasBook -> "请先打开一本书"
            !engineReady -> "开始听书（系统语音）"
            needsOneTapPrepare(selected, characterCount, assignedCount) -> "识别角色并开始"
            else -> "开始听书"
        }
        return SceneSnapshot(
            cards = cards,
            statusLine = statusLine,
            primaryLabel = primaryLabel,
            primaryEnabled = hasBook,
            engineReady = engineReady,
            selectedCard = selected,
            characterCount = characterCount,
            assignedCount = assignedCount,
            storyboardSegments = storyboardSegments,
            guideSteps = guideSteps(engineReady, voiceCount, hasBook),
            resourceLine = resourceLine(voiceCount, templateCount, bgmCount, storageBytes),
            scriptLine = if (selected == CARD_MULTI && scriptStatusLabel.isNotBlank()) {
                "本章剧本：$scriptStatusLabel"
            } else {
                ""
            },
            canGenerateScript = canGenerateScript,
            advancedEntries = advancedEntriesOf(hasBook, engineReady),
            // 1.7：胶囊只在有书时才有意义（未开书时剧本状态无从谈起）
            capsule = if (hasBook) {
                capsuleText(engineTitle, engineReady, scriptReady, scriptDegraded)
            } else {
                ""
            }
        )
    }

    /** 状态行里的剧本提示（3.7）：只在多角色卡展示，避免单声/双声用户被无关状态干扰 */
    private fun scriptStatusHint(label: String): String =
        if (label.isBlank()) "" else " · 本章剧本：$label"
}
