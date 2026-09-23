package io.legado.app.ui.highlight

import io.legado.app.ui.book.read.config.HighlightRule
import io.legado.app.ui.book.read.config.HighlightRuleStore
import io.legado.app.utils.GSON
import io.legado.app.utils.Utf8Sha256
import io.legado.app.utils.compress.ZipUtils
import io.legado.app.utils.externalFiles
import io.legado.app.utils.fromJsonArray
import android.content.Context
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import splitties.init.appCtx

/**
 * R27（B6）高亮规则**包文件**导入导出（复用 R21 解压防护）。
 *
 * 与既有「剪贴板 JSON」路径的关系：**并存**——剪贴板适合少量规则快速粘贴；
 * 包文件适合整套规则分发给他人（含 envelope 元信息，可校验完整性/版本）。
 *
 * 包结构（envelope，`*.zip`）：
 * - `manifest.json`：`{format, version, count, sha256}`（format 固定串；sha256 为规则 JSON 的摘要）
 * - `highlightRules.json`：规则数组（与剪贴板导出同一序列化口径）
 *
 * 安全/健壮性（验收口径：**四类失败均中止且零写入**）：
 * - 解压走 `ZipUtils.unZipToPath` ⇒ 自动获得 R21 防护（ZipSlip / 单条目 / 总量 / 条目数）；
 * - 校验链：manifest 存在 → format 匹配 → version 受支持 → sha256 匹配 → count 匹配 → 规则可解析且非空；
 *   任一失败抛异常，**调用方在全部校验通过后才写存储** ⇒ 存储零写入。
 */
object HighlightRulePack {

    const val FORMAT = "legado.highlight.rules"
    const val VERSION = 1
    const val ENTRY_MANIFEST = "manifest.json"
    const val ENTRY_RULES = "highlightRules.json"

    /** 解压工作目录名（cacheDir 下；导入完成后由调用方清理） */
    const val WORK_DIR_NAME = "highlight_rule_pack"

    data class Manifest(
        val format: String = FORMAT,
        val version: Int = VERSION,
        val count: Int = 0,
        val sha256: String = ""
    )

    class PackException(message: String) : Exception(message)

    /** 导出为包字节（可写文件或直接分享）。 */
    fun export(bytes: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry(ENTRY_RULES))
            zip.write(bytes)
            zip.closeEntry()
            zip.putNextEntry(ZipEntry(ENTRY_MANIFEST))
            zip.write(
                GSON.toJson(
                    Manifest(count = countOf(bytes), sha256 = Utf8Sha256.hex(bytes))
                ).toByteArray(Charsets.UTF_8)
            )
            zip.closeEntry()
        }
        return out.toByteArray()
    }

    /** 导出规则列表为包字节 */
    fun exportRules(rules: List<HighlightRule>): ByteArray = export(rulesJson(rules).toByteArray(Charsets.UTF_8))

    /** 规则列表 → JSON 文本（与剪贴板导出口径一致） */
    fun rulesJson(rules: List<HighlightRule>): String = GSON.toJson(rules)

    /**
     * 从解压目录读取并校验规则（**全部校验通过才返回**；任一失败抛 [PackException]/[SecurityException]）。
     */
    fun readFromDir(dir: File): List<HighlightRule> {
        val manifestFile = File(dir, ENTRY_MANIFEST)
        val rulesFile = File(dir, ENTRY_RULES)
        if (!manifestFile.isFile || !rulesFile.isFile) {
            throw PackException("包内缺少必要条目")
        }
        val manifest = GSON.fromJson(manifestFile.readText(), Manifest::class.java)
            ?: throw PackException("manifest 无法解析")
        if (manifest.format != FORMAT) {
            throw PackException("包格式不匹配: ${manifest.format}")
        }
        if (manifest.version > VERSION || manifest.version <= 0) {
            throw PackException("包版本不支持: ${manifest.version}")
        }
        val payload = rulesFile.readBytes()
        if (manifest.sha256.isNotBlank() && Utf8Sha256.hex(payload) != manifest.sha256) {
            throw PackException("内容校验失败（摘要不一致）")
        }
        val rules = GSON.fromJsonArray<HighlightRule>(String(payload, Charsets.UTF_8)).getOrNull()
        if (rules.isNullOrEmpty()) {
            throw PackException("包内规则为空或无法解析")
        }
        if (manifest.count > 0 && manifest.count != rules.size) {
            throw PackException("规则条数与清单不符: ${rules.size} != ${manifest.count}")
        }
        return rules
    }

    /**
     * 从包文件导入（解压 → 校验），返回规则列表；**不写存储**（由调用方在校验通过后一次性保存）。
     *
     * @param limits 解压阈值（默认 R21 默认档；测试可传更严档制造「超限中止」）
     */
    fun importFromZip(
        context: Context,
        zip: File,
        limits: ZipUtils.UnzipLimits = ZipUtils.DEFAULT_UNZIP_LIMITS
    ): List<HighlightRule> {
        val workDir = File(context.cacheDir, WORK_DIR_NAME)
        return importInto(zip, workDir, limits)
    }

    /**
     * 解压+校验的**纯文件**版本（无 Android 依赖，可 JVM 单测；context 版即其薄封装）。
     *
     * 解压走 `ZipUtils.unZipToPath` ⇒ 自动获得 R21 防护（ZipSlip/单条目/总量/条目数）。
     */
    fun importInto(
        zip: File,
        workDir: File,
        limits: ZipUtils.UnzipLimits = ZipUtils.DEFAULT_UNZIP_LIMITS
    ): List<HighlightRule> {
        workDir.deleteRecursively()
        workDir.mkdirs()
        try {
            ZipUtils.unZipToPath(zip, workDir, limits = limits)
            return readFromDir(workDir)
        } finally {
            workDir.deleteRecursively()
        }
    }

    /** 写入规则到存储（**唯一写入口**：调用方必须只在全部校验通过后调用） */
    fun commit(context: Context, incoming: List<HighlightRule>): Int {
        val current = HighlightRuleStore.load(context).toMutableList()
        val existingIds = current.map { it.id }.toSet()
        val toAdd = incoming.filter { it.id !in existingIds }
        if (toAdd.isNotEmpty()) {
            current.addAll(toAdd)
            HighlightRuleStore.save(context, current)
        }
        return toAdd.size
    }

    /** 导出到应用外部目录（用户可在文件管理中取用），返回落盘文件 */
    fun exportToFile(rules: List<HighlightRule>, timestamp: Long = System.currentTimeMillis()): File {
        val dir = File(appCtx.externalFiles, "highlightRules").apply { mkdirs() }
        val file = File(dir, "highlightRules_$timestamp.zip")
        file.writeBytes(exportRules(rules))
        return file
    }

    private fun countOf(bytes: ByteArray): Int {
        return GSON.fromJsonArray<HighlightRule>(String(bytes, Charsets.UTF_8)).getOrNull()?.size ?: 0
    }
}