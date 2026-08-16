package io.legado.app.ui.book.read

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.animation.Animation
import android.widget.FrameLayout
import android.widget.SeekBar
import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.view.isGone
import androidx.core.view.isVisible
import io.legado.app.R
import io.legado.app.databinding.ViewMangaMenuBinding
import io.legado.app.help.config.AppConfig
import io.legado.app.help.source.getSourceType
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.theme.bottomBackground
import io.legado.app.model.ReadBook
import io.legado.app.model.ReadManga
import io.legado.app.ui.browser.WebViewActivity
import io.legado.app.ui.theme.LegadoTheme
import io.legado.app.ui.widget.components.AppDropdownMenu
import io.legado.app.ui.widget.components.GlassTopAppBar
import io.legado.app.ui.widget.components.MenuAction
import io.legado.app.ui.widget.seekbar.SeekBarChangeListener
import io.legado.app.utils.ColorUtils
import io.legado.app.utils.ConstraintModify
import io.legado.app.utils.activity
import io.legado.app.utils.applyNavigationBarPadding
import io.legado.app.utils.dpToPx
import io.legado.app.utils.gone
import io.legado.app.utils.invisible
import io.legado.app.utils.loadAnimation
import io.legado.app.utils.modifyBegin
import io.legado.app.utils.openUrl
import io.legado.app.utils.startActivity
import io.legado.app.utils.visible

class MangaMenu @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {
    private val binding = ViewMangaMenuBinding.inflate(LayoutInflater.from(context), this, true)
    private val callBack: CallBack get() = activity as CallBack

    /**
     * 顶栏标题（Compose 状态驱动，Activity 通过 [setTitle] 更新，触发重组）
     */
    var topBarTitle by mutableStateOf("")
        private set
    var canShowMenu: Boolean = false
    private val menuTopIn: Animation by lazy {
        loadAnimation(context, R.anim.anim_readbook_top_in)
    }
    private val menuTopOut: Animation by lazy {
        loadAnimation(context, R.anim.anim_readbook_top_out)
    }
    private val menuBottomIn: Animation by lazy {
        loadAnimation(context, R.anim.anim_readbook_bottom_in)
    }
    private val menuBottomOut: Animation by lazy {
        loadAnimation(context, R.anim.anim_readbook_bottom_out)
    }
    private var isMenuOutAnimating = false
    private var bgColor = context.bottomBackground

    private val menuOutListener = object : Animation.AnimationListener {
        override fun onAnimationStart(animation: Animation) {
            isMenuOutAnimating = true
            binding.vwMenuBg.setOnClickListener(null)
        }

        override fun onAnimationEnd(animation: Animation) {
            this@MangaMenu.invisible()
            binding.topBarContainer.invisible()
            binding.bottomMenu.invisible()
            isMenuOutAnimating = false
            canShowMenu = false
            callBack.upSystemUiVisibility(false)
        }

        override fun onAnimationRepeat(animation: Animation) = Unit
    }
    private val menuInListener = object : Animation.AnimationListener {
        override fun onAnimationStart(animation: Animation) {
            binding.tvSourceAction.text =
                ReadManga.bookSource?.bookSourceName ?: context.getString(R.string.book_source)
            callBack.upSystemUiVisibility(true)
            binding.tvSourceAction.isGone = false
        }

        @SuppressLint("RtlHardcoded")
        override fun onAnimationEnd(animation: Animation) {
            binding.run {
                vwMenuBg.setOnClickListener { runMenuOut() }
            }
        }

        override fun onAnimationRepeat(animation: Animation) = Unit
    }

    init {
        initView()
        bindEvent()
    }

    private fun initView() = binding.run {
        initAnimation()
        val brightnessBackground = GradientDrawable()
        brightnessBackground.cornerRadius = 5F.dpToPx()
        brightnessBackground.setColor(ColorUtils.adjustAlpha(bgColor, 0.5f))
        if (AppConfig.isEInkMode) {
            topBarContainer.setBackgroundResource(R.drawable.bg_eink_border_bottom)
            bottomMenu.setBackgroundResource(R.drawable.bg_eink_border_top)
        } else {
            bottomMenu.setBackgroundColor(bgColor)
        }
        if (AppConfig.showReadTitleBarAddition) {
            titleBarAddition.visible()
        } else {
            titleBarAddition.gone()
        }
        upBrightnessVwPos()
        /**
         * 确保视图不被导航栏遮挡
         */
        bottomMenu.applyNavigationBarPadding()
        initComposeTopBar()
    }

    private fun upBrightnessVwPos() {
        if (AppConfig.brightnessVwPos) {
            binding.root.modifyBegin()
                .clear(R.id.ll_brightness, ConstraintModify.Anchor.LEFT)
                .rightToRightOf(R.id.ll_brightness, R.id.vw_menu_root)
                .commit()
        } else {
            binding.root.modifyBegin()
                .clear(R.id.ll_brightness, ConstraintModify.Anchor.RIGHT)
                .leftToLeftOf(R.id.ll_brightness, R.id.vw_menu_root)
                .commit()
        }
    }

    private fun initAnimation() {
        menuTopIn.setAnimationListener(menuInListener)
        menuTopOut.setAnimationListener(menuOutListener)
    }

