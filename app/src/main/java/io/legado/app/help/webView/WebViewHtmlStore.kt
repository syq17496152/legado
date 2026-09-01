package io.legado.app.help.webView

import splitties.init.appCtx
import java.io.File
import java.io.IOException
import java.util.UUID

/**
 * 将 WebView 页面 HTML 存储到 Fragment/Activity arguments 之外的文件。
 *
 * 网页可能包含大量内联内容，大 HTML 通过 Bundle 传递时，状态保存会全量序列化整个文档，
 * 可能超出 Binder 事务限制（TransactionTooLargeException）导致崩溃或静默丢状态。
 */
object WebViewHtmlStore {

    private const val DIRECTORY_NAME = "webview_html"
    private const val FILE_SUFFIX = ".html"

    /**
     * 将 HTML 写入 filesDir/webview_html/ 下的 UUID 命名文件。
     * @return 文件引用（文件名），失败时删除半成品并抛出 IOException
     */
    fun write(html: String): String {
        val directory = File(appCtx.filesDir, DIRECTORY_NAME)
        if (!directory.exists() && !directory.mkdirs()) {
            throw IOException("Unable to create WebView HTML directory: ${directory.absolutePath}")
        }
        val file = File(directory, "${UUID.randomUUID()}$FILE_SUFFIX")
        try {
            file.writeText(html, Charsets.UTF_8)
        } catch (error: Throwable) {
            file.delete()
            throw error
        }
        return file.name
    }

    /**
     * 按引用读取 HTML 内容，文件缺失时返回 null。
     * 引用经白名单正则校验，防止路径穿越。
     * @throws IllegalArgumentException 引用格式非法时抛出
     */
    fun read(reference: String): String? {
        if (!reference.matches(REFERENCE_PATTERN)) {
            throw IllegalArgumentException("Invalid WebView HTML reference: $reference")
        }
        val file = File(File(appCtx.filesDir, DIRECTORY_NAME), reference)
        return file.takeIf { it.isFile }?.readText(Charsets.UTF_8)
    }

    /**
     * 按引用删除 HTML 文件，引用为空或格式非法时忽略。
     */
    fun delete(reference: String?) {
        if (reference == null || !reference.matches(REFERENCE_PATTERN)) return
        File(File(appCtx.filesDir, DIRECTORY_NAME), reference).delete()
    }

    private val REFERENCE_PATTERN = Regex("""[0-9a-fA-F-]{36}\.html""")
}
