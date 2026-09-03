package io.legado.app.service

import android.app.PendingIntent
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import io.legado.app.R
import io.legado.app.constant.AppConst
import io.legado.app.constant.AppLog
import io.legado.app.constant.AppPattern
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.MediaHelp
import io.legado.app.help.config.AppConfig
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.lib.dialogs.SelectItem
import io.legado.app.model.ReadAloud
import io.legado.app.model.ReadBook
import io.legado.app.utils.GSON
import io.legado.app.utils.LogUtils
import io.legado.app.utils.fromJsonObject
import io.legado.app.utils.servicePendingIntent
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive

/**
 * 本地朗读
 *
 * C1：EMA 语速校准 + 页界预测调度（LC :60-206/:955-1047 对齐）。
 * 预测只影响位置事件的发布时机，不新增显示进度写点、不直翻页。
 */
class TTSReadAloudService : BaseReadAloudService(), TextToSpeech.OnInitListener {

    companion object {
        // EMA 校准常量（LC :996-1001）：新样本权重 0.3，小于 500ms 的采样丢弃
        private const val EMA_ALPHA = 0.3
        private const val MIN_SAMPLE_ELAPSED_MS = 500L
    }

    private var textToSpeech: TextToSpeech? = null
    private var ttsInitFinish = false
    private val ttsUtteranceListener = TTSUtteranceListener()
    private var speakJob: Coroutine<*>? = null
    private val TAG = "TTSReadAloudService"

    // ---- C1 页界预测状态（页间分段 readAloudByPage 映射 LC pageSplit） ----
    private val predictHandler = Handler(Looper.getMainLooper())
    private var predictRunnable: Runnable? = null
    private var utteranceStartRealtime = 0L
    private var lastRangeOffset = 0
    private var speakGeneration = 0L

    /** 实测语速（字/ms），初值 480 字/分钟（LC :60-74）。 */
    @Volatile
    private var measuredCharRate = 480.0 / 60_000.0

    override fun onCreate() {
        super.onCreate()
        kotlin.runCatching {
            initTts()
        }.onFailure {
            AppLog.put("${getString(R.string.tts_init_failed)}\n$it", it, true)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        clearTTS()
    }

    @Synchronized
    private fun initTts() {
        ttsInitFinish = false
        val engine = GSON.fromJsonObject<SelectItem<String>>(ReadAloud.ttsEngine).getOrNull()?.value
        LogUtils.d(TAG, "initTts engine:$engine")
        textToSpeech = if (engine.isNullOrBlank()) {
            TextToSpeech(this, this)
        } else {
            TextToSpeech(this, this, engine)
        }
        upSpeechRate()
    }

    @Synchronized
    fun clearTTS() {
        textToSpeech?.runCatching {
            stop()
            shutdown()
        }
        textToSpeech = null
        ttsInitFinish = false
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            textToSpeech?.let {
                it.setOnUtteranceProgressListener(ttsUtteranceListener)
                ttsInitFinish = true
                play()
            }
        } else {
            toastOnUi(R.string.tts_init_failed)
        }
    }

