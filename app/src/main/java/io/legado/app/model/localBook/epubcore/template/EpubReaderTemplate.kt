package io.legado.app.model.localBook.epubcore.template

import androidx.annotation.Keep
import com.google.gson.GsonBuilder
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.Strictness
import java.security.MessageDigest

/**
 * 可移植的阅读页模板（作者 HTML/CSS/JS **不做归一化、不做过滤**）。
 *
 * 迁移自 archive v15（阶段 1「提前带入」数据模型：`EpubDirectChapter` / `EpubCoreLayoutConfig`
 * 需要该类型作为可空字段；模板系统的其余部分仍在阶段 4 落地）。
 * 与本仓既有 `appearance_kits` 套件分层：本模型只描述「阅读页文档外观」。
 *
 * 注：`@Keep` 为 Gson 模型签名保留保险（R8 keep 规则见 AGENTS.md §强制规则 7）。
 */
@Keep
data class EpubReaderTemplate(
    val schemaVersion: Int = SCHEMA_VERSION,
    val id: String,
    val name: String,
    val description: String = "",
    val firstPageHtml: String = "",
    val otherPageHtml: String = "",
    val css: String = "",
    val javascript: String = "",
    val type: String = TYPE_PAGED,
    val scrollHtml: String = ""
) {
    val isScrolling: Boolean get() = type == TYPE_SCROLL

    val htmlDocuments: List<String>
        get() = if (isScrolling) listOf(scrollHtml) else listOf(firstPageHtml, otherPageHtml)

    /** 切换类型时未激活的一侧源码仍需保留 ⇒ 素材存活判定必须覆盖全部字段。 */
    fun resourceSource(): String =
        listOf(firstPageHtml, otherPageHtml, scrollHtml, css, javascript).joinToString("\n")

    /** 内容指纹：对每个字段加长度前缀，与 JSON 格式/字段顺序无关。 */
    fun contentHash(): String {
        val digest = MessageDigest.getInstance("SHA-256")
        listOf(schemaVersion.toString(), id, name, description, firstPageHtml, otherPageHtml, css, javascript, type, scrollHtml)
            .forEach { value ->
                val bytes = value.toByteArray(Charsets.UTF_8)
                digest.update((bytes.size.toString() + ":").toByteArray(Charsets.UTF_8))
                digest.update(bytes)
            }
        return digest.digest().joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
    }

    /** 仅做结构校验；浏览器语法/排版错误交给真实渲染器判定。 */
    fun validate(): List<String> = buildList {
        if (schemaVersion !in SCHEMA_VERSION..SCROLL_SCHEMA_VERSION) add("不支持的模板版本：" + schemaVersion)
        if (type != TYPE_PAGED && type != TYPE_SCROLL) add("不支持的模板类型：" + type)
        if (isScrolling && schemaVersion < SCROLL_SCHEMA_VERSION) add("滚动模板需要格式版本 2")
        if (id.isBlank()) add("模板 id 不能为空")
        if (name.isBlank()) add("模板名称不能为空")
        if (isScrolling) {
            if (scrollHtml.isBlank()) add("滚动 HTML 不能为空")
        } else {
            if (firstPageHtml.isBlank()) add("首页 HTML 不能为空")
            if (otherPageHtml.isBlank()) add("续页 HTML 不能为空")
        }
    }

    internal fun toJsonObject(): JsonObject = json.toJsonTree(this).asJsonObject
    fun toJson(): String = json.toJson(toJsonObject())

    companion object {
        const val SCHEMA_VERSION = 1
        const val SCROLL_SCHEMA_VERSION = 2
        const val TYPE_PAGED = "paged"
        const val TYPE_SCROLL = "scroll"

        internal val json = GsonBuilder()
            .disableHtmlEscaping()
            .setPrettyPrinting()
            .setStrictness(Strictness.STRICT)
            .create()

        fun fromJson(source: String): EpubReaderTemplate = fromJsonObject(parseObject(source))

        internal fun parseObject(source: String): JsonObject {
            val element = try {
                json.fromJson(source, JsonElement::class.java)
            } catch (error: Exception) {
                throw IllegalArgumentException("模板 JSON 格式错误：" + error.localizedMessage, error)
            }
            require(element != null && element.isJsonObject) { "模板 JSON 必须是一个对象" }
            return element.asJsonObject
        }

        internal fun readVersion(value: JsonObject): Int {
            val version = value.get("schemaVersion")
            require(version != null && version.isJsonPrimitive && version.asJsonPrimitive.isNumber) {
                "schemaVersion 必须是整数"
            }
            return try {
                version.asBigDecimal.intValueExact()
            } catch (error: ArithmeticException) {
                throw IllegalArgumentException("schemaVersion 必须是整数", error)
            }
        }

        internal fun fromJsonObject(value: JsonObject): EpubReaderTemplate {
            fun string(name: String, default: String? = null): String {
                val field = value.get(name)
                if (field == null && default != null) return default
                require(field != null && field.isJsonPrimitive && field.asJsonPrimitive.isString) {
                    name + " 必须是字符串"
                }
                return field.asString
            }
            return EpubReaderTemplate(
                schemaVersion = readVersion(value),
                id = string("id"),
                name = string("name"),
                description = string("description", ""),
                firstPageHtml = string("firstPageHtml", ""),
                otherPageHtml = string("otherPageHtml", ""),
                css = string("css", ""),
                javascript = string("javascript", ""),
                type = string("type", TYPE_PAGED),
                scrollHtml = string("scrollHtml", "")
            ).also { template ->
                val errors = template.validate()
                require(errors.isEmpty()) { errors.joinToString("\n") }
            }
        }
    }
}