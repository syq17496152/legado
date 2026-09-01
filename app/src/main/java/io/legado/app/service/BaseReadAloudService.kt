@file:Suppress("DEPRECATION")

package io.legado.app.service

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.AudioManager
import android.net.wifi.WifiManager
import android.os.Bundle
import android.os.PowerManager
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.telephony.PhoneStateListener
import android.telephony.TelephonyManager
import androidx.annotation.CallSuper
import androidx.core.app.NotificationCompat
import androidx.lifecycle.lifecycleScope
import androidx.media.AudioFocusRequestCompat
import androidx.media.AudioManagerCompat
import io.legado.app.R
import io.legado.app.base.BaseService
import io.legado.app.constant.AppConst
import io.legado.app.constant.AppLog
import io.legado.app.constant.AppPattern
import io.legado.app.constant.EventBus
import io.legado.app.constant.IntentAction
import io.legado.app.constant.NotificationId
import io.legado.app.constant.PreferKey
import io.legado.app.constant.Status
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.MediaHelp
import io.legado.app.help.config.AppConfig
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.help.glide.ImageLoader
import io.legado.app.lib.permission.Permissions
import io.legado.app.lib.permission.PermissionsCompat
import io.legado.app.model.ReadAloud
import io.legado.app.model.ReadAloudPosition
import io.legado.app.model.ReadBook
import io.legado.app.receiver.MediaButtonReceiver
import io.legado.app.ui.book.read.ReadBookActivity
import io.legado.app.ui.book.read.page.entities.TextChapter
import io.legado.app.utils.LogUtils
import io.legado.app.utils.activityPendingIntent
import io.legado.app.utils.broadcastPendingIntent
import io.legado.app.utils.getPrefBoolean
import io.legado.app.utils.observeEvent
import io.legado.app.utils.observeSharedPreferences
import io.legado.app.utils.postEvent
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.Dispatchers.Main
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import splitties.init.appCtx
import splitties.systemservices.audioManager
import splitties.systemservices.notificationManager
import splitties.systemservices.powerManager
import splitties.systemservices.telephonyManager
import splitties.systemservices.wifiManager

/**
 * 朗读服务
 */
