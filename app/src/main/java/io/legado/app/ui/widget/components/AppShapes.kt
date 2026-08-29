package io.legado.app.ui.widget.components

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
import io.legado.app.lib.theme.UiCorner

/**
 * 全局统一圆角 token（样式统一专项，task 12.1A / ui-theme-gap-audit G2）。
 *
 * 根因（2026-08-16 审计）：此前各组件/页面硬编码 `RoundedCornerShape(N.dp)`，
 * N 从 2 到 18 发散（卡 18/12/8/10/6/4、Sheet 16/12、按钮 12/8 混用），
 * 导致已 Compose 化页面视觉不统一。本文件收敛为 ui-standards §1 全局基线：
 * **卡 18dp / 按钮 12dp / 底部弹层顶角 16dp**，并派生通用 token。
 *
 * ui-theme-gap-audit G2（2026-08-26）：全部 token 改为 getter 随「圆角倍率」
 * （AppConfig.uiCornerScale，UiCorner.scale()）缩放——修复「主题改圆角倍率对
 * Compose 组件不生效」的 P0 联动缺口；特殊语义（Circle/Capsule/CornerZero）
 * 不随倍率（圆形/胶囊必须保持正圆，否则变椭圆）。
 *
 * 使用约定：新代码一律引用本 token，禁止再硬编码 `RoundedCornerShape`。
 * 存量硬编码点逐步迁移（见 tasks 12.1A）。
 */
object AppShapes {
    /** 卡片容器（SettingsCard/卡片化条目/弹窗）：18dp，随圆角倍率 */
    val Card: RoundedCornerShape get() = RoundedCornerShape((18 * UiCorner.scale()).dp)

    /** 按钮（M3 默认 12dp 圆角即本值）：12dp，随圆角倍率 */
    val Button: RoundedCornerShape get() = RoundedCornerShape((12 * UiCorner.scale()).dp)

    /** 搜索框（统一为 archive 订阅头部 searchEntry 口径）：18dp，随圆角倍率 */
    val Search: RoundedCornerShape get() = RoundedCornerShape((18 * UiCorner.scale()).dp)

    /** 底部弹层顶角（ModalBottomSheet）：16dp，随圆角倍率 */
    val SheetTop: RoundedCornerShape
        get() = RoundedCornerShape(topStart = (16 * UiCorner.scale()).dp, topEnd = (16 * UiCorner.scale()).dp)

    /** 图标容器（MetricTile 内图标底/小功能图标）：10dp，随圆角倍率 */
    val IconContainer: RoundedCornerShape get() = RoundedCornerShape((10 * UiCorner.scale()).dp)

    /** 小标签/Chip/缩略小图：8dp，随圆角倍率 */
    val Chip: RoundedCornerShape get() = RoundedCornerShape((8 * UiCorner.scale()).dp)

    /** 极小块状元素（进度标/点状装饰）：4dp，随圆角倍率 */
    val Tiny: RoundedCornerShape get() = RoundedCornerShape((4 * UiCorner.scale()).dp)

    /** 正圆（头像/圆形图标底），不随圆角倍率 */
    val Circle: RoundedCornerShape = RoundedCornerShape(50.dp)

    /** 胶囊（Chip/搜索胶囊等全圆角），不随圆角倍率 */
    val Capsule: RoundedCornerShape = RoundedCornerShape(999.dp)

    /** 直角，不随圆角倍率 */
    val CornerZero: RoundedCornerShape = RoundedCornerShape(0.dp)

    /** 通用圆角（按 dp 值，随圆角倍率）——存量硬编码 `RoundedCornerShape(N.dp)` 迁移入口 */
    fun rounded(dp: Int): RoundedCornerShape = RoundedCornerShape((dp * UiCorner.scale()).dp)
}
