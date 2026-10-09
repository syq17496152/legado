package io.legado.app.model.localBook.epubcore.style

import java.util.Locale

/**
 * CSS 旧/私有属性兼容层（epub-md-rich-rendering 阶段 1.5；吸收 NG 战术点「未知属性忽略」）。
 *
 * 存在的理由（台账 A8 / 缺点 X10）：出版方 EPUB 常携带三类**非标准**声明——
 * ①私有扩展（`duokan-*`）；②厂商前缀（`-webkit-`/`-epub-`/`-moz-`）；③已废弃属性。
 * 若一律丢弃，会连带丢失作者本意（典型：`-epub-writing-mode` 决定竖排）；
 * 若一律保留，又会把未知属性灌进渲染管线。本层的策略是**别名归一 + 未知忽略**：
 *
 * - **别名归一**：与标准属性等价的旧名改写为规范名 ⇒ 后续 `supportedProperties` 判定自然命中；
 * - **未知忽略**：无法归一且不支持的属性返回 null，由调用方丢弃（**只丢该条声明，不丢整条规则**）；
 * - **可观测**：归一/忽略数量可统计，便于诊断「某 EPUB 样式为何没生效」。
 *
 * 纯函数、无 Android 依赖 ⇒ JVM 可测。
 */
object EpubCssCompat {

    /** 私有/旧式属性名 → 标准属性名。仅收录**语义等价**的别名，不做近似映射。 */
    private val LEGACY_ALIASES = mapOf(
        // Duokan（多看）私有扩展：缩进语义与标准 text-indent 一致。
        "duokan-text-indent" to "text-indent",
        // 厂商/EPUB 前缀的书写模式（旧日文/中文竖排 EPUB 常见）。
        "-epub-writing-mode" to "writing-mode",
        "-webkit-writing-mode" to "writing-mode",
        // 前缀版文字变换。
        "-epub-text-transform" to "text-transform",
        "-webkit-text-transform" to "text-transform",
        "-moz-text-transform" to "text-transform",
        // 前缀版断行控制。
        "-epub-line-break" to "line-break",
        "-epub-word-break" to "word-break",
        "-webkit-word-break" to "word-break",
        // 前缀版文本对齐/缩进。
        "-epub-text-align-last" to "text-align-last",
        "-epub-text-indent" to "text-indent",
        // 前缀版分行/连字（保留判定路径，交由渲染层决定消费与否）。
        "-epub-hyphens" to "hyphens",
        "-webkit-hyphens" to "hyphens"
    )

    private val VENDOR_PREFIXES = listOf("-webkit-", "-moz-", "-o-", "-ms-", "-epub-")

    /** 别名映射结果：`canonical` 为规范名（未识别到别名时为原名的规范化形式）。 */
    data class Normalized(
        val canonical: String,
        val aliased: Boolean
    )

    /**
     * 属性名归一：小写化 → 别名映射 → （未命中别名时）剥离厂商前缀。
     *
     * 注意：剥前缀**只用于判定**，不改写为规范名交给渲染管线——
     * 因为「有前缀但无标准名」的属性（如 `-webkit-box-orient`）与标准属性并非严格等价，
     * 强行改写会引入错误渲染。此类属性统一走「未知忽略」。
     */
    fun normalizePropertyName(rawName: String): Normalized {
        val name = rawName.trim().lowercase(Locale.ROOT)
        if (name.isEmpty()) return Normalized(name, aliased = false)
        LEGACY_ALIASES[name]?.let { return Normalized(it, aliased = true) }
        return Normalized(name, aliased = false)
    }

    /** 剥离厂商前缀后的基名（无可剥前缀时返回原名）。仅用于支持性判定。 */
    fun basePropertyName(name: String): String {
        val lower = name.lowercase(Locale.ROOT)
        VENDOR_PREFIXES.forEach { prefix ->
            if (lower.startsWith(prefix)) return lower.removePrefix(prefix)
        }
        return lower
    }

    /**
     * 判定一条声明是否进入渲染管线。
     *
     * @param canonicalName 已归一的属性名。
     * @param isSupported 调用方给出的「本管线支持的标准属性」判据。
     * @return true ⇒ 保留（含别名归一命中，或剥前缀后基名被支持）。
     */
    fun isRenderable(canonicalName: String, isSupported: (String) -> Boolean): Boolean {
        if (isSupported(canonicalName)) return true
        // 前缀旧式声明：基名被支持时同样保留（值语义在前缀与标准间一致）。
        val base = basePropertyName(canonicalName)
        return base != canonicalName && isSupported(base)
    }

    /**
     * 归一 + 判定一步到位。
     *
     * @return 应写入管线的属性名；返回 null 表示**未知属性 ⇒ 忽略该条声明**。
     *   别名归一后的规范名同样必须通过 [isSupported]（防止把不支持的标准名灌进管线）。
     */
    fun resolvePropertyName(rawName: String, isSupported: (String) -> Boolean): String? {
        val canonical = normalizePropertyName(rawName).canonical
        if (isSupported(canonical)) return canonical
        val base = basePropertyName(canonical)
        return base.takeIf { it != canonical && isSupported(it) }
    }
}