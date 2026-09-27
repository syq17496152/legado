package io.legado.app.model

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import io.legado.app.R
import io.legado.app.constant.AppLog
import io.legado.app.constant.EventBus
import io.legado.app.constant.IntentAction
import io.legado.app.constant.Status
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.ReadRecord
import io.legado.app.help.ReadRecordDailyHelper
import io.legado.app.help.book.ContentProcessor
import io.legado.app.help.book.getBookSource
import io.legado.app.help.book.readSimulating
import io.legado.app.help.book.simulatedTotalChapterNum
import io.legado.app.help.book.update
import io.legado.app.help.config.AppConfig
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.help.globalExecutor
import io.legado.app.model.webBook.WebBook
import io.legado.app.service.AudioPlayService
import io.legado.app.model.SourceCallBack
import io.legado.app.utils.postEvent
import io.legado.app.utils.startService
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.runBlocking
import splitties.init.appCtx
import kotlin.text.trim

@SuppressLint("StaticFieldLeak")
@Suppress("unused")
object AudioPlay : CoroutineScope by MainScope() {

    /** W3 / REQ-15：听书时长结算节流间隔（播放进度循环 500ms/次 ⇒ 10s 结算一次，避免每秒写库）。 */
    private const val READ_TIME_SETTLE_INTERVAL_MS = 10_000L

    /**
     * 播放模式枚举
     */
    enum class PlayMode(val iconRes: Int) {
        LIST_END_STOP(R.drawable.ic_play_mode_list_end_stop),
        SINGLE_LOOP(R.drawable.ic_play_mode_single_loop),
        RANDOM(R.drawable.ic_play_mode_random),
        LIST_LOOP(R.drawable.ic_play_mode_list_loop);

        fun next(): PlayMode {
            return when (this) {
                LIST_END_STOP -> SINGLE_LOOP
                SINGLE_LOOP -> RANDOM
                RANDOM -> LIST_LOOP
                LIST_LOOP -> LIST_END_STOP
            }
        }
    }

    var playMode = PlayMode.LIST_END_STOP
    var status = Status.STOP
    private var activityContext: Context? = null
    private var serviceContext: Context? = null
    private val context: Context get() = activityContext ?: serviceContext ?: appCtx
    var callback: CallBack? = null
    var book: Book? = null
    var chapterSize = 0
    var simulatedChapterSize = 0
    var durChapterIndex = 0
    var durChapterPos = 0
    var durChapter: BookChapter? = null
    var durPlayUrl = ""
    var durLyric: String? = null
    var durAudioSize = 0
    var inBookshelf = false
    var bookSource: BookSource? = null
    val loadingChapters = arrayListOf<Int>()
    private val readRecord = ReadRecord()
    var readStartTime: Long = System.currentTimeMillis()

    /** W3 / REQ-15：上次结算听书时长的时刻（节流用；见 [upReadTime]）。 */
    private var lastReadTimeSettleAt = 0L
    val executor = globalExecutor

    fun changePlayMode() {
        playMode = playMode.next()
        book?.setPlayMode(playMode.ordinal)
        postEvent(EventBus.PLAY_MODE_CHANGED, playMode)
    }

    fun upData(book: Book) {
        AudioPlay.book = book
        chapterSize = runBlocking(IO) { appDb.bookChapterDao.getChapterCount(book.bookUrl) }
        simulatedChapterSize = if (book.readSimulating()) {
            book.simulatedTotalChapterNum()
        } else {
            chapterSize
        }
        if (durChapterIndex != book.durChapterIndex) {
            stopPlay()
            durChapterIndex = book.durChapterIndex
            durChapterPos = book.durChapterPos
            durPlayUrl = ""
            durLyric = null
            durAudioSize = 0
        }
        upDurChapter()
    }

