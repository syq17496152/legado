package io.legado.app.ui.book.read

import android.animation.ValueAnimator
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.Choreographer
import android.view.WindowInsets
import android.view.WindowManager
import android.view.animation.PathInterpolator
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import io.legado.app.R
import io.legado.app.constant.PreferKey
import io.legado.app.help.config.AppConfig
import io.legado.app.lib.theme.composeActionShape
import io.legado.app.lib.theme.composePanelShape
import io.legado.app.ui.book.read.config.ReaderSheetStyle
import io.legado.app.ui.widget.compose.BookCoverImage
import io.legado.app.ui.widget.image.CoverImageView
import io.legado.app.utils.LogUtils
import io.legado.app.utils.dpToPx
import io.legado.app.utils.getPrefInt
import io.legado.app.utils.putPrefInt
import kotlin.math.roundToInt

private const val EDGE_WINDOW_SIZE_DP = 60
private const val EDGE_BALL_SIZE_DP = 56

internal class ReadAloudSystemFloatingWindow(
    private val context: Context,
    lifecycleOwner: LifecycleOwner,
    private val onPlayPause: () -> Unit,
    private val onCueSelect: (Int, Int, Int) -> Unit,
    private val onChapterSelect: (Int) -> Unit,
    private val onExpand: () -> Unit,
    private val onClose: () -> Unit
) {

    internal enum class WindowMode {
        EdgeBall,
        FullBall,
        Controls,
        Reader
    }

    private data class WindowFrame(
        val x: Int,
        val y: Int,
        val width: Int,
        val height: Int
    )

    private data class ScreenSpace(
        val width: Int,
        val height: Int,
        val insetLeft: Int,
        val insetTop: Int,
        val insetRight: Int,
        val insetBottom: Int
    )

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val viewTreeOwners = FloatingViewTreeOwners(lifecycleOwner)
    private val handler = Handler(Looper.getMainLooper())
    private var uiState by mutableStateOf(ReadAloudPlayerPanel.PlayerUiState())
    private var themeRevision by mutableIntStateOf(0)
    private var mode by mutableStateOf(WindowMode.EdgeBall)
    private var side by mutableIntStateOf(
        context.getPrefInt(PreferKey.readAloudFloatingBallSide, 1).coerceIn(0, 1)
    )
    private var settingsVisible by mutableStateOf(false)
    private var chapterPickerVisible by mutableStateOf(false)
    private var floatingFontSize by mutableIntStateOf(AppConfig.readAloudFloatingFontSize)
    private var floatingBackgroundAlpha by mutableIntStateOf(AppConfig.readAloudFloatingBackgroundAlpha)
    private var floatingHeightPercent by mutableIntStateOf(AppConfig.readAloudFloatingHeightPercent)
    private var suppressed = false
    private var attached = false
    private var dragging = false
    private var transitioning by mutableStateOf(false)
    private var capsuleExpansion by mutableFloatStateOf(0f)
    private var layoutAnimator: ValueAnimator? = null
    private val choreographer = Choreographer.getInstance()
    private var dragLayoutUpdatePosted = false
    private val dragLayoutFrameCallback = Choreographer.FrameCallback {
        dragLayoutUpdatePosted = false
        updateLayout()
    }

    private val idleRunnable = Runnable {
        if (attached && (mode == WindowMode.FullBall || mode == WindowMode.Controls) && !dragging) {
            changeMode(WindowMode.EdgeBall, animate = true)
        }
    }

    private val layoutParams = WindowManager.LayoutParams(
        CONTROLS_WIDTH_DP.dpToPx(),
        CONTROLS_HEIGHT_DP.dpToPx(),
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        },
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.START or Gravity.TOP
        title = "Legado read aloud controls"
    }

    private val composeView = ComposeView(context).apply {
        setViewTreeLifecycleOwner(viewTreeOwners)
        setViewTreeViewModelStoreOwner(viewTreeOwners)
        setViewTreeSavedStateRegistryOwner(viewTreeOwners)
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
        setContent {
            val revision = themeRevision
            val palette = remember(revision) { ReaderSheetStyle.resolve(context) }
            val colors = rememberPlayerColors(palette)
            FloatingWindowContent(
                mode = mode,
                capsuleExpansion = capsuleExpansion,
                transitioning = transitioning,
                state = uiState,
                colors = colors,
                fontSize = floatingFontSize,
                backgroundAlpha = floatingBackgroundAlpha,
                heightPercent = floatingHeightPercent,
                settingsVisible = settingsVisible,
                chapterPickerVisible = chapterPickerVisible,
                onEdgeBallTap = {
                    changeMode(WindowMode.FullBall, animate = true)
                },
                onFullBallTap = {
                    expandFullBallToControls()
                },
                onCoverTap = onExpand,
                onCoverLongPress = {
                    noteInteraction()
                    changeMode(WindowMode.Reader, animate = true)
                },
                onPlayPause = {
                    noteInteraction()
                    onPlayPause()
                },
                onStop = onClose,
                onDragStart = ::startDrag,
                onDrag = ::dragBy,
                onDragEnd = ::finishDrag,
                onFullscreen = {
                    settingsVisible = false
                    chapterPickerVisible = false
                    changeMode(WindowMode.EdgeBall, animate = true)
                    onExpand()
                },
                onMinimize = {
                    settingsVisible = false
                    chapterPickerVisible = false
                    changeMode(WindowMode.EdgeBall, animate = true)
                },
                onSettings = {
                    settingsVisible = !settingsVisible
                    chapterPickerVisible = false
                },
                onChapterPicker = {
                    chapterPickerVisible = !chapterPickerVisible
                    settingsVisible = false
                },
                onChapterSelect = { index ->
                    chapterPickerVisible = false
                    onChapterSelect(index)
                },
                onCueSelect = onCueSelect,
                onFontSizeChange = { floatingFontSize = it.coerceIn(14, 34) },
                onFontSizeChangeFinished = {
                    AppConfig.readAloudFloatingFontSize = floatingFontSize
                },
                onBackgroundAlphaChange = {
                    floatingBackgroundAlpha = it.coerceIn(0, 100)
                },
                onBackgroundAlphaChangeFinished = {
                    AppConfig.readAloudFloatingBackgroundAlpha = floatingBackgroundAlpha
                },
                onHeightPercentChange = {
                    floatingHeightPercent = it.coerceIn(15, 90)
                    if (mode == WindowMode.Reader) {
                        applyFrame(targetFrame(WindowMode.Reader))
                    }
                },
                onHeightPercentChangeFinished = {
                    AppConfig.readAloudFloatingHeightPercent = floatingHeightPercent
                }
            )
        }
    }

    fun showOrUpdate(state: ReadAloudPlayerPanel.PlayerUiState) {
        uiState = state
        if (suppressed || !state.serviceRunning) {
            remove()
        } else {
            attachIfNeeded()
        }
    }

    fun setSuppressed(value: Boolean) {
        if (suppressed == value) return
        suppressed = value
        if (value) {
            remove()
        } else if (uiState.serviceRunning) {
            attachIfNeeded()
        }
    }

    fun refreshTheme() {
        themeRevision += 1
    }

    fun onConfigurationChanged() {
        if (!attached) return
        layoutAnimator?.cancel()
        transitioning = false
        capsuleExpansion = if (mode == WindowMode.Controls) 1f else 0f
        applyFrame(targetFrame(mode))
        scheduleIdleCollapse(mode)
        refreshTheme()
    }

    fun remove() {
        handler.removeCallbacks(idleRunnable)
        cancelPendingDragLayoutUpdate()
        layoutAnimator?.cancel()
        layoutAnimator = null
        transitioning = false
        capsuleExpansion = if (mode == WindowMode.Controls) 1f else 0f
        if (!attached) return
        runCatching { windowManager.removeViewImmediate(composeView) }
            .onFailure { LogUtils.d(TAG, "remove floating window failed: ${it.localizedMessage}") }
        attached = false
        dragging = false
    }

    fun dispose() {
        remove()
        composeView.disposeComposition()
        viewTreeOwners.clear()
    }

    private fun attachIfNeeded() {
        if (attached || !canDrawOverlays()) return
        applyFrame(targetFrame(mode), updateWindow = false)
        runCatching {
            windowManager.addView(composeView, layoutParams)
            attached = true
            LogUtils.d(TAG, "floating window attached mode=$mode")
            noteInteraction()
        }.onFailure {
            attached = false
            LogUtils.d(TAG, "add floating window failed: ${it.localizedMessage}")
        }
    }

    private fun noteInteraction() {
        handler.removeCallbacks(idleRunnable)
        if (mode == WindowMode.FullBall || mode == WindowMode.Controls) {
            handler.postDelayed(idleRunnable, IDLE_COLLAPSE_MILLIS)
        }
    }

    private fun changeMode(next: WindowMode, animate: Boolean) {
        if (transitioning) return
        if (mode == next && !dragging) {
            noteInteraction()
            return
        }
        if (mode == WindowMode.Controls && next == WindowMode.EdgeBall) {
            collapseControlsToEdge(animate)
            return
        }
        handler.removeCallbacks(idleRunnable)
        dragging = false
        mode = next
        capsuleExpansion = if (next == WindowMode.Controls) 1f else 0f
        val target = targetFrame(next)
        if (!attached || !animate || AppConfig.isEInkMode) {
            applyFrame(target)
            scheduleIdleCollapse(next)
        } else {
            animateToFrame(target) {
                scheduleIdleCollapse(next)
            }
        }
    }

    private fun expandFullBallToControls() {
        if (transitioning || dragging || mode != WindowMode.FullBall) return
        handler.removeCallbacks(idleRunnable)
        val target = targetFrame(WindowMode.Controls)
        if (!attached || AppConfig.isEInkMode) {
            applyFrame(target)
            mode = WindowMode.Controls
            capsuleExpansion = 1f
            scheduleIdleCollapse(WindowMode.Controls)
            return
        }
        mode = WindowMode.Controls
        capsuleExpansion = 0f
        animateToFrame(
            target = target,
            onProgress = { capsuleExpansion = it }
        ) {
            capsuleExpansion = 1f
            scheduleIdleCollapse(WindowMode.Controls)
        }
    }

    private fun collapseControlsToEdge(animate: Boolean) {
        handler.removeCallbacks(idleRunnable)
        dragging = false
        val target = targetFrame(WindowMode.EdgeBall)
        if (!attached || !animate || AppConfig.isEInkMode) {
            applyFrame(target)
            capsuleExpansion = 0f
            mode = WindowMode.EdgeBall
            return
        }
        capsuleExpansion = 1f
        animateToFrame(
            target = target,
            onProgress = { capsuleExpansion = 1f - it }
        ) {
            capsuleExpansion = 0f
            mode = WindowMode.EdgeBall
        }
    }

    private fun scheduleIdleCollapse(targetMode: WindowMode) {
        if (targetMode == WindowMode.FullBall || targetMode == WindowMode.Controls) {
            handler.removeCallbacks(idleRunnable)
            handler.postDelayed(idleRunnable, IDLE_COLLAPSE_MILLIS)
        }
    }

    private fun animateToFrame(
        target: WindowFrame,
        onProgress: (Float) -> Unit = {},
        onComplete: () -> Unit = {}
    ) {
        layoutAnimator?.cancel()
        val start = WindowFrame(
            x = layoutParams.x,
            y = layoutParams.y,
            width = layoutParams.width,
            height = layoutParams.height
        )
        transitioning = true
        layoutAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            var canceled = false
            duration = MODE_ANIMATION_MILLIS
            interpolator = MODE_INTERPOLATOR
            addUpdateListener { animator ->
                val fraction = animator.animatedFraction
                onProgress(fraction)
                applyFrame(
                    WindowFrame(
                        x = lerp(start.x, target.x, fraction),
                        y = lerp(start.y, target.y, fraction),
                        width = lerp(start.width, target.width, fraction),
                        height = lerp(start.height, target.height, fraction)
                    )
                )
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationCancel(animation: Animator) {
                    canceled = true
                    transitioning = false
                }

                override fun onAnimationEnd(animation: Animator) {
                    transitioning = false
                    if (layoutAnimator === animation) {
                        layoutAnimator = null
                    }
                    if (!canceled) onComplete()
                }
            })
            start()
        }
    }

    private fun startDrag() {
        if (!attached || transitioning || mode == WindowMode.Reader) return
        handler.removeCallbacks(idleRunnable)
        layoutAnimator?.cancel()
        dragging = true
        if (mode == WindowMode.EdgeBall) {
            mode = WindowMode.FullBall
        }
    }

    private fun dragBy(dx: Int, dy: Int) {
        if (!attached || !dragging || mode == WindowMode.Reader) return
        val space = resolveScreenSpace()
        val (minX, maxX) = if (mode == WindowMode.FullBall) {
            val halfWidth = layoutParams.width / 2
            -halfWidth to (space.width - halfWidth)
        } else {
            val safeMinX = space.insetLeft
            safeMinX to (space.width - space.insetRight - layoutParams.width).coerceAtLeast(safeMinX)
        }
        val minY = space.insetTop
        val maxY = (space.height - space.insetBottom - layoutParams.height).coerceAtLeast(minY)
        layoutParams.x = (layoutParams.x + dx).coerceIn(minX, maxX)
        layoutParams.y = (layoutParams.y + dy).coerceIn(minY, maxY)
        scheduleDragLayoutUpdate()
    }

    private fun finishDrag() {
        if (!attached || !dragging || mode == WindowMode.Reader) return
        dragging = false
        flushPendingDragLayoutUpdate()
        val space = resolveScreenSpace()
        side = if (layoutParams.x + layoutParams.width / 2 < space.width / 2) 0 else 1
        val verticalBounds = ReadAloudFloatingWindowLayout.bounds(
            screenWidth = space.width,
            screenHeight = space.height,
            insetLeft = space.insetLeft,
            insetTop = space.insetTop,
            insetRight = space.insetRight,
            insetBottom = space.insetBottom,
            windowWidth = layoutParams.width,
            windowHeight = layoutParams.height,
            sideMargin = COMPACT_SIDE_MARGIN_DP.dpToPx(),
            bottomMargin = COMPACT_BOTTOM_MARGIN_DP.dpToPx()
        )
        context.putPrefInt(PreferKey.readAloudFloatingBallSide, side)
        context.putPrefInt(
            PreferKey.readAloudFloatingBallYPercent,
            ReadAloudFloatingWindowLayout.percentForY(layoutParams.y, verticalBounds)
        )
        val target = targetFrame(mode)
        if (AppConfig.isEInkMode) {
            applyFrame(target)
            noteInteraction()
        } else {
            animateToFrame(target) {
                noteInteraction()
            }
        }
    }

    private fun targetFrame(targetMode: WindowMode): WindowFrame {
        val space = resolveScreenSpace()
        val yPercent = context.getPrefInt(PreferKey.readAloudFloatingBallYPercent, 72)
            .coerceIn(0, 100)
        return when (targetMode) {
            WindowMode.EdgeBall -> {
                val width = EDGE_WINDOW_SIZE_DP.dpToPx()
                val height = EDGE_WINDOW_SIZE_DP.dpToPx()
                val bounds = ReadAloudFloatingWindowLayout.bounds(
                    screenWidth = space.width,
                    screenHeight = space.height,
                    insetLeft = space.insetLeft,
                    insetTop = space.insetTop,
                    insetRight = space.insetRight,
                    insetBottom = space.insetBottom,
                    windowWidth = width,
                    windowHeight = height,
                    sideMargin = 0,
                    bottomMargin = COMPACT_BOTTOM_MARGIN_DP.dpToPx()
                )
                WindowFrame(
                    x = ReadAloudFloatingWindowLayout.edgeBallX(
                        side = side,
                        screenWidth = space.width,
                        windowSize = width
                    ),
                    y = ReadAloudFloatingWindowLayout.yForPercent(yPercent, bounds),
                    width = width,
                    height = height
                )
            }

            WindowMode.FullBall -> {
                val width = EDGE_WINDOW_SIZE_DP.dpToPx()
                val height = EDGE_WINDOW_SIZE_DP.dpToPx()
                val bounds = ReadAloudFloatingWindowLayout.bounds(
                    screenWidth = space.width,
                    screenHeight = space.height,
                    insetLeft = space.insetLeft,
                    insetTop = space.insetTop,
                    insetRight = space.insetRight,
                    insetBottom = space.insetBottom,
                    windowWidth = width,
                    windowHeight = height,
                    sideMargin = COMPACT_SIDE_MARGIN_DP.dpToPx(),
                    bottomMargin = COMPACT_BOTTOM_MARGIN_DP.dpToPx()
                )
                WindowFrame(
                    x = ReadAloudFloatingWindowLayout.xForSide(side, bounds),
                    y = ReadAloudFloatingWindowLayout.yForPercent(yPercent, bounds),
                    width = width,
                    height = height
                )
            }

            WindowMode.Controls -> {
                val availableWidth = space.width - space.insetLeft - space.insetRight
                val width = CONTROLS_WIDTH_DP.dpToPx().coerceAtMost(availableWidth)
                val height = CONTROLS_HEIGHT_DP.dpToPx()
                val bounds = ReadAloudFloatingWindowLayout.bounds(
                    screenWidth = space.width,
                    screenHeight = space.height,
                    insetLeft = space.insetLeft,
                    insetTop = space.insetTop,
                    insetRight = space.insetRight,
                    insetBottom = space.insetBottom,
                    windowWidth = width,
                    windowHeight = height,
                    sideMargin = COMPACT_SIDE_MARGIN_DP.dpToPx(),
                    bottomMargin = COMPACT_BOTTOM_MARGIN_DP.dpToPx()
                )
                WindowFrame(
                    x = ReadAloudFloatingWindowLayout.xForSide(side, bounds),
                    y = ReadAloudFloatingWindowLayout.yForPercent(yPercent, bounds),
                    width = width,
                    height = height
                )
            }

            WindowMode.Reader -> {
                val margin = READER_SIDE_MARGIN_DP.dpToPx()
                val width = (space.width - space.insetLeft - space.insetRight - margin * 2)
                    .coerceAtLeast(240.dpToPx())
                val verticalMargin = READER_TOP_MARGIN_DP.dpToPx()
                val availableHeight = (
                        space.height - space.insetTop - space.insetBottom - verticalMargin * 2
                        ).coerceAtLeast(1)
                val height = ReadAloudFloatingWindowLayout.readerHeight(
                    availableHeight = availableHeight,
                    minHeight = READER_MIN_HEIGHT_DP.dpToPx(),
                    heightPercent = floatingHeightPercent
                )
                WindowFrame(
                    x = space.insetLeft + margin,
                    y = space.insetTop + verticalMargin,
                    width = width,
                    height = height
                )
            }
        }
    }

    private fun applyFrame(frame: WindowFrame, updateWindow: Boolean = true) {
        layoutParams.x = frame.x
        layoutParams.y = frame.y
        layoutParams.width = frame.width
        layoutParams.height = frame.height
        if (updateWindow) updateLayout()
    }

    private fun updateLayout() {
        if (!attached) return
        runCatching { windowManager.updateViewLayout(composeView, layoutParams) }
            .onFailure {
                LogUtils.d(TAG, "update floating window failed: ${it.localizedMessage}")
                remove()
            }
    }

    private fun scheduleDragLayoutUpdate() {
        if (dragLayoutUpdatePosted) return
        dragLayoutUpdatePosted = true
        choreographer.postFrameCallback(dragLayoutFrameCallback)
    }

    private fun flushPendingDragLayoutUpdate() {
        if (!dragLayoutUpdatePosted) return
        choreographer.removeFrameCallback(dragLayoutFrameCallback)
        dragLayoutUpdatePosted = false
        updateLayout()
    }

    private fun cancelPendingDragLayoutUpdate() {
        if (!dragLayoutUpdatePosted) return
        choreographer.removeFrameCallback(dragLayoutFrameCallback)
        dragLayoutUpdatePosted = false
    }

    private fun resolveScreenSpace(): ScreenSpace {
        var width = context.resources.displayMetrics.widthPixels
        var height = context.resources.displayMetrics.heightPixels
        var insetLeft = 0
        var insetTop = systemDimension("status_bar_height")
        var insetRight = 0
        var insetBottom = systemDimension("navigation_bar_height")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val metrics = windowManager.currentWindowMetrics
            width = metrics.bounds.width()
            height = metrics.bounds.height()
            val insets = metrics.windowInsets.getInsetsIgnoringVisibility(
                WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout()
            )
            insetLeft = insets.left
            insetTop = insets.top
            insetRight = insets.right
            insetBottom = insets.bottom
        }
        return ScreenSpace(width, height, insetLeft, insetTop, insetRight, insetBottom)
    }

    private fun systemDimension(name: String): Int {
        val id = context.resources.getIdentifier(name, "dimen", "android")
        return if (id == 0) 0 else context.resources.getDimensionPixelSize(id)
    }

    private fun canDrawOverlays(): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(context)
    }

    private fun lerp(start: Int, end: Int, fraction: Float): Int {
        return (start + (end - start) * fraction).roundToInt()
    }

    private companion object {
        const val TAG = "ReadAloudFloating"
        const val CONTROLS_WIDTH_DP = READ_ALOUD_CAPSULE_WIDTH_DP
        const val CONTROLS_HEIGHT_DP = EDGE_WINDOW_SIZE_DP
        const val COMPACT_SIDE_MARGIN_DP = 10
        const val COMPACT_BOTTOM_MARGIN_DP = 20
        const val READER_SIDE_MARGIN_DP = 8
        const val READER_TOP_MARGIN_DP = 8
        const val READER_MIN_HEIGHT_DP = 160
        const val IDLE_COLLAPSE_MILLIS = 5_000L
        const val MODE_ANIMATION_MILLIS = 320L
        val MODE_INTERPOLATOR = PathInterpolator(0.22f, 0.61f, 0.36f, 1f)
    }

    private class FloatingViewTreeOwners(
        private val serviceLifecycleOwner: LifecycleOwner
    ) : LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner, LifecycleEventObserver {

        private val savedStateController = SavedStateRegistryController.create(this)
        private val lifecycleRegistry = LifecycleRegistry(this)

        override val lifecycle
            get() = lifecycleRegistry

        override val viewModelStore = ViewModelStore()

        override val savedStateRegistry: SavedStateRegistry
            get() = savedStateController.savedStateRegistry

        init {
            savedStateController.performAttach()
            savedStateController.performRestore(null)
            serviceLifecycleOwner.lifecycle.addObserver(this)
        }

        override fun onStateChanged(source: LifecycleOwner, event: Lifecycle.Event) {
            lifecycleRegistry.handleLifecycleEvent(event)
        }

        fun clear() {
            serviceLifecycleOwner.lifecycle.removeObserver(this)
            lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
            viewModelStore.clear()
        }
    }
}

