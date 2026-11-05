package io.legado.app.help.tts.builtin

import android.content.Context
import androidx.annotation.Keep
import com.google.gson.Gson
import java.io.File

/**
 * 模型档案（C-2）：一组模型文件 + 能力描述。
 * 注意：R8 × Gson 泛型签名门禁——含 List/Map 字段的 Gson 模型必须 @Keep。
 */
@Keep
data class BuiltinModelProfile(
    val id: String = "",
    val title: String = "",
    val engine: String = "",
    val sizeBytes: Long = 0L,
    val sampleRate: Int = 0,
    val numThreads: Int = 2,
    val numSteps: Int = 4,
    val files: List<String> = emptyList(),
    val sha256: Map<String, String> = emptyMap(),
    val sources: List<String> = emptyList(),
    val license: String = "",
    /** vits 档的单声兜底音色显示名（无参考音频时使用） */
    val fallbackVoiceName: String = "",
    /** 多说话人档的内置音色数（aishell3=174 / Kokoro 中文=100；0 或 1 表示单声档） */
    val speakerCount: Int = 0,
    /** 播种内置音色时的名称前缀（如「艾舍尔」；空则用 title） */
    val speakerNamePrefix: String = "",
    /**
     * 内置音色的 sid 起点（默认 1）。
     * Kokoro 中文音色的有效 sid 是 3..102（前两位为其它语种），从 0 或 1 播种会取到无关音色。
     */
    val speakerSidStart: Int = 1,
    /** 性别映射区间（供配声「男女硬约束」使用；未登记区间一律留空，不猜） */
    val speakerGenderRanges: List<SpeakerGenderRange> = emptyList()
) {
    val valid: Boolean
        get() = id.isNotBlank() && title.isNotBlank() &&
                engine in SUPPORTED_ENGINES && sampleRate > 0 && files.isNotEmpty()

    /** 只有克隆档需要参考音频；vits/matcha 为单声档 */
    val requiresReference: Boolean
        get() = engine == ENGINE_ZIPVOICE

    /** 是否多说话人档（可按 sid 切换内置音色，无需参考音频） */
    val multiSpeaker: Boolean
        get() = !requiresReference && speakerCount > 1

    /** sid 的性别标注（只有登记过区间才返回；空串表示未标注，交给配声按音色挑） */
    fun genderOfSid(sid: Int): String =
        speakerGenderRanges.firstOrNull { sid in it.fromSid..it.toSid }?.gender.orEmpty()

    companion object {
        const val ENGINE_VITS = "vits"
        const val ENGINE_ZIPVOICE = "zipvoice"
        const val ENGINE_MATCHA = "matcha"

        /** Kokoro（24kHz 多语种多音色；中文音色 sid 3..102，POC 实测 RTF 0.45@4线程） */
        const val ENGINE_KOKORO = "kokoro"
        val SUPPORTED_ENGINES = setOf(ENGINE_VITS, ENGINE_ZIPVOICE, ENGINE_MATCHA, ENGINE_KOKORO)
    }
}

/**
 * 内置音色的性别区间（闭区间 sid）。
 * R8 × Gson 泛型签名：profile 含 `List<SpeakerGenderRange>` ⇒ 本体必须 @Keep，否则元素类型回落 Object。
 */
@Keep
data class SpeakerGenderRange(
    val gender: String = "",
    val fromSid: Int = 0,
    val toSid: Int = 0
)

@Keep
private data class BuiltinModelCatalogJson(
    val version: Int = 0,
    val profiles: List<BuiltinModelProfile> = emptyList()
)

/** 模型档案解析与校验（T1.1）：缺字段/空 profiles/未知引擎一律显式失败，不静默 */
object BuiltinModelCatalog {

    const val ASSET_NAME = "builtin_models.json"

