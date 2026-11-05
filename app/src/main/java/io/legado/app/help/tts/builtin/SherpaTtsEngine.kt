package io.legado.app.help.tts.builtin

import io.legado.app.utils.LogUtils
import androidx.annotation.Keep
import com.k2fsa.sherpa.onnx.GenerationConfig
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsKokoroModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsMatchaModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsZipVoiceModelConfig
import java.io.File

/** 供 sherpa native 回调的具名类（禁止改名为 lambda；见类头注释） */
@Keep
private class SampleCallback(
    private val onSamples: (FloatArray) -> Unit,
    private val shouldAbort: () -> Boolean
) : Function1<FloatArray, Int> {
    override fun invoke(samples: FloatArray): Int {
        return if (shouldAbort()) 1 else {
            onSamples(samples)
            0
        }
    }
}

/** 引擎侧音色引用（参考音频已在内存/已缓存） */
class BuiltinVoiceRef(
    val id: String,
    val displayName: String,
    val referenceAudio: FloatArray? = null,
    val referenceSampleRate: Int = 0,
    val referenceText: String = "",
    val numSteps: Int = 0,
    /** 多说话人档内置音色编号（0 = 引擎默认音色） */
    val sid: Int = 0
)

/**
 * sherpa-onnx 推理封装（T9.3 / T1.3）。
 * 硬约束：
 * - **不在加载成功前调用 `sampleRate()`**（PoC K2：配置非法时该调用会 SIGSEGV）
 * - matcha 档缺 vocoder **直接判失败**，不冒充 vocoder（PoC K3 SIGSEGV）
 * - `release()` 与合成必须由调用方同锁串行（AD-05/AD-20）
 * - **模型从文件加载时 assetManager 必须为 null**（2026-10-30 真机实证：传 AssetManager +
 *   绝对路径会被 sherpa 的 ReadFile 守卫直接判死，进程无 tombstone 静默退出）
 */
class SherpaTtsEngine {

    private var tts: OfflineTts? = null
    private var profile: BuiltinModelProfile? = null
    private var sampleRate: Int = 0

    val isLoaded: Boolean get() = tts != null

    fun loadedProfileId(): String = profile?.id.orEmpty()

    fun sampleRate(): Int = sampleRate

    /** 装配并加载；返回 false 时保证 tts 为空（不抛异常给上层） */
    @Synchronized
    fun load(target: BuiltinModelProfile, dir: File): Boolean {
        releaseLocked()
        val files = resolveFiles(target, dir) ?: run {
            LogUtils.e(TAG, "模型文件不完整：profile=${target.id} dir=${dir.absolutePath}")
            return false
        }
        val threads = resolveThreads(target.numThreads)
        val config = runCatching {
            when (target.engine) {
                BuiltinModelProfile.ENGINE_VITS -> buildVitsConfig(files, threads)
                BuiltinModelProfile.ENGINE_ZIPVOICE -> buildZipVoiceConfig(files, threads, target.numSteps)
                BuiltinModelProfile.ENGINE_MATCHA -> buildMatchaConfig(files, threads)
                BuiltinModelProfile.ENGINE_KOKORO -> buildKokoroConfig(files, threads)
                else -> null
            }
        }.onFailure { LogUtils.e(TAG, "装配 OfflineTtsConfig 失败：${it.message}") }.getOrNull() ?: return false

        val engine = runCatching { OfflineTts(null, config) }
            .onFailure { LogUtils.e(TAG, "sherpa OfflineTts 加载失败：${it.message}") }
            .getOrNull() ?: return false
        // 采样率以档案声明为准（避免加载早期调用 native；首次合成成功后再校正）
        tts = engine
        profile = target
        sampleRate = target.sampleRate
        return true
    }

    /**
     * 流式合成。
     * @param shouldAbort 返回 true 立即中止（代际取消 / onStop）
     * @return true=成功；false=未加载或 native 失败
     */
    fun synthesize(
        text: String,
        voiceRef: BuiltinVoiceRef?,
        speed: Float,
        numSteps: Int,
        shouldAbort: () -> Boolean,
        onSamples: (FloatArray) -> Unit
    ): Boolean {
        val engine = tts ?: return false
        val current = profile ?: return false
        if (text.isBlank()) return false
        val config = GenerationConfig(
            speed = speed.coerceIn(0.5f, 2.0f),
            // 多说话人档（aishell3/Kokoro）按音色 sid 切换；克隆档与单声档恒 0
            sid = voiceRef?.sid ?: 0,
            referenceAudio = voiceRef?.referenceAudio,
            referenceSampleRate = voiceRef?.referenceSampleRate ?: 0,
            referenceText = voiceRef?.referenceText.orEmpty(),
            numSteps = if (numSteps > 0) numSteps else current.numSteps
        )
        var aborted = false
        // 必须用**具名类**回调：sherpa native 按 `invoke([F)Ljava/lang/Integer;` 精确查找，
        // 内联 lambda 的桥接方法会被 R8 裁掉（实证 JNI abort）
        val callback = SampleCallback(
            onSamples = onSamples,
            shouldAbort = { if (shouldAbort()) { aborted = true; true } else false }
        )
        val audio = runCatching {
            engine.generateWithConfigAndCallback(text, config, callback)
        }.onFailure {
            LogUtils.e(TAG, "合成失败：${it.stackTraceToString()}")
        }.getOrNull() ?: return false
        // 首次成功合成后以引擎真实采样率校正（整个进程只读一次）
        if (audio.sampleRate > 0 && audio.sampleRate != sampleRate) {
            sampleRate = audio.sampleRate
        }
        return !aborted
    }

