package io.legado.app.help.llm

import android.content.Context
import cn.hutool.crypto.digest.DigestUtil
import java.io.File
import java.io.RandomAccessFile

/**
 * 2.3 端侧 LLM 模型管理：`files/models/llm/<id>/model.gguf`。
 *
 * 口径（design AD-25 / 6.5）：
 * - **模型是可重建缓存**：不进备份，删除后可从内置目录二次下载或从本地导入；
 * - **导入必须校验**：GGUF 魔数 + 非空大小 +（可选）sha256，坏文件绝不进"已安装"清单；
 * - **原子落盘**：先写 `*.part`，校验通过再 rename，半写文件不参与扫描（防"看起来装了其实坏的"）。
 *
 * 已知上限：sha256 校验按整文件读取，几百 MB 模型导入会多花一次完整读盘（在 IO 线程，不阻塞 UI）。
 */
class LlmModelManager(private val root: File) {

    data class Model(
        val id: String,
        val dir: File,
        val modelFile: File,
        val bytes: Long,
        val displayName: String
    )

    sealed class ImportResult {
        data class Success(val model: Model) : ImportResult()
        data class Failure(val code: ErrorCode, val message: String) : ImportResult()
    }

    enum class ErrorCode { EMPTY, BAD_MAGIC, ALREADY_EXISTS, SHA_MISMATCH, IO }

    /** 扫描已安装模型（只认 `model.gguf` + 有效魔数；`.part/.tmp` 一律忽略） */
    fun scan(): List<Model> =
        root.listFiles { f -> f.isDirectory && !f.name.startsWith(".") }
            ?.mapNotNull { dir ->
                val file = File(dir, MODEL_FILE)
                if (!file.isFile || !isGguf(file)) return@mapNotNull null
                Model(
                    id = dir.name,
                    dir = dir,
                    modelFile = file,
                    bytes = file.length(),
                    displayName = dir.name
                )
            }
            ?.sortedBy { it.displayName }
            .orEmpty()

    fun find(id: String): Model? = scan().firstOrNull { it.id == id }

    fun totalBytes(): Long = scan().sumOf { it.bytes }

    /**
     * 从本地文件导入（复制，不改源文件）。
     * @param expectedSha256 非空时强校验（内置目录下载用；用户手导可留空）
     */
    fun importModel(
        source: File,
        id: String,
        displayName: String = "",
        expectedSha256: String = ""
    ): ImportResult {
        if (id.isBlank()) return ImportResult.Failure(ErrorCode.IO, "模型 id 不能为空")
        if (!source.isFile || source.length() <= 0L) {
            return ImportResult.Failure(ErrorCode.EMPTY, "源文件不存在或为空")
        }
        if (!isGguf(source)) {
            return ImportResult.Failure(ErrorCode.BAD_MAGIC, "不是有效的 GGUF 模型（魔数不匹配）")
        }
        val dir = File(root, id)
        if (dir.exists()) return ImportResult.Failure(ErrorCode.ALREADY_EXISTS, "已存在同名模型：$id")
        val partDir = File(root, "$id.part")
        partDir.deleteRecursively()
        return runCatching {
            partDir.mkdirs()
            val target = File(partDir, MODEL_FILE)
            source.copyTo(target, overwrite = true)
            if (!isGguf(target)) {
                partDir.deleteRecursively()
                return ImportResult.Failure(ErrorCode.BAD_MAGIC, "复制后文件损坏")
            }
            if (expectedSha256.isNotBlank() && !sha256(target).equals(expectedSha256, true)) {
                partDir.deleteRecursively()
                return ImportResult.Failure(ErrorCode.SHA_MISMATCH, "sha256 校验不通过")
            }
            root.mkdirs()
            if (!partDir.renameTo(dir)) {
                // 个别文件系统 rename 跨目录失败：先删空壳再移动
                dir.deleteRecursively()
                if (!partDir.renameTo(dir)) {
                    partDir.deleteRecursively()
                    return ImportResult.Failure(ErrorCode.IO, "落盘失败（rename 被拒绝）")
                }
            }
            File(dir, DISPLAY_NAME_FILE).writeText(displayName.ifBlank { id })
            val file = File(dir, MODEL_FILE)
            ImportResult.Success(
                Model(id, dir, file, file.length(), displayName.ifBlank { id })
            )
        }.getOrElse { e ->
            partDir.deleteRecursively()
            ImportResult.Failure(ErrorCode.IO, e.localizedMessage ?: e.javaClass.simpleName)
        }
    }

    fun remove(id: String): Boolean {
        val dir = File(root, id)
        if (!dir.exists()) return false
        return runCatching { dir.deleteRecursively() }.getOrDefault(false)
    }

    /** GGUF 魔数（ASCII "GGUF"，即 0x46554747 little-endian） */
    private fun isGguf(file: File): Boolean = runCatching {
        if (!file.isFile || file.length() < 16) return false
        RandomAccessFile(file, "r").use { raf ->
            val magic = ByteArray(4)
            raf.readFully(magic)
            magic[0] == 'G'.code.toByte() && magic[1] == 'G'.code.toByte() &&
                    magic[2] == 'U'.code.toByte() && magic[3] == 'F'.code.toByte()
        }
    }.getOrDefault(false)

    private fun sha256(file: File): String = runCatching {
        file.inputStream().use { DigestUtil.digester("SHA-256").digestHex(it) }
    }.getOrDefault("")

    companion object {
        private const val MODEL_FILE = "model.gguf"
        private const val DISPLAY_NAME_FILE = "display_name.txt"

        fun forApp(context: Context): LlmModelManager =
            LlmModelManager(File(context.filesDir, "models/llm"))
    }
}
