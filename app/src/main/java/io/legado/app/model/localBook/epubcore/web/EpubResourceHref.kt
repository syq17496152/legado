package io.legado.app.model.localBook.epubcore.web

import android.net.Uri
import io.legado.app.model.localBook.epubcore.archive.EpubPath

/**
 * EPUB 资源引用（`img` 的 `src` / 盒模型的背景图引用）→ **归档内规范路径**的单一解析口径。
 *
 * 为什么必须单源：同一章的图片会先后流经两条渲染链路 —— 保真级 WebView 布局
 * （[EpubWebLayoutAdapter] 把 DOM 里的 href 解析成归档路径）与归一化分页
 * （`facade/EpubFallbackPageFactory.canvasPages` 直接交给 `EpubImageResolver.decode(href)` 取字节）。
 * 两处各写一份 ⇒ 同一张图「一条链路能显示、另一条恒裂图」，且**任何用例都不会变红**
 * （口径分裂是本项目已多次实证的隐性缺陷类型）。
 *
 * 解析规则（自 [EpubWebLayoutAdapter] 原私有实现**原样提取**，行为逐字节不变）：
 * 1. `data:` 原样返回（内联像素，不经归档）；
 * 2. `epub.local` 自定义 scheme（WebView 内部回源地址）⇒ 取 path 段归一化；
 * 3. 空引用 ⇒ 回落到章节自身路径（保持原行为）；
 * 4. 绝对路径（`/x/y`）⇒ 去前导斜杠 + URL 解码后归一化；
 * 5. 带外部 scheme（`http:`/`urn:`/`//host` 等）⇒ 原样返回（不当作归档内资源）；
 * 6. 其余（相对引用）⇒ 以**章节路径**为基准 [EpubPath.resolve]。
 * 第 4/5/6 条均先剥掉 `#fragment` 与 `?query` 装饰。
 *
 * 纯函数（只依赖 `android.net.Uri` 解析与 [EpubPath]）⇒ JVM 可测。
 */
object EpubResourceHref {

    fun resolve(chapterHref: String, href: String): String {
        val raw = href.trim()
        if (raw.startsWith("data:", ignoreCase = true)) return raw
        epubLocalPath(raw)?.let { return it }
        val cleanHref = stripDecorations(raw)
        if (cleanHref.isBlank()) {
            return EpubPath.normalize(stripDecorations(chapterHref))
        }
        if (cleanHref.startsWith('/')) {
            return EpubPath.normalize(decodePath(cleanHref.trimStart('/')))
        }
        if (hasExternalScheme(cleanHref)) return cleanHref
        return EpubPath.resolve(stripDecorations(chapterHref), cleanHref)
    }

    private fun epubLocalPath(href: String): String? {
        val candidate = if (href.startsWith("//")) "https:$href" else href
        val uri = runCatching { Uri.parse(candidate) }.getOrNull() ?: return null
        if (!uri.host.equals("epub.local", ignoreCase = true)) return null
        val path = uri.encodedPath
            ?.takeIf { it.isNotBlank() }
            ?.trimStart('/')
            ?: return null
        return EpubPath.normalize(decodePath(path))
    }

    /** `Uri.decode` 在 JVM 单测下是 stub（返回 null）⇒ 回退原串，保证本单源在两种环境都可调用。 */
    private fun decodePath(path: String): String =
        runCatching { Uri.decode(path) }.getOrNull() ?: path

    private fun stripDecorations(href: String): String {
        return href.substringBefore('#').substringBefore('?')
    }

    private fun hasExternalScheme(href: String): Boolean {
        val schemeEnd = href.indexOf(':')
        if (schemeEnd <= 0) return false
        return href.take(schemeEnd).all { it.isLetterOrDigit() || it == '+' || it == '-' || it == '.' }
    }
}
