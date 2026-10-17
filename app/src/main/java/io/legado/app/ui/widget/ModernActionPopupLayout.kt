package io.legado.app.ui.widget

import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.unit.IntOffset
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import io.legado.app.utils.dpToPx

/*
 * 现代动作弹层 · **布局与几何测量**（从 `ModernActionPopup.kt` 拆分而来，G-38 存量超限拆分）。
 *
 * 拆分口径（同包 ⇒ 调用点零改动）：把"**测量与定位**"这一族纯计算从"弹层编排 + 内容渲染"里分出来 ——
 * 前者只依赖 `View` 几何与常量，后者依赖 Compose 运行时；两者职责本就不同，混在一处才需要 800+ 行。
 *
 * ⚠️ `internal` 是**必须**的（不是随手放宽）：原先这些声明是 `object` 的成员/对象级 `private`，
 * 拆出后要能被 `ModernActionPopup` 调用 ⇒ 提为顶层 `internal`；同时**同包内不得再出现第二个同名声明**
 * （Kotlin 里同名顶层声明会与对象的成员争用名字解析）。
 */

internal const val MIN_WIDTH_DP = 124
internal const val MAX_WIDTH_DP = 244
internal const val ROW_HEIGHT_DP = 44
// F35（2026-09-21）：滑杆行需要固定宽面板（滑杆手感 + 快捷档位并排）
internal const val SLIDER_WIDTH_DP = 244
// F35：滑杆行估高（标题行 + 滑杆 + 档位行），用于首帧定位，避免与真实高度差过大而跳动
internal const val SLIDER_ROW_HEIGHT_DP = 132

/** 锚点与宿主几何的快照（一次测量结果，供定位/尺寸估算复用）。 */
internal data class AnchorSnapshot(
    val anchorLeft: Int,
    val anchorTop: Int,
    val anchorRight: Int,
    val anchorBottom: Int,
    val hostWidth: Int,
    val hostHeight: Int,
    val maxWidthPx: Int,
    val maxHeightPx: Int,
    val fallbackWidthPx: Int,
    val fallbackHeightPx: Int
)

internal fun calculateAnchorSnapshot(
    anchor: View,
    host: ViewGroup,
    actions: List<ModernActionPopup.Action>,
    maxHeightRatio: Float,
    bottomGapDp: Int
): AnchorSnapshot {
    val gap = 8.dpToPx()
    val bottomGap = bottomGapDp.dpToPx()
    val hostLocation = IntArray(2)
    val anchorLocation = IntArray(2)
    host.getLocationOnScreen(hostLocation)
    anchor.getLocationOnScreen(anchorLocation)
    val hostWidth = host.width.takeIf { it > 0 } ?: anchor.rootView.width
    val hostHeight = host.height.takeIf { it > 0 } ?: anchor.rootView.height
    val safeHostHeight = host.safePopupHostHeight(hostHeight)
    val anchorLeft = anchorLocation[0] - hostLocation[0]
    val anchorTop = anchorLocation[1] - hostLocation[1]
    val anchorRight = anchorLeft + anchor.width
    val anchorBottom = anchorTop + anchor.height
    val belowSpace = safeHostHeight - anchorBottom - gap - bottomGap
    val aboveSpace = anchorTop - gap
    val usableHeight = (safeHostHeight - gap * 2 - bottomGap).coerceAtLeast(1)
    val minimumHeight = minOf(72.dpToPx(), usableHeight).coerceAtLeast(1)
    val anchorSpace = maxOf(belowSpace, aboveSpace).coerceIn(
        minOf(48.dpToPx(), usableHeight).coerceAtLeast(1),
        usableHeight
    )
    val ratio = maxHeightRatio.coerceIn(0.35f, 0.9f)
    val ratioHeight = (usableHeight * ratio).toInt().coerceAtLeast(minimumHeight)
    val maxHeight = minOf(anchorSpace, ratioHeight)
        .coerceAtLeast(minimumHeight)
        .coerceAtMost(usableHeight)
    val maxWidth = (hostWidth - gap * 2).coerceAtLeast(1)
    val fallbackWidth = estimatePanelWidthPx(actions, maxWidth)
    val rowHeight = ROW_HEIGHT_DP.dpToPx()
    val fallbackHeight = estimatePanelHeightPx(actions, rowHeight, maxHeight, minimumHeight)
    return AnchorSnapshot(
        anchorLeft = anchorLeft,
        anchorTop = anchorTop,
        anchorRight = anchorRight,
        anchorBottom = anchorBottom,
        hostWidth = hostWidth,
        hostHeight = safeHostHeight,
        maxWidthPx = maxWidth,
        maxHeightPx = maxHeight,
        fallbackWidthPx = fallbackWidth,
        fallbackHeightPx = fallbackHeight
    )
}