    @Synchronized
    fun release() {
        releaseLocked()
    }

    private fun releaseLocked() {
        runCatching { tts?.release() }
        tts = null
        profile = null
        sampleRate = 0
    }

    private class ResolvedFiles(
        val model: File? = null,
        /** Kokoro 的音色向量包 voices.bin */
        val voices: File? = null,
        val acousticModel: File? = null,
        val vocoder: File? = null,
        val encoder: File? = null,
        val decoder: File? = null,
        val tokens: File? = null,
        val lexicon: File? = null,
        val dataDir: File? = null,
        val dictDir: File? = null,
        val ruleFsts: String = ""
    )

    private fun resolveFiles(target: BuiltinModelProfile, dir: File): ResolvedFiles? {
        if (!dir.isDirectory) return null
        // 目录也要参与解析：ZipVoice/Matcha 的 `espeak-ng-data` 与 `dict` 是**目录**，
        // 只筛文件会让 data_dir 为空 → sherpa 报 "Please provide data dir for this model" 后 abort
        val all = dir.walkTopDown().toList()
        fun pick(vararg patterns: String): File? {
            patterns.forEach { pattern ->
                all.firstOrNull { it.isFile && it.name.equals(pattern, ignoreCase = true) && it.length() > 0 }
                    ?.let { return it }
            }
            patterns.forEach { pattern ->
                all.firstOrNull { it.isFile && it.name.contains(pattern, ignoreCase = true) && it.length() > 0 }
                    ?.let { return it }
            }
            return null
        }
        val tokens = pick("tokens.txt") ?: return null
        val lexicon = pick("lexicon.txt")
        val espeak = all.firstOrNull { it.isDirectory && it.name.equals("espeak-ng-data", true) }
        val dict = all.firstOrNull { it.isDirectory && it.name.equals("dict", true) }
        // 文本正则化：date/number 必带；有 lexicon 时不再叠加 phone.fst（否则音素二次映射）
        val ruleFsts = all.filter { it.name.endsWith(".fst", true) }
            .filter { lexicon == null || !it.name.equals("phone.fst", true) }
            .sortedBy { it.name }
            .joinToString(",") { it.absolutePath }
            .ifBlank { "" }
        return when (target.engine) {
            BuiltinModelProfile.ENGINE_VITS -> {
                val model = pick("model.onnx")
                    ?: all.firstOrNull { it.name.endsWith(".onnx", true) && it.length() > 0 }
                    ?: return null
                ResolvedFiles(model = model, tokens = tokens, lexicon = lexicon,
                    dataDir = espeak, dictDir = dict, ruleFsts = ruleFsts)
            }
            BuiltinModelProfile.ENGINE_ZIPVOICE -> {
                val encoder = pick("encoder.int8.onnx", "encoder.onnx") ?: return null
                val decoder = pick("decoder.int8.onnx", "decoder.onnx") ?: return null
                val vocoder = pick("vocos_24khz.onnx", "vocoder.onnx") ?: return null
                ResolvedFiles(encoder = encoder, decoder = decoder, vocoder = vocoder,
                    tokens = tokens, lexicon = lexicon, dataDir = espeak, ruleFsts = ruleFsts)
            }
            BuiltinModelProfile.ENGINE_KOKORO -> {
                val model = pick("model.onnx") ?: return null
                // Kokoro 必须有 voices.bin（音色向量）；缺了会加载失败，显式拒绝而不是静默回退单声
                val voices = pick("voices.bin") ?: run {
                    LogUtils.e(TAG, "kokoro 档缺少 voices.bin，拒绝加载")
                    return null
                }
                ResolvedFiles(model = model, voices = voices, tokens = tokens,
                    lexicon = pick("lexicon-zh.txt", "lexicon.txt"),
                    // 实测（2026-11-05 模拟器）：native 校验 `data_dir/phontab`，
                    // 必须直接传 espeak-ng-data 目录本身（传它的父目录会报 does not exist 并 SIGSEGV）
                    dataDir = espeak, dictDir = dict, ruleFsts = ruleFsts)
            }
            BuiltinModelProfile.ENGINE_MATCHA -> {
                val acoustic = pick("model-steps-3.onnx", "model.onnx", "acoustic.onnx") ?: return null
                // PoC K3：matcha 必须有独立 vocoder，指向自身会 SIGSEGV ⇒ 显式失败
                val vocoder = all.firstOrNull {
                    it.isFile && it.name.contains("vocoder", true) && it.length() > 0
                } ?: run {
                    LogUtils.e(TAG, "matcha 档缺少独立 vocoder，拒绝加载（防 SIGSEGV）")
                    return null
                }
                ResolvedFiles(acousticModel = acoustic, vocoder = vocoder, tokens = tokens,
                    lexicon = lexicon, dataDir = espeak, dictDir = dict, ruleFsts = ruleFsts)
            }
            else -> null
        }
    }