@Composable
private fun FloatingWindowContent(
    mode: ReadAloudSystemFloatingWindow.WindowMode,
    capsuleExpansion: Float,
    transitioning: Boolean,
    state: ReadAloudPlayerPanel.PlayerUiState,
    colors: PlayerColors,
    fontSize: Int,
    backgroundAlpha: Int,
    heightPercent: Int,
    settingsVisible: Boolean,
    chapterPickerVisible: Boolean,
    onEdgeBallTap: () -> Unit,
    onFullBallTap: () -> Unit,
    onCoverTap: () -> Unit,
    onCoverLongPress: () -> Unit,
    onPlayPause: () -> Unit,
    onStop: () -> Unit,
    onDragStart: () -> Unit,
    onDrag: (Int, Int) -> Unit,
    onDragEnd: () -> Unit,
    onFullscreen: () -> Unit,
    onMinimize: () -> Unit,
    onSettings: () -> Unit,
    onChapterPicker: () -> Unit,
    onChapterSelect: (Int) -> Unit,
    onCueSelect: (Int, Int, Int) -> Unit,
    onFontSizeChange: (Int) -> Unit,
    onFontSizeChangeFinished: () -> Unit,
    onBackgroundAlphaChange: (Int) -> Unit,
    onBackgroundAlphaChangeFinished: () -> Unit,
    onHeightPercentChange: (Int) -> Unit,
    onHeightPercentChangeFinished: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .then(
                if (mode == ReadAloudSystemFloatingWindow.WindowMode.Reader) {
                    Modifier
                } else {
                    Modifier.floatingDrag(onDragStart, onDrag, onDragEnd)
                }
            )
    ) {
        when (mode) {
            ReadAloudSystemFloatingWindow.WindowMode.EdgeBall,
            ReadAloudSystemFloatingWindow.WindowMode.FullBall -> FloatingEdgeBall(
                state = state,
                colors = colors,
                onTap = if (mode == ReadAloudSystemFloatingWindow.WindowMode.FullBall) {
                    onFullBallTap
                } else {
                    onEdgeBallTap
                },
                onLongPress = onCoverLongPress
            )

            ReadAloudSystemFloatingWindow.WindowMode.Controls -> FloatingControls(
                state = state,
                colors = colors,
                expansion = capsuleExpansion,
                interactionEnabled = !transitioning && capsuleExpansion >= 1f,
                onCoverTap = onCoverTap,
                onCoverLongPress = onCoverLongPress,
                onPlayPause = onPlayPause,
                onStop = onStop
            )

            ReadAloudSystemFloatingWindow.WindowMode.Reader -> FloatingReaderWindow(
                state = state,
                colors = colors,
                fontSize = fontSize,
                backgroundAlpha = backgroundAlpha,
                heightPercent = heightPercent,
                settingsVisible = settingsVisible,
                chapterPickerVisible = chapterPickerVisible,
                onFullscreen = onFullscreen,
                onMinimize = onMinimize,
                onSettings = onSettings,
                onChapterPicker = onChapterPicker,
                onChapterSelect = onChapterSelect,
                onCueSelect = onCueSelect,
                onFontSizeChange = onFontSizeChange,
                onFontSizeChangeFinished = onFontSizeChangeFinished,
                onBackgroundAlphaChange = onBackgroundAlphaChange,
                onBackgroundAlphaChangeFinished = onBackgroundAlphaChangeFinished,
                onHeightPercentChange = onHeightPercentChange,
                onHeightPercentChangeFinished = onHeightPercentChangeFinished
            )
        }
    }
}

