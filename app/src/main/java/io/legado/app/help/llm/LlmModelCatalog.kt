package io.legado.app.help.llm

import android.content.Context
import androidx.annotation.Keep
import com.google.gson.JsonParser
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonArray

/**
 * 端侧 LLM 档位目录（1.2 落地的 `assets/llm/llm_models.json` 的运行时读取）。
 *
 * 为什么要有这个类：目录此前只有单测在读，App 内**没有消费者** ⇒
 * 「哪些模型可以二次下载、哪些必须导入」只能靠文档，用户点不到。
 * 这里把目录解析成强类型，并把「可下载」的判据收紧为
 * **`distribution=download` + 有下载地址 + 有 sha256**（缺一不可，否则下载完无法校验）。
 */
object LlmModelCatalog {

    @Keep
    data class Profile(
        val id: String = "",
        val title: String = "",
        val family: String = "",
        val quant: String = "",
        val sizeBytes: Long = 0L,
        val sha256: String = "",
        val distribution: String = "",
        val contextTokens: Int = 0,
        val chatTemplate: String = "",
        val stopTokens: List<String> = emptyList(),
        val license: String = "",
        val sourceUrl: String = "",
        val recommended: Boolean = false
    ) {
        /** 可二次下载 = 有公开源 + 有 sha256 + 标注 download（三者缺一不可） */
        val downloadable: Boolean
            get() = distribution == DISTRIBUTION_DOWNLOAD &&
                    sourceUrl.isNotBlank() &&
                    sha256.isNotBlank()
    }

    const val DISTRIBUTION_DOWNLOAD = "download"
    const val DISTRIBUTION_IMPORT = "import"

    private const val ASSET_PATH = "llm/llm_models.json"

    /** 纯函数（JVM 可测）：解析目录文本，坏数据返回空列表而不抛 */
    fun parse(json: String?): List<Profile> {
        if (json.isNullOrBlank()) return emptyList()
        return runCatching {
            val array = JsonParser.parseString(json).asJsonObject.getAsJsonArray("profiles")
            GSON.fromJsonArray<Profile>(array.toString()).getOrDefault(emptyList())
        }.getOrDefault(emptyList())
    }

    fun load(context: Context): List<Profile> = runCatching {
        context.assets.open(ASSET_PATH).bufferedReader().use { it.readText() }
    }.let { parse(it.getOrNull()) }

    /** 推荐且可下载的档位（设置页「下载」按钮的唯一判据） */
    fun recommendedDownloadable(profiles: List<Profile>): Profile? =
        profiles.firstOrNull { it.recommended && it.downloadable }
}
