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
import io.legado.app.help.config.AppConfig
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.help.readaloud.speech.SpeechRoute
import io.legado.app.help.readaloud.speech.TtsEngineParamsStore
import io.legado.app.model.ReadAloud
import io.legado.app.model.ReadBook
import io.legado.app.utils.LogUtils
import io.legado.app.utils.servicePendingIntent
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive

/**
 * 本地朗读（AD-03/AD-05/AD-09）
 * init 按路由包名构造 + 8s 超时看门狗 + 失败降级默认引擎明示 + 每引擎独立参数
 */
class TTSReadAloudService : BaseReadAloudService(), TextToSpeech.OnInitListener {

    private var textToSpeech: TextToSpeech? = null
    private var ttsInitFinish = false
    private val ttsUtteranceListener = TTSUtteranceListener()
    private var speakJob: Coroutine<*>? = null
    private val TAG = "TTSReadAloudService"

    // init 代际（AD-03）：每次 initTts 递增，迟到回调/看门狗按代际判定失效
    @Volatile
    private var initGeneration = 0

    // 降级初始化标记：回退默认引擎的初始化单次不挂看门狗（防死循环，AD-03）
    @Volatile
    private var fallbackInit = false

    private val mainHandler = Handler(Looper.getMainLooper())

    // init 看门狗（AD-03：约 8s）
    private val initWatchdog = Runnable {
        if (ttsInitFinish || fallbackInit) return@Runnable
        val engineLabel = currentEngineLabel()
        AppLog.put("TTS 引擎初始化超时（8s）：$engineLabel")
        toastOnUi("引擎 $engineLabel 初始化失败已回退默认")
        startFallbackInit()
    }

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
        mainHandler.removeCallbacks(initWatchdog)
        clearTTS()
    }

    private fun currentEngineLabel(): String {
        return ReadAloud.currentRoute.speakerName.ifBlank {
            ReadAloud.currentRoute.engineValue.ifBlank { "系统默认" }
        }
    }

    @Synchronized
    private fun initTts(forceDefault: Boolean = false) {
        ttsInitFinish = false
        initGeneration++
        // 清理迟到看门狗，本轮重新判定
        mainHandler.removeCallbacks(initWatchdog)
        val route = SpeechRoute.resolveSpeechRoute(ReadAloud.ttsEngine)
        // AD-03：回退初始化（单次）强制默认引擎，不挂看门狗
        val engine = if (forceDefault || fallbackInit) {
            ""
        } else {
            route.engineValue
        }
        LogUtils.d(TAG, "initTts engine:$engine generation:$initGeneration forceDefault:$forceDefault")
        val created = if (engine.isBlank()) {
            TextToSpeech(this, this)
        } else {
            TextToSpeech(this, this, engine)
        }
        textToSpeech = created
        if (!forceDefault && !fallbackInit) {
            // 看门狗持 init 代际，迟到触发按代际失效（onDestroy/重入防护）
            val generation = initGeneration
            mainHandler.postDelayed({
                if (generation == initGeneration && !ttsInitFinish && !fallbackInit) {
                    initWatchdog.run()
                }
            }, WATCHDOG_TIMEOUT_MS)
        }
        upSpeechRate()
    }

    @Synchronized
    fun clearTTS() {
        mainHandler.removeCallbacks(initWatchdog)
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
                // init 成功：撤看门狗，应用每引擎独立参数（AD-05）
                mainHandler.removeCallbacks(initWatchdog)
                applyEngineParams(it)
                play()
            }
        } else {
            // onInit 失败：降级默认引擎（单次）或暂停（AD-03 终止条件）
            handleInitFailure()
        }
    }

    /**
     * init 失败/超时统一降级入口（AD-03）：
     * ①回退目标已是默认引擎→不再重建（防死循环），暂停朗读+通知终态；
     * ②否则 clearTTS 后回退默认引擎重建（单次不挂看门狗）。
     */
    private fun handleInitFailure() {
        mainHandler.removeCallbacks(initWatchdog)
        if (fallbackInit) {
            // 终止条件：回退目标已是默认引擎仍失败→暂停+通知（不无限循环）
            toastOnUi("TTS 引擎初始化失败，已暂停朗读")
            AppLog.put("TTS 引擎初始化失败（含回退），已暂停朗读", toast = true)
            pauseReadAloud()
            return
        }
        fallbackInit = true
        val engineLabel = currentEngineLabel()
        toastOnUi("引擎 $engineLabel 初始化失败已回退默认")
        AppLog.put("TTS 引擎初始化失败已回退默认：$engineLabel", toast = true)
        clearTTS()
        kotlin.runCatching {
            initTts(forceDefault = true)
        }.onFailure {
            AppLog.put("${getString(R.string.tts_init_failed)}\n$it", it, true)
        }
    }

    /**
     * 应用每引擎独立参数（AD-05）：语速/音调/音量，键=engineValue
     */
    private fun applyEngineParams(tts: TextToSpeech) {
        val route = SpeechRoute.resolveSpeechRoute(ReadAloud.ttsEngine)
        if (route.engineValue.isBlank()) return
        val params = TtsEngineParamsStore.get(route.engineValue)
        kotlin.runCatching {
            if (params.speechRate != 1.0f) tts.setSpeechRate(params.speechRate)
            if (params.pitch != 1.0f) tts.setPitch(params.pitch)
            if (params.volume != 1.0f) tts.setVolume(params.volume, params.volume)
        }
    }

    override fun onReInitTts() {
        // AD-02：同服务类型切换，引擎内重建（fallback 状态重置，新引擎重新挂看门狗）
        fallbackInit = false
        clearTTS()
        initTts()
    }

    @Synchronized
    override fun play() {
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
                        AppLog.put("tts朗读出错:$text")
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

    /**
     * 朗读监听
     * 段游标推进闸：仅 onDone/onError 推进；onStop 不推进（pause/stop/seek 流程已处理状态，防双推进）
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
                    pageIndex++
                    ReadBook.moveToNextPage()
                }
                upTtsProgress(readAloudNumber + 1)
            }
        }

        override fun onDone(s: String) {
            LogUtils.d(TAG, "onDone utteranceId:$s")
            // 段落停顿静音项不推进段落 (R7.1)
            if (s.contains("pause")) {
                return
            }
            nextParagraph()
        }

        override fun onStop(utteranceId: String?, interrupted: Boolean) {
            // 三路推进闸（AD-09）：onStop 不推进游标——pause/stop/seek 流程已处理状态
            LogUtils.d(TAG, "onStop utteranceId:$utteranceId interrupted:$interrupted")
        }

        override fun onRangeStart(utteranceId: String?, start: Int, end: Int, frame: Int) {
            super.onRangeStart(utteranceId, start, end, frame)
            val msg =
                "onRangeStart nowSpeak:$nowSpeak pageIndex:$pageIndex utteranceId:$utteranceId start:$start end:$end frame:$frame"
            LogUtils.d(TAG, msg)
            textChapter?.let {
                if (pageIndex + 1 < it.pageSize
                    && readAloudNumber + start > it.getReadLength(pageIndex + 1)
                ) {
                    pageIndex++
                    ReadBook.moveToNextPage()
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

    companion object {
        private const val WATCHDOG_TIMEOUT_MS = 8000L

        /** 外部触发引擎内重建（AD-02 reInitTts IntentAction 分发入口） */
        fun reInitTts(context: Context) {
            val intent = Intent(context, TTSReadAloudService::class.java)
            intent.action = IntentAction.reInitTts
            context.startForegroundServiceCompat(intent)
        }
    }

}