    @Synchronized
    override fun play() {
        speakGeneration++
        cancelPageBreakPrediction()
        if (!ttsInitFinish) return
        if (!requestFocus()) return
        if (contentList.isEmpty()) {
            AppLog.putDebug("朗读列表为空")
            ReadBook.readAloud()
            return
        }
        super.play()
        MediaHelp.playSilentSound(this@TTSReadAloudService)
        speakJob?.cancel()
        speakJob = execute {
            LogUtils.d(TAG, "朗读列表大小 ${contentList.size}")
            LogUtils.d(TAG, "朗读页数 ${textChapter?.pageSize}")
            val tts = textToSpeech ?: throw NoStackTraceException("tts is null")
            val contentList = contentList
            var isAddedText = false
            for (i in nowSpeak until contentList.size) {
                ensureActive()
                var text = contentList[i]
                if (paragraphStartPos > 0 && i == nowSpeak) {
                    text = text.substring(paragraphStartPos)
                }
                if (text.matches(AppPattern.notReadAloudRegex)) {
                    continue
                }
                if (!isAddedText) {
                    val result = tts.runCatching {
                        speak(text, TextToSpeech.QUEUE_FLUSH, null, AppConst.APP_TAG + i)
                    }.getOrElse {
                        AppLog.put("tts出错\n${it.localizedMessage}", it, true)
                        TextToSpeech.ERROR
                    }
                    if (result == TextToSpeech.ERROR) {
                        AppLog.put("tts出错 尝试重新初始化")
                        clearTTS()
                        initTts()
                        return@execute
                    }
                } else {
                    val result = tts.runCatching {
                        speak(text, TextToSpeech.QUEUE_ADD, null, AppConst.APP_TAG + i)
                    }.getOrElse {
                        AppLog.put("tts出错\n${it.localizedMessage}", it, true)
                        TextToSpeech.ERROR
                    }
                    if (result == TextToSpeech.ERROR) {
                        AppLog.put("tts朗读出错:$text")
                    }
                }
                isAddedText = true
                // 段落间停顿: 非末段后插入静音朗读项 (R7.1)
                val pauseMs = AppConfig.ttsParagraphPauseMs
                if (pauseMs > 0 && i < contentList.lastIndex) {
                    tts.runCatching {
                        @Suppress("DEPRECATION")
                        playSilentUtterance(pauseMs.toLong(), TextToSpeech.QUEUE_ADD, "${AppConst.APP_TAG}pause$i")
                    }
                }
            }
            LogUtils.d(TAG, "朗读内容添加完成")
            if (!isAddedText) {
                playStop()
                delay(1000)
                if (!checkTimerAtChapterEnd()) {
                    nextChapter()
                }
            }
        }.onError {
            AppLog.put("tts朗读出错\n${it.localizedMessage}", it, true)
        }
    }

    override fun playStop() {
        speakGeneration++
        cancelPageBreakPrediction()
        textToSpeech?.runCatching {
            stop()
        }
    }

    /**
     * 更新朗读速度
     */
    override fun upSpeechRate(reset: Boolean) {
        if (AppConfig.ttsFlowSys) {
            if (reset) {
                clearTTS()
                initTts()
            }
        } else {
            val speechRate = (AppConfig.ttsSpeechRate + 5) / 10f
            textToSpeech?.setSpeechRate(speechRate)
        }
    }

    /**
     * 暂停朗读
     */
    override fun pauseReadAloud(abandonFocus: Boolean) {
        super.pauseReadAloud(abandonFocus)
        speakJob?.cancel()
        speakGeneration++
        cancelPageBreakPrediction()
        textToSpeech?.runCatching {
            stop()
        }
    }

    /**
     * 恢复朗读
     */
    override fun resumeReadAloud() {
        super.resumeReadAloud()
        play()
    }

    /** 作废当前单元的页界预测（LC :199 语义）。 */
    private fun cancelPageBreakPrediction() {
        predictRunnable?.let { predictHandler.removeCallbacks(it) }
        predictRunnable = null
    }

    /**
     * 页界预测调度（LC :180-206 直译）：单元 [utteranceStart, utteranceEnd) 覆盖下一页页界时，
     * 按实测语速 postDelayed 到点发布页界位置（只发布位置，不翻页）。
     * speakGeneration 代数防乱序（暂停/停止/重试后调度失效）。
     */
    private fun schedulePageBreakPrediction(utteranceTextLength: Int) {
        cancelPageBreakPrediction()
        if (readAloudByPage) return          // 页分段时单元已在页界裂开，无需预测
        val chapter = textChapter ?: return
        if (utteranceTextLength <= 0) return // 静音项等无文本单元不调度（R10）
        if (pageIndex + 1 >= chapter.pageSize) return
        val nextPageStart = chapter.getReadLength(pageIndex + 1)
        if (nextPageStart <= 0) return
        val utteranceStart = readAloudNumber
        val utteranceEnd = utteranceStart + utteranceTextLength
        if (nextPageStart <= utteranceStart || nextPageStart >= utteranceEnd) return
        val breakOffset = nextPageStart - utteranceStart
        val delayMs = (breakOffset / measuredCharRate).toLong().coerceAtLeast(0L)
        val generation = speakGeneration
        val runnable = Runnable {
            predictRunnable = null
            if (generation != speakGeneration || pause) return@Runnable
            // onRangeStart 真实信号已先发布则跳过（LC :200）
            if (lastRangeOffset + utteranceStart >= nextPageStart) return@Runnable
            AppLog.putDebugWithTag(
                AppLog.TAG_READ_ALOUD, "预测换页触发 pos:$nextPageStart", level = AppLog.Level.INFO
            )
            upTtsProgress(nextPageStart)
        }
        predictRunnable = runnable
        predictHandler.postDelayed(runnable, delayMs)
    }

