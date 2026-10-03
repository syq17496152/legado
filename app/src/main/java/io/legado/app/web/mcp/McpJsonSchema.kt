package io.legado.app.web.mcp

import com.google.gson.JsonArray
import com.google.gson.JsonObject

/**
 * 入参 JSON Schema 生成器（web-mcp-productization 二期 · §2.3 / REQ-2-204）。
 *
 * 工具声明只写"参数名 + 类型"，Schema 由本单源生成 —— 避免 232 个工具各手写一段易漂移的 Schema。
 *
 * 口径：
 * - `additionalProperties = true`（**保持宽松**）：AI 客户端多传未知键时不因校验失败而中断调用，
 *   服务端本来也只按名取值、忽略多余键；
 * - `required` **恒存在**（无必填参数时为空数组）—— 使"必须给出 required 数组"成为可断言不变量；
 * - 数组类型自动补 `items: {type: string}`（本产品工具里的数组入参全是字符串列表）。
 */
object McpJsonSchema {

    const val TYPE_STRING = "string"
    const val TYPE_INTEGER = "integer"
    const val TYPE_NUMBER = "number"
    const val TYPE_BOOLEAN = "boolean"
    const val TYPE_ARRAY = "array"
    const val TYPE_OBJECT = "object"

    /** 无参工具的 Schema（`{}` 的合法 JSON Schema 形式）。 */
    fun empty(): JsonObject = of()

    /**
     * 生成 `{type:"object", properties:{...}, required:[...], additionalProperties:true}`。
     *
     * @param required 必填参数：参数名 → 类型（[TYPE_STRING] / [TYPE_INTEGER] / …）
     * @param optional 可选参数：参数名 → 类型
     * @param descriptions 参数说明（可选，便于 AI 理解语义）
     */
    fun of(
        required: Map<String, String> = emptyMap(),
        optional: Map<String, String> = emptyMap(),
        descriptions: Map<String, String> = emptyMap(),
    ): JsonObject {
        val properties = JsonObject()
        (required.keys + optional.keys).forEach { name ->
            properties.add(name, propertyOf(optional[name] ?: required[name] ?: TYPE_STRING, descriptions[name]))
        }
        val requiredArray = JsonArray()
        required.keys.forEach { requiredArray.add(it) }
        return JsonObject().apply {
            addProperty("type", TYPE_OBJECT)
            add("properties", properties)
            add("required", requiredArray)
            addProperty("additionalProperties", true)
        }
    }

    private fun propertyOf(type: String, description: String?): JsonObject =
        JsonObject().apply {
            addProperty("type", type)
            if (!description.isNullOrBlank()) addProperty("description", description)
            if (type == TYPE_ARRAY) {
                add("items", JsonObject().apply { addProperty("type", TYPE_STRING) })
            }
        }

    /** 单必填字符串参数的便捷写法（`{key}` 是高频形态）。 */
    fun singleRequiredString(key: String, description: String? = null): JsonObject =
        of(
            required = mapOf(key to TYPE_STRING),
            descriptions = if (description == null) emptyMap() else mapOf(key to description),
        )
}
