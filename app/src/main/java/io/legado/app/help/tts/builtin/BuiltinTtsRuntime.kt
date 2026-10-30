package io.legado.app.help.tts.builtin

import android.app.ActivityManager
import android.content.Context
import io.legado.app.utils.LogUtils
import androidx.annotation.Keep
import com.google.gson.Gson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * 进程级运行时门面（C-6 / AD-20）：
 * - 唯一 `SherpaTtsEngine`（引擎服务与 UI 试听共用同一份）
 * - `synthesisMutex` 串行（Talkify 实证：并发合成会内存翻倍且释放期 SIGSEGV）
 * - 空闲 5 分钟释放 native（保留磁盘缓存与音色索引）
 * - 参考音频内存缓存（`profileId:voiceId`）
 */
object BuiltinTtsRuntime {

    @Keep
    private data class EngineStateFile(
        val activeProfileId: String = "",
        val updatedAt: Long = 0
    )

    data class Stats(
        val state: BuiltinEngineState,
        val profileId: String,
        val voiceCount: Int,
        val cacheBytes: Long,
        val lastRtf: Float,
        val avgRtf: Float,
        val cacheHits: Long,
        val cacheMisses: Long,
        val lastError: String
    )

    /** 存储占用三分类（T4.9 总览）：模型 / 音色 / 合成缓存（预合成与实时共用同一缓存目录） */
    data class StorageBreakdown(
        val modelBytes: Long = 0,
        val voiceBytes: Long = 0,
        val cacheBytes: Long = 0
    ) {
        val totalBytes: Long get() = modelBytes + voiceBytes + cacheBytes
    }

    data class SelfCheckReport(
        val ok: Boolean,
        val profileId: String,
        val profileTitle: String,
        val sampleRate: Int,
        val voiceCount: Int,
        val cacheBytes: Long,
        val costMs: Long,
        val audioMs: Long,
        val rtf: Float,
        val message: String
    )

    data class PrebuildUnit(val text: String, val voiceName: String)

    data class PrebuildResult(
        val total: Int,
        val hit: Int,
        val synthesized: Int,
        val failed: Int
    )

    private const val IDLE_RELEASE_MS = 5 * 60 * 1000L
    private const val SELF_CHECK_TEXT = "离线朗读自检通过。"

    private val initLock = Any()
    private var initialized = false
    private lateinit var appContext: Context
    private lateinit var paths: BuiltinTtsPaths
    private lateinit var cache: BuiltinTtsCache
    private lateinit var voiceStore: BuiltinVoiceStore
    private lateinit var pronDict: BuiltinPronunciationDict
    private lateinit var modelManager: BuiltinModelManager

    private var engine: SherpaTtsEngine? = null
    private val synthesisMutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val referenceCache = HashMap<String, BuiltinVoiceRef>()
    private var idleReleaseJob: Job? = null

    @Volatile
    private var realtimeBusy = false

    @Volatile
    private var generation: Long = 0

    @Volatile
    private var lastError: String = ""

    private var lastRtf = 0f
    private var avgRtf = 0f
    private var rtfSamples = 0
    private var cacheHits = 0L
    private var cacheMisses = 0L
    private var seeded = false

    fun init(context: Context) {
        if (initialized) return
        synchronized(initLock) {
            if (initialized) return
            appContext = context.applicationContext
            paths = BuiltinTtsPaths.of(appContext)
            cache = BuiltinTtsCache(paths.cacheDir)
            voiceStore = BuiltinVoiceStore(paths.voiceRoot)
            pronDict = BuiltinPronunciationDict(paths.pronFile)
            modelManager = BuiltinModelManager(appContext, paths)
            initialized = true
        }
    }

    fun paths(): BuiltinTtsPaths {
        check(initialized) { "BuiltinTtsRuntime 未初始化" }
        return paths
    }

    fun modelManager(): BuiltinModelManager {
        check(initialized) { "BuiltinTtsRuntime 未初始化" }
        return modelManager
    }

    fun voiceStore(): BuiltinVoiceStore {
        check(initialized) { "BuiltinTtsRuntime 未初始化" }
        return voiceStore
    }

