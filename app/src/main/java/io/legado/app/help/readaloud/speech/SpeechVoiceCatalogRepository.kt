package io.legado.app.help.readaloud.speech

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import io.legado.app.constant.AppLog
import io.legado.app.data.entities.HttpTTS
import io.legado.app.lib.dialogs.SelectItem
import io.legado.app.utils.GSON
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

data class SpeechVoiceOption(
    val key: String,
    val engineType: String,
    val engineValue: String,
    val engineName: String,
    val speakerName: String,
    val toneID: String = "",
    val groupId: String = "",
    val groupName: String = "",
    val explicitSpeaker: Boolean = false
) {
    fun toRoute(emotion: SpeechEmotion? = null, source: String = SpeechRoute.SOURCE_MANUAL): SpeechRoute {
        return SpeechRoute(
            engineType = engineType,
            engineValue = engineValue,
            speakerName = speakerName,
            toneID = toneID,
            emotionName = emotion?.emotionName.orEmpty(),
            emotionTag = emotion?.emotionTag.orEmpty(),
            groupId = groupId,
            groupName = groupName,
            source = source
        )
    }
}

data class SpeechVoiceEngineGroup(
    val key: String,
    val title: String,
    val subtitle: String,
    val engineType: String,
    val engineValue: String,
    val options: List<SpeechVoiceOption>,
    val emotions: List<SpeechEmotion> = emptyList(),
    val loginKey: String = "",
    val loginUrl: String? = null,
    val warning: String = ""
)

object SpeechVoiceCatalogRepository {

    fun allGroups(
        context: Context,
        httpTtsList: List<HttpTTS>,
        includeSystem: Boolean = true
    ): List<SpeechVoiceEngineGroup> {
        return buildList {
            if (includeSystem) addAll(systemGroups(context))
            addAll(httpGroups(httpTtsList))
        }
    }

    fun httpGroups(httpTtsList: List<HttpTTS>): List<SpeechVoiceEngineGroup> {
        return httpTtsList.map { httpTts ->
            val emotions = SpeechVoiceCatalogParser.flattenEmotions(httpTts.emotionsJson)
            val speakerGroups = SpeechVoiceCatalogParser.parseSpeakerGroups(httpTts.speakersJson)
            val speakerWarning = if (httpTts.speakersJson.isNotBlank() && speakerGroups.isEmpty()) {
                "发言人 JSON 无效"
            } else {
                ""
            }
            val options = if (speakerGroups.isEmpty()) {
                listOf(
                    SpeechVoiceOption(
                        key = "http:${httpTts.id}:default",
                        engineType = SpeechRoute.ENGINE_HTTP,
                        engineValue = httpTts.id.toString(),
                        engineName = httpTts.name.ifBlank { "HTTP TTS" },
                        speakerName = httpTts.name.ifBlank { "HTTP TTS" },
                        explicitSpeaker = false
                    )
                )
            } else {
                speakerGroups.flatMap { group ->
                    group.items.map { speaker ->
                        SpeechVoiceOption(
                            key = "http:${httpTts.id}:${speaker.groupId}:${speaker.toneID}",
                            engineType = SpeechRoute.ENGINE_HTTP,
                            engineValue = httpTts.id.toString(),
                            engineName = httpTts.name.ifBlank { "HTTP TTS" },
                            speakerName = speaker.speakerName,
                            toneID = speaker.toneID,
                            groupId = speaker.groupId,
                            groupName = speaker.groupName,
                            explicitSpeaker = true
                        )
                    }
                }
            }
            val subtitle = buildList {
                val explicitCount = options.count { it.explicitSpeaker }
                if (explicitCount > 0) add("${explicitCount} 个发言人")
                if (emotions.isNotEmpty()) add("${emotions.size} 个情绪")
                if (speakerWarning.isNotBlank()) add(speakerWarning)
            }.joinToString(" · ").ifBlank { "普通 HTTP TTS" }
            SpeechVoiceEngineGroup(
                key = "http:${httpTts.id}",
                title = httpTts.name.ifBlank { "HTTP TTS" },
                subtitle = subtitle,
                engineType = SpeechRoute.ENGINE_HTTP,
                engineValue = httpTts.id.toString(),
                options = options,
                emotions = emotions,
                loginKey = httpTts.id.toString(),
                loginUrl = httpTts.loginUrl,
                warning = speakerWarning
            )
        }
    }

