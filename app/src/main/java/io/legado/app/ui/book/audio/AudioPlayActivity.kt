package io.legado.app.ui.book.audio

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.View
import android.widget.SeekBar
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Login
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.constraintlayout.widget.ConstraintSet
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.base.VMBaseActivity
import io.legado.app.constant.BookType
import io.legado.app.constant.EventBus
import io.legado.app.constant.Status
import io.legado.app.constant.Theme
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookSource
import io.legado.app.databinding.ActivityAudioPlayBinding
import io.legado.app.help.book.isAudio
import io.legado.app.help.book.removeType
import io.legado.app.help.config.AppConfig
import io.legado.app.model.AudioPlay
import io.legado.app.model.BookCover
import io.legado.app.service.AudioPlayService
import io.legado.app.ui.about.AppLogDialog
import io.legado.app.ui.theme.LegadoTheme
import io.legado.app.ui.widget.ModernActionPopup
import io.legado.app.ui.widget.components.AppDropdownMenu
import io.legado.app.ui.widget.components.GlassTopAppBar
import io.legado.app.ui.widget.components.MenuAction
import io.legado.app.ui.widget.compose.AppUiTokens
import io.legado.app.ui.widget.compose.showComposeConfirmDialog
import io.legado.app.ui.book.changesource.ChangeBookSourceDialog
import io.legado.app.ui.book.source.edit.BookSourceEditActivity
import io.legado.app.ui.book.toc.TocActivityResult
import io.legado.app.ui.login.SourceLoginActivity
import io.legado.app.ui.widget.seekbar.SeekBarChangeListener
import io.legado.app.utils.StartActivityContract
import io.legado.app.utils.applyNavigationBarPadding
import io.legado.app.utils.dpToPx
import io.legado.app.utils.invisible
import io.legado.app.utils.observeEvent
import io.legado.app.utils.observeEventSticky
import io.legado.app.utils.sendToClip
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.startActivity
import io.legado.app.utils.startActivityForBook
import io.legado.app.utils.toDurationTime
import io.legado.app.utils.viewbindingdelegate.viewBinding
import io.legado.app.utils.visible
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.Dispatchers.Main
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import splitties.views.onLongClick
import java.util.Locale
import io.legado.app.ui.book.audio.config.AudioSkipCredits
import com.dirror.lyricviewx.OnPlayClickListener
import io.legado.app.lib.theme.ThemeStore.Companion.accentColor
import io.legado.app.model.SourceCallBack
import io.legado.app.utils.gone
import kotlin.math.roundToInt

/**
 * 音频播放
 */
