package io.legado.app.ui.widget

import android.content.Context
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.PopupMenu
import androidx.activity.OnBackPressedCallback
import androidx.annotation.MenuRes
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect as ComposeRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import io.legado.app.help.config.AppConfig
import io.legado.app.lib.theme.UiCorner
import io.legado.app.ui.widget.compose.AppDialogStyle
import io.legado.app.ui.widget.compose.LegadoMiuixChoiceRow
import io.legado.app.ui.widget.compose.installViewTreeOwnersFrom
import io.legado.app.ui.widget.compose.rememberAppDialogStyle
import io.legado.app.ui.widget.compose.toMiuixPalette
import io.legado.app.utils.activity
import io.legado.app.utils.dpToPx

object ModernActionPopup {

    private const val MIN_WIDTH_DP = 124
    private const val MAX_WIDTH_DP = 244
    private const val ROW_HEIGHT_DP = 44
    // F35（2026-09-21）：滑杆行需要固定宽面板（滑杆手感 + 快捷档位并排）
    private const val SLIDER_WIDTH_DP = 244
    // F35：滑杆行估高（标题行 + 滑杆 + 档位行），用于首帧定位，避免与真实高度差过大而跳动
    private const val SLIDER_ROW_HEIGHT_DP = 132

    /**
     * 滑杆行描述（F35，2026-09-21）——把传统 `PopupWindow` 滑杆收口进共享弹层族。
     *
     * 非空时该行渲染为「标题 + 当前值 + 滑杆 + 可选快捷档位」，不可点关闭；
     * 行的 `invoke` 不参与（留空即可）。默认 `null` ⇒ 全部既有调用点零改动。
     *
     * @property value 当前值（打开瞬间的快照，滑动后由行内状态接管）
     * @property valueRange 取值区间（`steps` 恒为连续，由调用方在 [onValueChange] 内自行量化）
     * @property valueText 值文案格式化（随滑动实时刷新）
     * @property presets 快捷档位（空 = 不渲染档位行）
     * @property onValueChange 取值变化回调（滑杆拖动 / 档位点击都会触发）
     */
    data class SliderSpec(
        val value: Float,
        val valueRange: ClosedFloatingPointRange<Float>,
        val valueText: (Float) -> String,
        val presets: List<Preset> = emptyList(),
        val onValueChange: (Float) -> Unit
    ) {
        data class Preset(val text: String, val value: Float)
    }

    data class Action(
        val title: String,
        val description: String? = null,
        val iconName: String? = null,
        val checked: Boolean = false,
        val enabled: Boolean = true,
        val persistent: Boolean = false,  // true = 点击不关闭弹窗，就地更新状态
        // F39（2026-09-21）：分组标题行——仅作视觉分组标签，不可点、无选中态；
        // 默认 false ⇒ 全部既有调用点零改动（组件层只做可选参数扩展）。
        val header: Boolean = false,
        // F35（2026-09-21）：滑杆行——非空时本行渲染滑杆（见 [SliderSpec]）；
        // 默认 null ⇒ 全部既有调用点零改动。
        val slider: SliderSpec? = null,
        // 默认为空实现：滑杆行不需要点击回调，调用方可省略（既有尾随 lambda 写法不受影响）
        val invoke: () -> Unit = {}
    )

    class Handle internal constructor(
        private val visibleState: MutableState<Boolean>,
        private val overlay: ComposeView,
        private val host: ViewGroup,
        private val backCallback: OnBackPressedCallback?,
        private val anchor: View,
        private val anchorDetachListener: View.OnAttachStateChangeListener,
        private val hostDetachListener: View.OnAttachStateChangeListener
    ) {
        private var dismissed = false
        val isShowing: Boolean
            get() = !dismissed && overlay.parent != null

        fun dismiss() {
            if (dismissed) return
            dismissed = true
            visibleState.value = false
            backCallback?.remove()
            anchor.removeOnAttachStateChangeListener(anchorDetachListener)
            host.removeOnAttachStateChangeListener(hostDetachListener)
            val delay = if (AppConfig.isEInkMode) 0L else 170L
            overlay.postDelayed({
                if (overlay.parent === host) {
                    host.removeView(overlay)
                }
            }, delay)
        }
    }