    fun systemGroups(context: Context): List<SpeechVoiceEngineGroup> {
        val engines = runCatching {
            val tts = TextToSpeech(context.applicationContext, null)
            try {
                tts.engines.map { it.label.toString() to it.name }
            } finally {
                tts.shutdown()
            }
        }.getOrDefault(emptyList())
        return (listOf("系统默认" to "") + engines)
            .distinctBy { it.second }
            .map { (title, value) ->
                val engineValue = GSON.toJson(SelectItem(title, value))
                val option = SpeechVoiceOption(
                    key = "system:$value",
                    engineType = SpeechRoute.ENGINE_SYSTEM,
                    engineValue = engineValue,
                    engineName = title,
                    speakerName = title,
                    explicitSpeaker = false
                )
                SpeechVoiceEngineGroup(
                    key = "system:$value",
                    title = title,
                    subtitle = if (value.isBlank()) "系统默认" else "系统 TTS",
                    engineType = SpeechRoute.ENGINE_SYSTEM,
                    engineValue = engineValue,
                    options = listOf(option)
                )
            }
    }

    // ---------------- F8/2.23：系统引擎音色枚举（本仓独有能力，打通 CloneTTS 等多音色引擎绑定） ----------------

    /** 音色目录结果（三态可观测：notReady/emptyVoiceEngines 计数供 UI 明示，禁止静默回落） */
    data class SystemCatalogResult(
        val groups: List<SpeechVoiceEngineGroup>,
        val notReadyEngines: Int,
        val emptyVoiceEngines: Int
    )

    private data class CachedVoices(val names: List<String>, val at: Long)

    /** 音色枚举结果缓存（临时 TTS 实例 init 秒级开销，缓存后二次打开编辑器零等待） */
    private val voiceCache = ConcurrentHashMap<String, CachedVoices>()
    private val VOICE_CACHE_TTL_MS = 10 * 60_000L

    /**
     * 系统引擎组（异步详版）：每引擎接 getVoices() 枚举真实音色，toneID=voice.name（命中链
     * TtsVoiceRef→SystemEngineSource.applyVoice 按 tts.voices.name 匹配 setVoice，CloneTTS 等注册系统
     * voices 的引擎即可被绑定为角色声源）。三态：未就绪→warning"请稍后重试"；空集→warning"无可枚举
     * 音色"；正常→多 option（引擎级默认 option 保留置首位）。
     * 调用方必须 produceState 消费（禁主线程同步等待 init，ANR 风险——增量红队 P1）。
     */
    suspend fun systemGroupsDetailed(context: Context): SystemCatalogResult {
        val engines = runCatching {
            withContext(Dispatchers.IO) {
                val tts = TextToSpeech(context.applicationContext, null)
                try {
                    tts.engines.map { it.label.toString() to it.name }
                } finally {
                    tts.shutdown()
                }
            }
        }.getOrDefault(emptyList())
        var notReady = 0
        var emptyCount = 0
        val groups = (listOf("系统默认" to "") + engines)
            .distinctBy { it.second }
            .map { (title, value) ->
                val engineValue = GSON.toJson(SelectItem(title, value))
                val baseOption = SpeechVoiceOption(
                    key = "system:$value",
                    engineType = SpeechRoute.ENGINE_SYSTEM,
                    engineValue = engineValue,
                    engineName = title,
                    speakerName = title,
                    explicitSpeaker = false
                )
                val baseGroup = SpeechVoiceEngineGroup(
                    key = "system:$value",
                    title = title,
                    subtitle = if (value.isBlank()) "系统默认" else "系统 TTS",
                    engineType = SpeechRoute.ENGINE_SYSTEM,
                    engineValue = engineValue,
                    options = listOf(baseOption)
                )
                if (value.isBlank()) return@map baseGroup
                val voices = getVoicesForEngine(context.applicationContext, value)
                when {
                    voices == null -> {
                        notReady++
                        baseGroup.copy(
                            subtitle = "引擎未就绪，请稍后重试",
                            warning = "引擎未就绪，请重试"
                        )
                    }
                    voices.isEmpty() -> {
                        emptyCount++
                        baseGroup.copy(
                            subtitle = "该引擎无可枚举音色",
                            warning = "该引擎无可枚举音色"
                        )
                    }
                    else -> baseGroup.copy(
                        subtitle = "系统 TTS · ${voices.size} 个音色",
                        options = listOf(baseOption) + voices.map { voiceName ->
                            SpeechVoiceOption(
                                key = "system:$value:$voiceName",
                                engineType = SpeechRoute.ENGINE_SYSTEM,
                                engineValue = engineValue,
                                engineName = title,
                                speakerName = voiceName,
                                toneID = voiceName,
                                explicitSpeaker = true
                            )
                        }
                    )
                }
            }
        return SystemCatalogResult(groups, notReady, emptyCount)
    }

