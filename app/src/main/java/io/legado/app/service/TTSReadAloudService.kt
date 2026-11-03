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
import io.legado.app.help.book.characterBookKey
import io.legado.app.help.config.AppConfig
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.help.readaloud.ReadAloudRoleBadge
import io.legado.app.help.readaloud.bgm.ReadAloudBgmPlayer
import io.legado.app.help.readaloud.casting.CharacterVoiceAssignService
import io.legado.app.help.readaloud.casting.CastingProsody
import io.legado.app.help.readaloud.casting.CastingTag
import io.legado.app.help.readaloud.casting.CastingRuleSet
import io.legado.app.help.readaloud.casting.SystemEngineSource
import io.legado.app.help.readaloud.casting.TtsCastingStore
import io.legado.app.help.readaloud.casting.TtsMultiRoleDiagnostics
import io.legado.app.help.readaloud.casting.TtsTagSplitter
import io.legado.app.help.readaloud.casting.TtsVoiceRef
import io.legado.app.help.readaloud.casting.UtteranceProgressSink
import io.legado.app.help.readaloud.role.BookAliasStore
import io.legado.app.help.readaloud.role.OfflineCharacterDiscovery
import io.legado.app.help.readaloud.role.OfflineRoleResolver
import io.legado.app.help.readaloud.speech.SpeechRoute
import io.legado.app.help.readaloud.speech.TtsEngineParamsStore
import io.legado.app.help.readaloud.TtsEngineGuard
import io.legado.app.help.tts.builtin.BuiltinTtsRuntime
import io.legado.app.model.ReadAloud
import io.legado.app.model.ReadBook
import io.legado.app.utils.LogUtils
import io.legado.app.utils.servicePendingIntent
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume

/**
 * 角色绑定口径（纯函数可单测）：`BookCharacter.bookUrl` / cast_role / 离线归因缓存一律按
 * [characterBookKey]（`work:作者/书名`）存档；只有模板偏好键沿用 raw bookUrl（与既有 UI 写入口一致）。
 * 两种键混用 ⇒ 角色库永远查不到（2026-10-31 模拟器铁证）。
 */
internal fun readAloudRoleBookKey(book: io.legado.app.data.entities.Book?): String =
    book?.characterBookKey().orEmpty()

/**
 * 角色徽章标签（T8.7，纯函数可单测）：角色名优先；无角色时旁白段显示"旁白"，其余留空（不猜）。
 */
internal fun readAloudRoleLabel(tag: String, characterName: String): String =
    characterName.ifBlank { if (tag == "narration") "旁白" else "" }

/**
 * 旁白音色选择（纯函数可单测）：命中本书 fixed_roles.narrator 的显示名 → 否则回落音色表首个。
 * 返回 (`voiceName`=`id|显示名`, `displayName`)；表为空返回 null。
 */
internal fun pickNarratorVoice(
    voices: List<Pair<String, String>>,
    narrator: String?
): Pair<String, String>? {
    if (voices.isEmpty()) return null
    val name = narrator.orEmpty()
    return if (name.isNotBlank()) voices.firstOrNull { it.second == name } ?: voices.first()
    else voices.first()
}

/**
 * 未识别对白的音色稳定策略（纯函数可单测）：
 * 角色归因失败时若沿用旁白音色，同一段对话里会出现"角色音 → 旁白音"的跳变
 * （2026-11-03 用户反馈「一会主角是这个音色，一会又变成另一个音色」）。
 * 对白/独白段只要已有上一句角色音色就沿用它；旁白段与已识别段不受影响。
 */
internal fun stableDialogueVoice(
    isNarration: Boolean,
    hasRole: Boolean,
    lastDialogueVoice: TtsVoiceRef?,
    current: TtsVoiceRef?
): TtsVoiceRef? =
    if (!isNarration && !hasRole && lastDialogueVoice != null) lastDialogueVoice else current

/**
 * 本地朗读（AD-03/AD-05/AD-09）
 * init 按路由包名构造 + 8s 超时看门狗 + 失败降级默认引擎明示 + 每引擎独立参数
 * 多人模式（AD-09 期1）：选角模板分段 → 逐段 onDone 驱动（suspend utterance）
 */
