package io.legado.app.help.storage

import androidx.annotation.Keep
import com.google.gson.Gson
import io.legado.app.help.tts.builtin.BuiltinHash
import java.io.File
import java.util.zip.ZipFile

/**
 * 离线资产包（4.10）：manifest + 原子安装 + 配额/校验/卸载。
 *
 * 对用户的直接价值：把「哪些资源删了能重新下、哪些删了就找不回」写进数据，
 * 而不是靠文案约定（资源中心/备份清单都读这一份口径）。
 */
@Keep
data class AssetManifest(
    val schemaVersion: Int = CURRENT_SCHEMA,
    val id: String = "",
    val title: String = "",
    /** model | voice | ner | eye | bgm | template | text | runtime */
    val kind: String = KIND_MODEL,
    /** builtin（随包）/ download（可重下）/ import（必须用户导入或备份） */
    val distribution: String = DIST_IMPORT,
    val sha256: String = "",
    val sizeBytes: Long = 0,
    val license: String = "",
    val sourceUrl: String = "",
    /** 生成方式留痕（素材禁令要求：图片/素材必须可由脚本重放） */
    val generatedBy: String = "",
    val installedAt: Long = 0
) {
    companion object {
        const val CURRENT_SCHEMA = 1

        const val KIND_MODEL = "model"
        const val KIND_VOICE = "voice"
        const val KIND_NER = "ner"
        const val KIND_EYE = "eye"
        const val KIND_BGM = "bgm"
        const val KIND_TEMPLATE = "template"
        const val KIND_TEXT = "text"

        const val DIST_BUILTIN = "builtin"
        const val DIST_DOWNLOAD = "download"
        const val DIST_IMPORT = "import"
    }
}

/**
 * 资产归属目录（公共资源可重下 vs 用户私有必须导入/备份）。
 * 这份表是「用户问题的直接答案」，资源页与备份选择器共用。
 */
object AssetCatalog {

    data class Entry(
        val id: String,
        val title: String,
        val kind: String,
        val distribution: String,
        val reason: String
    )

    /** 可删除后重新下载（服务端/官方源可再取） */
    val downloadable: List<Entry> = listOf(
        Entry("tts-fast", "极速单声语音包", AssetManifest.KIND_MODEL, AssetManifest.DIST_DOWNLOAD, "官方语音包，删了可再下"),
        Entry("tts-clone", "高质量克隆语音包", AssetManifest.KIND_MODEL, AssetManifest.DIST_DOWNLOAD, "官方语音包，删了可再下"),
        Entry("tts-multi", "多音色语音包", AssetManifest.KIND_MODEL, AssetManifest.DIST_DOWNLOAD, "官方语音包，删了可再下"),
        Entry("tts-hq", "多音色高质量语音包", AssetManifest.KIND_MODEL, AssetManifest.DIST_DOWNLOAD, "官方语音包，删了可再下"),
        Entry("llm", "本地 AI 模型", AssetManifest.KIND_MODEL, AssetManifest.DIST_DOWNLOAD, "GGUF 可重新下载或重新导入"),
        Entry("ner", "离线人名模型", AssetManifest.KIND_NER, AssetManifest.DIST_DOWNLOAD, "模型包可重新下载"),
        Entry("eye", "眼动追踪模型", AssetManifest.KIND_EYE, AssetManifest.DIST_DOWNLOAD, "开启眼动时按需下载"),
        Entry("mermaid", "文档图表库", AssetManifest.KIND_TEXT, AssetManifest.DIST_DOWNLOAD, "前端资源可重下"),
        Entry("synth-cache", "合成音频缓存", AssetManifest.KIND_TEXT, AssetManifest.DIST_DOWNLOAD, "纯缓存，删了会自动重合成")
    )

    /** 删了找不回：必须由用户导入或备份 */
    val mustImport: List<Entry> = listOf(
        Entry("voices-imported", "导入/自录的音色", AssetManifest.KIND_VOICE, AssetManifest.DIST_IMPORT, "参考音频不在服务端，删了找不回"),
        Entry("casting-templates", "选角模板", AssetManifest.KIND_TEMPLATE, AssetManifest.DIST_IMPORT, "用户自定义规则，仅本地"),
        Entry("bgm", "配乐与音效", AssetManifest.KIND_BGM, AssetManifest.DIST_IMPORT, "用户自有素材"),
        Entry("pron-dict", "发音词典", AssetManifest.KIND_TEXT, AssetManifest.DIST_IMPORT, "用户纠正数据"),
        Entry("split-rules", "文本切分规则", AssetManifest.KIND_TEXT, AssetManifest.DIST_IMPORT, "用户调参结果"),
        Entry("role-binding", "角色与音色绑定", AssetManifest.KIND_VOICE, AssetManifest.DIST_IMPORT, "配声结果，重算会变")
    )

    fun all(): List<Entry> = downloadable + mustImport

    fun entryOf(id: String): Entry? = all().firstOrNull { it.id == id }

    /** 归属标签（资源页/备份清单统一文案） */
    fun label(distribution: String): String = when (distribution) {
        AssetManifest.DIST_BUILTIN -> "内置"
        AssetManifest.DIST_DOWNLOAD -> "可重下"
        else -> "需备份"
    }
}

/**
 * 资产安装器：配额检查 → 校验 → staging → 原子替换 → manifest 落盘。
 *
 * 关键安全点（都有单测）：
 * - **空间不足直接拒绝**（不留半装）；
 * - sha256 不匹配拒绝；
 * - staging 目录在失败时清理；
 * - 旧包（目录已存在）替换时先挪 `.bak` 再删，避免替换中途失败导致目录为空。
 */
