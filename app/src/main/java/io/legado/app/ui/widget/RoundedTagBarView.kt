package io.legado.app.ui.widget

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.util.AttributeSet
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import io.legado.app.R
import io.legado.app.constant.PreferKey
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.TopBarConfig
import io.legado.app.lib.theme.UiCorner
import io.legado.app.lib.theme.accentColor
import io.legado.app.lib.theme.primaryTextColor
import io.legado.app.lib.theme.secondaryTextColor
import io.legado.app.lib.theme.themeCardColorOrDefault
import io.legado.app.lib.theme.themeColorOrNull
import io.legado.app.lib.theme.themeMutedColorOrDefault
import io.legado.app.lib.theme.uiTypeface
import io.legado.app.utils.ColorUtils

class RoundedTagBarView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    enum class DisplayMode { CHIP, LIGHT, TEXT }

    /**
     * 标签层级（F29 两级导航视觉分层）：
     * - [PRIMARY] 一级胶囊：现状（实心选中底 + 主文字色），用于分组/类型等主导航层级；
     * - [SECONDARY] 二级 chip：小字号 + accent 细描边弱底，用于挂在层级下的源/类目标签。
     * 默认 PRIMARY ⇒ 未显式设置的调用方行为完全不变（零回归面）。
     */
    enum class TagLevel { PRIMARY, SECONDARY }

    data class Item(
        val text: CharSequence,
        val alpha: Float = 1f,
        val tag: Any? = null
    )

    private val layoutManager = LinearLayoutManager(context, RecyclerView.HORIZONTAL, false)
    private val adapter = TagAdapter()
    private val recyclerView = RecyclerView(context).apply {
        layoutManager = this@RoundedTagBarView.layoutManager
        adapter = this@RoundedTagBarView.adapter
        overScrollMode = OVER_SCROLL_NEVER
        itemAnimator = null
        clipToPadding = false
        isHorizontalScrollBarEnabled = false
        isHorizontalFadingEdgeEnabled = false
        isVerticalFadingEdgeEnabled = false
        setFadingEdgeLength(0)
        val verticalPadding = resources.getDimensionPixelSize(R.dimen.bookshelf_tag_recycler_padding_vertical)
        setPadding(0, verticalPadding, 0, verticalPadding)
    }
    private var items = emptyList<Item>()
    private var selectedIndex = RecyclerView.NO_POSITION
    private var onTagClick: ((Int) -> Unit)? = null
    private var onTagLongClick: ((Int) -> Boolean)? = null
    private var styleSignature: String? = null
    private var selectedBackgroundVisible = true
    private var displayMode = DisplayMode.CHIP
    private var tagLevel = TagLevel.PRIMARY
    private var backgroundOverrideColor: Int? = null
    /** F3（优化 2，2026-09-21）：横向溢出渐隐开关。默认关 ⇒ 既有调用点行为零改动（共享件只做可选扩展）。 */
    private var overflowFadeEnabled = false
    /** 渐隐收敛色 = 本栏解析后的底色（与 background 同源），保证渐隐融入栏底而非露出异色块 */
    private var fadeColor: Int = 0
    private var fadeLeftActive = false
    private var fadeRightActive = false
    private val fadePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    /** 渐隐遮罩宽度（F3）：取 30dp，与设计稿「右缘 30px 渐隐」一致 */
    private val fadeWidth: Float get() = 30.dp.toFloat()

    init {
        clipToOutline = true
        applyTopBarStyle(force = true)
        val horizontalPadding = resources.getDimensionPixelSize(R.dimen.bookshelf_tag_bar_padding_horizontal)
        val verticalPadding = resources.getDimensionPixelSize(R.dimen.bookshelf_tag_bar_padding_vertical)
        setPadding(horizontalPadding, verticalPadding, horizontalPadding, verticalPadding)
        addView(
            recyclerView,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        )
        // 溢出方向随滚动变化 ⇒ 监听滚动刷新渐隐侧
        recyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                updateFadeEdges()
            }
        })
    }

    /**
     * F3（优化 2）：开启横向溢出渐隐（可滚动性的视觉暗示，仅在确有溢出方向时绘制）。
     * 默认关闭：只有显式开启的宿主（主 Tab 顶栏标签行）生效，其他调用点零影响。
     */
    fun setOverflowFadeEnabled(enabled: Boolean) {
        if (overflowFadeEnabled == enabled) return
        overflowFadeEnabled = enabled
        updateFadeEdges()
        invalidate()
    }

    /** 依当前溢出方向刷新左右渐隐侧；无变化时不重绘 */
    private fun updateFadeEdges() {
        val active = overflowFadeEnabled && fadeColor != 0
        val left = active && recyclerView.canScrollHorizontally(-1)
        val right = active && recyclerView.canScrollHorizontally(1)
        if (left == fadeLeftActive && right == fadeRightActive) return
        fadeLeftActive = left
        fadeRightActive = right
        invalidate()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        // 宿主显隐切换后需在布局完成时重算（隐藏态 canScrollHorizontally 恒为 false）
        recyclerView.post { updateFadeEdges() }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        updateFadeEdges()
    }

    /**
     * 渐隐遮罩：绘制在子视图（标签）之上，用栏底色由透明渐变收敛到实色。
     * 不拦截触摸（纯绘制，无子 View），圆角由 clipToOutline 自动裁剪。
     */
    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        if (!fadeLeftActive && !fadeRightActive) return
        val top = paddingTop.toFloat()
        val bottom = (height - paddingBottom).toFloat()
        if (bottom <= top) return
        val leftEdge = paddingLeft.toFloat()
        val rightEdge = (width - paddingRight).toFloat()
        if (fadeRightActive) {
            fadePaint.shader = LinearGradient(
                rightEdge - fadeWidth, 0f, rightEdge, 0f,
                intArrayOf(Color.TRANSPARENT, fadeColor), null, Shader.TileMode.CLAMP
            )
            canvas.drawRect(rightEdge - fadeWidth, top, rightEdge, bottom, fadePaint)
        }
        if (fadeLeftActive) {
            fadePaint.shader = LinearGradient(
                leftEdge, 0f, leftEdge + fadeWidth, 0f,
                intArrayOf(fadeColor, Color.TRANSPARENT), null, Shader.TileMode.CLAMP
            )
            canvas.drawRect(leftEdge, top, leftEdge + fadeWidth, bottom, fadePaint)
        }
        fadePaint.shader = null
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        applyTopBarStyle()
    }

    fun applyTopBarStyle(force: Boolean = false) {
        val signature = "${TopBarConfig.currentSignature(AppConfig.isNightTheme)}|$displayMode|$backgroundOverrideColor"
        if (!force && styleSignature == signature) return
        styleSignature = signature
        val config = TopBarConfig.currentConfig(context, AppConfig.isNightTheme)
        val tagBarColor = config.tagBarColor
            // R28（2026-09-23）：regular 风格 tagBarAlpha=0（栏底完全透明），此兜底色仅作
            // withOpacity 的基色、其 RGB 永不参与渲染 ⇒ 改用「无填充」语义替代原硬编码白色字面值。
            // 非 regular 风格仍走 tabBackgroundColor / mutedColor（面 token 归属表）。
            ?: if (config.style == TopBarConfig.STYLE_REGULAR) {
                Color.TRANSPARENT
            } else {
                context.themeColorOrNull(PreferKey.themeTabBackgroundColor)
                    ?: context.themeMutedColorOrDefault()
            }
        val selectedColor = config.tagSelectedColor
            ?: context.themeCardColorOrDefault()
        val barColor = backgroundOverrideColor ?: TopBarConfig.withOpacity(tagBarColor, config.tagBarAlpha)
        background = when (displayMode) {
            DisplayMode.TEXT -> null
            else -> UiCorner.opaqueRounded(barColor, UiCorner.panelRadius(context))
        }
        // F3：渐隐收敛色必须等于"肉眼可见的栏底"——regular 风格 tagBarAlpha=0（栏底完全透明、标签浮在
        // 顶栏底色/壁纸上），若仍按透明色渐隐则整条渐隐不可见（真机实测 delta≈0）⇒ 回落到该顶栏的
        // 页面底色（与 MainTopBarView 背景层同源 resolvePageBarColorWithAlpha）；TEXT 形态无底色则不绘
        fadeColor = when {
            displayMode == DisplayMode.TEXT -> 0
            Color.alpha(barColor) >= FADE_MIN_BAR_ALPHA -> barColor
            else -> TopBarConfig.resolvePageBarColorWithAlpha(context, config)
        }
        val horizontalPadding = resources.getDimensionPixelSize(R.dimen.bookshelf_tag_bar_padding_horizontal)
        val verticalPadding = resources.getDimensionPixelSize(R.dimen.bookshelf_tag_bar_padding_vertical)
        setPadding(
            if (displayMode == DisplayMode.TEXT) 0 else horizontalPadding,
            if (displayMode == DisplayMode.TEXT) 0 else verticalPadding,
            if (displayMode == DisplayMode.TEXT) 0 else horizontalPadding,
            if (displayMode == DisplayMode.TEXT) 0 else verticalPadding
        )
        adapter.selectedBackgroundColor = TopBarConfig.withOpacity(selectedColor, config.tagSelectedAlpha)
        adapter.selectedTextColor = readableTagTextColor(context.accentColor, adapter.selectedBackgroundColor)
        adapter.normalTextColor = context.primaryTextColor
        adapter.notifyDataSetChanged()
        // 底色/内边距变更会影响溢出方向 ⇒ 布局完成后重算渐隐侧
        recyclerView.post { updateFadeEdges() }
    }

    private fun readableTagTextColor(preferredColor: Int, backgroundColor: Int): Int {
        if (Color.alpha(backgroundColor) < 40) return preferredColor
        // R31 单源（2026-09-23）：对比度兜底统一走 ColorUtils.contrastOnColor，
        // 不再本文件自建黑白兜底（改造前为三套并存之一：本函数 / badgeTextBright / contrastOn）。
        // 保留「preferred 与底已反差 ⇒ 沿用 preferred」的既有快捷分支，保证既有观感零回归。
        val preferredContrasts =
            ColorUtils.isColorLight(preferredColor) != ColorUtils.isColorLight(backgroundColor)
        return if (preferredContrasts) preferredColor else ColorUtils.contrastOnColor(backgroundColor)
    }

    fun setDisplayMode(mode: DisplayMode) {
        if (displayMode == mode) return
        displayMode = mode
        styleSignature = null
        applyTopBarStyle(force = true)
    }

    fun setBackgroundOverrideColor(color: Int?) {
        if (backgroundOverrideColor == color) return
        backgroundOverrideColor = color
        styleSignature = null
        applyTopBarStyle(force = true)
    }

    fun setSelectedBackgroundVisible(visible: Boolean) {
        if (selectedBackgroundVisible == visible) return
        selectedBackgroundVisible = visible
        adapter.notifyDataSetChanged()
    }

    /** 设置标签层级（F29）；同值幂等，仅触发条目重绘，不影响顶栏包背景/间距等既有单源样式 */
    fun setTagLevel(level: TagLevel) {
        if (tagLevel == level) return
        tagLevel = level
        adapter.notifyDataSetChanged()
    }

    fun submitItems(items: List<Item>, selectedIndex: Int = this.selectedIndex) {
        val sameItems = this.items == items
        if (sameItems) {
            setSelectedIndex(selectedIndex, smooth = false)
            return
        }
        this.items = items.toList()
        this.selectedIndex = normalizeIndex(selectedIndex)
        adapter.notifyDataSetChanged()
        if (this.selectedIndex != RecyclerView.NO_POSITION) {
            scrollToIndex(this.selectedIndex, smooth = false)
        }
        // 条目变更决定是否溢出 ⇒ 布局完成后重算渐隐侧
        recyclerView.post { updateFadeEdges() }
    }

    fun setSelectedIndex(index: Int, smooth: Boolean = true) {
        val newIndex = normalizeIndex(index)
        if (selectedIndex == newIndex) {
            if (newIndex != RecyclerView.NO_POSITION) {
                scrollToIndex(newIndex, smooth)
            }
            return
        }
        val oldIndex = selectedIndex
        selectedIndex = newIndex
        if (oldIndex in items.indices) {
            adapter.notifyItemChanged(oldIndex)
        }
        if (newIndex != RecyclerView.NO_POSITION) {
            adapter.notifyItemChanged(newIndex)
            scrollToIndex(newIndex, smooth)
        }
    }

    fun getSelectedIndex(): Int = selectedIndex

    fun setOnTagClickListener(listener: ((Int) -> Unit)?) {
        onTagClick = listener
    }

    fun setOnTagLongClickListener(listener: ((Int) -> Boolean)?) {
        onTagLongClick = listener
    }

    private fun normalizeIndex(index: Int): Int {
        return if (index in items.indices) index else RecyclerView.NO_POSITION
    }

    private fun scrollToIndex(index: Int, smooth: Boolean) {
        recyclerView.post {
            if (index !in items.indices) return@post
            val child = layoutManager.findViewByPosition(index)
            if (child == null) {
                if (smooth) {
                    recyclerView.smoothScrollToPosition(index)
                } else {
                    recyclerView.scrollToPosition(index)
                }
                recyclerView.post { centerVisibleChild(index, false) }
                return@post
            }
            centerChild(child.left, child.width, smooth)
        }
    }

    private fun centerVisibleChild(index: Int, smooth: Boolean) {
        val child = layoutManager.findViewByPosition(index) ?: return
        centerChild(child.left, child.width, smooth)
    }

    private fun centerChild(childLeft: Int, childWidth: Int, smooth: Boolean) {
        val dx = childLeft - (recyclerView.width - childWidth) / 2
        if (dx == 0) return
        if (smooth) {
            recyclerView.smoothScrollBy(dx, 0)
        } else {
            recyclerView.scrollBy(dx, 0)
        }
    }

    private inner class TagAdapter : RecyclerView.Adapter<TagViewHolder>() {

        var selectedBackgroundColor: Int = context.themeCardColorOrDefault()
        var selectedTextColor: Int = context.accentColor
        var normalTextColor: Int = context.primaryTextColor

        override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): TagViewHolder {
            val textView = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_bookshelf_group_tag, parent, false) as TextView
            textView.background = UiCorner.actionSelector(
                android.graphics.Color.TRANSPARENT,
                selectedBackgroundColor,
                UiCorner.actionRadius(parent.context)
            )
            textView.setTextColor(
                ColorStateList(
                    arrayOf(intArrayOf(android.R.attr.state_selected), intArrayOf()),
                    intArrayOf(selectedTextColor, normalTextColor)
                )
            )
            return TagViewHolder(textView, textView.textSize)
        }

        override fun onBindViewHolder(holder: TagViewHolder, position: Int) {
            val item = items[position]
            val tagContext = holder.textView.context
            // F29：二级层级只在 CHIP 形态生效（TEXT 形态本就是无底纯文字，无需再降级）
            val secondary = tagLevel == TagLevel.SECONDARY && displayMode != DisplayMode.TEXT
            val accent = tagContext.accentColor
            holder.textView.background = if (secondary) {
                UiCorner.actionStrokeSelector(
                    android.graphics.Color.TRANSPARENT,
                    ColorUtils.adjustAlpha(accent, SECONDARY_SELECTED_FILL_ALPHA),
                    UiCorner.actionRadius(tagContext),
                    1.dp,
                    ColorUtils.adjustAlpha(accent, SECONDARY_STROKE_ALPHA)
                )
            } else {
                UiCorner.actionSelector(
                    android.graphics.Color.TRANSPARENT,
                    when {
                        !selectedBackgroundVisible -> android.graphics.Color.TRANSPARENT
                        displayMode == DisplayMode.TEXT -> android.graphics.Color.TRANSPARENT
                        else -> selectedBackgroundColor
                    },
                    UiCorner.actionRadius(tagContext)
                )
            }
            val verticalPadding = if (displayMode == DisplayMode.TEXT) 0 else resources.getDimensionPixelSize(R.dimen.bookshelf_tag_recycler_padding_vertical)
            val horizontalPadding = if (displayMode == DisplayMode.TEXT) 8.dp else resources.getDimensionPixelSize(R.dimen.bookshelf_tag_item_padding_horizontal)
            holder.textView.setPadding(horizontalPadding, verticalPadding, horizontalPadding, verticalPadding)
            holder.textView.setTextColor(
                ColorStateList(
                    arrayOf(intArrayOf(android.R.attr.state_selected), intArrayOf()),
                    if (secondary) {
                        // 二级：未选中=次级文字色（弱），选中=accent（描边弱底上仍可读）
                        intArrayOf(accent, tagContext.secondaryTextColor)
                    } else {
                        intArrayOf(selectedTextColor, normalTextColor)
                    }
                )
            )
            if (secondary) {
                // 二级字号单源：以 XML 默认字号为一级基准，二级按其比例缩一档（避免双处硬编码字号）
                holder.textView.setTextSize(
                    TypedValue.COMPLEX_UNIT_PX,
                    holder.defaultTextSizePx * SECONDARY_TEXT_SCALE
                )
            } else {
                holder.textView.setTextSize(TypedValue.COMPLEX_UNIT_PX, holder.defaultTextSizePx)
            }
            holder.textView.text = item.text
            holder.textView.typeface = holder.textView.context.uiTypeface()
            holder.textView.alpha = item.alpha
            holder.textView.isSelected = position == selectedIndex
            holder.textView.setOnClickListener {
                val bindingPosition = holder.bindingAdapterPosition
                if (bindingPosition != RecyclerView.NO_POSITION) {
                    onTagClick?.invoke(bindingPosition)
                }
            }
            holder.textView.setOnLongClickListener {
                val bindingPosition = holder.bindingAdapterPosition
                if (bindingPosition == RecyclerView.NO_POSITION) {
                    false
                } else {
                    onTagLongClick?.invoke(bindingPosition) ?: false
                }
            }
        }

        override fun getItemCount(): Int = items.size
    }

    private class TagViewHolder(val textView: TextView, val defaultTextSizePx: Float) :
        RecyclerView.ViewHolder(textView)

    private val Int.dp: Int get() = (this * resources.displayMetrics.density).toInt()

    private companion object {
        /** 二级层级相对一级的字号比例（一级字号取自 XML，见 TagViewHolder.defaultTextSizePx） */
        const val SECONDARY_TEXT_SCALE = 0.86f
        /** 二级描边透明度 */
        const val SECONDARY_STROKE_ALPHA = 0.45f
        /** 二级选中弱底透明度 */
        const val SECONDARY_SELECTED_FILL_ALPHA = 0.18f
        /** F3：栏底透明度低于此值视为"无可见底色"，渐隐收敛色回落到顶栏页面底色 */
        const val FADE_MIN_BAR_ALPHA = 40
    }
}