    fun runMenuOut(anim: Boolean = !AppConfig.isEInkMode) {
        if (isMenuOutAnimating) {
            return
        }
        if (this.isVisible) {
            if (anim) {
                binding.topBarContainer.startAnimation(menuTopOut)
                binding.bottomMenu.startAnimation(menuBottomOut)
            } else {
                menuOutListener.onAnimationStart(menuBottomOut)
                menuOutListener.onAnimationEnd(menuBottomOut)
            }
        }
    }

    fun runMenuIn(anim: Boolean = !AppConfig.isEInkMode) {
        this.visible()
        binding.topBarContainer.visible()
        binding.bottomMenu.visible()
        if (anim) {
            binding.topBarContainer.startAnimation(menuTopIn)
            binding.bottomMenu.startAnimation(menuBottomIn)
        } else {
            menuInListener.onAnimationStart(menuBottomIn)
            menuInListener.onAnimationEnd(menuBottomIn)
        }
    }


    private fun bindEvent() = binding.run {
        vwMenuBg.setOnClickListener { runMenuOut() }
        val chapterViewClickListener = OnClickListener {
            if (AppConfig.readUrlInBrowser) {
                context.openUrl(tvChapterUrl.text.toString().substringBefore(",{"))
            } else {
                context.startActivity<WebViewActivity> {
                    val url = tvChapterUrl.text.toString()
                    val bookSource = ReadBook.bookSource
                    putExtra("title", tvChapterName.text)
                    putExtra("url", url)
                    putExtra("sourceOrigin", bookSource?.bookSourceUrl)
                    putExtra("sourceName", bookSource?.bookSourceName)
                    putExtra("sourceType", bookSource?.getSourceType())
                }
            }
        }
        val chapterViewLongClickListener = OnLongClickListener {
            context.alert(R.string.open_fun) {
                setMessage(R.string.use_browser_open)
                okButton {
                    AppConfig.readUrlInBrowser = true
                }
                noButton {
                    AppConfig.readUrlInBrowser = false
                }
            }
            true
        }
        tvChapterName.setOnClickListener(chapterViewClickListener)
        tvChapterName.setOnLongClickListener(chapterViewLongClickListener)
        tvChapterUrl.setOnClickListener(chapterViewClickListener)
        tvChapterUrl.setOnLongClickListener(chapterViewLongClickListener)

        tvNext.setOnClickListener {
            ReadManga.moveToNextChapter(true)
        }
        tvPre.setOnClickListener {
            ReadManga.moveToPrevChapter(true)
        }

        seekReadPage.setOnSeekBarChangeListener(object : SeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    callBack.skipToPage(seekBar.progress)
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) {
                binding.vwMenuBg.setOnClickListener(null)
            }

            override fun onStopTrackingTouch(seekBar: SeekBar) {
                binding.vwMenuBg.setOnClickListener { runMenuOut() }
            }
        })
    }

    fun upSeekBar(value: Int, count: Int) {
        binding.seekReadPage.apply {
            max = count.minus(1)
            progress = value
        }
    }

    /**
     * 更新顶栏标题（Compose 状态，Activity 在 upContent 时调用）
     */
    fun setTitle(title: CharSequence?) {
        topBarTitle = title?.toString() ?: ""
    }

    /**
     * 顶栏（L-B12 S5 改造）：Compose GlassTopAppBar + 换源/目录/刷新图标 + MoreVert 下拉菜单
     *
     * - 标题/图标区点击打开书籍信息（原 titleBar.toolbar 点击）
     * - 下拉菜单由 Activity 通过 [CallBack.buildMangaMenuActions] 数据驱动（AppDropdownMenu）
     * - 章节信息栏（title_bar_addition）保留为顶栏下方独立 View，随 topBarContainer 动画显隐
     */
    private fun initComposeTopBar() {
        binding.composeTopBar.setContent {
            LegadoTheme {
                var menuExpanded by remember { mutableStateOf(false) }
                GlassTopAppBar(
                    title = topBarTitle,
                    onNavClick = { callBack.openBookInfoActivity() },
                    actions = {
                        IconButton(onClick = { callBack.onMangaChangeSource() }) {
                            Icon(
                                imageVector = Icons.Filled.SwapHoriz,
                                contentDescription = context.getString(R.string.change_origin)
                            )
                        }
                        IconButton(onClick = { callBack.onMangaCatalog() }) {
                            Icon(
                                imageVector = Icons.Filled.List,
                                contentDescription = context.getString(R.string.chapter_list)
                            )
                        }
                        IconButton(onClick = { callBack.onMangaRefresh() }) {
                            Icon(
                                imageVector = Icons.Filled.Refresh,
                                contentDescription = context.getString(R.string.refresh)
                            )
                        }
                        Box {
                            IconButton(onClick = { menuExpanded = true }) {
                                Icon(
                                    imageVector = Icons.Filled.MoreVert,
                                    contentDescription = context.getString(R.string.more)
                                )
                            }
                            AppDropdownMenu(
                                expanded = menuExpanded,
                                onDismiss = { menuExpanded = false },
                                actions = callBack.buildMangaMenuActions()
                            )
                        }
                    }
                )
            }
        }
    }

    interface CallBack {
        fun openBookInfoActivity()
        fun upSystemUiVisibility(menuIsVisible: Boolean)
        fun skipToPage(index: Int)
        fun buildMangaMenuActions(): List<MenuAction>
        fun onMangaChangeSource()
        fun onMangaCatalog()
        fun onMangaRefresh()
    }

}