    fun parse(json: String): List<BuiltinModelProfile> {
        require(json.isNotBlank()) { "模型档案为空" }
        val catalog = runCatching {
            Gson().fromJson(json, BuiltinModelCatalogJson::class.java)
        }.getOrElse { throw IllegalArgumentException("模型档案 JSON 非法：${it.message}") }
        val profiles = catalog.profiles
        require(profiles.isNotEmpty()) { "模型档案缺少 profiles" }
        profiles.forEach { profile ->
            require(profile.id.isNotBlank()) { "模型档案存在缺少 id 的条目" }
            require(profile.title.isNotBlank()) { "模型档案 ${profile.id} 缺少 title" }
            require(profile.engine in BuiltinModelProfile.SUPPORTED_ENGINES) {
                "模型档案 ${profile.id} 使用未知引擎：${profile.engine}"
            }
            require(profile.sampleRate > 0) { "模型档案 ${profile.id} 缺少 sampleRate" }
            require(profile.files.isNotEmpty()) { "模型档案 ${profile.id} 缺少 files" }
            require(profile.speakerSidStart >= 0) { "模型档案 ${profile.id} 的 speakerSidStart 不能为负" }
            profile.speakerGenderRanges.forEach { range ->
                require(range.fromSid in 0..range.toSid) {
                    "模型档案 ${profile.id} 的性别区间非法：${range.fromSid}..${range.toSid}"
                }
            }
        }
        require(profiles.map { it.id }.distinct().size == profiles.size) { "模型档案存在重复 id" }
        return profiles
    }

    fun load(context: Context): List<BuiltinModelProfile> {
        return runCatching {
            context.assets.open(ASSET_NAME).bufferedReader().use { it.readText() }
        }.getOrNull()?.let(::parse).orEmpty()
    }
}

/** 目录约定（单源）：模型/音色/词典/规则/缓存 各自唯一根 */
class BuiltinTtsPaths(
    val filesRoot: File,
    val cacheRoot: File,
    importRoot: File? = null
) {
    /** filesDir/tts/models/<profile>/ */
    val modelRoot: File = File(filesRoot, "tts/models")

    /** filesDir/tts/voices/index.json + <id>/ref.wav */
    val voiceRoot: File = File(filesRoot, "tts/voices")

    /** filesDir/tts/pron/dict.json */
    val pronFile: File = File(filesRoot, "tts/pron/dict.json")

    /** filesDir/tts/state/engine.json（激活档位/上次错误，非偏好备份范围） */
    val engineStateFile: File = File(filesRoot, "tts/state/engine.json")

    /** filesDir/tts/split_rules.txt（可选：自定义切分正则，按行、`#` 注释；AD-19⑦） */
    val splitRulesFile: File = File(filesRoot, "tts/split_rules.txt")

    /** filesDir/legado_rules/（与 CloneTTS 生态互通：fixed_roles.json / book/<key>.json） */
    val rulesRoot: File = File(filesRoot, "legado_rules")

    /** filesDir/scripts/（章节剧本：<bookKey>/<chapterIndex>.json；可重建缓存，不进备份） */
    val scriptsDir: File = File(filesRoot, "scripts")

    /** cacheDir/builtin_tts/（与 http 通道 TtsCacheKeys 命名空间隔离，见 AD-04） */
    val cacheDir: File = File(cacheRoot, "builtin_tts")

    /** 旁路发现的导入目录（**外置私有目录**：`adb push` 可直接写入，免存储权限） */
    val importDir: File = importRoot ?: File(filesRoot, "tts-import")

    fun modelDir(profileId: String): File = File(modelRoot, profileId)

    companion object {
        fun of(context: Context): BuiltinTtsPaths {
            val external = runCatching { context.getExternalFilesDir(null) }.getOrNull()
            return BuiltinTtsPaths(
                filesRoot = context.filesDir,
                cacheRoot = context.cacheDir,
                importRoot = File(external ?: context.filesDir, "tts-import")
            )
        }
    }
}

/** 引擎状态（失败与降级矩阵 §8 的判定源） */
enum class BuiltinEngineState {
    NONE,
    DOWNLOADING,
    READY,
    CORRUPT,
    LOW_MEM
}
