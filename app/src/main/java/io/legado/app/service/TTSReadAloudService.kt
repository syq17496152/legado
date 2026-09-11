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
import io.legado.app.help.readaloud.casting.CastingProsody
import io.legado.app.help.readaloud.casting.CastingTag
import io.legado.app.help.readaloud.casting.CastingRuleSet
import io.legado.app.help.readaloud.casting.SystemEngineSource
import io.legado.app.help.readaloud.casting.TtsCastingStore
import io.legado.app.help.readaloud.casting.TtsMultiRoleDiagnostics
import io.legado.app.help.readaloud.casting.TtsTagSplitter
import io.legado.app.help.readaloud.casting.TtsVoiceRef
import io.legado.app.help.readaloud.casting.UtteranceProgressSink
import io.legado.app.help.readaloud.speech.SpeechRoute
import io.legado.app.help.readaloud.speech.TtsEngineParamsStore
import io.legado.app.model.ReadAloud
import io.legado.app.model.ReadBook
import io.legado.app.utils.LogUtils
import io.legado.app.utils.servicePendingIntent
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.delay
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume

/**
 * 本地朗读（AD-03/AD-05/AD-09）
 * init 按路由包名构造 + 8s 超时看门狗 + 失败降级默认引擎明示 + 每引擎独立参数
 * 多人模式（AD-09 期1）：选角模板分段 → 逐段 onDone 驱动（suspend utterance）
 */
class TTSReadAloudService : BaseReadAloudService() {

    companion object {
        private const val WATCHDOG_TIMEOUT_MS = 8000L
    }

    private var textToSpeech: TextToSpeech? = null
    private var ttsInitFinish = false
    private val ttsUtteranceListener = TTSUtteranceListener()
    private var speakJob: Coroutine<*>? = null
    private val TAG = "TTSReadAloudService"

