package io.legado.app.ui.book.read.config

/**
 * 听书场景卡状态机（T5.1 / ui-design §2/§8）：纯函数，JVM 可单测。
 *
 * 四卡固定映射到已有内置模板（不新造第二套模板语义）：
 * - 普通朗读 → `builtin_mono`（单声，关闭多人）
 * - 双声对读 → `builtin_dual_voice`（旁白/对白两音色）
 * - 沉浸多角色 → `builtin_immersive_multi`（角色级配声，推荐）
 * - 自定义 → 其余模板（可跳模板管理）
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

data class SceneSnapshot(
    val cards: List<SceneCardRow>,
    val statusLine: String,
    val primaryLabel: String,
    val primaryEnabled: Boolean,
    val engineReady: Boolean,
    val selectedCard: String
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

    /**
     * @param engineReady 内置引擎就绪（模型 READY + 有音色）
     * @param characterCount 本书已识别角色数
     * @param assignedCount 已配声角色数
     * @param storyboardSegments 当前章离线分镜段数（0 = 未产出）
     * @param activeTemplateId 当前激活模板
     * @param customName 当前模板显示名（"自定义"卡副标题用）
     * @param hasBook 是否已打开书（未开书时禁用主按钮）
     */
    fun evaluate(
        engineReady: Boolean,
        characterCount: Int,
        assignedCount: Int,
        storyboardSegments: Int,
        activeTemplateId: String?,
        customName: String = "",
        hasBook: Boolean = true
    ): SceneSnapshot {
        val selected = cardOf(activeTemplateId)
        val multiSubtitle = if (characterCount <= 0) {
            "新书，未识别角色"
        } else {
            "已识别 $characterCount 个角色 · 音色已配 $assignedCount 个"
        }
        val customSubtitle = if (selected == CARD_CUSTOM) {
            "当前模板：${customName.ifBlank { "跟随默认（单声）" }}"
        } else {
            "可进入模板管理自选"
        }
        val cards = listOf(
            SceneCardRow(CARD_MONO, "普通朗读", "当前引擎 · 单声", selected == CARD_MONO),
            SceneCardRow(CARD_DUAL, "双声对读", "旁白 + 对白两个音色", selected == CARD_DUAL),
            SceneCardRow(
                CARD_MULTI, "沉浸多角色", multiSubtitle, selected == CARD_MULTI, recommended = true
            ),
            SceneCardRow(CARD_CUSTOM, "自定义", customSubtitle, selected == CARD_CUSTOM)
        )
        val statusLine = when {
            !hasBook -> "请先打开一本书（在阅读页侧拉菜单打开「听书场景卡」）"
            !engineReady -> "离线引擎未就绪：先下载/导入语音包，再回来一键启用"
            selected == CARD_MULTI && storyboardSegments <= 0 ->
                if (characterCount <= 0) "角色识别：未开始" else "角色识别：已完成 · 本章还没有分镜"
            selected == CARD_MULTI -> "角色识别：已完成 · 本章 $storyboardSegments 段分镜"
            else -> "当前为「${cards.first { it.selected }.title}」；需要更细的模板可点「自定义」"
        }
        val primaryLabel = when {
            !hasBook -> "请先打开一本书"
            !engineReady -> "启用离线朗读"
            selected == CARD_MULTI && (characterCount <= 0 || assignedCount <= 0) -> "识别角色并开始"
            else -> "开始听书"
        }
        return SceneSnapshot(
            cards = cards,
            statusLine = statusLine,
            primaryLabel = primaryLabel,
            primaryEnabled = hasBook,
            engineReady = engineReady,
            selectedCard = selected
        )
    }
}