class AudioPlayActivity :
    VMBaseActivity<ActivityAudioPlayBinding, AudioPlayViewModel>(toolBarTheme = Theme.Dark),
    ChangeBookSourceDialog.CallBack,
    AudioPlay.CallBack {

    override val binding by viewBinding(ActivityAudioPlayBinding::inflate)
    override val viewModel by viewModels<AudioPlayViewModel>()

    // 主题架构 v2：沉浸播放页不随主题事件重建（避免打断播放），Compose 侧经 ThemeSync 刷新
    // B7 修正（2026-09-24）：原注「本页无 View 侧主题色消费」**失实** —— 歌词时间轴字色取自
    // `accentColor`（View 侧 `LyricViewX`，见 [loadLyric]）⇒ 补 `EventBus.RECREATE` 原位刷新
    // [refreshLyricThemeInPlace]，否则主题变更后歌词时间轴字色停留在旧主题。
    override val recreateOnThemeChange: Boolean
        get() = false
    private var adjustProgress = false
    private var playMode = AudioPlay.PlayMode.LIST_END_STOP
    private val lyricViewX by lazy { binding.lyricViewX }
    private var lyricOn = false
    private var oldLyric: String? = null
    // F35：定时/倍速滑杆已收口共享弹层族，各自持有句柄以便「再点即切换/关闭」
    private var timerPopup: ModernActionPopup.Handle? = null
    private var speedPopup: ModernActionPopup.Handle? = null
    // 优化 2：歌词展开态封面缩图，避免重复改约束
    private var lyricCoverMode = false

    // L-B13 S5 改造：Compose 顶栏状态
    private var composeTitle by mutableStateOf("")
    private var menuExpanded by mutableStateOf(false)
    private var showCustomBtn by mutableStateOf(false)

    private val tocActivityResult = registerForActivityResult(TocActivityResult()) {
        it?.let {
            if (it[0] != AudioPlay.book?.durChapterIndex
                || it[1] == 0
            ) {
                AudioPlay.skipTo(it[0] as Int)
            }
        }
    }
    private val sourceEditResult =
        registerForActivityResult(StartActivityContract(BookSourceEditActivity::class.java)) {
            if (it.resultCode == RESULT_OK) {
                viewModel.upSource()
            }
        }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        AudioPlay.register(this)
        viewModel.titleData.observe(this) { name ->
            composeTitle = name
            val lyric = AudioPlay.durChapter?.getVariable("lyric")?.takeIf { it.isNotBlank() }
            upLyric(lyric ?: AudioPlay.durLyric)
        }
        viewModel.coverData.observe(this) {
            upCover(it)
        }
        viewModel.customBtnListData.observe(this) { showCustomBtn = it }
        viewModel.initData(intent) {
            initListener()
        }
        initView()
        initComposeTopBar()
    }

    // ==================== L-B13 S5 改造：Compose 顶栏迁移 ====================

    /**
     * Compose 顶栏（L-B13 S5 改造）：GlassTopAppBar + 自定义/换源图标按钮 + MoreVert 下拉菜单
     */
    private fun initComposeTopBar() {
        binding.composeTopBar.setContent {
            LegadoTheme {
                GlassTopAppBar(
                    title = composeTitle,
                    navIcon = Icons.AutoMirrored.Filled.ArrowBack,
                    onNavClick = { onBackPressedDispatcher.onBackPressed() },
                    actions = {
                        if (showCustomBtn) {
                            IconButton(onClick = { clickCustomButton() }) {
                                Icon(
                                    imageVector = Icons.Filled.Tune,
                                    contentDescription = getString(R.string.custom_button)
                                )
                            }
                        }
                        IconButton(onClick = {
                            AudioPlay.book?.let {
                                showDialogFragment(ChangeBookSourceDialog(it.name, it.author))
                            }
                        }) {
                            Icon(
                                imageVector = Icons.Filled.SwapHoriz,
                                contentDescription = getString(R.string.change_origin)
                            )
                        }
                        Box {
                            IconButton(onClick = { menuExpanded = true }) {
                                Icon(
                                    imageVector = Icons.Filled.MoreVert,
                                    contentDescription = getString(R.string.more)
                                )
                            }
                            AppDropdownMenu(
                                expanded = menuExpanded,
                                onDismiss = { menuExpanded = false },
                                actions = buildAudioPlayMenuActions()
                            )
                        }
                    }
                )
            }
        }
    }

    /**
     * 下拉菜单数据驱动（迁移自 audio_play.xml + onCompatOptionsItemSelected）
     */
    private fun buildAudioPlayMenuActions(): List<MenuAction> {
        val actions = mutableListOf<MenuAction>()
        // 登录（源配置了登录地址才显示）
        if (!AudioPlay.bookSource?.loginUrl.isNullOrBlank()) {
            actions += MenuAction(
                icon = Icons.Filled.Login,
                title = getString(R.string.login),
                onClick = {
                    AudioPlay.bookSource?.let {
                        startActivity<SourceLoginActivity> {
                            putExtra("bookType", BookType.audio)
                        }
                    }
                }
            )
        }
        // 复制播放地址
        actions += MenuAction(
            icon = Icons.Filled.ContentCopy,
            title = getString(R.string.copy_play_url),
            onClick = { copyAudioUrl() }
        )
        // 编辑书源
        actions += MenuAction(
            icon = Icons.Filled.Edit,
            title = getString(R.string.edit_book_source),
            onClick = {
                AudioPlay.bookSource?.let {
                    sourceEditResult.launch {
                        putExtra("sourceUrl", it.bookSourceUrl)
                    }
                }
            }
        )
        // 唤醒锁
        actions += MenuAction(
            icon = Icons.Filled.Visibility,
            title = getString(R.string.audio_play_wake_lock),
            checked = AppConfig.audioPlayUseWakeLock,
            onClick = {
                AppConfig.audioPlayUseWakeLock = !AppConfig.audioPlayUseWakeLock
            }
        )
        // 跳过片头片尾
        actions += MenuAction(
            icon = Icons.Filled.SkipNext,
            title = getString(R.string.skip_book_credits),
            onClick = {
                AudioPlay.book?.let {
                    showDialogFragment(AudioSkipCredits.newInstance(it))
                }
            }
        )
        // 日志
        actions += MenuAction(
            icon = Icons.Filled.Info,
            title = getString(R.string.log),
            onClick = { showDialogFragment<AppLogDialog>() }
        )
        // F34（2026-09-21）：停止播放由「零发现的 FAB 长按」补一个可发现入口；
        // 与长按同走确认弹框（停止会关闭后台播报服务且不可撤销，danger 语义）。
        if (AudioPlay.book != null) {
            actions += MenuAction(
                icon = Icons.Filled.Stop,
                title = getString(R.string.audio_stop_play),
                tint = AppUiTokens.danger,
                onClick = { confirmStopPlay() }
            )
        }
        return actions
    }

    private fun clickCustomButton() {
        AudioPlay.bookSource?.let { source ->
            AudioPlay.book?.let { book ->
                SourceCallBack.callBackBtn(
                    this,
                    SourceCallBack.CLICK_CUSTOM_BUTTON,
                    source,
                    book,
                    AudioPlay.durChapter,
                    BookType.audio
                )
            }
        }
    }

    private fun copyAudioUrl() {
        AudioPlay.book?.let {
            val url = AudioPlayService.url
            SourceCallBack.callBackBtn(
                this,
                SourceCallBack.CLICK_COPY_PLAY_URL,
                AudioPlay.bookSource,
                it,
                AudioPlay.durChapter,
                BookType.audio,
                url
            ) {
                sendToClip(url)
            }
        }
    }

    // ==================== 原有业务逻辑（未改动） ====================

    private fun initView() {
        observeEventSticky<AudioPlay.PlayMode>(EventBus.PLAY_MODE_CHANGED) {
            playMode = it
            updatePlayModeIcon()
        }
        binding.playerProgress.setOnSeekBarChangeListener(object : SeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                binding.tvDurTime.text = progress.toDurationTime()
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) {
                adjustProgress = true
            }

            override fun onStopTrackingTouch(seekBar: SeekBar) {
                adjustProgress = false
                AudioPlay.adjustProgress(seekBar.progress)
            }
        })
        // F35：滑杆迁共享弹层族（原 PopupWindow + (-100dp) 负偏移定位弃用）
        binding.ivTimer.setOnClickListener { showTimerPanel(it) }
        binding.ivSpeedControl.setOnClickListener { showSpeedPanel(it) }
        binding.llPlayMenu.applyNavigationBarPadding()
    }

    /**
     * F35（2026-09-21）：定时滑杆收口共享弹层族 —— 卡片 token + 圆角描边 + 快捷档位。
     * 档位取最常用的 15/30/60/90 分钟；滑杆保持连续（范围 0~180 分钟，按整分钟量化）。
     */
    private fun showTimerPanel(anchor: View) {
        val spec = ModernActionPopup.SliderSpec(
            value = AudioPlayService.timeMinute.toFloat(),
            valueRange = 0f..180f,
            valueText = { getString(R.string.timer_m, it.roundToInt()) },
            presets = TIMER_PRESETS_MINUTES.map {
                ModernActionPopup.SliderSpec.Preset(getString(R.string.timer_m, it), it.toFloat())
            },
            onValueChange = { AudioPlay.setTimer(it.roundToInt()) }
        )
        timerPopup = ModernActionPopup.show(
            anchor = anchor,
            actions = listOf(ModernActionPopup.Action(title = getString(R.string.set_timer), slider = spec)),
            previousPopup = timerPopup,
            maxHeightRatio = 0.4f
        )
    }

    /**
     * F35（2026-09-21）：倍速滑杆与定时同构（范围 0.5~3.0，按 0.1 量化）。
     */
    private fun showSpeedPanel(anchor: View) {
        val spec = ModernActionPopup.SliderSpec(
            value = AudioPlayService.playSpeed,
            valueRange = 0.5f..3.0f,
            valueText = { formatSpeed(it) },
            presets = SPEED_PRESETS.map {
                ModernActionPopup.SliderSpec.Preset(formatSpeed(it), it)
            },
            onValueChange = { AudioPlay.setSpeed((it * 10f).roundToInt() / 10f) }
        )
        speedPopup = ModernActionPopup.show(
            anchor = anchor,
            actions = listOf(ModernActionPopup.Action(title = getString(R.string.speed_control), slider = spec)),
            previousPopup = speedPopup,
            maxHeightRatio = 0.4f
        )
    }

    /**
     * 倍速文案：固定 2 位小数后去掉末尾 0（0.75X / 1.5X / 2.0X），
     * 与滑杆值文案、旧 SeekBar 的 `%.1fX` 口径一致，避免档位与当前值两种写法。
     */
    private fun formatSpeed(speed: Float): String {
        val text = String.format(Locale.ROOT, "%.2f", speed)
        return (if (text.endsWith("0")) text.dropLast(1) else text) + "X"
    }

    /**
     * F34（2026-09-21）：停止播放是破坏性且不可撤销的动作（关闭后台播报服务），
     * 长按 FAB 与更多菜单入口共用本确认；动词式按钮「继续播放 / 停止」+ 影响范围说明。
     */
    private fun confirmStopPlay() {
        showComposeConfirmDialog(
            title = getString(R.string.audio_stop_play),
            message = getString(R.string.audio_stop_play_confirm),
            positiveText = getString(R.string.audio_stop_play),
            negativeText = getString(R.string.audio_continue_play),
            dangerPositive = true,
            onPositive = { AudioPlay.stop() }
        )
    }

    private fun initListener() {
        binding.ivPlayMode.setOnClickListener {
            AudioPlay.changePlayMode()
        }
        binding.fabPlayStop.setOnClickListener {
            playButton()
        }
        binding.fabPlayStop.onLongClick {
            // F34：长按停止改为先确认（原为直接 AudioPlay.stop()）
            confirmStopPlay()
        }
        binding.ivSkipNext.setOnClickListener {
            AudioPlay.next()
        }
        binding.ivSkipPrevious.setOnClickListener {
            AudioPlay.prev()
        }
        binding.ivChapter.setOnClickListener {
            AudioPlay.book?.let {
                tocActivityResult.launch(it.bookUrl)
            }
        }
    }

    private fun updatePlayModeIcon() {
        binding.ivPlayMode.setImageResource(playMode.iconRes)
    }

    private fun upCover(path: String?) {
        BookCover.load(this, path, sourceOrigin = AudioPlay.bookSource?.bookSourceUrl) {
            BookCover.loadBlur(this, path, sourceOrigin = AudioPlay.bookSource?.bookSourceUrl)
                .into(binding.ivBg)
        }.into(binding.ivCover)
    }

    override fun upLyric(lyric: String?) {
        if (oldLyric == lyric) return
        oldLyric = lyric
        if(lyric.isNullOrBlank()) {
            binding.lyricViewX.gone()
            applyLyricCoverMode(false)
            return
        }
        lyricViewX.loadLyric(lyric)
        binding.lyricViewX.visible()
        // 优化 2：歌词展开 ⇒ 封面缩为小图，把纵向空间让给歌词区（歌词非空才切换）
        applyLyricCoverMode(true)
        if (lyricOn) {
            upLyricP(AudioPlay.durChapterPos)
        } else {
            lyricOn = true
            lyricViewX.apply {
                setNormalTextSize(50F)
                setCurrentTextSize(60F)
                setTimelineTextColor(accentColor)
                setDraggable(true, object : OnPlayClickListener {
                    override fun onPlayClick(time: Long): Boolean {
                        AudioPlay.adjustProgress(time.toInt())
                        playButton(false)
                        return true
                    }
                })
            }
            lyricViewX.postDelayed({
                upLyricP(AudioPlay.durChapterPos)
            }, 100)
        }
    }
    override fun upLyricP(position: Int) {
        lyricViewX.updateTime(position.toLong(),false)
    }

    /**
     * B7（2026-09-24）：主题变更时原位刷新 View 侧主题色。
     *
     * 本页 `recreateOnThemeChange=false`（沉浸页不重建）⇒ 主题切换后 `LyricViewX` 的
     * 时间轴字色（[loadLyric] 内 `setTimelineTextColor(accentColor)`）不会自动跟随；
     * 歌词未加载时无需处理（下次 [loadLyric] 会取新值）。
     */
    private fun refreshLyricThemeInPlace() {
        if (lyricOn) {
            lyricViewX.setTimelineTextColor(accentColor)
        }
    }

    /**
     * 优化 2（P1，2026-09-21）：歌词展开态封面缩为小图。
     *
     * 本来：`iv_cover` 固定 260dp 且上下双向约束（垂直居中），歌词区只能拿到剩余高度，小屏可读行数少。
     * 优化后：歌词非空 ⇒ 封面 120dp 置顶靠左，歌词区上边界随之抬升约 140dp（`lyricViewX` 仍
     * `top_toBottomOf=iv_cover`，无需改自身约束）；歌词为空 ⇒ 还原原 260dp 居中。
     * 顺带把睡眠定时标签让位到小图右侧，避免与「置顶靠左」的封面重叠。
     */
    private fun applyLyricCoverMode(lyricMode: Boolean) {
        if (lyricCoverMode == lyricMode) return
        lyricCoverMode = lyricMode
        val coverSize = if (lyricMode) LYRIC_COVER_SIZE_DP.dpToPx() else COVER_SIZE_DP.dpToPx()
        val set = ConstraintSet().apply { clone(binding.root) }
        set.constrainWidth(R.id.iv_cover, coverSize)
        set.constrainHeight(R.id.iv_cover, coverSize)
        if (lyricMode) {
            set.clear(R.id.iv_cover, ConstraintSet.BOTTOM)
            set.clear(R.id.iv_cover, ConstraintSet.RIGHT)
            set.connect(
                R.id.iv_cover, ConstraintSet.LEFT,
                ConstraintSet.PARENT_ID, ConstraintSet.LEFT, 16.dpToPx()
            )
            set.connect(
                R.id.iv_cover, ConstraintSet.TOP,
                R.id.compose_top_bar, ConstraintSet.BOTTOM, 16.dpToPx()
            )
            set.clear(R.id.tv_timer, ConstraintSet.LEFT)
            set.connect(R.id.tv_timer, ConstraintSet.LEFT, R.id.iv_cover, ConstraintSet.RIGHT, 0)
        } else {
            set.clear(R.id.iv_cover, ConstraintSet.LEFT)
            set.connect(
                R.id.iv_cover, ConstraintSet.LEFT,
                ConstraintSet.PARENT_ID, ConstraintSet.LEFT, 0
            )
            set.connect(
                R.id.iv_cover, ConstraintSet.RIGHT,
                ConstraintSet.PARENT_ID, ConstraintSet.RIGHT, 0
            )
            set.connect(R.id.iv_cover, ConstraintSet.BOTTOM, R.id.lyricViewX, ConstraintSet.TOP, 0)
            set.clear(R.id.tv_timer, ConstraintSet.LEFT)
            set.connect(R.id.tv_timer, ConstraintSet.LEFT, ConstraintSet.PARENT_ID, ConstraintSet.LEFT, 0)
        }
        set.applyTo(binding.root)
    }

    companion object {
        /** `iv_cover` 常规尺寸（与 activity_audio_play.xml 初值一致） */
        private const val COVER_SIZE_DP = 260

        /** 歌词展开态封面尺寸 */
        private const val LYRIC_COVER_SIZE_DP = 120

        /** 定时快捷档位（分钟） */
        private val TIMER_PRESETS_MINUTES = listOf(15, 30, 60, 90)

        /** 倍速快捷档位 */
        private val SPEED_PRESETS = listOf(0.75f, 1.0f, 1.5f, 2.0f)
    }

    private fun playButton(noLyr: Boolean = true) {
        val status = AudioPlay.status
        when (status) {
            Status.PLAY if noLyr -> {
                AudioPlay.pause(this)
            }
            Status.PAUSE -> {
                AudioPlay.resume(this)
            }
            else -> {
                AudioPlay.loadOrUpPlayUrl()
            }
        }
    }

    override val oldBook: Book?
        get() = AudioPlay.book

    override fun changeTo(source: BookSource, book: Book, toc: List<BookChapter>) {
        if (book.isAudio) {
            viewModel.changeTo(source, book, toc)
        } else {
            AudioPlay.stop()
            lifecycleScope.launch {
                withContext(IO) {
                    AudioPlay.book?.migrateTo(book, toc)
                    book.removeType(BookType.updateError)
                    AudioPlay.book?.delete()
                    appDb.bookDao.insert(book)
                }
                startActivityForBook(book)
                finish()
            }
        }
    }

    override fun finish() {
        val book = AudioPlay.book ?: return super.finish()
        if (AudioPlay.inBookshelf) {
            callBackBookEnd()
            return super.finish()
        }
        if (!AppConfig.showAddToShelfAlert) {
            callBackBookEnd()
            viewModel.removeFromBookshelf { super.finish() }
        } else {
            showComposeConfirmDialog(
                title = getString(R.string.add_to_bookshelf),
                message = getString(R.string.check_add_bookshelf, book.name),
                positiveText = getString(R.string.ok),
                negativeText = getString(R.string.no),
                onPositive = {
                    val book = AudioPlay.book
                    book?.removeType(BookType.notShelf)
                    lifecycleScope.launch(IO) {
                        book?.save()
                        withContext(Main) {
                            SourceCallBack.callBackBook(SourceCallBack.ADD_BOOK_SHELF, AudioPlay.bookSource, AudioPlay.book)
                            AudioPlay.inBookshelf = true
                            setResult(RESULT_OK)
                        }
                    }
                },
                onNegative = {
                    callBackBookEnd()
                    viewModel.removeFromBookshelf { super.finish() }
                }
            )
        }
    }

    private fun callBackBookEnd() {
        SourceCallBack.callBackBook(SourceCallBack.END_READ, AudioPlay.bookSource, AudioPlay.book, AudioPlay.durChapter)
    }

    override fun onDestroy() {
        super.onDestroy()
        if (AudioPlay.status != Status.PLAY) {
            AudioPlay.stop()
        }
        AudioPlay.unregister(this)
    }

    @SuppressLint("SetTextI18n")
    override fun observeLiveBus() {
        // B7（2026-09-24）：本页 recreateOnThemeChange=false（沉浸页不重建）⇒
        // 主题变更必须**原位**刷新 View 侧主题色，否则歌词时间轴字色停留旧主题。
        observeEvent<String>(EventBus.RECREATE) {
            refreshLyricThemeInPlace()
        }
        observeEvent<Boolean>(EventBus.MEDIA_BUTTON) {
            if (it) {
                playButton()
            }
        }
        observeEventSticky<Int>(EventBus.AUDIO_STATE) {
            AudioPlay.status = it
            if (it == Status.PLAY) {
                binding.fabPlayStop.setImageResource(R.drawable.ic_pause_24dp)
            } else {
                binding.fabPlayStop.setImageResource(R.drawable.ic_play_24dp)
            }
        }
        observeEventSticky<String>(EventBus.AUDIO_SUB_TITLE) {
            binding.tvSubTitle.text = it
            binding.ivSkipPrevious.isEnabled = AudioPlay.durChapterIndex > 0
            binding.ivSkipNext.isEnabled =
                AudioPlay.durChapterIndex < AudioPlay.simulatedChapterSize - 1
        }
        observeEventSticky<Int>(EventBus.AUDIO_SIZE) {
            binding.playerProgress.max = it
            binding.tvAllTime.text = it.toDurationTime()
        }
        observeEventSticky<Int>(EventBus.AUDIO_PROGRESS) {
            if (!adjustProgress) binding.playerProgress.progress = it
            binding.tvDurTime.text = it.toDurationTime()
        }
        observeEventSticky<Int>(EventBus.AUDIO_BUFFER_PROGRESS) {
            binding.playerProgress.secondaryProgress = it
        }
        observeEventSticky<Float>(EventBus.AUDIO_SPEED) {
            if (it == 1f) {
                binding.tvSpeed.invisible()
            } else {
                binding.tvSpeed.text = String.format(Locale.ROOT, "%.1fX", it)
                binding.tvSpeed.visible()
            }
        }
        observeEventSticky<Int>(EventBus.AUDIO_DS) {
            binding.tvTimer.text = "${it}m"
            binding.tvTimer.visible(it > 0)
        }
    }

    override fun upLoading(loading: Boolean) {
        runOnUiThread {
            binding.progressLoading.visible(loading)
        }
    }
}