    fun pronunciationDict(): BuiltinPronunciationDict {
        check(initialized) { "BuiltinTtsRuntime 未初始化" }
        return pronDict
    }

    fun cache(): BuiltinTtsCache {
        check(initialized) { "BuiltinTtsRuntime 未初始化" }
        return cache
    }

    /** 首次使用前播种内置 6 音色（幂等） */
    fun ensureSeeded() {
        if (seeded) return
        synchronized(initLock) {
            if (seeded) return
            runCatching { BuiltinVoiceAssets.seed(appContext.assets, voiceStore) }
                .onFailure { LogUtils.e(TAG, "内置音色播种失败：${it.message}") }
            seeded = true
        }
    }

    fun profiles(): List<BuiltinModelProfile> {
        check(initialized) { "BuiltinTtsRuntime 未初始化" }
        return modelManager.profiles()
    }

    /** 当前激活档：显式设置 > READY 的 clone > READY 的 fast */
    fun activeProfile(): BuiltinModelProfile? {
        check(initialized) { "BuiltinTtsRuntime 未初始化" }
        val profiles = modelManager.profiles()
        if (profiles.isEmpty()) return null
        val stored = readActiveProfileId()
        val ready = { id: String ->
            profiles.firstOrNull { it.id == id }?.takeIf { modelManager.state(it) == BuiltinEngineState.READY }
        }
        stored.takeIf { it.isNotBlank() }?.let { id -> ready(id)?.let { return it } }
        listOf("clone", "fast").forEach { id -> ready(id)?.let { return it } }
        return profiles.firstOrNull { modelManager.state(it) == BuiltinEngineState.READY }
    }

    /**
     * 是否已完成 [init]。
     * TextToSpeechService 生命周期里 `super.onCreate()` 会先回调 `onLoadLanguage`/`onIsLanguageAvailable`，
     * 这类早调入口必须能安全判空（否则抛异常导致系统拉起引擎服务即崩溃，2026-10-30 模拟器铁证）。
     */
    fun isInitialized(): Boolean = initialized

    fun state(): BuiltinEngineState {
        // 早调安全：未初始化一律视为无可用档（不得抛异常，见 isInitialized 注释）
        if (!initialized) return BuiltinEngineState.NONE
        val profile = activeProfile() ?: return BuiltinEngineState.NONE
        return modelManager.state(profile)
    }

    fun isReady(): Boolean = state() == BuiltinEngineState.READY

    /** 当前朗读路由是否指向本应用引擎（系统引擎选择写入的是包名） */
    fun isActiveEngine(context: Context, engineValue: String): Boolean {
        if (engineValue.isBlank()) return false
        return engineValue == context.applicationContext.packageName
    }

    fun setActiveProfile(profileId: String) {
        check(initialized) { "BuiltinTtsRuntime 未初始化" }
        runCatching {
            paths.engineStateFile.parentFile?.mkdirs()
            paths.engineStateFile.writeText(
                Gson().toJson(EngineStateFile(profileId, System.currentTimeMillis()))
            )
        }.onFailure { LogUtils.e(TAG, "写入激活档失败：${it.message}") }
    }

    fun voices(): List<BuiltinVoice> {
        check(initialized) { "BuiltinTtsRuntime 未初始化" }
        ensureSeeded()
        return voiceStore.list()
    }

    fun stats(): Stats {
        if (!initialized) {
            return Stats(BuiltinEngineState.NONE, "", 0, 0, 0f, 0f, 0, 0, "")
        }
        return Stats(
            state = state(),
            profileId = activeProfile()?.id.orEmpty(),
            voiceCount = runCatching { voiceStore.list().size }.getOrDefault(0),
            cacheBytes = cache.sizeBytes(),
            lastRtf = lastRtf,
            avgRtf = avgRtf,
            cacheHits = cacheHits,
            cacheMisses = cacheMisses,
            lastError = lastError
        )
    }