    /**
     * 朗读监听
     */
    private inner class TTSUtteranceListener : UtteranceProgressListener() {

        private val TAG = "TTSUtteranceListener"

        override fun onStart(s: String) {
            LogUtils.d(TAG, "onStart nowSpeak:$nowSpeak pageIndex:$pageIndex utteranceId:$s")
            textChapter?.let {
                if (contentList[nowSpeak].matches(AppPattern.notReadAloudRegex)) {
                    nextParagraph()
                }
                if (pageIndex + 1 < it.pageSize
                    && readAloudNumber + 1 > it.getReadLength(pageIndex + 1)
                ) {
                    // 只推进引擎私有页光标，显示翻页由 UI 侧跟随规则处理（D7 拆除，LC :964-966）
                    pageIndex++
                }
                // 段落停顿静音项：不重置计时基准、不调度预测（R10）
                if (!s.contains("pause")) {
                    utteranceStartRealtime = System.currentTimeMillis()
                    lastRangeOffset = 0
                    schedulePageBreakPrediction(contentList.getOrNull(nowSpeak)?.length ?: 0)
                }
                upTtsProgress(readAloudNumber)
            }
        }

        override fun onDone(s: String) {
            LogUtils.d(TAG, "onDone utteranceId:$s")
            // 段落停顿静音项不推进段落 (R7.1)
            if (s.contains("pause")) {
                return
            }
            // 整句真实总时长更准：EMA 再校准 + 作废旧单元预测（LC :1035-1047）
            val len = contentList.getOrNull(nowSpeak)?.length ?: 0
            val elapsed = System.currentTimeMillis() - utteranceStartRealtime
            if (len > 0 && elapsed > MIN_SAMPLE_ELAPSED_MS) {
                val sample = len / elapsed.toDouble()
                measuredCharRate = measuredCharRate * 0.7 + sample * 0.3
            }
            cancelPageBreakPrediction()
            nextParagraph()
        }

        override fun onRangeStart(utteranceId: String?, start: Int, end: Int, frame: Int) {
            super.onRangeStart(utteranceId, start, end, frame)
            LogUtils.d(
                TAG,
                "onRangeStart nowSpeak:$nowSpeak utteranceId:$utteranceId start:$start end:$end frame:$frame"
            )
            textChapter?.let {
                // EMA 语速校准：句中真实信号驱动（LC :988-1013）
                val elapsed = System.currentTimeMillis() - utteranceStartRealtime
                if (start > 0 && elapsed > MIN_SAMPLE_ELAPSED_MS) {
                    val sample = start / elapsed.toDouble()
                    measuredCharRate = measuredCharRate * (1 - EMA_ALPHA) + sample * EMA_ALPHA
                }
                lastRangeOffset = start
                if (pageIndex + 1 < it.pageSize
                    && readAloudNumber + start > it.getReadLength(pageIndex + 1)
                ) {
                    // 只推进引擎私有页光标 + 发布位置（D8 拆除，LC :1009-1011）
                    pageIndex++
                    upTtsProgress(readAloudNumber + start)
                }
            }
        }

        override fun onError(utteranceId: String?, errorCode: Int) {
            LogUtils.d(
                TAG,
                "onError nowSpeak:$nowSpeak pageIndex:$pageIndex utteranceId:$utteranceId errorCode:$errorCode"
            )
            nextParagraph()
        }

        private fun nextParagraph() {
            //跳过全标点段落
            do {
                readAloudNumber += contentList[nowSpeak].length + 1 - paragraphStartPos
                paragraphStartPos = 0
                nowSpeak++
                if (nowSpeak >= contentList.size) {
                    nextChapter()
                    return
                }
            } while (contentList[nowSpeak].matches(AppPattern.notReadAloudRegex))
        }

        @Deprecated("Deprecated in Java")
        override fun onError(s: String) {
            LogUtils.d(TAG, "onError nowSpeak:$nowSpeak pageIndex:$pageIndex s:$s")
            nextParagraph()
        }

    }

    override fun aloudServicePendingIntent(actionStr: String): PendingIntent? {
        return servicePendingIntent<TTSReadAloudService>(actionStr)
    }

}