abstract class BaseReadAloudService : BaseService(),
    AudioManager.OnAudioFocusChangeListener {

    companion object {
        @JvmStatic
        var isRun = false
            private set

        @JvmStatic
        var pause = true
            private set

        @JvmStatic
        var timeMinute: Int = 0
            private set

        // 定时朗读模式 0=按分钟 1=读完本章 2=剩余章节; remainChapters 为模式2剩余章数
        @JvmStatic
        var ttsTimerMode: Int = 0
            private set

        @JvmStatic
        var remainChapters: Int = 0
            private set

        fun isPlay(): Boolean {
            return isRun && !pause
        }

        /** C1 段进度快照：引擎单元推进时写快照 + 广播（LC :126-133）。 */
        @Volatile
        var readAloudProgress: ReadAloudProgress? = null
            private set

        fun publishReadAloudProgress(progress: ReadAloudProgress) {
            readAloudProgress = progress
            postEvent(EventBus.READ_ALOUD_PARAGRAPH_PROGRESS, progress)
        }

        private const val TAG = "BaseReadAloudService"

    }

    private val useWakeLock = appCtx.getPrefBoolean(PreferKey.readAloudWakeLock, false)
    private val wakeLock by lazy {
        powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "legado:ReadAloudService")
            .apply {
                this.setReferenceCounted(false)
            }
    }
    private val wifiLock by lazy {
        @Suppress("DEPRECATION")
        wifiManager?.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "legado:AudioPlayService")
            ?.apply {
                setReferenceCounted(false)
            }
    }
    private val mFocusRequest: AudioFocusRequestCompat by lazy {
        MediaHelp.buildAudioFocusRequestCompat(this)
    }
    private val mediaSessionCompat by lazy {
        MediaSessionCompat(this, "readAloud")
    }
    private val phoneStateListener by lazy {
        ReadAloudPhoneStateListener()
    }
    /**
     * 引擎私有位置光标所有权契约（LC :166-174 同文）：
     * contentList/nowSpeak/readAloudNumber/textChapter/pageIndex/paragraphStartPos
     * 只能由引擎推进方法（prepareReadAloudChapter/prevP/nextP/seek 系列）读写，
     * 对外唯一出口是两个发布点（upTtsProgress→位置发布、publishParagraphProgress→段进度发布），
     * UI 与阅读模型不得直接读写；引擎绝不直写显示进度（durChapterPos/moveTo*）。
     */
    internal var contentList = emptyList<String>()
    internal var nowSpeak: Int = 0
    internal var readAloudNumber: Int = 0
    internal var textChapter: TextChapter? = null
    internal var pageIndex = 0
    private var needResumeOnAudioFocusGain = false
    private var needResumeOnCallStateIdle = false
    private var registeredPhoneStateListener = false
    private var dsJob: Job? = null
    private var upNotificationJob: Coroutine<*>? = null
    private var cover: Bitmap =
        BitmapFactory.decodeResource(appCtx.resources, R.drawable.icon_read_book)
    var pageChanged = false
    private var toLast = false
    var paragraphStartPos = 0
    var readAloudByPage = false
        private set

    /** C1 启动代数守卫：每次朗读请求 ++；prepare 成功时落值到 preparedReadAloudStartRequest（LC :298-301/:1276）。 */
    @Volatile
    var readAloudStartRequest = 0L
        private set
    @Volatile
    private var preparedReadAloudStartRequest = -1L

    /** 引擎私有章号（-1 表示未准备）。 */
    private val currentChapterIndex: Int
        get() = textChapter?.chapter?.index ?: -1

    private val broadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (AudioManager.ACTION_AUDIO_BECOMING_NOISY == intent.action) {
                pauseReadAloud()
            }
        }
    }

    @SuppressLint("WakelockTimeout")
    override fun onCreate() {
        super.onCreate()
        isRun = true
        pause = false
        observeLiveBus()
        initMediaSession()
        initBroadcastReceiver()
        initPhoneStateListener()
        upMediaSessionPlaybackState(PlaybackStateCompat.STATE_PLAYING)
        when (AppConfig.ttsTimerMode) {
            1 -> {
                ttsTimerMode = 1
                toastOnUi("读完本章后停止朗读")
                doDs()
            }
            2 -> {
                ttsTimerMode = 2
                remainChapters = AppConfig.ttsTimerChapters
                toastOnUi("读完 $remainChapters 章后停止朗读")
                doDs()
            }
            else -> {
                setTimer(AppConfig.ttsTimer)
                if (AppConfig.ttsTimer > 0) {
                    toastOnUi("朗读定时 ${AppConfig.ttsTimer} 分钟")
                }
            }
        }
        execute {
            ImageLoader
                .loadBitmap(this@BaseReadAloudService, ReadBook.book?.getDisplayCover())
                .submit()
                .get()
        }.onSuccess {
            if (it.width > 16 && it.height > 16) {
                cover = it
                upReadAloudNotification()
            }
        }
    }

    fun observeLiveBus() {
        observeEvent<Bundle>(EventBus.READ_ALOUD_PLAY) {
            val play = it.getBoolean("play")
            val pageIndex = it.getInt("pageIndex")
            val startPos = it.getInt("startPos")
            newReadAloud(play, pageIndex, startPos)
        }
        observeSharedPreferences { _, key ->
            when (key) {
                PreferKey.ignoreAudioFocus,
                PreferKey.pauseReadAloudWhilePhoneCalls -> {
                    initPhoneStateListener()
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // C1 清场：清空朗读位置并递增代数，使在途位置事件与绘制投影全部失效（R5/OQ-4）
        ReadAloud.clearAloudPosition()
        if (useWakeLock) {
            wakeLock.release()
            wifiLock?.release()
        }
        isRun = false
        pause = true
        abandonFocus()
        unregisterReceiver(broadcastReceiver)
        postEvent(EventBus.ALOUD_STATE, Status.STOP)
        notificationManager.cancel(NotificationId.ReadAloudService)
        upMediaSessionPlaybackState(PlaybackStateCompat.STATE_STOPPED)
        mediaSessionCompat.release()
        ReadBook.uploadProgress()
        unregisterPhoneStateListener(phoneStateListener)
        upNotificationJob?.invokeOnCompletion {
            notificationManager.cancel(NotificationId.ReadAloudService)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            IntentAction.play -> newReadAloud(
                intent.getBooleanExtra("play", true),
                intent.getIntExtra("pageIndex", ReadBook.durPageIndex),
                intent.getIntExtra("startPos", 0)
            )

            IntentAction.pause -> pauseReadAloud()
            IntentAction.resume -> resumeReadAloud()
            IntentAction.upTtsSpeechRate -> upSpeechRate(true)
            IntentAction.prevParagraph -> prevP()
            IntentAction.nextParagraph -> nextP()
            // 通知栏/线控上一章/下一章为用户显式动作：syncView=true 显式传送（§7 前端入口④）
            IntentAction.prev -> prevChapter(syncView = true)
            IntentAction.next -> nextChapter(syncView = true)
            IntentAction.seekReadAloudProgress -> seekToReadAloudProgress(
                intent.getIntExtra("chapterIndex", ReadBook.durChapterIndex),
                intent.getIntExtra("position", 0),
                intent.getBooleanExtra("syncView", false)
            )
            IntentAction.seekReadAloudTextPosition -> seekToReadAloudTextPosition(
                intent.getIntExtra("chapterIndex", ReadBook.durChapterIndex),
                intent.getIntExtra("chapterPosition", 0),
                intent.getBooleanExtra("syncView", false)
            )
            IntentAction.addTimer -> addTimer()
            IntentAction.setTimer -> setTimerExt(
                intent.getIntExtra("mode", 0),
                intent.getIntExtra("minute", 0),
                intent.getIntExtra("chapters", 0)
            )
            IntentAction.stop -> stopSelf()
        }
        return super.onStartCommand(intent, flags, startId)
    }

    private fun newReadAloud(play: Boolean, pageIndex: Int, startPos: Int) {
        readAloudStartRequest++
        execute(executeContext = IO) {
            this@BaseReadAloudService.pageIndex = pageIndex
            textChapter = ReadBook.curTextChapter
            val textChapter = textChapter ?: return@execute
            readAloudByPage = getPrefBoolean(PreferKey.readAloudByPage)
            if (!prepareReadAloudChapter(textChapter, pageIndex, startPos)) return@execute
            preparedReadAloudStartRequest = readAloudStartRequest
            launch(Main) {
                if (play) play() else pageChanged = true
            }
        }.onError {
            AppLog.put("启动朗读出错\n${it.localizedMessage}", it, true)
        }
    }

    /**
     * 章节准备：校验并初始化引擎私有光标（LC :1102-1148 对齐）。
     * 起点偏移一律经 [resolveParagraphStartPos] 统一为"朗读单元内部偏移"（M3：替代旧页内/段内混算）；
     * toLast（读上章末段）分支保留在此（LC :1135-1139）。
     * 成功后立即发布段进度与首帧位置（无前值，UI 收 prev=null 不跟随）。
     */
    internal fun prepareReadAloudChapter(
        chapter: TextChapter,
        pageIndex: Int,
        startPos: Int
    ): Boolean {
        if (!chapter.isCompleted) return false
        val page = chapter.getPage(pageIndex) ?: return false
        readAloudNumber = chapter.getReadLength(pageIndex) + startPos
        contentList = chapter.getNeedReadAloud(0, readAloudByPage, 0)
            .split("\n")
            .filter { it.isNotEmpty() }
        if (contentList.isEmpty()) return false
        nowSpeak = (chapter.getParagraphNum(readAloudNumber + 1, readAloudByPage) - 1)
            .coerceIn(0, contentList.lastIndex)
        paragraphStartPos = if (toLast) {
            toLast = false
            readAloudNumber = chapter.getLastParagraphPosition()
            nowSpeak = contentList.lastIndex
            if (page.paragraphs.size == 1) {
                (page.chapterPosition - chapter.paragraphs[nowSpeak].chapterPosition)
                    .coerceAtLeast(0)
            } else {
                0
            }
        } else {
            resolveParagraphStartPos(chapter)
        }
        publishParagraphProgress()
        publishPreparedAloudPosition()
        return true
    }

    /**
     * 起点偏移解析（LC :1157-1169 对齐）：paragraphStartPos 必须是朗读单元内部偏移，
     * 绝不能是页内偏移；页分段/段分段共用公式 readAloudNumber - paragraph.chapterPosition，
     * 跨页段落续读语义由此保持。
     */
    internal fun resolveParagraphStartPos(chapter: TextChapter): Int {
        val paragraph = chapter.getParagraphs(readAloudByPage)
            .lastOrNull { readAloudNumber >= it.chapterPosition } ?: return 0
        return (readAloudNumber - paragraph.chapterPosition).coerceAtLeast(0)
    }

    @SuppressLint("WakelockTimeout")
    open fun play() {
        if (useWakeLock) {
            wakeLock.acquire()
            wifiLock?.acquire()
        }
        isRun = true
        pause = false
        needResumeOnAudioFocusGain = false
        needResumeOnCallStateIdle = false
        upReadAloudNotification()
        upMediaSessionPlaybackState(PlaybackStateCompat.STATE_PLAYING)
        postEvent(EventBus.ALOUD_STATE, Status.PLAY)
    }

    abstract fun playStop()

    @CallSuper
    open fun pauseReadAloud(abandonFocus: Boolean = true) {
        if (useWakeLock) {
            wakeLock.release()
            wifiLock?.release()
        }
        pause = true
        if (abandonFocus) {
            abandonFocus()
        }
        upReadAloudNotification()
        upMediaSessionPlaybackState(PlaybackStateCompat.STATE_PAUSED)
        postEvent(EventBus.ALOUD_STATE, Status.PAUSE)
        ReadBook.uploadProgress()
        doDs()
    }

    @SuppressLint("WakelockTimeout")
    @CallSuper
    open fun resumeReadAloud() {
        resumeReadAloudInternal()
    }

    private fun resumeReadAloudInternal() {
        pause = false
        needResumeOnAudioFocusGain = false
        needResumeOnCallStateIdle = false
        upReadAloudNotification()
        upMediaSessionPlaybackState(PlaybackStateCompat.STATE_PLAYING)
        postEvent(EventBus.ALOUD_STATE, Status.PLAY)
    }

    abstract fun upSpeechRate(reset: Boolean = false)

    /**
     * 引擎推进统一出口：段进度发布 + 位置发布（LC :1270-1284 对齐）。
     * 引擎只发布朗读位置，绝不直写显示进度；显示是否跟随/何时翻页由 UI 跟随规则现算。
     *
     * OQ-11 进度语义（对照表见 C1 分册 §13）：position 一律为章节绝对字符位——
     * 段推进/起点发布传 readAloudNumber（单元起始位，不再 +1），
     * 句内真实信号传 readAloudNumber + offset。
     */
    fun upTtsProgress(progress: Int, syncView: Boolean = false) {
        publishParagraphProgress()
        postReadAloudTextPosition(progress, syncView)
    }

    protected fun postReadAloudTextPosition(progress: Int, syncView: Boolean = false) {
        if (preparedReadAloudStartRequest != readAloudStartRequest) return  // 启动代数守卫（LC :1276）
        val chapterIndex = currentChapterIndex.takeIf { it >= 0 } ?: ReadBook.durChapterIndex
        ReadAloud.publishAloudPosition(ReadAloudPosition(chapterIndex, progress), syncView)
    }

    /** 段进度发布（LC :1286-1299 对齐）：写 companion 快照 + 广播。 */
    protected fun publishParagraphProgress() {
        val chapter = textChapter ?: return
        if (nowSpeak !in contentList.indices) return
        runCatching {
            publishReadAloudProgress(
                ReadAloudProgress(
                    chapter.chapter.index,
                    nowSpeak,
                    contentList.size,
                    ReadAloudProgress.Kind.PARAGRAPH
                )
            )
        }.onFailure {
            AppLog.putDebugWithTag(
                AppLog.TAG_READ_ALOUD, "段进度发布失败:${it.localizedMessage}", level = AppLog.Level.WARN
            )
        }
    }

    /** prepare 完成后立即发布一次位置（无前值，UI 收 prev=null 不跟随，仅作面板/投影输入）（LC :1171-1177）。 */
    private fun publishPreparedAloudPosition() {
        val chapterIndex = currentChapterIndex.takeIf { it >= 0 } ?: ReadBook.durChapterIndex
        ReadAloud.publishAloudPosition(ReadAloudPosition(chapterIndex, readAloudNumber))
    }

    private fun prevP() {
        if (nowSpeak > 0) {
            playStop()
            do {
                nowSpeak--
                readAloudNumber -= contentList[nowSpeak].length + 1 + paragraphStartPos
                paragraphStartPos = 0
            } while (contentList[nowSpeak].matches(AppPattern.notReadAloudRegex))
            textChapter?.let {
                if (readAloudByPage) {
                    val paragraphs = it.getParagraphs(true)
                    if (!paragraphs[nowSpeak].isParagraphEnd) readAloudNumber++
                }
                if (readAloudNumber < it.getReadLength(pageIndex)) {
                    // 只推进引擎私有页光标；显示翻页由 UI 侧跟随规则处理（D1 拆除）
                    pageIndex--
                }
            }
            upTtsProgress(readAloudNumber)
            play()
        } else {
            advanceToPrevChapter(toLast = true)
        }
    }

    private fun nextP() {
        if (nowSpeak < contentList.size - 1) {
            playStop()
            readAloudNumber += contentList[nowSpeak].length.plus(1) - paragraphStartPos
            paragraphStartPos = 0
            nowSpeak++
            textChapter?.let {
                if (readAloudByPage) {
                    val paragraphs = it.getParagraphs(true)
                    if (!paragraphs[nowSpeak].isParagraphEnd) readAloudNumber--
                }
                if (pageIndex + 1 < it.pageSize
                    && readAloudNumber >= it.getReadLength(pageIndex + 1)
                ) {
                    // 只推进引擎私有页光标；显示翻页由 UI 侧跟随规则处理（D2 拆除）
                    pageIndex++
                }
            }
            upTtsProgress(readAloudNumber)
            play()
        } else {
            if (!checkTimerAtChapterEnd()) {
                nextChapter()
            }
        }
    }

    private fun setTimer(minute: Int) {
        setTimerExt(0, minute, 0)
    }

    // 定时朗读统一入口: mode 0=按分钟(恢复原有行为) 1=读完本章 2=剩余 chapters 章 (R7.2)
    private fun setTimerExt(mode: Int, minute: Int, chapters: Int) {
        ttsTimerMode = mode
        AppConfig.ttsTimerMode = mode
        remainChapters = 0
        when (mode) {
            1 -> {
                timeMinute = 0
                AppConfig.ttsTimer = 0
                toastOnUi("读完本章后停止朗读")
            }
            2 -> {
                timeMinute = 0
                AppConfig.ttsTimer = 0
                remainChapters = chapters
                AppConfig.ttsTimerChapters = chapters
                toastOnUi("读完 $chapters 章后停止朗读")
            }
            else -> {
                timeMinute = minute
                AppConfig.ttsTimer = minute
            }
        }
        doDs()
    }

    // 自然读完当前章节时判定是否按定时模式停止; 返回 true 表示已停止不再进入下一章
    internal fun checkTimerAtChapterEnd(): Boolean {
        when (ttsTimerMode) {
            1 -> {
                toastOnUi("读完本章，定时停止朗读")
                ReadAloud.stop(this)
                return true
            }
            2 -> {
                remainChapters--
                if (remainChapters <= 0) {
                    toastOnUi("已读完设定章节，定时停止朗读")
                    ReadAloud.stop(this)
                    return true
                }
            }
        }
        return false
    }

    private fun addTimer() {
        if (timeMinute == 180) {
            timeMinute = 0
        } else {
            timeMinute += 10
            if (timeMinute > 180) timeMinute = 180
        }
        doDs()
    }

    /**
     * 定时
     */
    @Synchronized
    private fun doDs() {
        postEvent(EventBus.READ_ALOUD_DS, timeMinute)
        upReadAloudNotification()
        dsJob?.cancel()
        if (ttsTimerMode != 0) {
            // 按章节定时: 无分钟倒计时, 章末由 checkTimerAtChapterEnd 判定
            return
        }
        dsJob = lifecycleScope.launch {
            while (isActive) {
                delay(60000)
                if (!pause) {
                    if (timeMinute >= 0) {
                        timeMinute--
                    }
                    if (timeMinute == 0) {
                        ReadAloud.stop(this@BaseReadAloudService)
                        postEvent(EventBus.READ_ALOUD_DS, timeMinute)
                        break
                    }
                }
                postEvent(EventBus.READ_ALOUD_DS, timeMinute)
                upReadAloudNotification()
            }
        }
    }

    /**
     * 请求音频焦点
     * @return 音频焦点
     */
    fun requestFocus(): Boolean {
        if (AppConfig.ignoreAudioFocus) {
            return true
        }
        val requestFocus = MediaHelp.requestFocus(mFocusRequest)
        if (!requestFocus) {
            pauseReadAloud(false)
            toastOnUi("未获取到音频焦点")
        }
        return requestFocus
    }

    /**
     * 放弃音频焦点
     */
    private fun abandonFocus() {
        AudioManagerCompat.abandonAudioFocusRequest(audioManager, mFocusRequest)
    }

    /**
     * 更新媒体状态
     */
    private fun upMediaSessionPlaybackState(state: Int) {
        mediaSessionCompat.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setActions(MediaHelp.MEDIA_SESSION_ACTIONS)
                .setState(state, nowSpeak.toLong(), 1f)
                // 为系统媒体控件添加定时按钮
                .addCustomAction(
                    "ACTION_ADD_TIMER",
                    getString(R.string.set_timer),
                    R.drawable.ic_time_add_24dp
                )
                .build()
        )
    }

    /**
     * 初始化MediaSession, 注册多媒体按钮
     */
    @SuppressLint("UnspecifiedImmutableFlag")
    private fun initMediaSession() {
        mediaSessionCompat.setFlags(
            MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS or
                    MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS
        )
        mediaSessionCompat.setCallback(object : MediaSessionCompat.Callback() {
            override fun onPlay() {
                resumeReadAloud()
            }

            override fun onPause() {
                pauseReadAloud()
            }

            override fun onSkipToNext() {
                if (getPrefBoolean("mediaButtonPerNext", false)) {
                    nextChapter()
                } else {
                    nextP()
                }
            }

            override fun onSkipToPrevious() {
                if (getPrefBoolean("mediaButtonPerNext", false)) {
                    prevChapter()
                } else {
                    prevP()
                }
            }

            override fun onStop() {
                stopSelf()
            }

            override fun onCustomAction(action: String, extras: Bundle?) {
                if (action == "ACTION_ADD_TIMER") addTimer()
            }

            override fun onMediaButtonEvent(mediaButtonEvent: Intent): Boolean {
                return MediaButtonReceiver.handleIntent(
                    this@BaseReadAloudService, mediaButtonEvent
                )
            }
        })
        mediaSessionCompat.setMediaButtonReceiver(
            broadcastPendingIntent<MediaButtonReceiver>(Intent.ACTION_MEDIA_BUTTON)
        )
        mediaSessionCompat.isActive = true
    }

    private fun upMediaMetadata() {
        var nTitle: String = when {
            pause -> getString(R.string.read_aloud_pause)
            ttsTimerMode == 1 -> getString(R.string.read_aloud_timer_chapter)
            ttsTimerMode == 2 && remainChapters > 0 -> getString(
                R.string.read_aloud_timer_chapters,
                remainChapters
            )
            timeMinute > 0 -> getString(
                R.string.read_aloud_timer,
                timeMinute
            )

            else -> getString(R.string.read_aloud_t)
        }
        nTitle += ": ${ReadBook.book?.name}"
        val metadata = MediaMetadataCompat.Builder()
            .putBitmap(MediaMetadataCompat.METADATA_KEY_ART, cover)
            .putText(MediaMetadataCompat.METADATA_KEY_TITLE, ReadBook.curTextChapter?.title ?: "null")
            .putText(MediaMetadataCompat.METADATA_KEY_ARTIST, nTitle)
            .putText(MediaMetadataCompat.METADATA_KEY_ALBUM, ReadBook.book?.author ?: "null")
//            .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, nowSpeak.toLong())
            .build()
        mediaSessionCompat.setMetadata(metadata)
    }

    /**
     * 注册多媒体按钮监听
     */
    private fun initBroadcastReceiver() {
        val intentFilter = IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
        registerReceiver(broadcastReceiver, intentFilter)
    }

    /**
     * 音频焦点变化
     */
    override fun onAudioFocusChange(focusChange: Int) {
        if (AppConfig.ignoreAudioFocus) {
            AppLog.put("忽略音频焦点处理(TTS)")
            return
        }
        when (focusChange) {
            AudioManager.AUDIOFOCUS_GAIN -> {
                if (needResumeOnAudioFocusGain) {
                    AppLog.put("音频焦点获得,继续朗读")
                    resumeReadAloud()
                } else {
                    AppLog.put("音频焦点获得")
                }
            }

            AudioManager.AUDIOFOCUS_LOSS -> {
                AppLog.put("音频焦点丢失,暂停朗读")
                pauseReadAloud()
            }

            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                AppLog.put("音频焦点暂时丢失并会很快再次获得,暂停朗读")
                if (!pause) {
                    needResumeOnAudioFocusGain = true
                    pauseReadAloud(false)
                }
            }

            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                // 短暂丢失焦点，这种情况是被其他应用申请了短暂的焦点希望其他声音能压低音量（或者关闭声音）凸显这个声音（比如短信提示音），
                AppLog.put("音频焦点短暂丢失,不做处理")
            }
        }
    }

    private fun upReadAloudNotification() {
        upNotificationJob = execute {
            try {
                upMediaMetadata()
                val notification = createNotification()
                notificationManager.notify(NotificationId.ReadAloudService, notification.build())
            } catch (e: Exception) {
                AppLog.put("创建朗读通知出错,${e.localizedMessage}", e, true)
            }
        }
    }

    private fun createNotification(): NotificationCompat.Builder {
        var nTitle: String = when {
            pause -> getString(R.string.read_aloud_pause)
            ttsTimerMode == 1 -> getString(R.string.read_aloud_timer_chapter)
            ttsTimerMode == 2 && remainChapters > 0 -> getString(
                R.string.read_aloud_timer_chapters,
                remainChapters
            )
            timeMinute > 0 -> getString(
                R.string.read_aloud_timer,
                timeMinute
            )

            else -> getString(R.string.read_aloud_t)
        }
        nTitle += ": ${ReadBook.book?.name}"
        var nSubtitle = ReadBook.curTextChapter?.title
        if (nSubtitle.isNullOrBlank())
            nSubtitle = getString(R.string.read_aloud_s)
        val builder = NotificationCompat
            .Builder(this, AppConst.channelIdReadAloud)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .setSmallIcon(R.drawable.ic_volume_up)
            .setSubText(getString(R.string.read_aloud))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentTitle(nTitle)
            .setContentText(nSubtitle)
            .setContentIntent(
                activityPendingIntent<ReadBookActivity>("activity")
            )
            .setVibrate(null)
            .setSound(null)
            .setLights(0, 0, 0)
        builder.setLargeIcon(cover)
        // 按钮定义：上一章、播放、停止、下一章、定时
        builder.addAction(
            R.drawable.ic_skip_previous,
            getString(R.string.previous_chapter),
            aloudServicePendingIntent(IntentAction.prev)
        )
        if (pause) {
            builder.addAction(
                R.drawable.ic_play_24dp,
                getString(R.string.resume),
                aloudServicePendingIntent(IntentAction.resume)
            )
        } else {
            builder.addAction(
                R.drawable.ic_pause_24dp,
                getString(R.string.pause),
                aloudServicePendingIntent(IntentAction.pause)
            )
        }
        builder.addAction(
            R.drawable.ic_skip_next,
            getString(R.string.next_chapter),
            aloudServicePendingIntent(IntentAction.next)
        )
        builder.addAction(
            R.drawable.ic_stop_black_24dp,
            getString(R.string.stop),
            aloudServicePendingIntent(IntentAction.stop)
        )
        builder.addAction(
            R.drawable.ic_time_add_24dp,
            getString(R.string.set_timer),
            aloudServicePendingIntent(IntentAction.addTimer)
        )
        builder.setStyle(androidx.media.app.NotificationCompat.MediaStyle()
            .setShowActionsInCompactView(0, 1, 2)
            .setMediaSession(mediaSessionCompat.sessionToken)
        )
        return builder
    }

    /**
     * 更新通知
     */
    override fun startForegroundNotification() {
        execute {
            try {
                upMediaMetadata()
                val notification = createNotification()
                startForeground(NotificationId.ReadAloudService, notification.build())
            } catch (e: Exception) {
                AppLog.put("创建朗读通知出错,${e.localizedMessage}", e, true)
                //创建通知出错不结束服务就会崩溃,服务必须绑定通知
                stopSelf()
            }
        }
    }

    abstract fun aloudServicePendingIntent(actionStr: String): PendingIntent?

    /** 上一章（LC :1778-1802）：syncView=true 为用户显式传送。 */
    open fun prevChapter(syncView: Boolean = false) {
        resumeReadAloudInternal()
        advanceToPrevChapter(toLast = false, syncView = syncView)
    }

    /** 下一章（LC :1827-1847 对齐）：syncView=true 为用户显式传送。 */
    open fun nextChapter(syncView: Boolean = false) {
        ReadBook.upReadTime()
        AppLog.putDebug("${ReadBook.curTextChapter?.chapter?.title} 朗读结束跳转下一章并朗读")
        resumeReadAloudInternal()
        advanceToNextChapter(syncView)
    }

    /**
     * 跨章派生跟随（LC :1804-1847 对齐，AD-C1-3）：
     * 用户显式传送或显示一直在跟 → 显示+朗读一起切（fromReadAloud=true 让 curPageChanged 链接管重启）；
     * 显示在别处 → switchReadAloudChapterKeepingView 只切朗读章，显示视角保持。
     */
    private fun advanceToPrevChapter(toLast: Boolean, syncView: Boolean = false) {
        this.toLast = toLast
        val followDisplay = syncView || ReadBook.durChapterIndex == currentChapterIndex
        if (followDisplay) {
            ReadBook.moveToPrevChapter(true, toLast = toLast, fromReadAloud = true)
        } else {
            switchReadAloudChapterKeepingViewByOffset(-1, toLast)
        }
    }

    private fun advanceToNextChapter(syncView: Boolean = false) {
        val followDisplay = syncView || ReadBook.durChapterIndex == currentChapterIndex
        if (followDisplay) {
            if (!ReadBook.moveToNextChapter(true, fromReadAloud = true)) {
                stopSelf()
            }
        } else {
            switchReadAloudChapterKeepingViewByOffset(1, false)
        }
    }

    private fun switchReadAloudChapterKeepingViewByOffset(offset: Int, toLast: Boolean) {
        switchReadAloudChapterKeepingView(ReadBook.durChapterIndex + offset, toLast)
    }

    /**
     * 只切朗读章、保持显示视角（LC :1864-1906 对齐）：
     * 目标章越界 stopSelf；独立加载正文（不触碰显示状态）后 prepare + 发布 + 恢复播放。
     */
    private fun switchReadAloudChapterKeepingView(targetIndex: Int, toLast: Boolean = false) {
        if (targetIndex < 0 || targetIndex >= ReadBook.simulatedChapterSize) {
            stopSelf()
            return
        }
        Coroutine.async(
            scope = lifecycleScope,
            executeContext = Main
        ) {
            ReadBook.loadTextChapterForReadAloud(targetIndex, lifecycleScope)
                ?: throw NoStackTraceException("朗读切章加载失败:$targetIndex")
        }.onError {
            AppLog.putDebugWithTag(
                AppLog.TAG_READ_ALOUD, "朗读切章失败:$targetIndex ${it.localizedMessage}",
                level = AppLog.Level.WARN
            )
            stopSelf()
        }.onSuccess { chapter ->
            if (!isRun) return@onSuccess
            textChapter = chapter
            readAloudByPage = getPrefBoolean(PreferKey.readAloudByPage)
            val startPageIndex = if (toLast) chapter.lastIndex else 0
            pageIndex = startPageIndex
            readAloudStartRequest++
            if (!prepareReadAloudChapter(chapter, startPageIndex, 0)) {
                stopSelf()
                return@onSuccess
            }
            preparedReadAloudStartRequest = readAloudStartRequest
            upTtsProgress(readAloudNumber)
            play()
        }
    }

    /** seek 越界兜底：无效目标位置直接停止朗读（LC stopReadAloudOnInvalidPosition 语义）。 */
    private fun stopReadAloudOnInvalidPosition() {
        AppLog.putDebugWithTag(
            AppLog.TAG_READ_ALOUD, "seek目标位置无效，停止朗读", level = AppLog.Level.WARN
        )
        stopSelf()
    }

    /**
     * 按朗读单元号 seek（LC :1301-1357 对齐）：章不一致忽略并重发段进度；
     * 写引擎私有光标 → 发布位置（含 syncView 元数据）→ 按需恢复播放。
     */
    internal fun seekToReadAloudProgress(
        chapterIndex: Int,
        position: Int,
        syncView: Boolean = false
    ) {
        val chapter = textChapter ?: return
        if (chapter.chapter.index != chapterIndex) {
            publishParagraphProgress()
            return
        }
        if (position !in contentList.indices) {
            stopReadAloudOnInvalidPosition()
            return
        }
        val resumeAfterSeek = !pause
        playStop()
        var number = 0
        for (i in 0 until position) {
            number += contentList[i].length + 1
        }
        readAloudNumber = number
        paragraphStartPos = 0
        nowSpeak = position
        pageIndex = chapter.getPageIndexByCharIndex(readAloudNumber).coerceAtLeast(0)
        upTtsProgress(readAloudNumber, syncView)
        if (resumeAfterSeek) {
            play()
        }
    }

    /** 按章节绝对字符位 seek（LC :1359-1385 对齐）：字符位→段落映射→复用段号 seek。 */
    internal fun seekToReadAloudTextPosition(
        chapterIndex: Int,
        chapterPosition: Int,
        syncView: Boolean = false
    ) {
        val chapter = textChapter ?: return
        if (chapter.chapter.index != chapterIndex) {
            publishParagraphProgress()
            return
        }
        if (chapterPosition !in 0 until chapter.lastReadLength) {
            stopReadAloudOnInvalidPosition()
            return
        }
        val paragraphNum = chapter.getParagraphNum(chapterPosition + 1, readAloudByPage)
        if (paragraphNum <= 0) {
            stopReadAloudOnInvalidPosition()
            return
        }
        seekToReadAloudProgress(chapterIndex, paragraphNum - 1, syncView)
    }

    private fun initPhoneStateListener() {
        val needRegister = AppConfig.ignoreAudioFocus && AppConfig.pauseReadAloudWhilePhoneCalls
        if (needRegister && registeredPhoneStateListener) {
            return
        }
        if (needRegister) {
            registerPhoneStateListener(phoneStateListener)
        } else {
            unregisterPhoneStateListener(phoneStateListener)
        }
    }

    private fun unregisterPhoneStateListener(l: PhoneStateListener) {
        if (registeredPhoneStateListener) {
            withReadPhoneStatePermission {
                telephonyManager.listen(l, PhoneStateListener.LISTEN_NONE)
                registeredPhoneStateListener = false
            }
        }
    }

    private fun registerPhoneStateListener(l: PhoneStateListener) {
        withReadPhoneStatePermission {
            telephonyManager.listen(l, PhoneStateListener.LISTEN_CALL_STATE)
            registeredPhoneStateListener = true
        }
    }

    private fun withReadPhoneStatePermission(block: () -> Unit) {
        try {
            block.invoke()
        } catch (_: SecurityException) {
            PermissionsCompat.Builder()
                .addPermissions(Permissions.READ_PHONE_STATE)
                .rationale(R.string.read_aloud_read_phone_state_permission_rationale)
                .onGranted {
                    try {
                        block.invoke()
                    } catch (_: SecurityException) {
                        LogUtils.d(TAG, "Grant read phone state permission fail.")
                    }
                }
                .request()
        }
    }

    @Suppress("OVERRIDE_DEPRECATION")
    inner class ReadAloudPhoneStateListener : PhoneStateListener() {
        override fun onCallStateChanged(state: Int, phoneNumber: String?) {
            super.onCallStateChanged(state, phoneNumber)
            when (state) {
                TelephonyManager.CALL_STATE_IDLE -> {
                    if (needResumeOnCallStateIdle) {
                        AppLog.put("来电结束,继续朗读")
                        resumeReadAloud()
                    } else {
                        AppLog.put("来电结束")
                    }
                }

                TelephonyManager.CALL_STATE_RINGING -> {
                    if (!pause) {
                        AppLog.put("来电响铃,暂停朗读")
                        needResumeOnCallStateIdle = true
                        pauseReadAloud()
                    } else {
                        AppLog.put("来电响铃")
                    }
                }

                TelephonyManager.CALL_STATE_OFFHOOK -> {
                    AppLog.put("来电接听,不做处理")
                }
            }
        }
    }

}
