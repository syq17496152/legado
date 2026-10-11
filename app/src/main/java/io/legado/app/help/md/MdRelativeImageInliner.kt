package io.legado.app.help.md

import android.net.Uri
import android.provider.DocumentsContract
import android.util.Base64
import io.legado.app.constant.AppLog
import io.legado.app.data.entities.Book
import io.legado.app.help.book.getLocalUri
import io.legado.app.utils.FileDoc
import io.legado.app.utils.exists
import io.legado.app.utils.find
import io.legado.app.utils.isContentScheme
import java.io.File

/**
 * Markdown 相对路径图片内联（epub-md-rich-rendering 补充任务 S3「md 生态补齐」①）。
 *
 * 把 `![图](./img/a.png)` 的目标换成 `data:image/png;base64,…`，让三种承载面都能显示
 * （富渲染 WebView / 模板沙箱 / canvas 的 `<usehtml>`；原因见 [MdImageRefPolicy] 的类注释）。
 *
 * **判定与 IO 分离**：[MdImageRefPolicy] 负责"该不该内联 / 路径归一化 / MIME / 预算"（纯函数，JVM 可测）；
 * 本对象只做"在哪儿找文件、读出来、编码"（平台相关）。
 *
 * 两种文档位置（都由 `book.getLocalUri()` 得出，与阅读链路同源）：
 * - **文件路径**（`file:` / 裸路径）：以父目录为基准，读前做 **canonical 包含性校验**（目录穿越防护）；
 * - **SAF 内容 URI**（`content:`）：由 documentId 推出**父目录的 tree URI** 再按文件名查找。
 *   ⚠️ 单文件授权的 SAF 场景下**没有** tree 权限 ⇒ 查找会失败，此时**返回 null（保持原引用）**，
 *   与改造前行为一致，不会把可显示的图片弄坏。
 *
 * 缓存与预算：同一本书共用一个实例 ⇒ 结果缓存（含"未内联"结论，避免逐章反复失败重试）
 * 与总量预算（[MdImageRefPolicy.MaxInlineTotalBytes]）都跨章生效。
 */
object MdRelativeImageInliner {

    /** 为某本书构造解析器（同一本书共用一个实例：缓存与预算跨章生效）。 */
    fun forBook(book: Book): MdDocumentBuilder.ImageDestinationResolver = Resolver(book)

    /** 文档所在目录的两种形态。 */
    private sealed interface DirRef {
        /** 文件系统目录（`file:` / 裸路径）。 */
        class FileDir(val dir: File) : DirRef {
            /** 规范化目录前缀（含尾分隔符）；取不到时为空串 ⇒ 包含性校验退化为"拒绝"。 */
            val canonicalPrefix: String = runCatching {
                dir.canonicalPath.trimEnd(File.separatorChar) + File.separator
            }.getOrDefault("")
        }

        /** SAF 目录（tree URI 对应的 DocumentFile）。 */
        class TreeDir(val fileDoc: FileDoc) : DirRef
    }

    private class Resolver(private val book: Book) : MdDocumentBuilder.ImageDestinationResolver {

        private val cache = HashMap<String, String?>()
        private var inlinedBytes = 0L

        /** 延迟解析（`book` 可能在首次取内容后才拿到有效本地路径）。 */
        private var dirRefResolved = false
        private var dirRef: DirRef? = null

        @Synchronized
        override fun resolve(src: String): String? {
            if (!MdImageRefPolicy.isInlineCandidate(src)) return null
            // containsKey 判定（缓存值本身可能是 null = "已判定不可内联"）⇒ 不做重复 IO
            if (cache.containsKey(src)) return cache[src]
            val resolved = runCatching { inline(src) }.getOrElse { error ->
                logMiss(src, "io:${error.javaClass.simpleName}")
                null
            }
            cache[src] = resolved
            return resolved
        }

