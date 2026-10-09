package io.legado.app.model.localBook.epubcore.template

import io.legado.app.help.config.NioFileExchange
import java.io.File
import java.io.FileOutputStream

/**
 * 模板素材库（epub-md-rich-rendering 阶段 4.3；台账 X2 / C3）。
 *
 * **为什么自建**：archive v15 的素材库与它自己的 infra 强耦合（缺点 X2），
 * 本仓改为**复用既有原语**：原子移动复用 [NioFileExchange]（与 `AtomicTextFileStore` 同一实现），
 * 只保留"按模板分目录 + 容量上限 + 损坏不空库"三件事。
 *
 * 契约：
 * - 目录 `<root>/<templateId>/<alias>.<ext>`（templateId 经**安全化**处理，杜绝路径穿越）；
 * - **原子写**：staging → 长度校验 → 原子替换（中途被杀不留半成品）；
 * - **容量上限**：按模板维度限额，超限拒写（**不做静默截断**）；
 * - **损坏不空库**：单条读取失败只影响该条（返回 null），不清空整个模板目录。
 *
 * 纯 JVM（`java.io` + 既有 [NioFileExchange]）⇒ 可在临时目录上单测。
 */
internal class ReaderAssetStore(
    private val root: File,
    private val maxBytesPerTemplate: Long = DefaultMaxBytesPerTemplate
) {

    /** 写入（原子）；超限或 IO 失败返回 false，**不抛异常**（模板不可信，写失败不应中断阅读）。 */
    fun writeAsset(templateId: String, alias: String, extension: String, bytes: ByteArray): Boolean {
        val dir = templateDir(templateId) ?: return false
        if (bytes.size > maxBytesPerTemplate) return false
        val target = assetFile(dir, alias, extension) ?: return false
        val staging = File(dir, ".${target.name}.staging")
        return runCatching {
            if (!dir.exists() && !dir.mkdirs()) return false
            deleteQuietly(staging)
            FileOutputStream(staging).use { output ->
                output.write(bytes)
                output.flush()
                output.fd.sync()
            }
            if (staging.length() != bytes.size.toLong()) {
                deleteQuietly(staging)
                return false
            }
            deleteQuietly(target)
            NioFileExchange.move(staging, target)
            true
        }.getOrElse {
            deleteQuietly(staging)
            false
        }
    }

    /** 读取；缺失或读取失败返回 null（**单条失败不清库**）。 */
    fun readAsset(templateId: String, alias: String, extension: String): ByteArray? {
        val dir = templateDir(templateId) ?: return null
        val file = assetFile(dir, alias, extension) ?: return null
        if (!file.isFile) return null
        return runCatching { file.readBytes() }.getOrNull()
    }

    fun deleteAsset(templateId: String, alias: String, extension: String): Long {
        val dir = templateDir(templateId) ?: return 0L
        val file = assetFile(dir, alias, extension) ?: return 0L
        val size = if (file.isFile) file.length() else 0L
        deleteQuietly(file)
        return size
    }

    /** 删除整个模板的素材目录。 */
    fun deleteTemplate(templateId: String): Int {
        val dir = templateDir(templateId) ?: return 0
        if (!dir.isDirectory) return 0
        val count = dir.listFiles()?.size ?: 0
        dir.deleteRecursively()
        return count
    }

    fun clear(): Int {
        if (!root.isDirectory) return 0
        val count = root.listFiles()?.size ?: 0
        root.deleteRecursively()
        return count
    }

    fun templateBytes(templateId: String): Long {
        val dir = templateDir(templateId) ?: return 0L
        return dir.listFiles()?.sumOf { if (it.isFile) it.length() else 0L } ?: 0L
    }

    fun listAliases(templateId: String): List<String> {
        val dir = templateDir(templateId) ?: return emptyList()
        return dir.listFiles { file -> file.isFile && !file.name.startsWith(".") }
            ?.map { it.name }
            ?.sorted()
            .orEmpty()
    }

    /** 模板目录（含安全化 templateId）；非法 id 返回 null。 */
    private fun templateDir(templateId: String): File? {
        val safe = safeSegment(templateId) ?: return null
        return File(root, safe)
    }

    private fun assetFile(dir: File, alias: String, extension: String): File? {
        val safeAlias = safeSegment(alias) ?: return null
        val safeExtension = safeSegment(extension) ?: return null
        return File(dir, "$safeAlias.$safeExtension")
    }

    /** 路径段安全化：只允许字母/数字/`-`/`_`/`.`，拒点段与空段（杜绝穿越）。 */
    private fun safeSegment(value: String): String? {
        val trimmed = value.trim()
        if (trimmed.isEmpty() || trimmed == "." || trimmed == "..") return null
        if (trimmed.length > MaxSegmentChars) return null
        val allowed = trimmed.all { char ->
            char.isLetterOrDigit() || char == '-' || char == '_' || char == '.'
        }
        return if (allowed) trimmed else null
    }

    private fun deleteQuietly(file: File) {
        runCatching { if (file.exists()) NioFileExchange.delete(file) }
    }

    companion object {
        const val DefaultMaxBytesPerTemplate = 64L * 1024 * 1024
        private const val MaxSegmentChars = 96
    }
}