package io.legado.app.ui.code

import android.content.Context
import java.io.File

/**
 * 编辑器载荷**临时文件仓库**（epub-md-rich-rendering 阶段 4.8c）。
 *
 * 单一职责：大载荷（模板 CSS ≈320KB）**进出编辑器都不经 Intent/Binder**，一律落在这里的临时文件。
 *
 * 三个不变量（缺一即出问题，故集中在单处实现，禁止调用方各自拼路径）：
 * 1. **只落应用私有缓存目录**（系统可回收，不污染外部存储）；
 * 2. **写前清历史残留**（编辑器被强杀/进程回收会留下未删的临时件）；
 * 3. **读完即删**（数百 KB 的正文拷贝不得在缓存里积压）。
 *
 * 真机铁证（2026-10-10）：出方向未走本通道时，320KB 文本经 result Intent 回传 ⇒
 * `TransactionTooLargeException`（parcel 662368 bytes）⇒ **进程被杀、用户编辑全部丢失**。
 */
class CodeEditPayloadStore(private val context: Context) {

    /** 写入临时载荷；失败返回 null（调用方**必须**回落内联通道，不得静默丢载荷）。 */
    fun write(text: String): File? = runCatching {
        val dir = File(context.cacheDir, CodeEditPayloadPolicy.TempDirName).apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        File(dir, "payload_${System.currentTimeMillis()}.txt").apply { writeText(text) }
    }.getOrNull()

    /** 读取并**删除**临时载荷；读取失败返回 null（调用方负责给出显式错误，不得当空文本处理）。 */
    fun read(path: String): String? {
        val file = File(path)
        return try {
            file.readText()
        } catch (e: Exception) {
            null
        } finally {
            runCatching { file.delete() }
        }
    }
}