class AssetInstaller(
    private val root: File,
    private val availableBytes: () -> Long = { Long.MAX_VALUE },
    private val now: () -> Long = { System.currentTimeMillis() }
) {

    data class Result(val ok: Boolean, val message: String)

    fun installed(): List<AssetManifest> {
        val dirs = root.listFiles()?.filter { it.isDirectory && !it.name.startsWith(".") } ?: return emptyList()
        return dirs.mapNotNull { dir -> readManifest(dir) }.sortedBy { it.id }
    }

    fun manifestOf(id: String): AssetManifest? = readManifest(targetOf(id))

    fun install(source: File, manifest: AssetManifest): Result {
        if (manifest.id.isBlank()) return Result(false, "缺少资产 id")
        if (!source.exists()) return Result(false, "源文件不存在")
        val sha = sha256(source)
        if (manifest.sha256.isNotBlank() && manifest.sha256 != sha) {
            return Result(false, "校验失败：sha256 不匹配")
        }
        val size = if (manifest.sizeBytes > 0) manifest.sizeBytes else source.length()
        if (size > availableBytes()) return Result(false, "空间不足：需要 $size 字节")
        val staging = File(root, ".staging").apply { mkdirs() }
        val part = File(staging, "${manifest.id}.part")
        val target = targetOf(manifest.id)
        return runCatching {
            // 1) 先完整解到 staging（失败不会污染现有资产）
            if (part.exists()) part.deleteRecursively()
            part.mkdirs()
            unpack(source, part)
            // 2) 原子替换：旧目录先挪 .bak，撤换成功后再删
            val backup = File(root, "${manifest.id}.bak")
            if (backup.exists()) backup.deleteRecursively()
            if (target.exists() && !moveDir(target, backup)) {
                return Result(false, "替换失败：旧资产被占用")
            }
            if (!moveDir(part, target)) {
                // 回滚：把旧资产挪回来
                moveDir(backup, target)
                return Result(false, "替换失败：无法写入目标目录")
            }
            backup.deleteRecursively()
            // 3) manifest 落盘（安装成功才算）
            val stored = manifest.copy(
                sha256 = manifest.sha256.ifBlank { sha },
                sizeBytes = size,
                installedAt = now()
            )
            writeManifest(target, stored)
            Result(true, "已安装 ${stored.title.ifBlank { stored.id }}")
        }.getOrElse { error ->
            part.deleteRecursively()
            Result(false, "安装失败：${error.message}")
        }
    }

    fun uninstall(id: String): Boolean = runCatching {
        targetOf(id).deleteRecursively()
    }.getOrDefault(false)

    private fun targetOf(id: String): File = File(root, safeId(id))

    private fun readManifest(dir: File): AssetManifest? {
        val file = File(dir, MANIFEST_FILE)
        if (!file.isFile) return null
        return runCatching { Gson().fromJson(file.readText(), AssetManifest::class.java) }.getOrNull()
    }

    private fun writeManifest(dir: File, manifest: AssetManifest) {
        dir.mkdirs()
        File(dir, MANIFEST_FILE).writeText(Gson().toJson(manifest))
    }

    /** 支持 zip（解包）与单文件；目录源直接递归拷贝 */
    private fun unpack(source: File, target: File) {
        when {
            source.isDirectory -> copyDir(source, target)
            source.name.endsWith(".zip") -> unzip(source, target)
            else -> source.copyTo(File(target, source.name), overwrite = true)
        }
    }

    private fun unzip(source: File, target: File) {
        ZipFile(source).use { zip ->
            zip.entries().asSequence().forEach { entry ->
                val out = File(target, entry.name)
                // 防 zip slip：规范化后必须在 target 内
                if (!out.canonicalPath.startsWith(target.canonicalPath + File.separator)) return@forEach
                if (entry.isDirectory) out.mkdirs() else {
                    out.parentFile?.mkdirs()
                    zip.getInputStream(entry).use { input ->
                        out.outputStream().use { output -> input.copyTo(output) }
                    }
                }
            }
        }
    }

    private fun copyDir(source: File, target: File) {
        source.listFiles()?.forEach { file ->
            val out = File(target, file.name)
            if (file.isDirectory) {
                out.mkdirs()
                copyDir(file, out)
            } else {
                file.copyTo(out, overwrite = true)
            }
        }
    }

    /**
     * 目录移动：优先文件系统原子 move；不支持时退回「拷贝后删源」。
     * 单测实证：Windows 下非空目录 `File.renameTo` 会偶发失败 ⇒ 不能只用它。
     */
    private fun moveDir(from: File, to: File): Boolean = runCatching {
        java.nio.file.Files.move(
            from.toPath(), to.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING
        )
        true
    }.getOrElse {
        runCatching {
            to.mkdirs()
            from.copyRecursively(to, overwrite = true)
            from.deleteRecursively()
            true
        }.getOrDefault(false)
    }

    private fun safeId(id: String): String =
        if (id.length <= 40 && id.all { it.isLetterOrDigit() || it == '-' || it == '_' }) id
        else BuiltinHash.md5Hex(id)

    private fun sha256(file: File): String = runCatching {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    }.getOrDefault("")

    companion object {
        const val MANIFEST_FILE = "manifest.json"
    }
}
