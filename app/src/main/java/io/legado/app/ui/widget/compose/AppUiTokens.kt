package io.legado.app.ui.widget.compose

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import io.legado.app.ui.widget.components.contrastOn

/**
 * Shared dialog width tiers. Content type chooses the tier while every dialog in that tier
 * keeps the same phone width and tablet cap.
 */
enum class AppDialogSize(
    val widthFraction: Float,
    val maxWidthDp: Int
) {
    Confirm(widthFraction = 0.92f, maxWidthDp = 620),
    Form(widthFraction = 0.94f, maxWidthDp = 660),
    Management(widthFraction = 0.96f, maxWidthDp = 700),
    Wide(widthFraction = 0.98f, maxWidthDp = 760)
}

object AppListSpacing {
    val Compact = 6.dp
    val Normal = 8.dp
    val Section = 12.dp
}

/**
 * 语义色单源（AD-14：danger 语义色单一化）。
 *
 * `Danger` 真值 = `#D44848`：123 页蓝图原型全按 `--danger: #D44848` 绘制，代码若沿用 Material
 * 的 `#F44336`（`R.color.md_red_500`）则「截图对照原型」验收**永远不可能通过**；且 `#D44848`
 * 是已登记的收口值并配深底变体 `#FF7A6B`（见 `docs/UI/theme-tokens.md` §9.3）。
 *
 * ⚠️ 本 object 是 `docs/project-flow/ui-standards/architecture.md` 铁律 1「禁止硬编码色号」的
 * **登记豁免点**（语义色单源），其他任何位置禁止再写 danger 色值——
 * `ai_tests/scripts/ui_gate.py` 的 EXEMPT 已按此登记。
 */
object AppSemanticColors {
    val Danger = Color(0xFFD44848)

    /**
     * 警示语义色（**新增单源**，2026-09-21，code-edit F197 只读警示条/徽章引入）。
     * 真值取自原型帧 F 的 `--warning`（`preview-optimized.html` 内 `rgba(232,178,107,…)` ⇒ `#E8B26B`）：
     * 原型以「状态说明」而非「错误」呈现只读态，故不得复用 danger。
     */
    val Warning = Color(0xFFE8B26B)

    /**
     * danger 的 **ARGB Int 形式**（View 侧 / Int 参数场景复用）。
     * 避免各调用点再写 `Color(...).toArgb()` 甚至直接写字面色值（color.md §7.1 禁令）。
     */
    val DangerArgb: Int
        get() = Danger.toArgb()
}

/**
 * 主题令牌门面（AD-15：主题令牌载体收敛）。
 *
 * 背景：`docs/UI/COMPOSE_MIGRATION_PLAN.md:36`/`:162` 两处硬约束要求「颜色一律走 `AppUiTokens`」，
 * 但此前 `AppUiTokens` 全仓 0 命中（幽灵符号）。本 object 补齐为**真实门面**。
 *
 * ⚠️ 口径（tasks.md A3.3 第五批定案）：**只做门面转发，不做全量替换**——门面内部调用
 * `rememberAppDialogStyle()` / `rememberAppSettingPalette()` / `rememberAppManagementPalette()`
 * 并暴露语义命名，存量调用点零改动。**新增代码优先走本门面，禁止再自建取色链。**
 */
object AppUiTokens {

    /**
     * accent 底上的前景色**单源**（AD-15 第 3 条）：统一委托 [contrastOn]，
     * 禁止任何调用点再写 `if (isColorLight(accent)) Black else White`。
     */
    fun onAccent(accent: Color): Color = contrastOn(accent)

    /** 危险语义色真值（转发 [AppSemanticColors]，禁止在此写具体色值） */
    val danger: Color
        get() = AppSemanticColors.Danger

    /** 对话框取色链门面 */
    @Composable
    fun dialogStyle(): AppDialogStyle = rememberAppDialogStyle()

    /** 设置页取色链门面 */
    @Composable
    fun settingPalette(): AppSettingPalette = rememberAppSettingPalette()

    /** 管理页取色链门面 */
    @Composable
    fun managementPalette(): AppManagementPalette = rememberAppManagementPalette()
}