    /**
     * 存储总览（T4.9）：模型目录 / 音色目录 / 合成缓存三类实占字节。
     * 未初始化返回全 0（早调安全，与 [state] 同口径）。
     */
    fun storageBreakdown(): StorageBreakdown {
        if (!initialized) return StorageBreakdown()
        val modelBytes = profiles().sumOf { profile ->
            runCatching { dirBytes(paths.modelDir(profile.id)) }.getOrDefault(0L)
        }
        val voiceBytes = runCatching { dirBytes(paths.voiceRoot) }.getOrDefault(0L)
        return StorageBreakdown(
            modelBytes = modelBytes,
            voiceBytes = voiceBytes,
            cacheBytes = cache.sizeBytes()
        )
    }

    private fun dirBytes(dir: File): Long =
        if (!dir.isDirectory) 0L else dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    /** 合成到 PCM（命中缓存零耗时）；失败返回 null 并记录最近错误 */
    suspend fun synthesizeToPcm(
        text: String,
        voiceName: String,
        rate: Float,
        requestGeneration: Long = currentGeneration(),
        bypassCache: Boolean = false
    ): CachedAudio? {
        check(initialized) { "BuiltinTtsRuntime 未初始化" }
        if (text.isBlank()) return null
        ensureSeeded()
        val profile = activeProfile() ?: run {
            lastError = "未下载离线语音包"
            return null
        }
        val voice = voiceStore.find(voiceName) ?: voices().firstOrNull()
        // T8.2：繁体章节先简化再送引擎（只影响合成，正文显示不变）
        val normalizedText = pronDict.transform(BuiltinTextNormalizer.ensureSimplified(text))
        // 单声档（vits/matcha）音色不参与合成，缓存键固定，避免同音频多份缓存
        val voiceKey = if (profile.requiresReference) voice?.voiceName.orEmpty() else "single"
        val gain = voice?.gainMultiplier ?: 1.0f
        val key = BuiltinCacheKeys.key(profile.id, voiceKey, rate, normalizedText, gain)
        if (!bypassCache) {
            cache.get(key)?.let {
                if (requestGeneration != currentGeneration()) return null
                cacheHits++
                return it
            }
        }
        cacheMisses++
        realtimeBusy = true
        return try {
            withContext(Dispatchers.IO.limitedParallelism(1)) {
                synthesisMutex.withLock {
                    if (!bypassCache) {
                        cache.get(key)?.let { return@withLock it }
                    }
                    if (requestGeneration != currentGeneration()) return@withLock null
                    val engineInstance = ensureEngine(profile) ?: return@withLock null
                    val chunks = BuiltinTextChunker.split(normalizedText)
                    if (chunks.isEmpty()) return@withLock null
                    val pcmOut = ByteArrayOutputStream()
                    var audioSamples = 0
                    val startedAt = System.currentTimeMillis()
                    for (chunk in chunks) {
                        if (requestGeneration != currentGeneration()) return@withLock null
                        val ref = buildVoiceRef(profile, voice)
                        val success = engineInstance.synthesize(
                            text = chunk.text,
                            voiceRef = ref,
                            speed = rate,
                            numSteps = 0,
                            shouldAbort = { requestGeneration != currentGeneration() },
                            onSamples = { samples ->
                                // T8.1：出流前响度归一 + 音色增益 + 峰值限制（跨音色听感一致）
                                val adjusted = BuiltinLoudnessNormalizer.apply(samples, gain)
                                pcmOut.write(PcmAudioUtils.floatToPcm16(adjusted))
                                audioSamples += adjusted.size
                            }
                        )
                        if (!success) {
                            lastError = "合成失败（${chunk.text.take(12)}…）"
                            return@withLock null
                        }
                        if (chunk.pauseMs > 0) {
                            val silence = PcmAudioUtils.silence(chunk.pauseMs, engineInstance.sampleRate())
                            pcmOut.write(PcmAudioUtils.floatToPcm16(silence))
                            audioSamples += silence.size
                        }
                    }
                    val costMs = System.currentTimeMillis() - startedAt
                    val sampleRate = engineInstance.sampleRate().takeIf { it > 0 } ?: profile.sampleRate
                    val audio = CachedAudio(sampleRate, pcmOut.toByteArray())
                    if (audioSamples > 0) {
                        recordRtf(costMs, audioSamples, sampleRate)
                    }
                    if (!bypassCache) cache.put(key, audio)
                    lastError = ""
                    audio
                }
            }
        } finally {
            realtimeBusy = false
            scheduleIdleRelease()
        }
    }

