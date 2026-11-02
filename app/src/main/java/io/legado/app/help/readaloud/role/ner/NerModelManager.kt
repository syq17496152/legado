package io.legado.app.help.readaloud.role.ner

import android.content.Context
import android.net.Uri
import io.legado.app.constant.AppLog
import io.legado.app.help.readaloud.role.NerTextEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.ZipInputStream

/**
 * 端侧 NER 模型管理（AD-16）：模型下载/导入，**不进 APK**。
 *
 * 目录：`files/ner/lac/`，包含：
 * - `lac_encoder.onnx`（LAC 编码器，约 30MB）
 * - `lac_crf_transitions.txt`（CRF 转移矩阵，59×59）
 * - `word.dic` / `tag.dic` / `q2b.dic`（词表与标签表）
 *
 * 无模型时 [NerTextEngine] 自动回落姓氏规则路径（兜底，不再是主路径）。
 */
object NerModelManager {

    const val MODEL_DIR = "ner/lac"

    val REQUIRED_FILES = listOf(
        "lac_encoder.onnx",
        "lac_crf_transitions.txt",
        "word.dic",
        "tag.dic",
        "q2b.dic"
    )

    data class ImportResult(
        val ok: Boolean,
        val files: Int,
        val message: String
    )

    @Volatile
    private var engine: LacNerEngine? = null

    @Volatile
    private var enginePath: String? = null

    fun dir(context: Context): File = File(context.filesDir, MODEL_DIR)

    fun isReady(context: Context): Boolean {
        val modelDir = dir(context)
        return REQUIRED_FILES.all { name ->
            File(modelDir, name).let { it.isFile && it.length() > 0L }
        }
    }

    /** 单例引擎（首次调用创建 ORT Session；失败返回 null 并回落规则） */
    fun engine(context: Context): LacNerEngine? {
        if (!isReady(context)) return null
        val path = dir(context).absolutePath
        synchronized(this) {
            if (engine == null || enginePath != path) {
                runCatching { engine?.close() }
                engine = null
                val started = System.currentTimeMillis()
                engine = runCatching { LacNerEngine(dir(context)) }
                    .onSuccess {
                        val cost = System.currentTimeMillis() - started
                        AppLog.put("NER 模型就绪（LAC-ONNX）加载 ${cost}ms")
                    }
                    .onFailure { AppLog.put("NER 模型加载失败：${it.stackTraceToString()}") }
                    .getOrNull()
                enginePath = path
            }
            return engine
        }
    }

    fun reset() {
        synchronized(this) {
            runCatching { engine?.close() }
            engine = null
            enginePath = null
        }
        // 模型切换后旧推理结果不可复用（段落缓存与模型版本绑定）
        NerTextEngine.clearSignalCache()
    }

    /** 导入模型 ZIP（可从任意子目录读，只取文件名匹配的文件），原子替换旧模型 */
    suspend fun importZip(context: Context, uri: Uri): ImportResult =
        withContext(Dispatchers.IO) {
            runCatching {
                val tempDir = File(context.filesDir, "$MODEL_DIR.tmp")
                tempDir.deleteRecursively()
                tempDir.mkdirs()
                var count = 0
                context.contentResolver.openInputStream(uri)?.use { input ->
                    ZipInputStream(input).use { zip ->
                        while (true) {
                            val entry = zip.nextEntry ?: break
                            if (!entry.isDirectory) {
                                val name = entry.name.substringAfterLast('/')
                                if (name in REQUIRED_FILES) {
                                    File(tempDir, name).outputStream().use { out -> zip.copyTo(out) }
                                    count++
                                }
                            }
                            zip.closeEntry()
                        }
                    }
                }
                val missing = REQUIRED_FILES.filter { !File(tempDir, it).let { f -> f.isFile && f.length() > 0 } }
                require(missing.isEmpty()) { "压缩包缺少 NER 模型文件：${missing.joinToString()}" }
                val target = dir(context)
                target.deleteRecursively()
                if (!tempDir.renameTo(target)) {
                    tempDir.copyRecursively(target, overwrite = true)
                    tempDir.deleteRecursively()
                }
                reset()
                ImportResult(true, count, "NER 模型导入成功（$count 个文件，重启识别后生效）")
            }.getOrElse {
                ImportResult(false, 0, "NER 模型导入失败：${it.message}")
            }
        }
}