class TTSReadAloudService : BaseReadAloudService() {

    companion object {
        private const val WATCHDOG_TIMEOUT_MS = 8000L

        /**
         * 初始化引擎决策（5.6 可单测纯函数）：返回 `engine`（传给 TextToSpeech，空=系统默认）与
         * `notice`（需提示给用户的回退原因，null=无需提示）。
         *
         * 为什么要单独抽出来：Android 对不存在的引擎包仍回调 SUCCESS（真机 2026-11-03 实证），
         * 若不在创建前探测，用户只会看到"音色变了"，拿不到可读原因。
         */
        internal fun resolveInitEngine(
            engineType: String,
            engineValue: String,
            packageInstalled: Boolean,
            forceDefault: Boolean,
            fallbackInit: Boolean,
            label: String
        ): Pair<String, String?> {
            if (forceDefault || fallbackInit) return "" to null
            if (TtsEngineGuard.needsFallback(engineType, engineValue, packageInstalled)) {
                // speakerName 为空时 label 会回落「系统默认」，提示改用目标包名，避免"「系统默认」未安装"自相矛盾
                val display = label.takeIf { it.isNotBlank() && it != "系统默认" } ?: engineValue
                return "" to TtsEngineGuard.fallbackMessage(display)
            }
            return engineValue to null
        }
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

    /**
     * 预取作用域（AD-09 ②）：**与当前段播放并行**起跑（由 [startedUtteranceId] 对齐播放起点）。
     * 2026-11-03 真机取证：旧实现在 `utterance()` 返回后才预取下一段 ⇒ 每段说完再等 1~2s 合成，
     * 用户感知就是"TTS 朗读一顿一顿"。每段合成均 1.9s、音频均 2.8s，流水线化后下一段可零等待衔接。
     */
    private val prefetchScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * 多角色段"已开始播放"的 utteranceId（由 [TTSUtteranceListener.onStart] 写入）。
     * 预取据此等到当前段真正出声后再合成下一段：既不抢占合成锁拖慢当前段起播，又能与播放并行。
     */
    @Volatile
    private var startedUtteranceId = ""

    /** 最近一个对白/独白段的角色音色（稳定性锚点）：归因失败的对白沿用它，避免切回旁白音色 */
    @Volatile
    private var lastDialogueVoiceRef: TtsVoiceRef? = null

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
        prefetchScope.cancel()
        clearTTS()
        ReadAloudRoleBadge.clear()
        ReadAloudBgmPlayer.stop()
    }

    private fun currentEngineLabel(): String {
        return ReadAloud.currentRoute.speakerName.ifBlank {
            ReadAloud.currentRoute.engineValue.ifBlank { "系统默认" }
        }
    }

    /** 目标引擎包是否仍安装（5.6 回退探测；探测失败按"未安装"处理更安全） */
    private fun isPackageInstalled(pkg: String): Boolean =
        pkg.isNotBlank() && runCatching { packageManager.getPackageInfo(pkg, 0) }.isSuccess