    /** 多角色 utterance 完成信号分发表（P1-9 监听器归一）：key=utteranceId，服务级唯一监听器按 id 回收 */
    private val utteranceSink = UtteranceSink()

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
        handleInitFailure()
    }

    // 看门狗包装 Runnable 持有（修复：postDelayed 挂的是捕获代际的包装 lambda，
    // removeCallbacks(initWatchdog) 移除的是裸对象永远移不掉 → 销毁后 8s 仍触发重建引擎）
    private var initWatchdogRunnable: Runnable? = null

    private fun removeWatchdog() {
        initWatchdogRunnable?.let { mainHandler.removeCallbacks(it) }
        initWatchdogRunnable = null
    }

    override fun onCreate() {
        super.onCreate()
        // 内置选角模板幂等导入（AD-09：DefaultData 同款 assets 链，builtin 冲突跳过）
        execute(executeContext = kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                val json = assets.open("defaultData/tts/castingTemplates.json")
                    .bufferedReader().use { it.readText() }
                TtsCastingStore.importBuiltinTemplates(json)
            }.onFailure {
                AppLog.put("内置选角模板导入失败：${it.message}", it)
            }
        }
        kotlin.runCatching {
            initTts()
        }.onFailure {
            AppLog.put("${getString(R.string.tts_init_failed)}\n$it", it, true)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        removeWatchdog()
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
        removeWatchdog()
        val route = SpeechRoute.resolveSpeechRoute(ReadAloud.ttsEngine)
        // AD-03：回退初始化（单次）强制默认引擎，不挂看门狗
        val engine = if (forceDefault || fallbackInit) {
            ""
        } else {
            route.engineValue
        }
        LogUtils.d(TAG, "initTts engine:$engine generation:$initGeneration forceDefault:$forceDefault")
        // P1-10 修复：onInit 携带发起本轮 init 的代际，迟到回调按代际失效
        // （否则旧引擎迟到的 SUCCESS 会把新引擎 ttsInitFinish 置 true，看门狗条件恒假永不降级）
        val generation = initGeneration
        val initListener = TextToSpeech.OnInitListener { status -> onInit(status, generation) }
        val created = if (engine.isBlank()) {
            TextToSpeech(this, initListener)
        } else {
            TextToSpeech(this, initListener, engine)
        }
        textToSpeech = created
        if (!forceDefault && !fallbackInit) {
            // 看门狗持 init 代际，迟到触发按代际失效（onDestroy/重入防护）；
            // 包装 Runnable 存引用，removeWatchdog 才移除得掉（P1-11）
            val watchdogGeneration = generation
            val watchdogRunnable = Runnable {
                if (watchdogGeneration == initGeneration && !ttsInitFinish && !fallbackInit) {
                    initWatchdog.run()
                }
            }
            initWatchdogRunnable = watchdogRunnable
            mainHandler.postDelayed(watchdogRunnable, WATCHDOG_TIMEOUT_MS)
        }
        upSpeechRate()
    }

    @Synchronized
    fun clearTTS() {
        removeWatchdog()
        // 引擎重建/清理：多角色挂起续体全部回收（防丢回调挂死，句级超时外第二道闸）
        utteranceSink.clearAll()
        textToSpeech?.runCatching {
            stop()
            shutdown()
        }
        textToSpeech = null
        ttsInitFinish = false
    }

    private fun onInit(status: Int, generation: Int) {
        // P1-10：非本轮代际的迟到回调直接失效（防旧引擎回调污染新引擎初始化状态）
        if (generation != initGeneration) {
            LogUtils.d(TAG, "onInit 迟到回调失效 generation:$generation current:$initGeneration")
            return
        }
        // TtsTrace 真机联调：init 回调结果（成功才走 play，失败走降级链）
        AppLog.putDebugWithTag(
            AppLog.TAG_TTS_TRACE,
            "onInit status=$status label=${currentEngineLabel()} fallbackInit=$fallbackInit",
            level = AppLog.Level.INFO
        )
        if (status == TextToSpeech.SUCCESS) {
            textToSpeech?.let {
                it.setOnUtteranceProgressListener(ttsUtteranceListener)
                ttsInitFinish = true
                // init 成功：撤看门狗，应用每引擎独立参数（AD-05）
                removeWatchdog()
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
        removeWatchdog()
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
     * 应用每引擎独立参数（AD-05）：语速/音调/音量，键=engineValue（TtsEngineParamsStore 承载）
     */
    private fun applyEngineParams(tts: TextToSpeech) {
        val route = SpeechRoute.resolveSpeechRoute(ReadAloud.ttsEngine)
        if (route.engineValue.isBlank()) return
        val params = TtsEngineParamsStore.get(route.engineValue)
        kotlin.runCatching {
            if (params.speechRate != 1.0f) tts.setSpeechRate(params.speechRate)
            if (params.pitch != 1.0f) tts.setPitch(params.pitch)
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
        // 多人模式：逐段 onDone 驱动（AD-09 期1）；否则原整章预入队路径
        speakJob = execute {
            val bookKey = ReadBook.book?.bookUrl.orEmpty()
            val ruleSet = runCatching {
                TtsCastingStore.resolveActiveRuleSet(bookKey)
            }.getOrNull()
            // TtsTrace 真机联调：multi/legacy 路径判定关键证据（模板激活状态+规则数）
            AppLog.putDebugWithTag(
                AppLog.TAG_TTS_TRACE,
                "play 路径判定 bookKey=${bookKey.takeLast(24)} ruleSet=${ruleSet != null} rules=${ruleSet?.rules?.size ?: 0} → ${if (ruleSet != null && ruleSet.rules.isNotEmpty()) "multiRole" else "legacy"}",
                level = AppLog.Level.INFO
            )
            if (ruleSet != null && ruleSet.rules.isNotEmpty()) {
                speakMultiRole(ruleSet)
            } else {
                speakLegacyLoop()
            }
        }.onError {
            AppLog.put("tts朗读出错\n${it.localizedMessage}", it, true)
        }
    }

    /** 原整章预入队朗读路径（单声模板/多人未启用时走此路径，行为与重构前一致） */
    private suspend fun speakLegacyLoop() {
        LogUtils.d(TAG, "朗读列表大小 ${contentList.size}")
        LogUtils.d(TAG, "朗读页数 ${textChapter?.pageSize}")
        // F8/2.26：legacy 路径=多人诊断态退出（防残留提示条）
        TtsMultiRoleDiagnostics.stop()
        // TtsTrace 真机联调：legacy 路径入口（整章预入队）
        AppLog.putDebugWithTag(
            AppLog.TAG_TTS_TRACE,
            "legacy 入队 单元=${contentList.size} nowSpeak=$nowSpeak 引擎=${ReadAloud.currentRoute.engineValue.ifBlank { "系统默认" }}",
            level = AppLog.Level.INFO
        )
        val tts = textToSpeech ?: throw NoStackTraceException("tts is null")
        val contentList = contentList
        var isAddedText = false
        for (i in nowSpeak until contentList.size) {
            currentCoroutineContext().ensureActive()
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
                    return
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
    }

    /**
     * 多人模式逐段驱动循环（AD-09 期1）：
     * TtsTagSplitter 分段（五元组）→ 模板 resolve(tag) → SystemEngineSource.utterance 逐句挂起。
     * 进度账=精确字符账：段完成 readAloudNumber += seg.length；段界 +=1（换行）并做翻页判定。
     */
    private suspend fun speakMultiRole(ruleSet: CastingRuleSet) {
        val bookKey = ReadBook.book?.bookUrl.orEmpty()
        // 监听器所有权归一（P1-9）：utterance 完成信号经 sink 分发表回收，不再逐句覆盖服务级监听器
        val source = SystemEngineSource(
            this,
            { textToSpeech },
            { ttsInitFinish },
            ReadAloud.currentRoute.engineValue,
            utteranceSink
        )
        val textChapter = textChapter ?: return
        val contentList = contentList
        // TtsTrace 真机联调：多角色章开始（章索引/段落数/规则数/当前引擎）
        AppLog.putDebugWithTag(
            AppLog.TAG_TTS_TRACE,
            "multiRole 章开始 ch=${textChapter.chapter.index} 段落=${contentList.size} 规则=${ruleSet.rules.size} 引擎=${ReadAloud.currentRoute.engineValue.ifBlank { "系统默认" }} nowSpeak=$nowSpeak",
            level = AppLog.Level.INFO
        )
        // F8/2.26+2.27：切分诊断上抛面板（章开始重置；引号字符扫描区分"无对话/未命中"）
        val chapterHasQuotes = contentList.any { paragraph ->
            paragraph.any { it == '“' || it == '”' || it == '"' || it == '「' || it == '」' || it == '『' || it == '』' }
        }
        TtsMultiRoleDiagnostics.reset(textChapter.chapter.index, chapterHasQuotes)
        for (p in nowSpeak until contentList.size) {
            currentCoroutineContext().ensureActive()
            val paragraphStart = if (p == nowSpeak) paragraphStartPos else 0
            val paragraph = contentList[p]
            if (paragraph.isBlank() || paragraph.matches(AppPattern.notReadAloudRegex)) {
                readAloudNumber += paragraph.length + 1 - paragraphStart
                paragraphStartPos = 0
                continue
            }
            val segments = TtsTagSplitter.splitParagraph(p, paragraph, ruleSet.rules)
                .filter { it.offsetInParagraph + it.length > paragraphStart }
            for (segment in segments) {
                currentCoroutineContext().ensureActive()
                var text = paragraph.substring(
                    segment.offsetInParagraph,
                    segment.offsetInParagraph + segment.length
                )
                if (segment.offsetInParagraph < paragraphStart) {
                    text = text.substring(paragraphStart - segment.offsetInParagraph)
                }
                // 对白段剥离引号（正文显示保留）；纯静默段跳过
                val speechText = if (segment.tag != CastingTag.NARRATION) {
                    TtsTagSplitter.stripQuotesForSpeech(text)
                } else {
                    text
                }
                if (speechText.replace(AppPattern.notReadAloudRegex, "").isBlank()) {
                    // 纯静默段跳过但须计账（P2：与空白段路径口径一致，防进度账少计）
                    readAloudNumber += text.length
                    continue
                }
                val sourceRoute = runCatching {
                    TtsCastingStore.resolveSourceForTag(bookKey, segment.tag, ruleSet)
                }.getOrNull() ?: SpeechRoute(engineType = SpeechRoute.ENGINE_DEFAULT)
                // 通道门禁（P1-15 门禁版）：模板声源指向 HTTP/脚本通道时本服务无法合成，
                // 明示降级为引擎默认音（静默降级会表现为"角色不生效"，须留痕可排查）
                val voiceRef = if (sourceRoute.engineType != SpeechRoute.ENGINE_SYSTEM &&
                    sourceRoute.engineType != SpeechRoute.ENGINE_DEFAULT
                ) {
                    AppLog.put("多人听书：模板声源指向非系统通道（${sourceRoute.engineType}），该角色降级为引擎默认音")
                    null
                } else if (sourceRoute.speakerName.isNotBlank()) {
                    TtsVoiceRef(
                        voiceId = sourceRoute.toneID,
                        displayName = sourceRoute.speakerName,
                        channel = TtsVoiceRef.CHANNEL_SYSTEM
                    )
                } else {
                    null
                }
                // 韵律送达（P1-14）：取命中规则配置的韵律（0=跟随全局），不再恒传默认全零
                val ruleProsody = ruleSet.rules.firstOrNull { it.tag == segment.tag }?.prosody
                    ?: CastingProsody()
                val utteranceId = "${AppConst.APP_TAG}mr_${p}_${segment.offsetInParagraph}"
                // F8/2.26+2.27：逐段诊断计数（voiceMiss=该段将用引擎默认音）
                TtsMultiRoleDiagnostics.countSegment(
                    isNarration = segment.tag == CastingTag.NARRATION,
                    voiceMiss = voiceRef == null || voiceRef.voiceId.isBlank()
                )
                // TtsTrace 真机联调：逐段合成证据（分段来源 tag/声源/文本长度）
                AppLog.putDebugWithTag(
                    AppLog.TAG_TTS_TRACE,
                    "multiRole 段 p=$p off=${segment.offsetInParagraph} len=${segment.length} tag=${segment.tag} 声源=${sourceRoute.engineType}:${sourceRoute.engineValue}:${voiceRef?.displayName ?: "-"} prosody=${ruleProsody.rate}/${ruleProsody.pitch}/${ruleProsody.volume} utteranceId=$utteranceId",
                    level = AppLog.Level.INFO
                )
                val completed = source.utterance(
                    speechText,
                    voiceRef,
                    ruleProsody,
                    utteranceId
                )
                if (!completed) {
                    // onStop（pause/stop/seek 已接管状态），静默退出循环
                    AppLog.putDebugWithTag(
                        AppLog.TAG_TTS_TRACE,
                        "multiRole 段中断（onStop） utteranceId=$utteranceId p=$p",
                        level = AppLog.Level.INFO
                    )
                    return
                }
                readAloudNumber += text.length
                // 翻页判定（对齐原 onStart/onRangeStart 语义）
                textChapter?.let {
                    if (pageIndex + 1 < it.pageSize &&
                        readAloudNumber + 1 > it.getReadLength(pageIndex + 1)
                    ) {
                        pageIndex++
                        ReadBook.moveToNextPage()
                    }
                    upTtsProgress(readAloudNumber + 1)
                }
            }
            // 段界换行计数
            readAloudNumber += 1 - paragraphStartPos
            paragraphStartPos = 0
            // 段间停顿（§1.8-F C10：逐段驱动下的插入位置）
            val pauseMs = AppConfig.ttsParagraphPauseMs
            if (pauseMs > 0 && p < contentList.lastIndex) {
                delay(pauseMs.toLong())
            }
        }
        // 章末
        AppLog.putDebugWithTag(
            AppLog.TAG_TTS_TRACE,
            "multiRole 章末 ch=${textChapter.chapter.index} timerMode=${AppConfig.ttsTimerMode}",
            level = AppLog.Level.INFO
        )
        delay(500)
        if (!checkTimerAtChapterEnd()) {
            nextChapter()
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
     * 多角色 utterance 分发表实现：
     * register/unregister 由 SystemEngineSource 调用；dispatch 由 TTSUtteranceListener 按回调路由，
     * 分发命中=多角色段（continuation 推进），未命中=legacy utterance（走 legacy 游标推进）
     */
    private inner class UtteranceSink : UtteranceProgressSink {
        private val pending = ConcurrentHashMap<String, CancellableContinuation<Boolean>>()

        override fun registerUtterance(utteranceId: String, cont: CancellableContinuation<Boolean>) {
            pending[utteranceId] = cont
        }

        override fun unregisterUtterance(utteranceId: String) {
            pending.remove(utteranceId)
        }

        fun contains(utteranceId: String): Boolean = pending.containsKey(utteranceId)

        /** 分发完成信号；返回 false=非多角色 utterance */
        fun dispatch(utteranceId: String, completed: Boolean): Boolean {
            val cont = pending.remove(utteranceId) ?: return false
            if (cont.isActive) cont.resume(completed)
            return true
        }

        /** 引擎重建/服务清理：全部挂起续体按未完成回收（防 clearTTS 后丢回调挂死，超时兜底外第二道闸） */
        fun clearAll() {
            pending.keys.toList().forEach { id -> dispatch(id, false) }
        }
    }

    /**
     * 朗读监听（服务级唯一监听器，全生命周期仅 onInit 注册一次）
     * 段游标推进闸：仅 onDone/onError 推进；onStop 不推进（pause/stop/seek 流程已处理状态，防双推进）
     * 多角色 utterance（mr_ 前缀，经 sink 注册）按 id 分发回收，不走 legacy 游标
     */
    private inner class TTSUtteranceListener : UtteranceProgressListener() {

        private val TAG = "TTSUtteranceListener"

        override fun onStart(s: String) {
            // 多角色段：翻页判定由 speakMultiRole 循环内处理，legacy onStart 逻辑不适用
            if (utteranceSink.contains(s)) return
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
            // 多角色段：信号经分发表回收（完成=true 推进到下一段）
            if (utteranceSink.dispatch(s, true)) return
            // 段落停顿静音项不推进段落 (R7.1)
            if (s.contains("pause")) {
                return
            }
            nextParagraph()
        }

        override fun onStop(utteranceId: String?, interrupted: Boolean) {
            // 三路推进闸（AD-09）：onStop 不推进游标——pause/stop/seek 流程已处理状态
            LogUtils.d(TAG, "onStop utteranceId:$utteranceId interrupted:$interrupted")
            // 多角色段：回收挂起续体（completed=false → speakMultiRole 静默退出循环）
            utteranceId?.let { utteranceSink.dispatch(it, false) }
        }

        override fun onRangeStart(utteranceId: String?, start: Int, end: Int, frame: Int) {
            super.onRangeStart(utteranceId, start, end, frame)
            if (utteranceId != null && utteranceSink.contains(utteranceId)) return
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
            // 多角色段：引擎错误按完成推进（与 legacy 推进口径一致，防单段错误卡死整章）
            if (utteranceId != null && utteranceSink.dispatch(utteranceId, true)) return
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
            if (utteranceSink.dispatch(s, true)) return
            nextParagraph()
        }

    }

    override fun aloudServicePendingIntent(actionStr: String): PendingIntent? {
        return servicePendingIntent<TTSReadAloudService>(actionStr)
    }

}
