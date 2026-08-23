package io.legado.app.ui.widget

import android.animation.LayoutTransition
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.text.TextUtils
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.LinearLayout.LayoutParams
import android.widget.Space
import android.widget.TextView
import androidx.appcompat.widget.AppCompatImageButton
import androidx.appcompat.widget.AppCompatImageView
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import io.legado.app.R
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.TopBarConfig
import io.legado.app.lib.theme.TopBarSearchStyle
import io.legado.app.lib.theme.UiCorner
import io.legado.app.lib.theme.applyUiTitleTypeface
import io.legado.app.lib.theme.backgroundColor
import io.legado.app.lib.theme.primaryTextColor
import io.legado.app.lib.theme.titleTextColor
import io.legado.app.lib.theme.uiTypeface
import io.legado.app.ui.widget.compose.ComposeThemeImageLayer
import io.legado.app.ui.widget.compose.ComposeThemeImageCrop
import io.legado.app.ui.widget.compose.ComposeThemeImageState
import io.legado.app.utils.ImageTypeUtils
import io.legado.app.utils.StatusBarInsetAware

class MainTopBarView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : LinearLayout(context, attrs), StatusBarInsetAware {

    enum class Mode { BOOKSHELF, DISCOVERY, RSS, READ_RECORD }

    val titleSelect = LinearLayout(context)
    val titleText = TextView(context)
    val titleArrow = AppCompatImageView(context)
    val searchEntry = LinearLayout(context)
    private val searchEntryText = TextView(context)
    private val searchEntryIcon = AppCompatImageView(context)
    val moreButton = actionButton(R.drawable.ic_more_vert, R.string.menu)
    val searchButton = actionButton(R.drawable.ic_search, R.string.search)
    val filterButton = actionButton(R.drawable.ic_sort, R.string.sort)
    val starButton = actionButton(R.drawable.ic_star, R.string.favorite)
    val refreshButton = actionButton(R.drawable.ic_refresh_black_24dp, R.string.refresh)
    val loginButton = actionButton(R.drawable.ic_bottom_person, R.string.login)
    val primaryBar = RoundedTagBarView(context)
    val selectsBar = RoundedTagBarView(context)
    val tagsBar = RoundedTagBarView(context)
    private val primaryFilterRow = LinearLayout(context)
    private val filterToggleButton = actionButton(R.drawable.ic_expand_more, R.string.screen)
    private val titleSpacer = Space(context)
    private val titleRow = buildTitleRow()
    private val surfaceLayout = ContentMeasuredFrameLayout(context)
    private val contentLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        clipChildren = true
        clipToPadding = false
    }
    private val backgroundLayer = ComposeView(context).apply {
        isClickable = false
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
    }
    /** 覆盖式宿主在 API>=33 时用于背景毛玻璃的采样层,默认隐藏。 */
    private val backdropGlass = StableLiquidGlassView(context).apply {
        visibility = View.GONE
        isClickable = false
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    private var backdropGlassActive = false
    private var mode = Mode.BOOKSHELF
    private var styleSignature: String? = null
    private var primaryBarRequested = false
    private var selectsBarRequested = false
    private var tagsBarRequested = false
    private var filtersExpanded = false
    private var searchEntryRequested = true
    private var onHeightChanged: (() -> Unit)? = null
    private var onFilterExpandedChanged: ((Boolean) -> Unit)? = null
    private var statusBarInsetTop: Int = 0
    private val topBarEaseOut = PathInterpolator(0.22f, 0.61f, 0.36f, 1.00f)
    private val topBarEaseInOut = PathInterpolator(0.45f, 0.00f, 0.20f, 1.00f)
    /** 覆盖式宿主(顶栏浮在列表之上，如发现页)置 true，使默认样式顶栏不透明，避免列表透出。 */
    var overlayOpaqueBackground = false
        set(value) {
            if (field == value) return
            field = value
            applyTopBarStyle(force = true, resetFilters = false)
        }

    init {
        orientation = VERTICAL
        clipChildren = true
        clipToPadding = false
        val horizontal = resources.getDimensionPixelSize(R.dimen.bookshelf_tag_bar_margin_horizontal)
        contentLayout.setPadding(horizontal, paddingTop, horizontal, 0)
        addView(surfaceLayout, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        surfaceLayout.addView(
            backgroundLayer,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        surfaceLayout.addView(
            contentLayout,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
        )
        // 玻璃层放在背景层之上、内容层之下:激活时作为半透明顶栏的实底,内容仍清晰。
        surfaceLayout.addView(
            backdropGlass,
            1,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        contentLayout.addView(titleRow)
        searchEntryText.typeface = context.uiTypeface()
        contentLayout.addView(primaryFilterRow.apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(primaryBar, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
            addView(filterToggleButton)
        }, tagLayoutParams().apply {
            topMargin = resources.getDimensionPixelSize(R.dimen.bookshelf_tag_bar_margin_top)
        })
        contentLayout.addView(selectsBar, tagLayoutParams().apply {
            topMargin = resources.getDimensionPixelSize(R.dimen.bookshelf_tag_bar_margin_top)
        })
        contentLayout.addView(tagsBar, tagLayoutParams().apply {
            topMargin = resources.getDimensionPixelSize(R.dimen.bookshelf_tag_bar_margin_top)
        })
        primaryBar.isVisible = false
        primaryFilterRow.isVisible = false
        filterToggleButton.isVisible = false
        selectsBar.isVisible = false
        tagsBar.isVisible = false
        filterToggleButton.setOnClickListener {
            filtersExpanded = !filtersExpanded
            updateFilterBarsVisibility()
            onFilterExpandedChanged?.invoke(filtersExpanded)
        }
        setMode(Mode.BOOKSHELF)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        applyTopBarStyle(force = true)
    }

    fun setMode(mode: Mode) {
        this.mode = mode
        moreButton.isVisible = mode == Mode.BOOKSHELF || mode == Mode.READ_RECORD
        searchButton.isVisible = mode == Mode.DISCOVERY || mode == Mode.RSS
        filterButton.isVisible = mode == Mode.DISCOVERY
        starButton.isVisible = mode == Mode.RSS
        refreshButton.isVisible = mode == Mode.RSS
        loginButton.isVisible = mode == Mode.DISCOVERY || mode == Mode.RSS
        titleText.textSize = if (mode == Mode.BOOKSHELF) 24f else 20f
        titleText.applyUiTitleTypeface(context)
        applyTopBarStyle(force = true)
    }

    fun setTitle(text: CharSequence) {
        titleText.text = text
    }

    fun setSearchHint(text: CharSequence) {
        searchEntryText.text = text
    }

    fun setSearchEntryVisible(visible: Boolean) {
        if (searchEntryRequested == visible) return
        searchEntryRequested = visible
        applyTopBarStyle(force = true)
    }

    fun setPrimaryItems(items: List<RoundedTagBarView.Item>, selectedIndex: Int) {
        primaryBarRequested = items.isNotEmpty()
        primaryBar.submitItems(items, selectedIndex)
        updatePrimaryBarVisibility()
    }

    fun isRegularStyle(): Boolean {
        return TopBarConfig.currentConfig(context, AppConfig.isNightTheme).style == TopBarConfig.STYLE_REGULAR
    }

    private fun isFloatingSearchHidden(): Boolean {
        return AppConfig.bottomBarLayoutMode == "floating" && AppConfig.floatingBottomBarHideSearch
    }

    fun isOverlayMode(): Boolean {
        return isRegularStyle()
    }

    /** 设备是否支持背景毛玻璃(液态玻璃着色器需 API 33+);宿主据此决定覆盖式 or 非覆盖兜底。 */
    fun supportsBackdropBlur(): Boolean {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
    }

    /**
     * 覆盖式宿主(列表滚到顶栏下方)在 API>=33 时,用背景毛玻璃做实底,避免透明顶栏透出滚动的列表。
     * @param source 列表容器(玻璃采样源);传 null 或低版本时关闭玻璃,改由宿主使用非覆盖布局兜底。
     */
    fun setBackdropBlur(source: ViewGroup?) {
        val active = source != null && supportsBackdropBlur()
        backdropGlassActive = active
        if (active) {
            val config = TopBarConfig.currentConfig(context, AppConfig.isNightTheme)
            val radius = if (isRegularStyle()) TopBarConfig.cornerRadius(context, config) else 0f
            backdropGlass.visibility = View.VISIBLE
            backdropGlass.setCornerRadius(radius)
            backdropGlass.setBlurRadius(18.dp.toFloat())
            backdropGlass.setTintAlpha(0.10f)
            backdropGlass.setDispersion(0.10f)
            backdropGlass.setRefractionHeight(14.dp.toFloat())
            backdropGlass.setRefractionOffset(26.dp.toFloat())
            backdropGlass.bind(source)
        } else {
            backdropGlass.visibility = View.GONE
            backdropGlass.bind(null)
        }
        applyTopBarStyle(force = true, resetFilters = false)
    }

    fun refreshStyle() {
        styleSignature = null
        listOf(primaryBar, selectsBar, tagsBar).forEach { it.applyTopBarStyle(force = true) }
        applyTopBarStyle(force = true)
        requestLayout()
        invalidate()
    }

    fun setOnHeightChangedListener(listener: (() -> Unit)?) {
        onHeightChanged = listener
        notifyHeightChangedAfterLayout()
    }

    fun setOnFilterExpandedChangedListener(listener: ((Boolean) -> Unit)?) {
        onFilterExpandedChanged = listener
    }

    fun setFiltersExpanded(expanded: Boolean) {
        if (filtersExpanded == expanded) {
            updateFilterBarsVisibility()
            return
        }
        filtersExpanded = expanded
        updateFilterBarsVisibility()
    }

    fun showSelects(show: Boolean) {
        selectsBarRequested = show
        if (!show) {
            filtersExpanded = tagsBarRequested && filtersExpanded
        }
        updateFilterBarsVisibility()
    }

    fun showTags(show: Boolean) {
        tagsBarRequested = show
        if (!show) {
            filtersExpanded = selectsBarRequested && filtersExpanded
        }
        updateFilterBarsVisibility()
    }

    fun setActionsVisible(
        search: Boolean? = null,
        filter: Boolean? = null,
        star: Boolean? = null,
        refresh: Boolean? = null,
        login: Boolean? = null
    ) {
        search?.let { searchButton.isVisible = it }
        filter?.let { filterButton.isVisible = it }
        star?.let { starButton.isVisible = it }
        refresh?.let { refreshButton.isVisible = it }
        login?.let { loginButton.isVisible = it }
    }

    private fun applyTopBarStyle(force: Boolean = false, resetFilters: Boolean = force) {
        val signature = "${TopBarConfig.currentSignature(AppConfig.isNightTheme)}|$mode"
        if (!force && styleSignature == signature) return
        val signatureChanged = styleSignature != signature
        styleSignature = signature
        val config = TopBarConfig.currentConfig(context, AppConfig.isNightTheme)
        if (resetFilters && (force || signatureChanged)) {
            filtersExpanded = config.style == TopBarConfig.STYLE_REGULAR && config.expandFiltersByDefault
        }
        if (config.style == TopBarConfig.STYLE_REGULAR) {
            applyRegularStyle(config)
        } else {
            applyDefaultStyle()
        }
        updatePrimaryBarVisibility()
        updateFilterBarsVisibility()
        updateIconColors()
    }

    override fun onStatusBarInsetChanged(insetTop: Int, initialPaddingTop: Int) {
        if (statusBarInsetTop == insetTop) return
        statusBarInsetTop = insetTop
        applyTopBarStyle(force = true, resetFilters = false)
        notifyHeightChangedAfterLayout()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (h != oldh) {
            notifyHeightChangedAfterLayout()
        }
    }

    private fun notifyHeightChangedAfterLayout() {
        post { onHeightChanged?.invoke() }
    }
    private fun updateTitleRowControlHeight(height: Int) {
        searchEntry.layoutParams = (searchEntry.layoutParams as? LayoutParams ?: LayoutParams(0, height, 1f)).apply {
            this.height = height
        }
        titleSelect.layoutParams = (titleSelect.layoutParams as? LayoutParams ?: LayoutParams(LayoutParams.WRAP_CONTENT, height)).apply {
            this.height = height
        }
    }

    private fun applyDefaultStyle() {
        contentLayout.layoutTransition = null
        val horizontal = resources.getDimensionPixelSize(R.dimen.bookshelf_tag_bar_margin_horizontal)
        contentLayout.setPadding(horizontal, statusBarInsetTop, horizontal, 0)
        // 覆盖式场景(如发现页顶栏浮在列表之上)需要不透明底，否则滚动的书籍会从透明顶栏后透出。
        background = if (overlayOpaqueBackground && !backdropGlassActive) {
            ColorDrawable(context.backgroundColor)
        } else {
            null
        }
        renderBackgroundLayer(null, 0f)
        titleRow.background = null
        titleRow.setPadding(0, resources.getDimensionPixelSize(R.dimen.bookshelf_title_row_margin_top), 0, 0)
        updateTitleRowControlHeight(resources.getDimensionPixelSize(R.dimen.bookshelf_title_select_height))
        val config = TopBarConfig.currentConfig(context, AppConfig.isNightTheme)
        // 顶栏搜索按钮：包自身开启，或悬浮底栏隐藏了搜索时自动顶上来，保证搜索始终可达。
        val showSearch = config.showSearchInDefaultStyle || isFloatingSearchHidden()
        searchEntry.isVisible = false
        titleSelect.isVisible = true
        titleSpacer.isVisible = true
        if (mode == Mode.BOOKSHELF) {
            searchButton.isVisible = showSearch
        }
        titleSelect.background = ContextCompat.getDrawable(context, R.drawable.bg_discover_embedded_action)
        listOf(moreButton, searchButton, filterButton, starButton, refreshButton, loginButton, filterToggleButton).forEach {
            it.background = ContextCompat.getDrawable(context, R.drawable.bg_discover_embedded_action)
            it.layoutParams = (it.layoutParams as LayoutParams).apply {
                width = resources.getDimensionPixelSize(R.dimen.bookshelf_action_button_size)
                height = resources.getDimensionPixelSize(R.dimen.bookshelf_action_button_size)
                marginStart = 8.dp
            }
            val padding = resources.getDimensionPixelSize(R.dimen.bookshelf_action_button_padding)
            it.setPadding(padding, padding, padding, padding)
        }
        titleText.gravity = Gravity.CENTER_VERTICAL
        titleText.setTextColor(context.titleTextColor)
        searchEntryText.setTextColor(context.primaryTextColor)
        primaryBar.setDisplayMode(RoundedTagBarView.DisplayMode.CHIP)
        selectsBar.setDisplayMode(RoundedTagBarView.DisplayMode.CHIP)
        tagsBar.setDisplayMode(RoundedTagBarView.DisplayMode.CHIP)
        primaryBar.setBackgroundOverrideColor(null)
        selectsBar.setBackgroundOverrideColor(null)
        tagsBar.setBackgroundOverrideColor(null)
        primaryBar.setSelectedBackgroundVisible(true)
        selectsBar.setSelectedBackgroundVisible(true)
        tagsBar.setSelectedBackgroundVisible(true)
    }

    private fun applyRegularStyle(config: TopBarConfig.Config) {
        if (contentLayout.layoutTransition == null) {
            contentLayout.layoutTransition = createTopBarLayoutTransition()
        }
        val horizontal = resources.getDimensionPixelSize(R.dimen.bookshelf_tag_bar_margin_horizontal)
        val vertical = 5.dp
        contentLayout.setPadding(horizontal, statusBarInsetTop + vertical, horizontal, vertical)
        background = if (overlayOpaqueBackground && !backdropGlassActive) {
            ColorDrawable(context.backgroundColor)
        } else {
            null
        }
        val hideConfigBg = overlayOpaqueBackground || backdropGlassActive
        renderBackgroundLayer(
            config.takeUnless { hideConfigBg },
            if (hideConfigBg) 0f else TopBarConfig.cornerRadius(context, config)
        )
        titleRow.background = null
        titleRow.setPadding(0, 0, 0, 0)
        updateTitleRowControlHeight(resources.getDimensionPixelSize(R.dimen.top_bar_regular_action_size))
        titleSelect.isVisible = !searchEntryRequested
        searchEntry.isVisible = searchEntryRequested
        titleSpacer.isVisible = !searchEntryRequested
        titleSelect.background = null
        searchEntry.background = TopBarSearchStyle.actionBackground(context)
        searchEntry.setPadding(14.dp, 0, 14.dp, 0)
        titleSelect.setPadding(12.dp, 0, 8.dp, 0)
        listOf(moreButton, searchButton, filterButton, starButton, refreshButton, loginButton, filterToggleButton).forEach {
            it.background = null
            it.layoutParams = (it.layoutParams as LayoutParams).apply {
                width = resources.getDimensionPixelSize(R.dimen.top_bar_regular_action_size)
                height = resources.getDimensionPixelSize(R.dimen.top_bar_regular_action_size)
                marginStart = 6.dp
            }
            val padding = 8.dp
            it.setPadding(padding, padding, padding, padding)
        }
        titleText.gravity = Gravity.CENTER_VERTICAL
        titleText.setTextColor(context.titleTextColor)
        searchEntryText.setTextColor(context.primaryTextColor)
        primaryBar.setDisplayMode(RoundedTagBarView.DisplayMode.CHIP)
        selectsBar.setDisplayMode(RoundedTagBarView.DisplayMode.CHIP)
        tagsBar.setDisplayMode(RoundedTagBarView.DisplayMode.CHIP)
        primaryBar.setBackgroundOverrideColor(null)
        selectsBar.setBackgroundOverrideColor(null)
        tagsBar.setBackgroundOverrideColor(null)
        primaryBar.setSelectedBackgroundVisible(true)
        selectsBar.setSelectedBackgroundVisible(true)
        tagsBar.setSelectedBackgroundVisible(mode == Mode.DISCOVERY)
    }

    private fun buildTitleRow(): LinearLayout {
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, resources.getDimensionPixelSize(R.dimen.bookshelf_title_row_margin_top), 0, 0)
            addView(searchEntry.apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                isClickable = true
                isFocusable = true
                visibility = View.GONE
                val height = resources.getDimensionPixelSize(R.dimen.bookshelf_title_select_height)
                layoutParams = LayoutParams(0, height, 1f)
                setPadding(14.dp, 0, 14.dp, 0)
                addView(searchEntryIcon.apply {
                    setImageResource(R.drawable.ic_search)
                    layoutParams = LayoutParams(17.dp, 17.dp)
                })
                addView(searchEntryText.apply {
                    includeFontPadding = false
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                    textSize = 14f
                    alpha = 0.78f
                    applyUiTitleTypeface(context)
                    layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply {
                        marginStart = 8.dp
                    }
                })
            })
            addView(titleSelect.apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                isClickable = true
                isFocusable = true
                background = ContextCompat.getDrawable(context, R.drawable.bg_discover_embedded_action)
                layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, resources.getDimensionPixelSize(R.dimen.bookshelf_title_select_height))
                addView(titleText.apply {
                    includeFontPadding = false
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                    setTextColor(context.titleTextColor)
                    applyUiTitleTypeface(context)
                })
                addView(titleArrow.apply {
                    setImageResource(R.drawable.ic_arrow_drop_down)
                    layoutParams = LayoutParams(
                        resources.getDimensionPixelSize(R.dimen.bookshelf_title_arrow_size),
                        resources.getDimensionPixelSize(R.dimen.bookshelf_title_arrow_size)
                    )
                })
            })
            addView(titleSpacer, LayoutParams(0, 1, 1f))
            addAction(searchButton)
            addAction(filterButton)
            addAction(starButton)
            addAction(refreshButton)
            addAction(loginButton)
            addAction(moreButton)
        }
    }

    private fun LinearLayout.addAction(view: View) {
        addView(view.apply {
            layoutParams = (layoutParams as? LayoutParams ?: LayoutParams(
                resources.getDimensionPixelSize(R.dimen.bookshelf_action_button_size),
                resources.getDimensionPixelSize(R.dimen.bookshelf_action_button_size)
            )).apply {
                marginStart = 8.dp
            }
        })
    }

    private fun actionButton(drawableRes: Int, contentDescRes: Int): AppCompatImageButton {
        return AppCompatImageButton(context).apply {
            setImageResource(drawableRes)
            contentDescription = context.getString(contentDescRes)
            background = ContextCompat.getDrawable(context, R.drawable.bg_discover_embedded_action)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            val padding = resources.getDimensionPixelSize(R.dimen.bookshelf_action_button_padding)
            setPadding(padding, padding, padding, padding)
            layoutParams = LayoutParams(
                resources.getDimensionPixelSize(R.dimen.bookshelf_action_button_size),
                resources.getDimensionPixelSize(R.dimen.bookshelf_action_button_size)
            )
        }
    }

    private fun tagLayoutParams(): LayoutParams {
        return LayoutParams(
            LayoutParams.MATCH_PARENT,
            resources.getDimensionPixelSize(R.dimen.bookshelf_tag_bar_height)
        )
    }

    private fun updateIconColors() {
        val color = context.primaryTextColor
        titleArrow.setColorFilter(color)
        searchEntryIcon.setColorFilter(color)
        listOf(moreButton, searchButton, filterButton, starButton, refreshButton, loginButton, filterToggleButton).forEach {
            it.setColorFilter(color)
        }
    }

    private fun renderBackgroundLayer(config: TopBarConfig.Config?, radius: Float) {
        val state = if (config == null) {
            ComposeThemeImageState(
                file = null,
                fallbackColor = Color.TRANSPARENT
            )
        } else {
            val file = TopBarConfig.currentWallpaperFile(context, AppConfig.isNightTheme)
            val alpha = config.wallpaperAlpha.coerceIn(0, 100) / 100f
            ComposeThemeImageState(
                file = file,
                animated = ImageTypeUtils.isAnimatedImage(file),
                alpha = alpha,
                crop = topBarWallpaperCrop(config),
                fallbackColor = TopBarConfig.withOpacity(
                    TopBarConfig.resolveBackgroundColor(config),
                    config.wallpaperAlpha
                )
            )
        }
        backgroundLayer.setContent {
            ComposeThemeImageLayer(
                state = state,
                cornerRadius = (radius / resources.displayMetrics.density).dp,
                stableWidthScale = true
            )
        }
    }

    private fun topBarWallpaperCrop(config: TopBarConfig.Config): ComposeThemeImageCrop? {
        val left = config.wallpaperCropLeft ?: return null
        val top = config.wallpaperCropTop ?: return null
        val right = config.wallpaperCropRight ?: return null
        val bottom = config.wallpaperCropBottom ?: return null
        if (right <= left || bottom <= top) return null
        return ComposeThemeImageCrop(left, top, right, bottom)
    }

    private fun updatePrimaryBarVisibility() {
        primaryBar.isVisible = isRegularStyle() && primaryBarRequested
        primaryFilterRow.isVisible = primaryBar.isVisible || filterToggleButton.isVisible
    }

    private fun updateFilterBarsVisibility() {
        val hasFilters = selectsBarRequested || tagsBarRequested
        val oldRowVisible = primaryFilterRow.isVisible
        val oldToggleVisible = filterToggleButton.isVisible
        val oldSelectsVisible = selectsBar.isVisible
        val oldTagsVisible = tagsBar.isVisible
        if (isRegularStyle()) {
            val config = TopBarConfig.currentConfig(context, AppConfig.isNightTheme)
            filterToggleButton.isVisible = hasFilters &&
                !(filtersExpanded && config.hideFilterToggleWhenExpanded)
            animateFilterToggle(filtersExpanded)
            setFilterBarVisible(selectsBar, filtersExpanded && selectsBarRequested, oldSelectsVisible)
            setFilterBarVisible(tagsBar, filtersExpanded && tagsBarRequested, oldTagsVisible)
        } else {
            filterToggleButton.isVisible = false
            selectsBar.isVisible = selectsBarRequested
            tagsBar.isVisible = tagsBarRequested
        }
        primaryFilterRow.isVisible = (isRegularStyle() && primaryBarRequested) || filterToggleButton.isVisible
        if (
            oldRowVisible != primaryFilterRow.isVisible ||
            oldToggleVisible != filterToggleButton.isVisible ||
            oldSelectsVisible != selectsBar.isVisible ||
            oldTagsVisible != tagsBar.isVisible
        ) {
            requestLayout()
            invalidate()
            notifyHeightChangedAfterLayout()
        }
    }

    private fun animateFilterToggle(expanded: Boolean) {
        filterToggleButton.setImageResource(R.drawable.ic_expand_more)
        val targetRotation = if (expanded) 180f else 0f
        filterToggleButton.animate().cancel()
        if (!isAttachedToWindow) {
            filterToggleButton.rotation = targetRotation
            return
        }
        filterToggleButton.animate()
            .rotation(targetRotation)
            .setDuration(260L)
            .setInterpolator(topBarEaseOut)
            .start()
    }

    private fun setFilterBarVisible(
        view: View,
        visible: Boolean,
        wasVisible: Boolean
    ) {
        view.animate().cancel()
        if (!isAttachedToWindow) {
            view.isVisible = visible
            view.alpha = 1f
            view.translationY = 0f
            view.scaleY = 1f
            return
        }
        if (visible) {
            if (!wasVisible) {
                view.alpha = 0f
                view.translationY = (-4).dp.toFloat()
                view.isVisible = true
            } else {
                view.isVisible = true
            }
            view.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(260L)
                .setInterpolator(topBarEaseOut)
                .start()
        } else if (wasVisible) {
            view.animate()
                .alpha(0f)
                .translationY((-2).dp.toFloat())
                .setDuration(150L)
                .setInterpolator(topBarEaseInOut)
                .withEndAction {
                    if (!shouldFilterBarBeVisible(view)) {
                        view.isVisible = false
                        view.alpha = 1f
                        view.translationY = 0f
                        requestLayout()
                        invalidate()
                        notifyHeightChangedAfterLayout()
                    }
                }
                .start()
        } else {
            view.isVisible = false
            view.alpha = 1f
            view.translationY = 0f
        }
    }

    private fun shouldFilterBarBeVisible(view: View): Boolean {
        return if (isRegularStyle()) {
            filtersExpanded && when (view) {
                selectsBar -> selectsBarRequested
                tagsBar -> tagsBarRequested
                else -> false
            }
        } else {
            when (view) {
                selectsBar -> selectsBarRequested
                tagsBar -> tagsBarRequested
                else -> false
            }
        }
    }

    private val Int.dp: Int get() = (this * resources.displayMetrics.density).toInt()

    private fun createTopBarLayoutTransition(): LayoutTransition {
        return LayoutTransition().apply {
            setAnimateParentHierarchy(false)
            enableTransitionType(LayoutTransition.CHANGING)
            setDuration(LayoutTransition.APPEARING, 220L)
            setDuration(LayoutTransition.DISAPPEARING, 160L)
            setDuration(LayoutTransition.CHANGE_APPEARING, 300L)
            setDuration(LayoutTransition.CHANGE_DISAPPEARING, 240L)
            setDuration(LayoutTransition.CHANGING, 300L)
            setStartDelay(LayoutTransition.APPEARING, 0L)
            setStartDelay(LayoutTransition.DISAPPEARING, 0L)
            setStartDelay(LayoutTransition.CHANGE_APPEARING, 0L)
            setStartDelay(LayoutTransition.CHANGE_DISAPPEARING, 0L)
            setStartDelay(LayoutTransition.CHANGING, 0L)
            setInterpolator(LayoutTransition.APPEARING, topBarEaseOut)
            setInterpolator(LayoutTransition.DISAPPEARING, topBarEaseInOut)
            setInterpolator(LayoutTransition.CHANGE_APPEARING, topBarEaseOut)
            setInterpolator(LayoutTransition.CHANGE_DISAPPEARING, topBarEaseInOut)
            setInterpolator(LayoutTransition.CHANGING, topBarEaseOut)
        }
    }

    private class ContentMeasuredFrameLayout(context: Context) : FrameLayout(context) {

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            // 最后一个子 view 为内容层(决定高度),其余(背景层/毛玻璃层)按内容测得尺寸铺满。
            val content = getChildAt(childCount - 1)
            if (content == null) {
                super.onMeasure(widthMeasureSpec, heightMeasureSpec)
                return
            }
            measureChildWithMargins(content, widthMeasureSpec, 0, heightMeasureSpec, 0)
            val contentLp = content.layoutParams as MarginLayoutParams
            val measuredWidth = resolveSize(
                paddingLeft + paddingRight + content.measuredWidth +
                    contentLp.leftMargin + contentLp.rightMargin,
                widthMeasureSpec
            )
            val contentHeight = paddingTop + paddingBottom + content.measuredHeight +
                contentLp.topMargin + contentLp.bottomMargin
            val measuredHeight = resolveSize(contentHeight, heightMeasureSpec)
            setMeasuredDimension(measuredWidth, measuredHeight)
            for (i in 0 until childCount - 1) {
                getChildAt(i).measure(
                    View.MeasureSpec.makeMeasureSpec(measuredWidth, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(measuredHeight, View.MeasureSpec.EXACTLY)
                )
            }
        }
    }
}