    /** 预取（AD-09 ②）：实时请求占用时让行；失败静默 */
    suspend fun prefetch(
        text: String,
        voiceName: String,
        rate: Float,
        requestGeneration: Long = currentGeneration()
    ): Boolean {
        if (realtimeBusy) return false
        if (requestGeneration != currentGeneration()) return false
        val audio = runCatching {
            synthesizeToPcm(text, voiceName, rate, requestGeneration)
        }.getOrNull() ?: return false
        return audio.bytes > 0
    }

    /** 播放循环内的非阻塞预取（不拖慢当前句） */
    fun prefetchAsync(text: String, voiceName: String, rate: Float) {
        if (!initialized || text.isBlank()) return
        scope.launch {
            runCatching { prefetch(text, voiceName, rate) }
        }
    }

    suspend fun prebuild(
        units: List<PrebuildUnit>,
        rate: Float,
        onProgress: (index: Int) -> Unit = {}
    ): PrebuildResult {
        var hit = 0
        var synthesized = 0
        var failed = 0
        val profile = activeProfile()
        val voiceList = voices()
        units.forEachIndexed { index, unit ->
            if (profile == null) {
                failed++
                onProgress(index + 1)
                return@forEachIndexed
            }
            val voice = voiceStore.find(unit.voiceName) ?: voiceList.firstOrNull()
            // 与实时合成共用同一键口径（含单声档 voiceKey=single 与音色增益），否则幂等判定永远不命中
            val key = cacheKeyOf(profile, voice, rate, unit.text)
            if (cache.contains(key)) {
                hit++
            } else {
                val audio = runCatching {
                    synthesizeToPcm(unit.text, unit.voiceName, rate, currentGeneration())
                }.getOrNull()
                if (audio == null) failed++ else synthesized++
            }
            onProgress(index + 1)
        }
        return PrebuildResult(units.size, hit, synthesized, failed)
    }

    suspend fun selfCheck(): SelfCheckReport = withContext(Dispatchers.IO) {
        val profile = activeProfile()
        if (profile == null) {
            return@withContext SelfCheckReport(
                false, "", "", 0, 0, 0, 0, 0, 0f,
                message = "未下载离线语音包"
            )
        }
        val started = System.currentTimeMillis()
        // 自检必须绕过缓存，否则二次自检 RTF 恒为 0（无法反映真实性能）
        val audio = runCatching {
            synthesizeToPcm(
                SELF_CHECK_TEXT,
                voices().firstOrNull()?.voiceName.orEmpty(),
                1.0f,
                bypassCache = true
            )
        }.onFailure {
            lastError = "自检异常：${it.message}"
            LogUtils.e(TAG, "自检异常：${it.stackTraceToString()}")
        }.getOrNull()
        val cost = System.currentTimeMillis() - started
        val sampleRate = audio?.sampleRate ?: 0
        val audioMs = if (audio != null && sampleRate > 0) {
            audio.pcm.size.toLong() * 1000L / (sampleRate * 2L)
        } else 0L
        SelfCheckReport(
            ok = audio != null,
            profileId = profile.id,
            profileTitle = profile.title,
            sampleRate = sampleRate,
            voiceCount = voices().size,
            cacheBytes = cache.sizeBytes(),
            costMs = cost,
            audioMs = audioMs,
            rtf = if (audioMs > 0) cost.toFloat() / audioMs else 0f,
            message = if (audio != null) "自检通过" else (lastError.ifBlank { "合成失败" })
        )
    }

    /** 释放 native 引擎（保留缓存与索引）；与合成同锁（AD-05） */
    suspend fun releaseEngine() {
        if (!initialized) return
        synthesisMutex.withLock {
            engine?.release()
            engine = null
            referenceCache.clear()
        }
    }

    fun currentGeneration(): Long = generation