    fun resetData(book: Book) {
        // 诊断埋点（2026-09-15 日志盲区审计 P1）：听书会话起播入口原为黑盒
        //（AudioPlayService 服务侧有日志，model 层入口无），"点了听书没反应"类问题无从定位
        AppLog.put("AudioDiag resetData: chapterSize=${book.totalChapterNum}, durIdx=${book.durChapterIndex}, sourceNull=${book.getBookSource() == null}")
        stop()
        AudioPlay.book = book
        readRecord.bookName = book.name
        readRecord.readTime = runBlocking(IO) { appDb.readRecordDao.getReadTime(book.name) } ?: 0
        chapterSize = runBlocking(IO) { appDb.bookChapterDao.getChapterCount(book.bookUrl) }
        simulatedChapterSize = if (book.readSimulating()) {
            book.simulatedTotalChapterNum()
        } else {
            chapterSize
        }
        bookSource = book.getBookSource()
        durChapterIndex = book.durChapterIndex
        durChapterPos = book.durChapterPos
        PlayMode.entries.getOrNull(book.getPlayMode())?.let{
            playMode = it
            postEvent(EventBus.PLAY_MODE_CHANGED, it)
        }
        val playSpeed = book.getPlaySpeed()
        AudioPlayService.playSpeed = playSpeed
        postEvent(EventBus.AUDIO_SPEED, playSpeed)
        durPlayUrl = ""
        durLyric = null
        durAudioSize = 0
        upDurChapter()
        SourceCallBack.callBackBook(SourceCallBack.START_READ, bookSource, book, durChapter)
        postEvent(EventBus.AUDIO_BUFFER_PROGRESS, 0)
    }

    /**
     * W3 / REQ-15（AD-06）：结算听书时长 —— **单源**走 [ReadRecordDailyHelper.record]，
     * 与阅读共用同一每日统计口径（产品承诺「听得算数」）。
     *
     * 三项关键约定：
     * 1. **先结算再重置**：`readStartTime` 在派发前同步前移到本次结算时刻，保证
     *    「一次 delta 只记一次」（executor 异步执行，若在任务内更新会与下一次调用竞态重复计数）；
     * 2. **节流**：真机实证节拍为播放进度循环 500ms/次 ⇒ 无条件结算会把「每秒一次写库」变成常态；
     *    故非强制调用间隔小于 [READ_TIME_SETTLE_INTERVAL_MS] 时直接返回（暂停/切章用 `force = true` 绕过）；
     * 3. **保留原语义**：`enableReadRecord` 关闭时整体早退；`record()` 自带 `readTime <= 0` 守卫防重复计数。
     *
     * @param force true = 忽略节流（暂停、切章、停止等**必须结清**的时点）
     */
    fun upReadTime(force: Boolean = false) {
        if (!AppConfig.enableReadRecord) {
            return
        }
        val now = System.currentTimeMillis()
        if (!force && now - lastReadTimeSettleAt < READ_TIME_SETTLE_INTERVAL_MS) {
            return
        }
        val delta = now - readStartTime
        if (delta <= 0) {
            return
        }
        lastReadTimeSettleAt = now
        readStartTime = now
        executor.execute {
            readRecord.readTime = readRecord.readTime + delta
            readRecord.lastRead = now
            runBlocking(IO) { appDb.readRecordDao.insert(readRecord) }
            ReadRecordDailyHelper.record(delta, now, forceWidgetUpdate = false)
        }
    }

    private fun addLoading(index: Int): Boolean {
        synchronized(this) {
            if (loadingChapters.contains(index)) return false
            loadingChapters.add(index)
            return true
        }
    }

    private fun removeLoading(index: Int) {
        synchronized(this) {
            loadingChapters.remove(index)
        }
    }

    fun loadOrUpPlayUrl() {
        if (durPlayUrl.isEmpty()) {
            loadPlayUrl()
        } else {
            upPlayUrl()
        }
    }

    /**
     * W2 / REQ-13：播放失败的 **DEGRADE 档** —— 丢弃当前播放地址，**重新经书源规则取新地址**后续播。
     *
     * 与 [loadOrUpPlayUrl] 的区别：后者在同地址非空时直接原地重播（等价自愈档）；本方法先清空地址，
     * 强制重跑书源正文规则（换链），用于「当前链接已被源站判定失效（404/解析失败）」的场景。
     * 不在此处 stop/release —— 由既有 play 通道接管（与 [AudioPlayService] 的播放权协调器保持一致）。
     */
    fun reloadPlayUrl() {
        durPlayUrl = ""
        loadOrUpPlayUrl()
    }