internal fun calculatePanelOffset(
    snapshot: AnchorSnapshot,
    panelWidth: Int,
    panelHeight: Int
): IntOffset {
    val gap = 4.dpToPx()
    val desiredX = snapshot.anchorRight - panelWidth
    val x = desiredX.coerceIn(
        gap,
        (snapshot.hostWidth - panelWidth - gap).coerceAtLeast(gap)
    )
    val belowY = snapshot.anchorBottom + gap
    val aboveY = snapshot.anchorTop - panelHeight - gap
    val hasRoomBelow = belowY + panelHeight <= snapshot.hostHeight - gap
    val y = if (hasRoomBelow || aboveY < gap) {
        belowY
    } else {
        aboveY
    }.coerceIn(
        gap,
        (snapshot.hostHeight - panelHeight - gap).coerceAtLeast(gap)
    )
    return IntOffset(x, y)
}

internal fun calculateTransformOrigin(
    snapshot: AnchorSnapshot,
    panelOffset: IntOffset,
    panelWidth: Int,
    panelHeight: Int
): TransformOrigin {
    val anchorCenterX = (snapshot.anchorLeft + snapshot.anchorRight) / 2f
    val anchorCenterY = (snapshot.anchorTop + snapshot.anchorBottom) / 2f
    val pivotX = ((anchorCenterX - panelOffset.x) / panelWidth.coerceAtLeast(1))
        .coerceIn(0f, 1f)
    val pivotY = ((anchorCenterY - panelOffset.y) / panelHeight.coerceAtLeast(1))
        .coerceIn(0f, 1f)
    return TransformOrigin(pivotX, pivotY)
}

internal fun estimatePanelWidthPx(actions: List<ModernActionPopup.Action>, maxWidthPx: Int): Int {
    // F35：含滑杆行时按固定宽面板（滑杆长度 + 档位并排需要稳定宽度，不随标题长度抖动）
    if (actions.any { it.slider != null }) {
        return minOf(SLIDER_WIDTH_DP.dpToPx(), maxWidthPx).coerceAtLeast(1)
    }
    val longest = actions.maxOfOrNull { it.title.length } ?: 0
    val estimatedDp = (56 + longest.coerceAtMost(14) * 13)
        .coerceIn(MIN_WIDTH_DP, MAX_WIDTH_DP)
    return minOf(estimatedDp.dpToPx(), maxWidthPx).coerceAtLeast(1)
}

/** 面板高度估算（F35 起把滑杆行按真实行高计入，避免首帧定位与实测高度差过大而跳动） */
internal fun estimatePanelHeightPx(
    actions: List<ModernActionPopup.Action>,
    rowHeightPx: Int,
    maxHeight: Int,
    minimumHeight: Int
): Int {
    val sliderCount = actions.count { it.slider != null }
    val plainCount = actions.size - sliderCount
    val contentPx = plainCount * rowHeightPx + sliderCount * SLIDER_ROW_HEIGHT_DP.dpToPx() + 12.dpToPx()
    return minOf(maxHeight, contentPx.coerceAtLeast(minimumHeight))
}

internal fun View.safePopupHostHeight(hostHeight: Int): Int {
    val navigationBottom = ViewCompat.getRootWindowInsets(this)
        ?.getInsets(WindowInsetsCompat.Type.navigationBars())
        ?.bottom ?: 0
    return (hostHeight - navigationBottom).coerceAtLeast(1)
}
