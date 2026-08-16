package io.legado.app.ui.widget.components

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp

/**
 * 全局统一圆角 token（样式统一专项，task 12.1A）。
 *
 * 根因（2026-08-16 审计）：此前各组件/页面硬编码 `RoundedCornerShape(N.dp)`，
 * N 从 2 到 18 发散（卡 18/12/8/10/6/4、Sheet 16/12、按钮 12/8 混用），
 * 导致已 Compose 化页面视觉不统一。本文件收敛为 ui-standards §1 全局基线：
 * **卡 18dp / 按钮 12dp / 底部弹层顶角 16dp**，并派生通用 token。
 *
 * 使用约定：新代码一律引用本 token，禁止再硬编码 `RoundedCornerShape`。
 * 存量硬编码点逐步迁移（见 tasks 12.1A）。
 */
object AppShapes {
    /** 卡片容器（SettingsCard/卡片化条目/弹窗）：18dp */
    val Card: RoundedCornerShape = RoundedCornerShape(18.dp)

    /** 按钮（M3 默认 12dp 圆角即本值）：12dp */
    val Button: RoundedCornerShape = RoundedCornerShape(12.dp)

    /** 底部弹层顶角（ModalBottomSheet）：16dp */
    val SheetTop: RoundedCornerShape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)

    /** 图标容器（MetricTile 内图标底/小功能图标）：10dp */
    val IconContainer: RoundedCornerShape = RoundedCornerShape(10.dp)

    /** 小标签/Chip/缩略小图：8dp */
    val Chip: RoundedCornerShape = RoundedCornerShape(8.dp)

    /** 极小块状元素（进度标/点状装饰）：4dp */
    val Tiny: RoundedCornerShape = RoundedCornerShape(4.dp)
}