    /**
     * 单引擎 voices 枚举：临时 TextToSpeech 显式绑定引擎 + OnInitListener 等待（3s 超时=未就绪 null），
     * 成功后 getVoices 取 voice.name 列表（缓存 10min）。失败不逃逸（fail-soft 返回 null/空集）。
     */
    private suspend fun getVoicesForEngine(context: Context, engine: String): List<String>? {
        voiceCache[engine]?.let { cached ->
            if (System.currentTimeMillis() - cached.at <= VOICE_CACHE_TTL_MS) {
                AppLog.putDebugWithTag(
                    AppLog.TAG_TTS_TRACE,
                    "音色枚举缓存命中 engine=${engine.take(30)} voices=${cached.names.size}",
                    level = AppLog.Level.INFO
                )
                return cached.names
            }
        }
        return withContext(Dispatchers.IO) {
            runCatching {
                val latch = CountDownLatch(1)
                var initOk = false
                val tts = TextToSpeech(
                    context,
                    { status ->
                        initOk = status == TextToSpeech.SUCCESS
                        latch.countDown()
                    },
                    engine
                )
                try {
                    val inited = latch.await(3, TimeUnit.SECONDS)
                    if (!inited || !initOk) {
                        AppLog.putDebugWithTag(
                            AppLog.TAG_TTS_TRACE,
                            "音色枚举引擎未就绪 engine=${engine.take(30)} inited=$inited initOk=$initOk",
                            level = AppLog.Level.WARN
                        )
                        return@runCatching null
                    }
                    val names = tts.voices?.mapNotNull { v: Voice -> v.name }.orEmpty()
                    voiceCache[engine] = CachedVoices(names, System.currentTimeMillis())
                    // F8/2.23 埋点：l2_verify_tts_engine.py 断言依据（枚举路径 TtsTrace）
                    AppLog.putDebugWithTag(
                        AppLog.TAG_TTS_TRACE,
                        "音色枚举 engine=${engine.take(30)} voices=${names.size} names=${names.take(5)}",
                        level = AppLog.Level.INFO
                    )
                    names
                } finally {
                    tts.shutdown()
                }
            }.getOrNull()
        }
    }

    fun assignableRoutes(httpTtsList: List<HttpTTS>): List<SpeechRoute> {
        val httpGroups = httpGroups(httpTtsList)
        val explicitHttp = httpGroups
            .flatMap { group -> group.options.map { it to group.emotions.firstOrNull() } }
            .filter { (option, _) -> option.explicitSpeaker }
            .map { (option, emotion) -> option.toRoute(emotion, SpeechRoute.SOURCE_AUTO) }
            .filterNot(SpeechVoiceGroupRepository::isBlockedRoute)
        if (explicitHttp.isNotEmpty()) return explicitHttp
        val httpDefaults = httpGroups
            .flatMap { group -> group.options.map { it.toRoute(group.emotions.firstOrNull(), SpeechRoute.SOURCE_AUTO) } }
            .filterNot(SpeechVoiceGroupRepository::isBlockedRoute)
        if (httpDefaults.isNotEmpty()) return httpDefaults
        val systemDefault = SpeechRoute(
            engineType = SpeechRoute.ENGINE_SYSTEM,
            engineValue = GSON.toJson(SelectItem("系统默认", "")),
            speakerName = "系统默认",
            source = SpeechRoute.SOURCE_AUTO
        )
        return listOf(systemDefault).filterNot(SpeechVoiceGroupRepository::isBlockedRoute)
    }
}