@Composable
private fun FloatingEdgeBall(
    state: ReadAloudPlayerPanel.PlayerUiState,
    colors: PlayerColors,
    onTap: () -> Unit,
    onLongPress: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .clipToBounds(),
        contentAlignment = Alignment.Center
    ) {
        FloatingCoverBall(
            state = state,
            colors = colors,
            size = EDGE_BALL_SIZE_DP,
            onTap = onTap,
            onLongPress = onLongPress
        )
    }
}

@Composable
private fun FloatingControls(
    state: ReadAloudPlayerPanel.PlayerUiState,
    colors: PlayerColors,
    expansion: Float,
    interactionEnabled: Boolean,
    onCoverTap: () -> Unit,
    onCoverLongPress: () -> Unit,
    onPlayPause: () -> Unit,
    onStop: () -> Unit
) {
    val progress = expansion.coerceIn(0f, 1f)
    val actionAlpha = ((progress - 0.35f) / 0.65f).coerceIn(0f, 1f)
    Surface(
        modifier = Modifier.fillMaxSize(),
        shape = CircleShape,
        color = colors.panelStrong.copy(alpha = progress),
        border = BorderStroke(1.dp, colors.panelBorder.copy(alpha = progress)),
        shadowElevation = 0.dp
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clipToBounds()
        ) {
            FloatingCover(
                state = state,
                colors = colors,
                enabled = interactionEnabled,
                onTap = onCoverTap,
                onLongPress = onCoverLongPress,
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .offset(x = 2.dp)
                    .requiredSize(EDGE_BALL_SIZE_DP.dp)
                    .graphicsLayer {
                        val scale = 1f - 0.25f * progress
                        scaleX = scale
                        scaleY = scale
                    }
            )
            Surface(
                onClick = onPlayPause,
                enabled = interactionEnabled,
                modifier = Modifier
                    .offset(x = 58.dp, y = 11.dp)
                    .size(38.dp)
                    .graphicsLayer { alpha = actionAlpha },
                shape = CircleShape,
                color = Color.White.copy(alpha = 0.92f)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    if (state.playbackBusy) {
                        androidx.compose.material3.CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = Color.Black.copy(alpha = 0.72f),
                            trackColor = Color.Black.copy(alpha = 0.12f)
                        )
                    } else {
                        Icon(
                            painter = painterResource(
                                if (state.playing) R.drawable.ic_pause_24dp else R.drawable.ic_play_24dp
                            ),
                            contentDescription = null,
                            tint = Color.Black.copy(alpha = 0.86f),
                            modifier = Modifier.size(21.dp)
                        )
                    }
                }
            }
            Surface(
                onClick = onStop,
                enabled = interactionEnabled,
                modifier = Modifier
                    .offset(x = 103.dp, y = 13.dp)
                    .size(34.dp)
                    .graphicsLayer { alpha = actionAlpha },
                shape = CircleShape,
                color = Color.White.copy(alpha = 0.12f)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        painter = painterResource(R.drawable.ic_close_x),
                        contentDescription = null,
                        tint = colors.primaryText,
                        modifier = Modifier.size(17.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun FloatingCoverBall(
    state: ReadAloudPlayerPanel.PlayerUiState,
    colors: PlayerColors,
    size: Int,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
    modifier: Modifier = Modifier
) {
    FloatingCover(
        state = state,
        colors = colors,
        enabled = true,
        onTap = onTap,
        onLongPress = onLongPress,
        modifier = modifier.requiredSize(size.dp)
    )
}

@Composable
private fun FloatingCover(
    state: ReadAloudPlayerPanel.PlayerUiState,
    colors: PlayerColors,
    enabled: Boolean,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
    modifier: Modifier = Modifier
) {
    val rotation = remember { Animatable(0f) }
    LaunchedEffect(state.playing) {
        if (state.playing && !AppConfig.isEInkMode) {
            while (true) {
                val start = rotation.value % 360f
                rotation.snapTo(start)
                rotation.animateTo(
                    start + 360f,
                    animationSpec = tween(16000, easing = LinearEasing)
                )
            }
        }
    }
    Surface(
        modifier = modifier
            .graphicsLayer { rotationZ = rotation.value % 360f }
            .pointerInput(enabled, onTap, onLongPress) {
                if (enabled) {
                    detectTapGestures(onTap = { onTap() }, onLongPress = { onLongPress() })
                }
            },
        shape = CircleShape,
        color = colors.panel,
        border = BorderStroke(1.dp, colors.panelBorder),
        shadowElevation = 0.dp
    ) {
        BookCoverImage(
            path = state.coverUrl,
            name = state.bookName,
            author = state.author,
            sourceOrigin = state.sourceOrigin,
            modifier = Modifier.fillMaxSize().clip(CircleShape),
            style = CoverImageView.CoverStyle.FLAT,
            loadOnlyWifi = false,
            preferThumb = true,
            forcePath = state.coverForcePath,
            allowNameOverlay = state.coverAllowNameOverlay,
            fillBounds = true
        )
    }
}

@Composable
private fun FloatingReaderWindow(
    state: ReadAloudPlayerPanel.PlayerUiState,
    colors: PlayerColors,
    fontSize: Int,
    backgroundAlpha: Int,
    heightPercent: Int,
    settingsVisible: Boolean,
    chapterPickerVisible: Boolean,
    onFullscreen: () -> Unit,
    onMinimize: () -> Unit,
    onSettings: () -> Unit,
    onChapterPicker: () -> Unit,
    onChapterSelect: (Int) -> Unit,
    onCueSelect: (Int, Int, Int) -> Unit,
    onFontSizeChange: (Int) -> Unit,
    onFontSizeChangeFinished: () -> Unit,
    onBackgroundAlphaChange: (Int) -> Unit,
    onBackgroundAlphaChangeFinished: () -> Unit,
    onHeightPercentChange: (Int) -> Unit,
    onHeightPercentChangeFinished: () -> Unit
) {
    val panelShape = LocalContext.current.composePanelShape()
    Surface(
        modifier = Modifier.fillMaxSize(),
        shape = panelShape,
        color = colors.panelStrong.copy(alpha = backgroundAlpha / 100f),
        border = BorderStroke(1.dp, colors.panelBorder),
        shadowElevation = 0.dp
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            FloatingReaderTopBar(
                state = state,
                colors = colors,
                onChapterPicker = onChapterPicker,
                onFullscreen = onFullscreen,
                onMinimize = onMinimize,
                onSettings = onSettings
            )
            when {
                settingsVisible -> FloatingReaderSettings(
                    colors = colors,
                    fontSize = fontSize,
                    backgroundAlpha = backgroundAlpha,
                    heightPercent = heightPercent,
                    onFontSizeChange = onFontSizeChange,
                    onFontSizeChangeFinished = onFontSizeChangeFinished,
                    onBackgroundAlphaChange = onBackgroundAlphaChange,
                    onBackgroundAlphaChangeFinished = onBackgroundAlphaChangeFinished,
                    onHeightPercentChange = onHeightPercentChange,
                    onHeightPercentChangeFinished = onHeightPercentChangeFinished
                )

                chapterPickerVisible -> FloatingChapterPicker(
                    state = state,
                    colors = colors,
                    onChapterSelect = onChapterSelect
                )

                else -> FloatingOriginalText(
                    state = state,
                    colors = colors,
                    fontSize = fontSize,
                    onCueSelect = onCueSelect
                )
            }
        }
    }
}

@Composable
private fun FloatingReaderTopBar(
    state: ReadAloudPlayerPanel.PlayerUiState,
    colors: PlayerColors,
    onChapterPicker: () -> Unit,
    onFullscreen: () -> Unit,
    onMinimize: () -> Unit,
    onSettings: () -> Unit
) {
    val actionShape = LocalContext.current.composeActionShape()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(58.dp)
            .padding(start = 14.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .height(42.dp)
                .clip(actionShape)
                .clickable(onClick = onChapterPicker)
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = state.chapterTitle.ifBlank { "当前章节" },
                color = colors.primaryText,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Icon(
                painter = painterResource(R.drawable.ic_expand_more),
                contentDescription = "切换章节",
                tint = colors.secondaryText,
                modifier = Modifier.size(18.dp)
            )
        }
        FloatingTopIcon(R.drawable.ic_fullscreen, "返回朗读页", colors, onFullscreen)
        FloatingTopIcon(R.drawable.ic_expand_less, "缩小", colors, onMinimize)
        FloatingTopIcon(R.drawable.ic_settings, "设置", colors, onSettings)
    }
}