    /**
     * 加载播放URL
     */
    private fun loadPlayUrl() {
        val index = durChapterIndex
        if (addLoading(index)) {
            val book = book
            val bookSource = bookSource
            if (book != null && bookSource != null) {
                upDurChapter()
                val chapter = durChapter
                if (chapter == null) {
                    removeLoading(index)
                    return
                }
                if (chapter.isVolume) {
                    skipTo(index + 1)
                    removeLoading(index)
                    return
                }
                upLoading(true)
                WebBook.getContent(this, bookSource, book, chapter)
                    .onSuccess { content ->
                        val content = content.trim()
                        if (content.isEmpty()) {
                            appCtx.toastOnUi("未获取到资源链接")
                        } else {
                            contentLoadFinish(chapter, content)
                        }
                    }.onError {
                        AppLog.put("获取资源链接出错\n$it", it, true)
                        upLoading(false)
                    }.onCancel {
                        removeLoading(index)
                    }.onFinally {
                        callback?.upLyric(durLyric)
                        removeLoading(index)
                    }
            } else {
                removeLoading(index)
                appCtx.toastOnUi("book or source is null")
            }
        }
    }

    /**
     * 加载完成
     */
    private fun contentLoadFinish(chapter: BookChapter, content: String) {
        if (chapter.index == book?.durChapterIndex) {
            durPlayUrl = content
            durLyric = chapter.getVariable("lyric")
            upPlayUrl()
        }
    }

    private fun upPlayUrl() {
        if (isPlayToEnd()) {
            playNew()
        } else {
            play()
        }
    }

    /**
     * 播放当前章节
     */
    fun play() {
        context.startService<AudioPlayService> {
            action = IntentAction.play
        }
    }

    /**
     * 从头播放新章节
     */
    private fun playNew() {
        context.startService<AudioPlayService> {
            action = IntentAction.playNew
        }
    }

    /**
     * 更新当前章节
     */
    fun upDurChapter() {
        val book = book ?: return
        durChapter = runBlocking(IO) { appDb.bookChapterDao.getChapter(book.bookUrl, durChapterIndex) }
        durAudioSize = durChapter?.end?.toInt() ?: 0
        val title = durChapter?.title ?: appCtx.getString(R.string.data_loading)
        postEvent(EventBus.AUDIO_SUB_TITLE, title)
        postEvent(EventBus.AUDIO_SIZE, durAudioSize)
        postEvent(EventBus.AUDIO_PROGRESS, durChapterPos)
    }

    fun pause(context: Context) {
        if (AudioPlayService.isRun) {
            // W3 / REQ-15（B5 偏差修复）：**先结算再重置** —— 原实现直接前移 readStartTime，
            // 使「最后一次结算 → 暂停」之间的听书时长永久丢失（表现为听书时长偏少）。
            upReadTime(force = true)
            // 计量关闭时 upReadTime 提前返回 ⇒ 此处兜住重置（保持原语义）
            readStartTime = System.currentTimeMillis()
            context.startService<AudioPlayService> {
                action = IntentAction.pause
            }
        }
    }

    fun resume(context: Context) {
        if (AudioPlayService.isRun) {
            context.startService<AudioPlayService> {
                action = IntentAction.resume
            }
        }
    }

    fun stop() {
        if (AudioPlayService.isRun) {
            context.startService<AudioPlayService> {
                action = IntentAction.stop
            }
        }
    }

    fun setSpeed(speed: Float) {
        if (AudioPlayService.isRun) {
            book?.setPlaySpeed(speed)
            val clampedSpeed = speed.coerceIn(0.5f, 3.0f)
            context.startService<AudioPlayService> {
                action = IntentAction.setSpeed
                putExtra("speed", clampedSpeed)
            }
        }
    }

     

    fun adjustProgress(position: Int) {
        durChapterPos = position
        saveRead()
        if (AudioPlayService.isRun) {
            context.startService<AudioPlayService> {
                action = IntentAction.adjustProgress
                putExtra("position", position)
            }
        }
    }

    fun skipTo(index: Int) {
        Coroutine.async {
            stopPlay()
            if (index in 0..<simulatedChapterSize) {
                durChapterIndex = index
                durChapterPos = 0
                durPlayUrl = ""
                durLyric = null
                saveRead()
                loadPlayUrl()
            }
        }
    }

