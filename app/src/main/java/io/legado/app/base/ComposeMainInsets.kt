package io.legado.app.base

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.legado.app.R

/**
 * 主壳底栏安全区「单源」（CP-1）。
 *
 * 为什么必须有这一层：主壳 `activity_main.xml` 中 `content_container` 与 `bottom_controls`
 * 是**同层兄弟** ⇒ 底栏是 overlay（覆盖在内容之上），承载于 `LockableViewPager` 内的页面
 * **必须自加底部留白**，否则滚动到末尾的最后若干项会被底栏盖住（用户 2026-09-24 报障）。
 *
 * 留白 = 单源 dimen `main_content_bottom_bar_padding`（90dp）+ **导航栏 inset**：
 * 底栏可见高随导航模式变化（手势导航 inset≈0 / 三键导航 inset≈48dp），View 侧的
 * `applyMainBottomBarPadding` 本来就含 inset；Compose 侧历史实现只取静态 dp
 * （另有 86dp 硬编码三处）⇒ 在三键导航设备上必然遮挡。故此处统一收口。
 *
 * 禁止在页面内硬编码底部留白值，统一引用本函数（门禁 `audit_bottom_bar_inset.py` 会校验）。
 */
@Composable
fun mainBottomBarContentPadding(
    start: Dp = 0.dp,
    top: Dp = 0.dp,
    end: Dp = 0.dp,
    extraBottom: Dp = 0.dp,
): PaddingValues {
    val base = dimensionResource(R.dimen.main_content_bottom_bar_padding)
    val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    return PaddingValues(
        start = start,
        top = top,
        end = end,
        bottom = base + navBottom + extraBottom,
    )
}

/**
 * 同上，但以 [Modifier] 形式提供，供 Column / ScrollView 等非 Lazy 列表场景使用。
 */
@Composable
fun Modifier.mainBottomBarPadding(extra: Dp = 0.dp): Modifier =
    padding(bottom = mainBottomBarContentPadding(extraBottom = extra).calculateBottomPadding())

/**
 * 主壳底栏**实际占位高**（= 底栏上沿到屏幕底的距离）。
 *
 * 与 [mainBottomBarContentPadding] 的区别：后者是**内容留白**（`main_content_bottom_bar_padding` = 90dp，
 * 含视觉余量，供列表滚动末尾避让）；本函数是**底栏真实高度**——`main_bottom_bar_height`（48dp）
 * + `main_bottom_controls_bottom_padding`（10dp）+ 导航栏 inset。
 *
 * 用途：需要「**紧贴底栏上沿**」的悬浮元素（如书架续读条的 overlay 定位）。若误用内容留白口径，
 * 会多出约 32dp 空档（用户 2026-10-02 报障「离底栏上部还是很远，中间至少有续读条两倍的空档」）。
 */
@Composable
fun mainBottomBarActualHeight(): Dp {
    val barHeight = dimensionResource(R.dimen.main_bottom_bar_height)
    val controlsBottomPadding = dimensionResource(R.dimen.main_bottom_controls_bottom_padding)
    val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    return barHeight + controlsBottomPadding + navBottom
}