@Composable
private fun FloatingTopIcon(
    icon: Int,
    description: String,
    colors: PlayerColors,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier.size(40.dp),
        shape = CircleShape,
        color = Color.Transparent,
        onClick = onClick
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                painter = painterResource(icon),
                contentDescription = description,
                tint = colors.primaryText,
                modifier = Modifier.size(19.dp)
            )
        }
    }
}

@Composable
private fun ColumnScope.FloatingOriginalText(
    state: ReadAloudPlayerPanel.PlayerUiState,
    colors: PlayerColors,
    fontSize: Int,
    onCueSelect: (Int, Int, Int) -> Unit
) {
    val cues = state.textCues.ifEmpty {
        listOf(
            ReadAloudPlayerPanel.TextCueUi(
                index = 1,
                text = state.paragraphText.ifBlank { "暂无原文" },
                current = true,
                key = state.paragraphKey.ifBlank { "current" },
                sequence = 0,
                chapterPosition = 0
            )
        )
    }
    val currentIndex = state.currentCueIndex.coerceIn(0, cues.lastIndex)
    val listState = rememberLazyListState()
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .weight(1f)
    ) {
        val centerPadding = (maxHeight / 2).coerceAtLeast(64.dp)
        suspend fun centerCurrent(animated: Boolean) {
            val target = currentIndex.coerceIn(0, cues.lastIndex)
            var item = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == target }
            if (item == null) {
                listState.scrollToItem(target)
                item = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == target }
            }
            item?.let {
                val delta = ReadAloudPanelLayout.centeredScrollDelta(
                    viewportStartOffset = listState.layoutInfo.viewportStartOffset,
                    viewportEndOffset = listState.layoutInfo.viewportEndOffset,
                    itemOffset = it.offset,
                    itemSize = it.size
                )
                if (kotlin.math.abs(delta) >= 1f) {
                    if (animated) listState.animateScrollBy(delta) else listState.scrollBy(delta)
                }
            }
        }
        var firstCenter by remember(state.chapterKey) { mutableStateOf(true) }
        LaunchedEffect(state.chapterKey, currentIndex, maxHeight) {
            centerCurrent(animated = !firstCenter && !AppConfig.isEInkMode)
            firstCenter = false
        }
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 18.dp),
            contentPadding = PaddingValues(vertical = centerPadding),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            itemsIndexed(cues, key = { _, cue -> cue.key }) { index, cue ->
                val current = index == currentIndex
                Text(
                    text = cue.text,
                    color = colors.primaryText.copy(alpha = if (current) 1f else 0.52f),
                    fontSize = fontSize.sp,
                    lineHeight = (fontSize * 1.55f).sp,
                    fontWeight = if (current) FontWeight.SemiBold else FontWeight.Normal,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onCueSelect(index, cue.chapterPosition, state.chapterIndex) }
                        .padding(vertical = 4.dp)
                )
            }
        }
    }
}