    private data class AnchorSnapshot(
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

    fun show(
        anchor: View,
        actions: List<Action>,
        previousPopup: Handle? = null,
        maxHeightRatio: Float = 0.62f,
        bottomGapDp: Int = 8
    ): Handle? {
        if (actions.isEmpty()) return previousPopup
        val host = anchor.rootView as? ViewGroup
            ?: anchor.activity?.window?.decorView as? ViewGroup
            ?: return previousPopup
        val snapshot = calculateAnchorSnapshot(anchor, host, actions, maxHeightRatio, bottomGapDp)
        previousPopup?.dismiss()
        val visibleState = mutableStateOf(false)
        var handle: Handle? = null
        val overlay = ComposeView(host.context).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            installViewTreeOwnersFrom(anchor, host.context)
            isFocusable = true
            isFocusableInTouchMode = true
            setOnKeyListener { _, keyCode, event ->
                if (keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
                    handle?.dismiss()
                    true
                } else {
                    false
                }
            }
        }
        val anchorDetachListener = object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) = Unit
            override fun onViewDetachedFromWindow(v: View) {
                handle?.dismiss()
            }
        }
        val hostDetachListener = object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) = Unit
            override fun onViewDetachedFromWindow(v: View) {
                handle?.dismiss()
            }
        }
        anchor.addOnAttachStateChangeListener(anchorDetachListener)
        host.addOnAttachStateChangeListener(hostDetachListener)
        val backCallback = anchor.activity?.let { activity ->
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    handle?.dismiss()
                }
            }.also { activity.onBackPressedDispatcher.addCallback(it) }
        }
        handle = Handle(
            visibleState = visibleState,
            overlay = overlay,
            host = host,
            backCallback = backCallback,
            anchor = anchor,
            anchorDetachListener = anchorDetachListener,
            hostDetachListener = hostDetachListener
        )
        overlay.setContent {
            ModernActionPopupOverlay(
                snapshot = snapshot,
                actions = actions,
                visible = visibleState.value,
                onDismiss = handle::dismiss
            )
        }
        host.addView(
            overlay,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        overlay.requestFocus()
        overlay.post {
            visibleState.value = true
        }
        return handle
    }

    fun showFromMenu(
        anchor: View,
        @MenuRes menuRes: Int,
        previousPopup: Handle? = null,
        maxHeightRatio: Float = 0.62f,
        bottomGapDp: Int = 8,
        prepare: (Menu.() -> Unit)? = null,
        onClick: (MenuItem) -> Boolean
    ): Handle? {
        val popupMenu = PopupMenu(anchor.context, anchor)
        popupMenu.inflate(menuRes)
        prepare?.invoke(popupMenu.menu)
        val actions = mutableListOf<Action>()
        for (index in 0 until popupMenu.menu.size()) {
            val item = popupMenu.menu.getItem(index)
            if (item.isVisible) {
                actions.add(
                    Action(
                        title = item.title.toString(),
                        checked = item.isChecked,
                        enabled = item.isEnabled
                    ) {
                        onClick(item)
                    }
                )
            }
        }
        return show(anchor, actions, previousPopup, maxHeightRatio, bottomGapDp)
    }

    /**
     * 多态重载：通过 Compose 坐标显示弹出菜单，不需要 View 锚点
     * @param context 上下文
     * @param anchorLeft 锚点左边界（相对于 host 的像素坐标）
     * @param anchorTop 锚点上边界
     * @param anchorRight 锚点右边界
     * @param anchorBottom 锚点下边界
     * @param actions 菜单项列表
     */
    fun show(
        context: Context,
        anchorLeft: Int,
        anchorTop: Int,
        anchorRight: Int,
        anchorBottom: Int,
        actions: List<Action>,
        previousPopup: Handle? = null,
        maxHeightRatio: Float = 0.62f,
        bottomGapDp: Int = 8
    ): Handle? {
        if (actions.isEmpty()) return previousPopup
        val host = (context as? android.app.Activity)
            ?.window?.decorView as? ViewGroup
            ?: return previousPopup
        val snapshot = calculateAnchorSnapshotFromCoords(
            anchorLeft, anchorTop, anchorRight, anchorBottom,
            host, actions, maxHeightRatio, bottomGapDp
        )
        previousPopup?.dismiss()
        val visibleState = mutableStateOf(false)
        var handle: Handle? = null
        val overlay = ComposeView(context).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            installViewTreeOwnersFrom(host, context)
            isFocusable = true
            isFocusableInTouchMode = true
            setOnKeyListener { _, keyCode, event ->
                if (keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
                    handle?.dismiss()
                    true
                } else {
                    false
                }
            }
        }
        val hostDetachListener = object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) = Unit
            override fun onViewDetachedFromWindow(v: View) {
                handle?.dismiss()
            }
        }
        host.addOnAttachStateChangeListener(hostDetachListener)
        val backCallback = (context as? androidx.activity.ComponentActivity)?.let { activity ->
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    handle?.dismiss()
                }
            }.also { activity.onBackPressedDispatcher.addCallback(it) }
        }
        // 创建一个不可见的锚点 View 用于生命周期管理
        val dummyAnchor = View(context)
        handle = Handle(
            visibleState = visibleState,
            overlay = overlay,
            host = host,
            backCallback = backCallback,
            anchor = dummyAnchor,
            anchorDetachListener = object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) = Unit
                override fun onViewDetachedFromWindow(v: View) = Unit
            },
            hostDetachListener = hostDetachListener
        )
        overlay.setContent {
            ModernActionPopupOverlay(
                snapshot = snapshot,
                actions = actions,
                visible = visibleState.value,
                onDismiss = handle::dismiss
            )
        }
        host.addView(
            overlay,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        overlay.requestFocus()
        overlay.post {
            visibleState.value = true
        }
        return handle
    }

    /**
     * 多态重载：通过 Compose 坐标显示菜单（从菜单资源）
     */
    fun showFromMenu(
        context: Context,
        anchorLeft: Int,
        anchorTop: Int,
        anchorRight: Int,
        anchorBottom: Int,
        @MenuRes menuRes: Int,
        previousPopup: Handle? = null,
        maxHeightRatio: Float = 0.62f,
        bottomGapDp: Int = 8,
        prepare: (Menu.() -> Unit)? = null,
        onClick: (MenuItem) -> Boolean
    ): Handle? {
        val popupMenu = PopupMenu(context, null)
        popupMenu.inflate(menuRes)
        prepare?.invoke(popupMenu.menu)
        val actions = mutableListOf<Action>()
        for (index in 0 until popupMenu.menu.size()) {
            val item = popupMenu.menu.getItem(index)
            if (item.isVisible) {
                actions.add(
                    Action(
                        title = item.title.toString(),
                        checked = item.isChecked,
                        enabled = item.isEnabled
                    ) {
                        onClick(item)
                    }
                )
            }
        }
        return show(context, anchorLeft, anchorTop, anchorRight, anchorBottom, actions, previousPopup, maxHeightRatio, bottomGapDp)
    }

    private fun calculateAnchorSnapshotFromCoords(
        anchorLeft: Int,
        anchorTop: Int,
        anchorRight: Int,
        anchorBottom: Int,
        host: ViewGroup,
        actions: List<Action>,
        maxHeightRatio: Float,
        bottomGapDp: Int
    ): AnchorSnapshot {
        val gap = 8.dpToPx()
        val bottomGap = bottomGapDp.dpToPx()
        val hostWidth = host.width.takeIf { it > 0 } ?: host.rootView.width
        val hostHeight = host.height.takeIf { it > 0 } ?: host.rootView.height
        val safeHostHeight = host.safePopupHostHeight(hostHeight)
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

    @Composable
    private fun ModernActionPopupOverlay(
        snapshot: AnchorSnapshot,
        actions: List<Action>,
        visible: Boolean,
        onDismiss: () -> Unit
    ) {
        val style = rememberAppDialogStyle()
        val palette = style.toMiuixPalette()
        val panelShape = RoundedCornerShape(style.panelRadius)
        val context = LocalContext.current
        // 仅当主题确实设置了面板边框色才画边框；无边框主题不应再显描边线。
        val hasPanelBorder = UiCorner.panelBorderColor(context) != null
        // 跟踪 persistent 项的 checked 状态
        var checkedStates by remember { mutableStateOf(actions.map { it.checked }) }
        val density = LocalDensity.current
        val maxHeightDp = with(density) { snapshot.maxHeightPx.toDp() }
        var panelSize by remember { mutableStateOf(IntSize.Zero) }
        var panelBounds by remember { mutableStateOf<ComposeRect?>(null) }
        val panelWidth = panelSize.width.takeIf { it > 0 } ?: snapshot.fallbackWidthPx
        val panelHeight = panelSize.height.takeIf { it > 0 } ?: snapshot.fallbackHeightPx
        val panelOffset = calculatePanelOffset(snapshot, panelWidth, panelHeight)
        val panelTransformOrigin = calculateTransformOrigin(snapshot, panelOffset, panelWidth, panelHeight)
        val progress by animateFloatAsState(
            targetValue = if (visible) 1f else 0f,
            animationSpec = tween(
                durationMillis = if (AppConfig.isEInkMode) 0 else 150,
                easing = FastOutSlowInEasing
            ),
            label = "modernActionPopup"
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(panelBounds) {
                    detectTapGestures { tap ->
                        val bounds = panelBounds
                        if (bounds == null || !bounds.contains(tap)) {
                            onDismiss()
                        }
                    }
                }
        ) {
            CompositionLocalProvider(
                LocalTextStyle provides LocalTextStyle.current.copy(fontFamily = style.bodyFontFamily)
            ) {
                Surface(
                    modifier = Modifier
                        .offset { panelOffset }
                        .width(with(density) { snapshot.fallbackWidthPx.toDp() })
                        .heightIn(max = maxHeightDp)
                        .onSizeChanged { panelSize = it }
                        .onGloballyPositioned { panelBounds = it.boundsInRoot() }
                        .graphicsLayer {
                            alpha = progress
                            transformOrigin = panelTransformOrigin
                            scaleX = 0.96f + 0.04f * progress
                            scaleY = 0.96f + 0.04f * progress
                            translationY = (if (panelOffset.y >= snapshot.anchorBottom) -6f else 6f) *
                                (1f - progress)
                        }
                        // 边框放在 graphicsLayer 之后，随弹出动画一起淡入，不再先于动画整条显示。
                        .then(
                            if (hasPanelBorder) Modifier.border(1.dp, style.stroke, panelShape)
                            else Modifier
                        ),
                    shape = panelShape,
                    color = style.surface,
                    contentColor = style.primaryText,
                    tonalElevation = 0.dp,
                    shadowElevation = 0.dp
                ) {
                    LazyColumn(
                        modifier = Modifier
                            .heightIn(max = maxHeightDp)
                            .padding(6.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        itemsIndexed(
                            items = actions,
                            key = { index, action -> "${action.title}#$index" }
                        ) { index, action ->
                            val sliderSpec = action.slider
                            if (sliderSpec != null) {
                                // 滑杆行（F35）：标题 + 当前值 + 滑杆 + 可选快捷档位；不参与点击关闭
                                ModernSliderRow(action.title, sliderSpec, style)
                            } else if (action.header) {
                                // 分组标题（F39）：非交互标签行，无选中态、不响应点击
                                Text(
                                    text = action.title,
                                    color = style.secondaryText,
                                    fontSize = MaterialTheme.typography.labelMedium.fontSize,
                                    fontWeight = FontWeight.Medium,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(start = 14.dp, end = 14.dp, top = 8.dp, bottom = 2.dp)
                                )
                            } else {
                            val isChecked = if (action.persistent) {
                                checkedStates.getOrElse(index) { action.checked }
                            } else {
                                action.checked
                            }
                            LegadoMiuixChoiceRow(
                                text = action.title,
                                selected = isChecked,
                                palette = palette,
                                onClick = {
                                    if (action.persistent) {
                                        // 不关闭弹窗，就地更新状态
                                        checkedStates = checkedStates.toMutableList().apply {
                                            set(index, !isChecked)
                                        }
                                        anchorPostAction(action.invoke)
                                    } else {
                                        onDismiss()
                                        anchorPostAction(action.invoke)
                                    }
                                },
                                minHeight = 42.dp,
                                compact = true,
                                showSelectedMark = action.checked || action.persistent,
                                enabled = action.enabled,
                                description = action.description,
                                leadingIconName = action.iconName,
                                textAlign = TextAlign.Start
                            )
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * 滑杆行（F35）：与弹层同源取色——标题行 + 连续滑杆 + 可选快捷档位 Chip。
     * 滑动/点档位即时回调调用方（音频页据此实时设置定时/倍速），全程不关闭弹层。
     */
    @Composable
    private fun ModernSliderRow(
        title: String,
        spec: SliderSpec,
        style: AppDialogStyle
    ) {
        // 以打开瞬间的快照为初值；弹层存活期间由行内状态接管
        var current by remember(spec) { mutableStateOf(spec.value) }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = title,
                    color = style.primaryText,
                    fontSize = MaterialTheme.typography.bodyMedium.fontSize,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = spec.valueText(current),
                    color = style.secondaryText,
                    fontSize = MaterialTheme.typography.labelLarge.fontSize
                )
            }
            Slider(
                value = current,
                onValueChange = {
                    current = it
                    spec.onValueChange(it)
                },
                valueRange = spec.valueRange,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 2.dp)
            )
            if (spec.presets.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    spec.presets.forEach { preset ->
                        val selected = kotlin.math.abs(preset.value - current) < 0.01f
                        Surface(
                            shape = RoundedCornerShape(50),
                            color = if (selected) style.accent.copy(alpha = 0.14f) else Color.Transparent,
                            contentColor = if (selected) style.accent else style.secondaryText,
                            border = BorderStroke(1.dp, if (selected) style.accent else style.stroke),
                            modifier = Modifier
                                .weight(1f)
                                .clickable {
                                    current = preset.value
                                    spec.onValueChange(preset.value)
                                }
                        ) {
                            Text(
                                text = preset.text,
                                fontSize = MaterialTheme.typography.labelSmall.fontSize,
                                textAlign = TextAlign.Center,
                                maxLines = 1,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 6.dp)
                            )
                        }
                    }
                }
            }
        }
    }

    private fun anchorPostAction(action: () -> Unit) {
        android.os.Handler(android.os.Looper.getMainLooper()).post(action)
    }

    private fun calculateAnchorSnapshot(
        anchor: View,
        host: ViewGroup,
        actions: List<Action>,
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

    private fun calculatePanelOffset(
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

    private fun calculateTransformOrigin(
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

    private fun estimatePanelWidthPx(actions: List<Action>, maxWidthPx: Int): Int {
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
    private fun estimatePanelHeightPx(
        actions: List<Action>,
        rowHeightPx: Int,
        maxHeight: Int,
        minimumHeight: Int
    ): Int {
        val sliderCount = actions.count { it.slider != null }
        val plainCount = actions.size - sliderCount
        val contentPx = plainCount * rowHeightPx + sliderCount * SLIDER_ROW_HEIGHT_DP.dpToPx() + 12.dpToPx()
        return minOf(maxHeight, contentPx.coerceAtLeast(minimumHeight))
    }

    private fun View.safePopupHostHeight(hostHeight: Int): Int {
        val navigationBottom = ViewCompat.getRootWindowInsets(this)
            ?.getInsets(WindowInsetsCompat.Type.navigationBars())
            ?.bottom ?: 0
        return (hostHeight - navigationBottom).coerceAtLeast(1)
    }
}
