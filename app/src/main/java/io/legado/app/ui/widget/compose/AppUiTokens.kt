package io.legado.app.ui.widget.compose

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

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
}
