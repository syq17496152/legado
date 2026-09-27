package io.legado.app.help.image

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 图片保存/分享的**语义命名上下文**（来源 → 文章 → 序号）。
 */
data class ImageNameContext(
    /** 订阅源名（无则回落通用名） */
    val sourceName: String? = null,
    /** 所属文章标题（图片画布按文章分段，标题即「章节」语义） */
    val articleTitle: String? = null,
    /** 该图在整篇（多文章拼接）图片序列中的序号（1 起） */
    val index: Int? = null,
)

/**
 * W5 6.5 / REQ-24：图片保存文件名规范化（纯逻辑，可 JVM 单测）。
 *
 * 为什么必须改（实证根因）：原实现只写 `${AppConst.fileNameFormat.format(Date())}.jpg`
 * （`AppConst.fileNameFormat` = `yy-MM-dd-HH-mm-ss`，**秒级**），且落盘走
 * `Uri.writeBytes` → `DocumentUtils.createFileIfNotExist`（**同名复用已存在文档** ⇒ 同名即覆盖）
 * ⇒ ① 文件名无任何语义（事后无法辨认）；② **同一秒内保存两张会静默覆盖**。
 *
 * 命名口径：`{来源}_{文章}_{p序号}_{yyMMdd_HHmmss_SSS}-{urlHash6}.{ext}`
 * - 去非法字符：Windows/SAF 禁用字符与换行一律替换；连续空白压缩；首尾点/空格去除；
 * - **同名去重**：`urlHash6` 保证不同图片不同名；同图同毫秒重存再由 [uniqueIn] 追加 `_n` 兜底。
 */
object ImageFileNameBuilder {

    private val ILLEGAL_CHARS = Regex("[\\\\/:*?\"<>|\\r\\n\\t\\u0000-\\u001F]")
    private val BLANKS = Regex("\\s+")
    private const val MAX_BASE_LENGTH = 48
    private const val FALLBACK_BASE = "image"
    private const val STAMP_PATTERN = "yyMMdd_HHmmss_SSS"
    private val KNOWN_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "heic")

    /** 生成语义化文件名（含毫秒时间戳与 URL 摘要，保证不同图不同名）。 */
    fun build(
        context: ImageNameContext,
        url: String,
        at: Long = System.currentTimeMillis(),
    ): String {
        val base = listOfNotNull(
            sanitize(context.sourceName),
            sanitize(context.articleTitle),
            context.index?.takeIf { it > 0 }?.let { "p%03d".format(it) },
        ).filter { it.isNotBlank() }
            .joinToString("_")
            .take(MAX_BASE_LENGTH)
            .ifBlank { FALLBACK_BASE }
        val stamp = SimpleDateFormat(STAMP_PATTERN, Locale.US).format(Date(at))
        return "${base}_$stamp-${urlDigest(url)}.${extensionOf(url)}"
    }

    /** 清洗非法字符（返回空串表示无法使用，由调用方回落）。 */
    fun sanitize(raw: String?): String {
        val text = raw?.takeIf { it.isNotBlank() } ?: return ""
        return text
            .replace(ILLEGAL_CHARS, "_")
            .replace(BLANKS, "")
            .trim('.', ' ')
    }

    /** 从 URL 推扩展名（无扩展名/未知一律 jpg，与既有保存实现一致）。 */
    fun extensionOf(url: String): String {
        val path = url.substringBefore('?').substringBefore('#')
        val ext = path.substringAfterLast('.', "").lowercase(Locale.US)
        return ext.takeIf { it in KNOWN_EXTENSIONS } ?: "jpg"
    }

    /**
     * 同名去重：候选名已存在时依次追加 `_1`、`_2`…（扩展名保持在末尾）。
     */
    fun uniqueIn(candidate: String, existing: Collection<String>): String {
        if (candidate !in existing) return candidate
        val dot = candidate.lastIndexOf('.')
        val stem = if (dot > 0) candidate.substring(0, dot) else candidate
        val ext = if (dot > 0) candidate.substring(dot) else ""
        var i = 1
        while ("${stem}_$i$ext" in existing) i++
        return "${stem}_$i$ext"
    }

    /** URL 摘要（6 位十六进制）：同一图片稳定、不同图片区分。 */
    private fun urlDigest(url: String): String =
        url.hashCode().toUInt().toString(16).padStart(8, '0').take(6)

    /**
     * 面向 SAF 目录的去重入口：读取目标目录已有文件名后再走 [uniqueIn]。
     *
     * 为什么必要：落盘走 `Uri.writeBytes` → `DocumentUtils.createFileIfNotExist`
     * （**同名复用已存在文档 ⇒ 同名即覆盖，无 UI 提示**），故必须在写之前先消解重名。
     */
    fun uniqueNameFor(context: Context, dirUri: Uri, candidate: String): String {
        val existing = kotlin.runCatching {
            DocumentFile.fromTreeUri(context, dirUri)?.listFiles()?.mapNotNull { it.name }
        }.getOrNull().orEmpty()
        return uniqueIn(candidate, existing)
    }
}