    @Synchronized
    private fun initTts(forceDefault: Boolean = false) {
        ttsInitFinish = false
        initGeneration++
        // 清理迟到看门狗，本轮重新判定
        removeWatchdog()
        val route = SpeechRoute.resolveSpeechRoute(ReadAloud.ttsEngine)
        // AD-03 + 5.6：回退（单次）强制默认引擎；目标引擎包已卸载时提前回退并给出可读原因
        val (engine, fallbackNotice) = resolveInitEngine(
            engineType = route.engineType,
            engineValue = route.engineValue,
            packageInstalled = isPackageInstalled(route.engineValue),
            forceDefault = forceDefault,
            fallbackInit = fallbackInit,
            label = currentEngineLabel()
        )
        fallbackNotice?.let { AppLog.put(it, toast = true) }
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
        val initFinished = ttsInitFinish
        // 焦点只在初始化完成后申请（与原分支顺序一致，避免无谓抢占）
        val focusGranted = initFinished && requestFocus()
        val blockReason = ReadAloudStartGuard.blockReason(
            initFinished = initFinished,
            focusGranted = focusGranted,
            contentEmpty = contentList.isEmpty()
        )
        if (blockReason.isNotEmpty()) {
            // IF-14-M：起播被前置条件静默拦截时**必须留痕**（否则长时朗读表现为「无痕断流」）
            AppLog.putDebugWithTag(
                AppLog.TAG_TTS_TRACE,
                "play 阻断 reason=$blockReason nowSpeak=$nowSpeak 单元=${contentList.size}" +
                        " chapter=${textChapter?.chapter?.index}",
                level = AppLog.Level.INFO
            )
            if (blockReason == ReadAloudStartGuard.REASON_EMPTY_LIST) {
                AppLog.putDebug("朗读列表为空")
                ReadBook.readAloud()
            }
            return
        }
        super.play()
        MediaHelp.playSilentSound(this@TTSReadAloudService)
        // T8.4：配乐开关打开时与朗读并行混音（关掉即无声；无可用配乐则自动跳过）
        ReadAloudBgmPlayer.start(this)
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
                // 单声路径没有角色概念 ⇒ 徽章必须清空（防残留上一轮多角色的角色名）
                ReadAloudRoleBadge.clear()
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
                // P1/B1-③：段中触发时对齐到句首（默认关；返回值恒 <= 原起点，且 coerceIn 防越界）
                val startPos = if (alignSentenceStart) {
                    ReadAloudSentenceAligner.alignToSentenceStart(text, paragraphStartPos)
                } else {
                    paragraphStartPos
                }
                text = text.substring(startPos.coerceIn(0, text.length))
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
        // 角色/绑定口径必须用 BookIdentity 键（work:作者/书名）：本服务给 TtsCastingStore 用 raw bookUrl（与既有 UI 写入口一致），
        // 但 BookCharacter.bookUrl / cast_role 绑定 / OfflineRoleResolver 缓存全部按 characterBookKey 存档
        // ⇒ 混用会让角色库永远查不到（2026-10-31 模拟器铁证：dialogue 段 tag 恒为 dialogue，ai: 从未命中）
        val roleBookKey = readAloudRoleBookKey(ReadBook.book)
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
        // AD-09 钩子①：章开始准备离线角色归因（缓存优先；失败不阻断播放）
        val builtinActive = BuiltinTtsRuntime.isReady() &&
                BuiltinTtsRuntime.isActiveEngine(this, ReadAloud.currentRoute.engineValue)
        // T3.1/T3.3 量化埋点：新一轮朗读重置段间隔计时与首段预热标记
        ReadAloudTrace.reset()
        // 跨章复用同一段落/偏移时 utteranceId 会重复 ⇒ 旧章残留的播放起点不得让新章预取提前起跑
        startedUtteranceId = ""
        // 音色稳定性锚点按章重置：上一章的角色音色不得带进新章的对白
        lastDialogueVoiceRef = null
        val chapterIndex = textChapter.chapter.index
        if (builtinActive) {
            runCatching {
                // AD-16：章开始自动累积角色（离线 NER 模型识别；fail-open，不阻断播放）。
                // 先发现再取角色快照，保证本章归因能用到新出场角色。
                var rolesChanged = false
                if (OfflineCharacterDiscovery.shouldDiscoverAtChapterStart(builtinActive, roleBookKey, contentList)) {
                    val startedAt = System.currentTimeMillis()
                    val discovery = OfflineCharacterDiscovery.ensureCharacters(roleBookKey, contentList)
                    rolesChanged = discovery.changed > 0
                    // 正式诊断日志（保留）：章开始 NER 发现耗时与产出，便于定位低配设备卡顿
                    AppLog.putDebugWithTag(
                        AppLog.TAG_TTS_TRACE,
                        "roleDiscovery ch=$chapterIndex cost=${System.currentTimeMillis() - startedAt}ms " +
                            "added=${discovery.added} pruned=${discovery.pruned}",
                        level = AppLog.Level.INFO
                    )
                    if (rolesChanged) {
                        AppLog.put(
                            "离线角色累积（ch=$chapterIndex）：新增 ${discovery.added} / 清理碎片 ${discovery.pruned}"
                        )
                        // 角色集合变化 ⇒ 章节归因缓存与内存结果都不可复用（缓存键不含角色集）
                        OfflineRoleResolver.clearMemory(roleBookKey)
                    }
                }
                val roleCharacters = CharacterVoiceAssignService.roleCharacters(roleBookKey)
                if (roleCharacters.isNotEmpty()) {
                    OfflineRoleResolver.ensure(
                        bookKey = roleBookKey,
                        chapterIndex = chapterIndex,
                        chapterTitle = textChapter.chapter.title,
                        paragraphs = contentList,
                        characters = roleCharacters,
                        aliasStore = BookAliasStore(BuiltinTtsRuntime.paths().rulesRoot),
                        forceRecompute = rolesChanged
                    )
                }
            }.onFailure { AppLog.put("离线角色归因准备失败：${it.message}") }
        }
        // 音色泄漏防护（P1，2026-10-31 铁证）：SystemEngineSource.applyVoice 只在 voiceId 非空时 setVoice，
        // 没有 toneID 的旁白/对白会沿用上一段角色音色（实测旁白段 voice=b6_m_youth=张三音色）。
        // 内置引擎激活时给"未绑定音色"的段补一个显式 voiceRef：旁白优先取本书 fixed_roles.narrator，否则引擎首音。
        val fallbackVoiceRef = if (builtinActive) {
            runCatching {
                val narrator = BookAliasStore(BuiltinTtsRuntime.paths().rulesRoot).narratorVoice(roleBookKey)
                val voices = BuiltinTtsRuntime.voices().map { it.voiceName to it.displayName }
                pickNarratorVoice(voices, narrator)?.let { (voiceName, display) ->
                    TtsVoiceRef(voiceName, display, TtsVoiceRef.CHANNEL_SYSTEM)
                }
            }.getOrNull()
        } else null
        for (p in nowSpeak until contentList.size) {
            currentCoroutineContext().ensureActive()
            val rawParagraphStart = if (p == nowSpeak) paragraphStartPos else 0
            // P1/B1-③：段中触发对齐句首。filter(L384) 与 trim(L391-392) 与本值同基准，
            // 否则「只改 trim」会 StringIndexOutOfBoundsException、「只改 filter」会漏读段头。
            val paragraphStart = if (rawParagraphStart > 0 && alignSentenceStart) {
                val paragraphText = contentList[p]
                ReadAloudSentenceAligner
                    .alignToSentenceStart(paragraphText, rawParagraphStart)
                    .coerceIn(0, paragraphText.length)
            } else {
                rawParagraphStart
            }
            val paragraph = contentList[p]
            if (paragraph.isBlank() || paragraph.matches(AppPattern.notReadAloudRegex)) {
                readAloudNumber += paragraph.length + 1 - paragraphStart
                paragraphStartPos = 0
                continue
            }
            val segments = TtsTagSplitter.splitParagraph(p, paragraph, ruleSet.rules)
                .filter { it.offsetInParagraph + it.length > paragraphStart }
            for ((segIndex, segment) in segments.withIndex()) {
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
                // C-10：离线命中角色 → 走既有六级链的 ai: 命名空间（①角色绑定 ②本书绑定）
                val roleHit = if (builtinActive) {
                    OfflineRoleResolver.lookup(
                        roleBookKey, chapterIndex, p,
                        segment.offsetInParagraph, segment.length
                    )
                } else null
                val sourceRoute = runCatching {
                    val resolveTag = roleHit?.let { CastingTag.AI_PREFIX + it.characterName } ?: segment.tag
                    TtsCastingStore.resolveSourceForTag(
                        roleBookKey, resolveTag, ruleSet, roleHit?.characterId ?: 0L
                    )
                }.getOrNull() ?: SpeechRoute(engineType = SpeechRoute.ENGINE_DEFAULT)
                // 通道门禁（P1-15 门禁版）：模板声源指向 HTTP/脚本通道时本服务无法合成，
                // 明示降级为引擎默认音（静默降级会表现为"角色不生效"，须留痕可排查）
                // 无 toneID 的段（旁白/模板对白）必须显式兜底音色，否则沿用上一段角色音色（音色泄漏）
                var voiceRef = when {
                    sourceRoute.engineType != SpeechRoute.ENGINE_SYSTEM &&
                        sourceRoute.engineType != SpeechRoute.ENGINE_DEFAULT -> {
                        AppLog.put("多人听书：模板声源指向非系统通道（${sourceRoute.engineType}），该角色降级为引擎默认音")
                        fallbackVoiceRef
                    }
                    sourceRoute.toneID.isNotBlank() -> TtsVoiceRef(
                        voiceId = sourceRoute.toneID,
                        displayName = sourceRoute.speakerName.ifBlank { sourceRoute.toneID },
                        channel = TtsVoiceRef.CHANNEL_SYSTEM
                    )
                    else -> fallbackVoiceRef
                }
                // 稳定性（2026-11-03 用户反馈"音色乱跳"）：归因失败的对白沿用上一句角色音色，
                // 不切回旁白音——否则同一段对话会听起来"突然换人"
                voiceRef = stableDialogueVoice(
                    isNarration = segment.tag == CastingTag.NARRATION,
                    hasRole = roleHit != null,
                    lastDialogueVoice = lastDialogueVoiceRef,
                    current = voiceRef
                )
                if (segment.tag != CastingTag.NARRATION && voiceRef?.voiceId?.isNotBlank() == true) {
                    lastDialogueVoiceRef = voiceRef
                }
                // T8.7 角色徽章：把本段角色名随 utterance 透传给引擎（内置引擎通知显示「正在朗读：张三」+ 阅读页浮层）
                val roleLabel = readAloudRoleLabel(segment.tag, roleHit?.characterName.orEmpty())
                ReadAloudRoleBadge.update(roleLabel)
                if (voiceRef != null && roleLabel.isNotBlank()) {
                    voiceRef = voiceRef.copy(roleName = roleLabel)
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
                ReadAloudTrace.segment(
                    p = p,
                    offset = segment.offsetInParagraph,
                    length = segment.length,
                    tag = segment.tag.toString(),
                    sourceRoute = sourceRoute,
                    voiceDisplayName = voiceRef?.displayName,
                    prosody = ruleProsody,
                    utteranceId = utteranceId
                )
                // T3.1：起播前预热首段（首段没有"上一段"可预取）；命中缓存后首段起播不再等合成
                if (ReadAloudTrace.needsWarm(builtinActive)) {
                    runCatching {
                        BuiltinTtsRuntime.prefetch(
                            speechText, voiceRef?.voiceId.orEmpty(), (AppConfig.ttsSpeechRate + 5) / 10f
                        )
                    }
                    ReadAloudTrace.markWarmed()
                }
                // AD-09 钩子②（流水线化）：**先**起下一段预取任务（等当前段出声后再合成），再播当前段。
                // 旧实现放在 utterance 之后 ⇒ 每段之间要等 1~2s 合成（TTS 朗读卡顿的根因）。
                prefetchScope.launch {
                    runCatching {
                        ReadAloudPrefetch.prefetchNext(
                            builtinActive = builtinActive,
                            playbackStarted = { startedUtteranceId == utteranceId },
                            paragraph = paragraph,
                            segments = segments,
                            segIndex = segIndex,
                            roleBookKey = roleBookKey,
                            chapterIndex = chapterIndex,
                            paragraphIndex = p,
                            ruleSet = ruleSet,
                            fallbackVoiceRef = fallbackVoiceRef,
                            lastDialogueVoiceRef = lastDialogueVoiceRef
                        )
                    }
                }
                val gapStart = ReadAloudTrace.begin()
                val completed = source.utterance(
                    speechText,
                    voiceRef,
                    ruleProsody,
                    utteranceId
                )
                ReadAloudTrace.end(gapStart, utteranceId)
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
            // P1/B1-③：记账基准与切片基准保持一致（对齐生效时用 paragraphStart，非首段时为 0，与改造前等价）
            readAloudNumber += 1 - paragraphStart
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
        ReadAloudRoleBadge.clear()
        ReadAloudBgmPlayer.stop()
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
        ReadAloudBgmPlayer.pause()
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
            // 多角色段：记录播放起点供下一段预取对齐（不推进任何游标）
            if (utteranceSink.contains(s)) {
                startedUtteranceId = s
                return
            }
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
            // W3 / REQ-16：系统 TTS 链路的段落切换（正常完成/错误推进共用）⇒ 写段落锚点
            persistVoiceParagraphAnchor("tts-paragraph")
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
