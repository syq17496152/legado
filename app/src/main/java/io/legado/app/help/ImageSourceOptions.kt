package io.legado.app.help

import com.google.gson.JsonElement
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import java.util.Locale

/**
 * Q3（R 批快赢）：富文本图片「源参数」解析（移植自上游 `help/ImageSourceOptions.kt`）
 *
 * 图片源形态：`image.png,{"width":"80%","style":"center"}` —— 逗号后紧跟 `{` 的 JSON 对象即参数段。
 * 上游实现比本仓原内联逻辑健壮，本仓原写法有三个真实缺陷：
 * 1. 只认字面 `,{` ⇒ **HTML 转义后的 `,&#123;`** 形态完全解不出参数（正文经 HTML 转义很常见）；
 * 2. `Map<String, String>` 强转 ⇒ 参数值为非字符串（嵌套对象/数字）时整体解析失败；
 * 3. 大小写敏感 ⇒ `Width`/`Style` 取不到。
 * 另上游还处理了「参数段被二次序列化、结构引号转义成 `\"`」的历史形态。
 */
internal data class ParsedImageSource(
    /** 去掉参数段后的图片源（无参数段时即原文） */
    val source: String,
    /** 参数段解出的键值（大小写不敏感访问，见 [option]） */
    val options: Map<String, String>
) {
    /** 大小写不敏感取参；空白值视为未设置 */
    fun option(name: String): String? = options.entries
        .firstOrNull { it.key.equals(name, ignoreCase = true) }
        ?.value
        ?.takeIf { it.isNotBlank() }

    val style: String?
        get() = option("style")

    val width: String?
        get() = option("width")
}

internal object ImageSourceOptions {

    /** 参数段分隔符：`,{` 或 HTML 实体形态 `,&#123;` / `,&#x7b;` */
    private val optionSeparator = Regex(
        "\\s*,\\s*(?=\\{|&#(?:x0*7b|0*123);)",
        RegexOption.IGNORE_CASE
    )

    private val htmlEntity = Regex("&(#x[0-9a-fA-F]+|#\\d+|quot|apos|amp|lt|gt);")

    /**
     * 解析完整图片源；空白入参返回 null。
     *
     * 从后往前试每一个分隔符：源串里可能有多个 `,{`（如 URL 查询串自带），只有能解成 JSON 对象的
     * 那一个才是参数段（与上游一致）。
     */
    fun parse(raw: String?): ParsedImageSource? {
        val value = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        optionSeparator.findAll(value).toList().asReversed().forEach { separator ->
            val optionText = decodeHtmlEntities(
                value.substring(separator.range.last + 1).trim()
            )
            val options = parseOptions(optionText) ?: return@forEach
            return ParsedImageSource(
                source = value.substring(0, separator.range.first).trim(),
                options = options
            )
        }
        return ParsedImageSource(value, emptyMap())
    }

    private fun parseOptions(optionText: String): Map<String, String>? {
        readOptions(optionText)?.let { return it }

        // 段落规则结果可能被二次序列化后塞进 HTML 属性，结构引号变成 \" ⇒ 回退再试一次
        if (optionText.contains("\\\"")) {
            runCatching {
                GSON.fromJson("\"$optionText\"", String::class.java)
            }.getOrNull()
                ?.takeIf { it != optionText }
                ?.let { decoded ->
                    readOptions(decoded)?.let { return it }
                }
            val unescaped = optionText.replace("\\\"", "\"")
            readOptions(unescaped)?.let { return it }
        }
        return null
    }

    private fun readOptions(value: String): Map<String, String>? = runCatching {
        // 参数值可能是嵌套对象（如 headers/body）⇒ 用 JsonElement 承接，不做字符串强转
        GSON.fromJsonObject<Map<String, JsonElement>>(value).getOrThrow().mapValues { (_, item) ->
            when {
                item.isJsonNull -> ""
                item.isJsonPrimitive -> item.asString
                else -> item.toString()
            }
        }
    }.getOrNull()

    /** 解 HTML 实体（`&#123;` 等），使转义后的参数段也能解析 */
    private fun decodeHtmlEntities(value: String): String {
        return htmlEntity.replace(value) { match ->
            when (val entity = match.groupValues[1].lowercase(Locale.ROOT)) {
                "quot" -> "\""
                "apos" -> "'"
                "amp" -> "&"
                "lt" -> "<"
                "gt" -> ">"
                else -> {
                    val codePoint = when {
                        entity.startsWith("#x") -> entity.substring(2).toIntOrNull(16)
                        entity.startsWith("#") -> entity.substring(1).toIntOrNull()
                        else -> null
                    }
                    codePoint
                        ?.takeIf { it in 0..Character.MAX_CODE_POINT }
                        ?.let { String(Character.toChars(it)) }
                        ?: match.value
                }
            }
        }
    }
}

/** 图片显示矩形（左/上/右/下）：与 `android.graphics.Rect` 同语义，但保持纯 JVM 可测 */
internal data class ImageBounds(val left: Int, val top: Int, val right: Int, val bottom: Int)

/**
 * Q3：按源参数求解图片显示矩形（上游 `GlideImageGetter.getDrawableRect` 语义 + 两处健壮性加固）
 *
 * 规则：
 * - 无 `width` 且无 `style` ⇒ 取原始尺寸
 * - 可用宽度非正（尚未布局完）⇒ 回落原始宽，避免 `center/right` 算出负偏移
 * - `width` 为百分比 ⇒ 按可用宽度换算（非法百分比回落 80）；为像素值 ⇒ 直接用；非法/为 0 ⇒ 回落原始宽
 * - 显示宽/高至少 1px（否则不可见且 `Rect` 语义异常）
 * - 高度按原始宽高比等比换算；`style` = center/right 决定左偏移，其余左对齐
 */
internal fun resolveImageBounds(
    parsed: ParsedImageSource?,
    intrinsicWidth: Int,
    intrinsicHeight: Int,
    availableWidth: Int
): ImageBounds {
    val drawableWidth = intrinsicWidth.coerceAtLeast(1)
    val drawableHeight = intrinsicHeight.coerceAtLeast(1)
    val styleWidth = parsed?.width
    val styleType = parsed?.style
    if (styleWidth == null && styleType == null) {
        return ImageBounds(0, 0, drawableWidth, drawableHeight)
    }
    val contentWidth = availableWidth.takeIf { it > 0 } ?: drawableWidth
    val imgWidth = if (styleWidth?.endsWith("%") == true) {
        val sWidth = styleWidth.dropLast(1).toIntOrNull() ?: 80
        contentWidth * sWidth / 100
    } else {
        val sWidth = styleWidth?.toIntOrNull() ?: 0
        if (sWidth > 0) sWidth else drawableWidth
    }
    val showWidth = (contentWidth.takeIf { it in 1..<imgWidth } ?: imgWidth).coerceAtLeast(1)
    val showHeight = (drawableHeight * showWidth / drawableWidth.toFloat()).toInt().coerceAtLeast(1)
    val left = when (styleType) {
        "center" -> (contentWidth - showWidth) / 2
        "right" -> contentWidth - showWidth
        else -> 0
    }
    return ImageBounds(left, 0, left + showWidth, showHeight)
}