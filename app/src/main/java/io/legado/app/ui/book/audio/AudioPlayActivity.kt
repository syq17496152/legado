package io.legado.app.ui.book.audio

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.Gravity
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
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
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
import io.legado.app.lib.dialogs.alert
import io.legado.app.model.AudioPlay
import io.legado.app.model.BookCover
import io.legado.app.service.AudioPlayService
import io.legado.app.ui.about.AppLogDialog
import io.legado.app.ui.theme.LegadoTheme
import io.legado.app.ui.widget.components.AppDropdownMenu
import io.legado.app.ui.widget.components.GlassTopAppBar
import io.legado.app.ui.widget.components.MenuAction
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
import io.legado.app.ui.book.audio.SliderPopup.Companion.SPEED
import io.legado.app.ui.book.audio.SliderPopup.Companion.TIMER
import io.legado.app.model.SourceCallBack
import io.legado.app.utils.gone

/**
 * 音频播放
 */
class AudioPlayActivity :
    VMBaseActivity<ActivityAudioPlayBinding, AudioPlayViewModel>(toolBarTheme = Theme.Dark),
    ChangeBookSourceDialog.CallBack,
    AudioPlay.CallBack {

    override val binding by viewBinding(ActivityAudioPlayBinding::inflate)
    override val viewModel by viewModels<AudioPlayViewModel>()
    private val timerSliderPopup by lazy { SliderPopup(this, TIMER) }
    private val speedControlPopup by lazy { SliderPopup(this, SPEED) }
    private var adjustProgress = false
    private var playMode = AudioPlay.PlayMode.LIST_END_STOP
    private val lyricViewX by lazy { binding.lyricViewX }
    private var lyricOn = false
    private var oldLyric: String? = null

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
        binding.ivSpeedControl.setOnClickListener {
            speedControlPopup.showAsDropDown(it, 0, (-100).dpToPx(), Gravity.TOP)
        }

        binding.ivTimer.setOnClickListener {
            timerSliderPopup.showAsDropDown(it, 0, (-100).dpToPx(), Gravity.TOP)
        }
        binding.llPlayMenu.applyNavigationBarPadding()
    }

    private fun initListener() {
        binding.ivPlayMode.setOnClickListener {
            AudioPlay.changePlayMode()
        }
        binding.fabPlayStop.setOnClickListener {
            playButton()
        }
        binding.fabPlayStop.onLongClick {
            AudioPlay.stop()
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
            return
        }
        lyricViewX.loadLyric(lyric)
        binding.lyricViewX.visible()
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
            alert(title = getString(R.string.add_to_bookshelf)) {
                setMessage(getString(R.string.check_add_bookshelf, book.name))
                okButton {
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
                }
                noButton {
                    callBackBookEnd()
                    viewModel.removeFromBookshelf { super.finish() }
                }
            }
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