@Composable
private fun ColumnScope.FloatingChapterPicker(
    state: ReadAloudPlayerPanel.PlayerUiState,
    colors: PlayerColors,
    onChapterSelect: (Int) -> Unit
) {
    val actionShape = LocalContext.current.composeActionShape()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .weight(1f)
            .padding(horizontal = 14.dp, vertical = 8.dp)
    ) {
        Text(
            text = "切换章节",
            color = colors.secondaryText,
            fontSize = 12.sp,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)
        )
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            items(state.chapterPreview, key = { it.key }) { chapter ->
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(46.dp),
                    shape = actionShape,
                    color = if (chapter.current) colors.accent else colors.panel,
                    onClick = { if (!chapter.volume) onChapterSelect(chapter.index) }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = chapter.indexText,
                            color = if (chapter.current) colors.accentText else colors.subtleText,
                            fontSize = 11.sp,
                            modifier = Modifier.width(58.dp)
                        )
                        Text(
                            text = chapter.title,
                            color = if (chapter.current) colors.accentText else colors.primaryText,
                            fontSize = 14.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            FloatingChapterStepButton(
                text = "上一章",
                colors = colors,
                enabled = state.chapterIndex > 0,
                modifier = Modifier.weight(1f),
                shape = actionShape,
                onClick = { onChapterSelect(state.chapterIndex - 1) }
            )
            FloatingChapterStepButton(
                text = "下一章",
                colors = colors,
                enabled = state.chapterIndex + 1 < state.chapterCount,
                modifier = Modifier.weight(1f),
                shape = actionShape,
                onClick = { onChapterSelect(state.chapterIndex + 1) }
            )
        }
    }
}