    fun prev() {
        Coroutine.async {
            stopPlay()
            if (durChapterIndex > 0) {
                durChapterIndex -= 1
                durChapterPos = 0
                durPlayUrl = ""
                durLyric = null
                saveRead()
                loadPlayUrl()
            }
        }
    }

    fun next() {
        stopPlay()
        // W3 / REQ-15：章节切换必须**结清**本段时长（force 绕过节流，否则切章会丢账）
        upReadTime(force = true)
        when (playMode) {
            PlayMode.LIST_END_STOP -> {
                if (durChapterIndex + 1 < simulatedChapterSize) {
                    durChapterIndex += 1
                    durChapterPos = 0
                    durPlayUrl = ""
                    durLyric = null
                    saveRead()
                    loadPlayUrl()
                }
            }

            PlayMode.SINGLE_LOOP -> {
                durChapterPos = 0
                durPlayUrl = ""
                durLyric = null
                saveRead()
                loadPlayUrl()
            }

            PlayMode.RANDOM -> {
                durChapterIndex = (0 until simulatedChapterSize).random()
                durChapterPos = 0
                durPlayUrl = ""
                durLyric = null
                saveRead()
                loadPlayUrl()
            }

            PlayMode.LIST_LOOP -> {
                durChapterIndex = (durChapterIndex + 1) % simulatedChapterSize
                durChapterPos = 0
                durPlayUrl = ""
                durLyric = null
                saveRead()
                loadPlayUrl()
            }
        }
    }

    fun setTimer(minute: Int) {
        if (AudioPlayService.isRun) {
            val intent = Intent(context, AudioPlayService::class.java)
            intent.action = IntentAction.setTimer
            intent.putExtra("minute", minute)
            context.startService(intent)
        } else {
            AudioPlayService.timeMinute = minute
            postEvent(EventBus.AUDIO_DS, minute)
        }
    }

    fun addTimer() {
        val intent = Intent(context, AudioPlayService::class.java)
        intent.action = IntentAction.addTimer
        context.startService(intent)
    }

    fun stopPlay() {
        if (AudioPlayService.isRun) {
            context.startService<AudioPlayService> {
                action = IntentAction.stopPlay
            }
        }
    }

    fun saveRead(first: Boolean = false) {
        val book = book ?: return
        Coroutine.async(executeContext = IO) {
            book.lastCheckCount = 0
            val durTime = System.currentTimeMillis()
            book.durChapterTime = durTime
            val chapterChanged = book.durChapterIndex != durChapterIndex
            book.durChapterIndex = durChapterIndex
            book.durChapterPos = durChapterPos
            if (first || chapterChanged) {
                appDb.bookChapterDao.getChapter(book.bookUrl, book.durChapterIndex)?.let {
                    book.durChapterTitle = it.getDisplayTitle(
                        ContentProcessor.get(book.name, book.origin).getTitleReplaceRules(),
                        book.getUseReplaceRule(),
                        replaceBook = book.toReplaceBook()
                    )
                    SourceCallBack.callBackBook(SourceCallBack.SAVE_READ, bookSource, book, it,durTime.toString())
                }
            }
            book.update()
        }
    }

    /**
     * 保存章节长度
     */
    fun saveDurChapter(audioSize: Long) {
        val chapter = durChapter ?: return
        Coroutine.async(executeContext = IO) {
            durAudioSize = audioSize.toInt()
            chapter.end = audioSize
            chapter.update()
        }
    }

    fun playPositionChanged(position: Int) {
        durChapterPos = position
        saveRead()
    }

    fun upLoading(loading: Boolean) {
        callback?.upLoading(loading)
    }

    private fun isPlayToEnd(): Boolean {
        return durChapterIndex + 1 == simulatedChapterSize
                && durChapterPos == durAudioSize
    }

    fun register(context: Context) {
        activityContext = context
        callback = context as CallBack
    }

    fun unregister(context: Context) {
        if (activityContext === context) {
            activityContext = null
            callback = null
        }
        coroutineContext.cancelChildren()
    }

    fun registerService(context: Context) {
        serviceContext = context
    }

    fun unregisterService() {
        serviceContext = null
    }

    interface CallBack {

        fun upLoading(loading: Boolean)
        fun upLyric(lyric: String?)
        fun upLyricP(position: Int)
    }

}