    private fun buildVitsConfig(files: ResolvedFiles, threads: Int): OfflineTtsConfig {
        val vits = OfflineTtsVitsModelConfig(
            model = files.model?.absolutePath.orEmpty(),
            lexicon = files.lexicon?.absolutePath.orEmpty(),
            tokens = files.tokens?.absolutePath.orEmpty(),
            dataDir = files.dataDir?.absolutePath.orEmpty(),
            dictDir = files.dictDir?.absolutePath.orEmpty(),
            noiseScale = 0.667f,
            noiseScaleW = 0.8f,
            lengthScale = 1.0f
        )
        return OfflineTtsConfig(
            model = OfflineTtsModelConfig(vits = vits, numThreads = threads, debug = false, provider = "cpu"),
            ruleFsts = files.ruleFsts,
            maxNumSentences = 1,
            silenceScale = 0.2f
        )
    }

    private fun buildZipVoiceConfig(files: ResolvedFiles, threads: Int, numSteps: Int): OfflineTtsConfig {
        val zip = OfflineTtsZipVoiceModelConfig(
            tokens = files.tokens?.absolutePath.orEmpty(),
            encoder = files.encoder?.absolutePath.orEmpty(),
            decoder = files.decoder?.absolutePath.orEmpty(),
            vocoder = files.vocoder?.absolutePath.orEmpty(),
            dataDir = files.dataDir?.absolutePath.orEmpty(),
            lexicon = files.lexicon?.absolutePath.orEmpty(),
            featScale = 1f,
            tShift = 0.5f,
            targetRms = 0.1f,
            guidanceScale = 1f
        )
        return OfflineTtsConfig(
            model = OfflineTtsModelConfig(zipvoice = zip, numThreads = threads, debug = false, provider = "cpu"),
            ruleFsts = files.ruleFsts,
            maxNumSentences = 1,
            silenceScale = 0.2f
        )
    }

    /**
     * Kokoro（POC 2026-11-05 实测 24kHz / 103 音色 / RTF 0.446@4线程）：
     * data_dir 必须指「包含 espeak-ng-data 的目录」；lexicon 用中文词表；dict_dir 可选（jieba 分词）。
     */
    private fun buildKokoroConfig(files: ResolvedFiles, threads: Int): OfflineTtsConfig {
        val kokoro = OfflineTtsKokoroModelConfig(
            model = files.model?.absolutePath.orEmpty(),
            voices = files.voices?.absolutePath.orEmpty(),
            tokens = files.tokens?.absolutePath.orEmpty(),
            dataDir = files.dataDir?.absolutePath.orEmpty(),
            lexicon = files.lexicon?.absolutePath.orEmpty(),
            dictDir = files.dictDir?.absolutePath.orEmpty(),
            lengthScale = 1.0f
        )
        return OfflineTtsConfig(
            model = OfflineTtsModelConfig(kokoro = kokoro, numThreads = threads, debug = false, provider = "cpu"),
            ruleFsts = files.ruleFsts,
            maxNumSentences = 1,
            silenceScale = 0.2f
        )
    }

    private fun buildMatchaConfig(files: ResolvedFiles, threads: Int): OfflineTtsConfig {
        val matcha = OfflineTtsMatchaModelConfig(
            acousticModel = files.acousticModel?.absolutePath.orEmpty(),
            vocoder = files.vocoder?.absolutePath.orEmpty(),
            lexicon = files.lexicon?.absolutePath.orEmpty(),
            tokens = files.tokens?.absolutePath.orEmpty(),
            dataDir = files.dataDir?.absolutePath.orEmpty(),
            dictDir = files.dictDir?.absolutePath.orEmpty(),
            noiseScale = 0.667f,
            lengthScale = 1.0f
        )
        return OfflineTtsConfig(
            model = OfflineTtsModelConfig(matcha = matcha, numThreads = threads, debug = false, provider = "cpu"),
            ruleFsts = files.ruleFsts,
            maxNumSentences = 1,
            silenceScale = 0.2f
        )
    }

    /** 线程数按核数自适应（性能核 2~4；保留 2 核给 UI/播放） */
    private fun resolveThreads(declared: Int): Int {
        val cores = Runtime.getRuntime().availableProcessors()
        val suggested = (cores - 2).coerceIn(2, 4)
        return if (declared in 1..suggested) declared else suggested
    }

    private companion object {
        const val TAG = "BuiltinSherpa"
    }
}