@Composable
private fun FloatingChapterStepButton(
    text: String,
    colors: PlayerColors,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    shape: RoundedCornerShape = RoundedCornerShape(14.dp),
    onClick: () -> Unit
) {
    Surface(
        modifier = modifier.height(42.dp),
        shape = shape,
        color = colors.panel,
        onClick = onClick,
        enabled = enabled
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = text,
                color = colors.primaryText.copy(alpha = if (enabled) 1f else 0.38f),
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

@Composable
private fun ColumnScope.FloatingReaderSettings(
    colors: PlayerColors,
    fontSize: Int,
    backgroundAlpha: Int,
    heightPercent: Int,
    onFontSizeChange: (Int) -> Unit,
    onFontSizeChangeFinished: () -> Unit,
    onBackgroundAlphaChange: (Int) -> Unit,
    onBackgroundAlphaChangeFinished: () -> Unit,
    onHeightPercentChange: (Int) -> Unit,
    onHeightPercentChangeFinished: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .weight(1f)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 22.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(22.dp)
    ) {
        FloatingSettingSlider(
            title = "原文字体大小",
            valueText = "${fontSize}sp",
            value = fontSize.toFloat(),
            range = 14f..34f,
            colors = colors,
            onValueChange = { onFontSizeChange(it.roundToInt()) },
            onValueChangeFinished = onFontSizeChangeFinished
        )
        FloatingSettingSlider(
            title = "窗口背景透明度",
            valueText = "${100 - backgroundAlpha}%",
            value = (100 - backgroundAlpha).toFloat(),
            range = 0f..100f,
            colors = colors,
            onValueChange = { onBackgroundAlphaChange(100 - it.roundToInt()) },
            onValueChangeFinished = onBackgroundAlphaChangeFinished
        )
        FloatingSettingSlider(
            title = "窗口高度",
            valueText = "$heightPercent%",
            value = heightPercent.toFloat(),
            range = 15f..90f,
            colors = colors,
            onValueChange = { onHeightPercentChange(it.roundToInt()) },
            onValueChangeFinished = onHeightPercentChangeFinished
        )
        Text(
            text = "透明度过高时，原文在复杂背景上可能不易阅读。",
            color = colors.subtleText,
            fontSize = 12.sp,
            lineHeight = 18.sp
        )
    }
}

@Composable
private fun FloatingSettingSlider(
    title: String,
    valueText: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    colors: PlayerColors,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = title,
                color = colors.primaryText,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f)
            )
            Text(text = valueText, color = colors.secondaryText, fontSize = 13.sp)
        }
        Slider(
            value = value.coerceIn(range.start, range.endInclusive),
            onValueChange = onValueChange,
            onValueChangeFinished = onValueChangeFinished,
            valueRange = range,
            colors = SliderDefaults.colors(
                thumbColor = colors.accent,
                activeTrackColor = colors.accent,
                inactiveTrackColor = colors.panel
            )
        )
    }
}

private fun Modifier.floatingDrag(
    onDragStart: () -> Unit,
    onDrag: (Int, Int) -> Unit,
    onDragEnd: () -> Unit
): Modifier = pointerInput(Unit) {
    detectDragGestures(
        onDragStart = { onDragStart() },
        onDragEnd = onDragEnd,
        onDragCancel = onDragEnd
    ) { change, amount ->
        change.consume()
        onDrag(amount.x.roundToInt(), amount.y.roundToInt())
    }
}