        private fun inline(src: String): String? {
            val ref = directory()
            if (ref == null) {
                logMiss(src, "no-dir")
                return null
            }
            val path = MdImageRefPolicy.percentDecode(
                MdImageRefPolicy.stripQueryAndFragment(
                    MdImageRefPolicy.toFilePath(src.trim())
                )
            )
            val name = MdImageRefPolicy.fileNameOf(path)
            // 只内联已知图片类型：避免把 md 里误写的任意路径读进 data:
            val mime = MdImageRefPolicy.mimeFor(name)
            if (mime == null) {
                logMiss(src, "not-image")
                return null
            }
            // 相对引用必须先归一化：`..` 逃出文档目录一律拒（内联等于把本地文件读进页面 ⇒ 硬约束）
            val relative = if (MdImageRefPolicy.isAbsoluteFilePath(path)) {
                null
            } else {
                val normalized = MdImageRefPolicy.normalizeRelative(path)
                if (normalized == null) {
                    logMiss(src, "traversal")
                    return null
                }
                normalized
            }
            val bytes = readBytes(ref, path, relative, MdImageRefPolicy.fileNameOf(relative ?: path))
            if (bytes == null) {
                logMiss(src, "unreadable")
                return null
            }
            if (bytes.size > MdImageRefPolicy.MaxInlineBytes) {
                logMiss(src, "too-large:${bytes.size}")
                return null
            }
            if (inlinedBytes + bytes.size > MdImageRefPolicy.MaxInlineTotalBytes) {
                logMiss(src, "budget:${inlinedBytes + bytes.size}")
                return null
            }
            inlinedBytes += bytes.size
            logInline(mime, bytes.size)
            return MdImageRefPolicy.dataUri(mime, Base64.encodeToString(bytes, Base64.NO_WRAP))
        }

        private fun readBytes(ref: DirRef, path: String, relative: String?, name: String): ByteArray? =
            when (ref) {
                is DirRef.FileDir -> readFromFileDir(ref, path, relative)
                is DirRef.TreeDir -> if (relative == null) null else readFromTreeDir(ref, name)
            }

        private fun readFromFileDir(ref: DirRef.FileDir, path: String, relative: String?): ByteArray? {
            // 绝对路径（`/data/x.png`）按用户所写直读；相对路径用已归一化的值拼目录
            val target = if (relative == null) File(path) else File(ref.dir, relative)
            if (ref.canonicalPrefix.isEmpty()) return null
            val canonical = runCatching { target.canonicalPath }.getOrNull() ?: return null
            if (!canonical.startsWith(ref.canonicalPrefix)) return null
            return runCatching { if (target.isFile) target.readBytes() else null }.getOrNull()
        }

        private fun readFromTreeDir(ref: DirRef.TreeDir, name: String): ByteArray? {
            val doc = runCatching {
                ref.fileDoc.find(name, MdImageRefPolicy.TreeSearchDepth, MdImageRefPolicy.TreeSearchMaxFinds)
            }.getOrNull() ?: return null
            return runCatching { doc.readBytes() }.getOrNull()
        }

        private fun directory(): DirRef? {
            if (dirRefResolved) return dirRef
            dirRefResolved = true
            dirRef = runCatching { resolveDirectory() }.getOrNull()
            return dirRef
        }

        private fun resolveDirectory(): DirRef? {
            val uri = book.getLocalUri()
            if (!uri.isContentScheme()) {
                val path = uri.path ?: return null
                val parent = File(path).parentFile ?: return null
                return DirRef.FileDir(parent)
            }
            val documentId = DocumentsContract.getDocumentId(uri)
            val parentId = parentDocumentId(documentId) ?: return null
            val authority = uri.authority ?: return null
            val treeUri = Uri.parse("content://$authority/tree/${Uri.encode(parentId)}")
            val treeDoc = FileDoc.fromUri(treeUri, true)
            if (!runCatching { treeDoc.exists() }.getOrDefault(false)) return null
            return DirRef.TreeDir(treeDoc)
        }

        /**
         * 由文档 id 推出**父目录** id。
         *
         * 形如 `primary:Download/sub/foo.md` → `primary:Download/sub`；
         * 卷根直挂（`primary:foo.md`）→ `primary:`（卷根）。
         */
        private fun parentDocumentId(documentId: String): String? {
            val volume = documentId.substringBefore(':', "")
            if (volume.isEmpty()) return null
            val rest = documentId.substringAfter(':')
            return "$volume:${rest.substringBeforeLast('/', "")}"
        }

        /** 未内联只留技术线索（扩展名/原因/字节数），**不打印用户的路径与文件名**。 */
        private fun logMiss(src: String, reason: String) {
            val ext = MdImageRefPolicy.fileNameOf(src).substringAfterLast('.', "").lowercase()
            AppLog.putDebugWithTag(
                AppLog.TAG_CONTENT,
                "md 图片未内联: ext=$ext, reason=$reason",
                level = AppLog.Level.INFO
            )
        }

        /** 内联成功留痕（真机 L2 的唯一确定性证据通道：`adb logcat -s ContentProcess:E`）。 */
        private fun logInline(mime: String, bytes: Int) {
            AppLog.putDebugWithTag(
                AppLog.TAG_CONTENT,
                "md 图片已内联: mime=$mime, bytes=$bytes, total=$inlinedBytes",
                level = AppLog.Level.INFO
            )
        }
    }
}