    /** 代际自增（onStop / 切章 / 切换音色时调用，旧请求结果不写缓存） */
    fun bumpGeneration(): Long {
        generation += 1
        return generation
    }

    fun clearCache() {
        if (initialized) cache.clear()
    }

    fun deleteVoice(id: String): Boolean {
        if (!initialized) return false
        val removed = voiceStore.delete(id)
        if (removed) {
            referenceCache.keys.filter { it.endsWith(":$id") }.forEach { referenceCache.remove(it) }
        }
        return removed
    }

    private fun ensureEngine(profile: BuiltinModelProfile): SherpaTtsEngine? {
        val current = engine
        if (current != null && current.isLoaded && current.loadedProfileId() == profile.id) return current
        val dir = paths.modelDir(profile.id)
        // 内存预检：克隆档加载约需 300MB+ 峰值，低内存设备直接拒绝（RK3）
        if (profile.requiresReference && !hasEnoughMemory()) {
            lastError = "内存不足，建议使用极速档"
            return null
        }
        val instance = SherpaTtsEngine()
        if (!instance.load(profile, dir)) {
            lastError = "语音包损坏或文件不完整"
            instance.release()
            return null
        }
        engine = instance
        return instance
    }

    private fun hasEnoughMemory(): Boolean {
        return runCatching {
            val am = appContext.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val info = ActivityManager.MemoryInfo()
            am.getMemoryInfo(info)
            info.availMem > 400L * 1024 * 1024
        }.getOrDefault(true)
    }

    private fun buildVoiceRef(
        profile: BuiltinModelProfile,
        voice: BuiltinVoice?
    ): BuiltinVoiceRef? {
        if (!profile.requiresReference) return null
        if (voice == null) return null
        val key = "${profile.id}:${voice.id}"
        referenceCache[key]?.let { return it }
        if (!voiceStore.exists(voice)) return null
        val wav = runCatching { PcmAudioUtils.readWav(voiceStore.audioFile(voice)) }.getOrNull() ?: return null
        val samples = PcmAudioUtils.pcm16ToFloat(wav.pcm)
        val resampled = PcmAudioUtils.resample(samples, wav.sampleRate, profile.sampleRate)
        val ref = BuiltinVoiceRef(
            id = voice.id,
            displayName = voice.displayName,
            referenceAudio = resampled,
            referenceSampleRate = profile.sampleRate,
            referenceText = voice.refText,
            numSteps = voice.numSteps
        )
        referenceCache[key] = ref
        return ref
    }

    /** 缓存键单源口径：实时合成与预合成必须一致（含单声档 voiceKey、音色增益、简繁归一） */
    private fun cacheKeyOf(
        profile: BuiltinModelProfile,
        voice: BuiltinVoice?,
        rate: Float,
        text: String
    ): String {
        val voiceKey = if (profile.requiresReference) voice?.voiceName.orEmpty() else "single"
        val normalized = pronDict.transform(BuiltinTextNormalizer.ensureSimplified(text))
        return BuiltinCacheKeys.key(profile.id, voiceKey, rate, normalized, voice?.gainMultiplier ?: 1.0f)
    }

    private fun recordRtf(costMs: Long, audioSamples: Int, sampleRate: Int) {
        if (audioSamples <= 0 || sampleRate <= 0) return
        val audioMs = audioSamples.toFloat() * 1000f / sampleRate
        if (audioMs <= 0f) return
        val rtf = costMs.toFloat() / audioMs
        lastRtf = rtf
        rtfSamples += 1
        avgRtf = if (rtfSamples == 1) rtf else avgRtf * 0.8f + rtf * 0.2f
    }

    private fun scheduleIdleRelease() {
        idleReleaseJob?.cancel()
        idleReleaseJob = scope.launch {
            delay(IDLE_RELEASE_MS)
            releaseEngine()
        }
    }

    private fun readActiveProfileId(): String {
        return runCatching {
            val file = paths.engineStateFile
            if (!file.isFile) "" else {
                Gson().fromJson(file.readText(), EngineStateFile::class.java)?.activeProfileId.orEmpty()
            }
        }.getOrDefault("")
    }

    private const val TAG = "BuiltinTtsRuntime"
}
