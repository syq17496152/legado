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
    val fallbackVoiceName: String = ""
) {
    val valid: Boolean
        get() = id.isNotBlank() && title.isNotBlank() &&
                engine in SUPPORTED_ENGINES && sampleRate > 0 && files.isNotEmpty()

    /** 只有克隆档需要参考音频；vits/matcha 为单声档 */
    val requiresReference: Boolean
        get() = engine == ENGINE_ZIPVOICE

    companion object {
        const val ENGINE_VITS = "vits"
        const val ENGINE_ZIPVOICE = "zipvoice"
        const val ENGINE_MATCHA = "matcha"
        val SUPPORTED_ENGINES = setOf(ENGINE_VITS, ENGINE_ZIPVOICE, ENGINE_MATCHA)
    }
}

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
