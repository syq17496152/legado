package io.legado.app.ui.widget.compose

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
 * Page-level spacing tokens (B2 freeze, 2026-08-30).
 * All values sit on the 4dp grid. Legacy [AppListSpacing] stays untouched
 * and must not spread to new code (6dp is a registered half-step exemption).
 */
object AppPageSpacing {
    /** 页面左右安全边距 */
    val PageHorizontal = 16.dp
    /** 顶栏下内容起始间距 */
    val PageTop = 8.dp
    /** 区块之间（表单分组/信息区） */
    val SectionGap = 16.dp
    /** 卡片与卡片之间 */
    val CardGap = 12.dp
    /** 行内元素间距（图标-文字） */
    val ItemGapInline = 8.dp
    /** 滚动列表尾部留白（无底栏页） */
    val ListBottom = 24.dp
    /** 列表尾部 FAB + 导航桥接避让 */
    val NavBridgeBottom = 88.dp
}
