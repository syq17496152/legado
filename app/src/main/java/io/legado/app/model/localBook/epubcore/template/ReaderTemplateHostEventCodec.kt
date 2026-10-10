package io.legado.app.model.localBook.epubcore.template

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * 模板沙箱消息**编解码**（epub-md-rich-rendering 阶段 4.14 / 4.2 的 Kotlin 侧权威校验）。
 *
 * 分工：[ReaderTemplateBridgePolicy] 是**准入判据**（纯函数、权威），本对象只负责
 * 「探针顺序 + JSON 解析 + 分类」这一段宿主胶水，且**顺序本身是不变量**：
 * 1. **先判长度**（解析器不得成为攻击面，`MaxRawChars` 必须在 parse 之前）；
 * 2. 再 parse（失败 ⇒ `invalid-json`，丢弃）；
 * 3. 取 token/type/字段集/深度 ⇒ 交策略 [ReaderTemplateBridgePolicy.verify] 权威判定；
 * 4. 通过才分类成 [Event]；未登记/越权/超限一律 [Result.rejection]，**不抛异常**。
 *
 * 为什么不做"直接反序列化成 data class"：那会把 `type` 之外的字段当成忽略项静默吸收，
 * 于是"越权字段"就再也看不见了（白名单形同虚设）。这里刻意只按类型取白名单字段。
 */
internal object ReaderTemplateHostEventCodec {

    sealed interface Event {
        /** 分页结算（宿主据此设定页数与当前位置）。 */
        data class Stable(val pageIndex: Int, val pageCount: Int) : Event

        /** 渲染状态（`ready` / `inject-done` / `inject-unavailable` / `inject-failed`）。 */
        data class RenderState(val state: String) : Event

        /** 耗时指标（诊断用）。 */
        data class Metrics(val costMs: Long?) : Event

        /** 沙箱侧错误（模板异常/降级信号）。 */
        data class SandboxError(val code: String, val message: String) : Event

        /** 白名单内、当前宿主不消费的类型（如 `contentChanged`/`textPosition`）：仅留痕。 */
        data class Ignored(val type: String) : Event
    }

    data class Result(val event: Event?, val rejection: ReaderTemplateBridgePolicy.Rejection?) {
        val accepted: Boolean get() = rejection == null && event != null
    }

    /** 消息类型 → 是否当前消费（未登记类型由策略层拒收，不会走到这里）。 */
    private val handledTypes = setOf("stable", "renderState", "metrics", "error")

    /** 信封字段（与 `template-host.js` 前置粗筛跳过的字段同一口径）。 */
    private val EnvelopeFields = setOf("type", "token", "sessionId")

    fun parse(raw: String?, expectedToken: String): Result {
        if (raw.isNullOrEmpty()) {
            return Result(null, ReaderTemplateBridgePolicy.Rejection("empty", "空消息"))
        }
        // ① 解析前：长度（避免把超长串喂给解析器）
        if (raw.length > ReaderTemplateBridgePolicy.MaxRawChars) {
            return Result(
                null,
                ReaderTemplateBridgePolicy.Rejection(
                    "too-long",
                    "消息超长：${raw.length} > ${ReaderTemplateBridgePolicy.MaxRawChars}"
                )
            )
        }
        // ② parse
        val element: JsonElement = try {
            JsonParser.parseString(raw)
        } catch (error: Exception) {
            return Result(
                null,
                ReaderTemplateBridgePolicy.Rejection("invalid-json", "消息不是合法 JSON：${error.localizedMessage}")
            )
        }
        if (element.isJsonNull || !element.isJsonObject) {
            return Result(
                null,
                ReaderTemplateBridgePolicy.Rejection("not-object", "消息必须是 JSON 对象")
            )
        }
        val obj = element.asJsonObject
        // ③ 权威校验（token + 类型 + 字段白名单 + 深度 + 矩形数）
        val type = obj.stringOrNull("type")
        val token = obj.stringOrNull("token")
        // 信封字段（type/token/sessionId）不是"负载字段"，必须剔除后再交白名单判定
        // （否则策略会把它们当成越权字段，把合法消息一律拒掉——那会让通道整体失效）
        val fields = obj.keySet() - EnvelopeFields
        val rejection = ReaderTemplateBridgePolicy.verify(
            direction = ReaderTemplateBridgePolicy.Direction.FROM_WEB,
            raw = raw,
            token = token,
            expectedToken = expectedToken,
            type = type,
            fields = fields,
            jsonDepth = depthOf(obj, ReaderTemplateBridgePolicy.MaxJsonDepth + 1),
            // 恶意/畸形 `rects`（非数组）不得让解析抛异常：取不到就按"超限"交策略拒收
            selectionRectCount = runCatching { obj.getAsJsonArray("rects")?.size() }
                .getOrNull() ?: if (obj.has("rects")) Int.MAX_VALUE else 0
        )
        if (rejection != null) return Result(null, rejection)
        // ④ 分类
        val resolvedType = type.orEmpty()
        val event = when (resolvedType) {
            "stable" -> Event.Stable(
                pageIndex = obj.intOrNull("pageIndex") ?: 0,
                pageCount = obj.intOrNull("pageCount") ?: 1
            )

            "renderState" -> Event.RenderState(obj.stringOrNull("state").orEmpty())
            "metrics" -> Event.Metrics(obj.longOrNull("costMs"))
            "error" -> Event.SandboxError(
                code = obj.stringOrNull("code").orEmpty(),
                message = obj.stringOrNull("message").orEmpty()
            )

            else -> Event.Ignored(resolvedType)
        }
        return Result(event, null)
    }

    /** 是否属于当前宿主消费的消息类型（供留痕与统计）。 */
    fun isHandled(type: String): Boolean = type in handledTypes

    /** JSON 嵌套深度（对象/数组计 1 层；标量 1 层；超 `limit` 提前返回）。 */
    private fun depthOf(element: JsonElement?, limit: Int): Int {
        if (element == null || element.isJsonNull || element.isJsonPrimitive) return 1
        if (limit <= 0) return Int.MAX_VALUE
        var max = 1
        if (element.isJsonObject) {
            for (entry in element.asJsonObject.entrySet()) {
                val child = depthOf(entry.value, limit - 1)
                if (child + 1 > max) max = child + 1
                if (max > limit) return max
            }
        } else if (element.isJsonArray) {
            for (child in element.asJsonArray) {
                val nested = depthOf(child, limit - 1)
                if (nested + 1 > max) max = nested + 1
                if (max > limit) return max
            }
        }
        return max
    }

    private fun JsonObject.stringOrNull(name: String): String? =
        get(name)?.takeIf { it.isJsonPrimitive }?.asString

    private fun JsonObject.intOrNull(name: String): Int? =
        get(name)?.takeIf { it.isJsonPrimitive }?.let { runCatching { it.asInt }.getOrNull() }

    private fun JsonObject.longOrNull(name: String): Long? =
        get(name)?.takeIf { it.isJsonPrimitive }?.let { runCatching { it.asLong }.getOrNull() }
}