package io.legado.app.model.localBook.epubcore.template

/**
 * 模板沙箱桥**策略层**（epub-md-rich-rendering 阶段 4.2；AD-23 / blueprint §七）。
 *
 * 定位：模板 = **第三方作者代码**，运行在沙箱 iframe 内；它与宿主之间只有一个
 * `postWebMessage`/`WebMessagePort` 通道（**禁 `addJavascriptInterface`**）。
 * 本对象是通道的**唯一准入判据**：字段白名单 + 长度上限 + 深度上限 + token 校验，
 * 全部为纯函数 ⇒ 越权/超长消息的拒收行为可在 JVM 逐条断言（audit T-06 指定的 JVM 契约项）。
 *
 * 设计要点：
 * - **先验长度后解析**：`MAX_RAW_CHARS` 校验必须在 JSON 解析之前（否则解析器本身成为攻击面）；
 * - **白名单而非黑名单**：未登记的消息类型/字段一律拒收（relax 一个字段就是 open a hole）；
 * - **token 校验**：宿主为每份模板文档生成一次性 token，消息必须回带；防止其他帧/注入脚本伪造；
 * - **拒收不抛异常**：返回 [Rejection]，由宿主记录并丢弃该消息（模板不可信，抛异常会拖垮宿主）。
 */
internal object ReaderTemplateBridgePolicy {

    /** 原始消息字符上限（解析前判据；默认 256KB）。 */
    const val MaxRawChars = 256 * 1024

    /** 选区矩形上限（防"矩形洪水"撑爆宿主内存）。 */
    const val MaxSelectionRects = 64

    /** JSON 嵌套深度上限（防深层结构导致的解析放大）。 */
    const val MaxJsonDepth = 4

    /** 单条消息字段数量上限。 */
    const val MaxFieldCount = 24

    enum class Direction { FROM_WEB, TO_WEB }

    /** 拒收原因（可诊断、可统计）。 */
    data class Rejection(val code: String, val message: String)

    /** 消息类型 → 允许字段（唯一真相源；未登记即拒收）。 */
    private val fromWebFields: Map<String, Set<String>> = mapOf(
        "stable" to setOf("pageIndex", "pageCount"),
        "error" to setOf("code", "message"),
        "metrics" to setOf("costMs"),
        "renderState" to setOf("state"),
        "contentChanged" to setOf("revision"),
        "textPosition" to setOf("revision", "charOffset"),
        "selection" to setOf("rects"),
        "sourceImage" to setOf("src", "alias"),
        "image" to setOf("src", "alias"),
        "link" to setOf("href"),
        "annotationState" to setOf("id", "enabled"),
        "boundary" to setOf("pageIndex", "pageCount"),
        "embeddedInteraction" to setOf("id")
    )

    private val toWebFields: Map<String, Set<String>> = mapOf(
        "inject-mermaid" to setOf("srcHash", "options"),
        "inject-katex" to setOf("srcHash", "options"),
        "remeasure" to setOf("revision"),
        "set-theme" to setOf("themeId")
    )

    fun allowedFields(direction: Direction, type: String): Set<String>? {
        val table = if (direction == Direction.FROM_WEB) fromWebFields else toWebFields
        return table[type]
    }

    /**
     * 解析前校验：长度 + token。
     *
     * @param raw 原始消息文本。
     * @param token 消息回带的 token（缺省 null）。
     * @param expectedToken 宿主为该文档生成的 token。
     */
    fun verifyRaw(raw: String?, token: String?, expectedToken: String): Rejection? {
        if (raw.isNullOrEmpty()) return Rejection("empty", "空消息")
        if (raw.length > MaxRawChars) {
            return Rejection("too-long", "消息超长：${raw.length} > $MaxRawChars")
        }
        if (expectedToken.isBlank()) return Rejection("no-token", "宿主未生成 token，拒绝通信")
        if (token.isNullOrEmpty()) return Rejection("missing-token", "缺少 token")
        if (!constantTimeEquals(token, expectedToken)) return Rejection("bad-token", "token 不匹配")
        return null
    }

    /**
     * 解析后校验：类型 + 字段白名单 + 容量。
     *
     * @param fields 消息携带的字段名集合。
     * @param jsonDepth JSON 嵌套深度（调用方实测）。
     * @param selectionRectCount `selection` 消息的矩形数量（非该类型传 0）。
     */
    fun verifyMessage(
        direction: Direction,
        type: String?,
        fields: Set<String>,
        jsonDepth: Int = 1,
        selectionRectCount: Int = 0
    ): Rejection? {
        if (type.isNullOrBlank()) return Rejection("no-type", "缺少消息类型")
        val allowed = allowedFields(direction, type)
            ?: return Rejection("unknown-type", "未登记的消息类型：$type")
        val unexpected = fields - allowed
        if (unexpected.isNotEmpty()) {
            return Rejection("unexpected-field", "消息类型 $type 不允许字段：${unexpected.sorted()}")
        }
        if (fields.size > MaxFieldCount) {
            return Rejection("too-many-fields", "字段数超限：${fields.size} > $MaxFieldCount")
        }
        if (jsonDepth > MaxJsonDepth) {
            return Rejection("too-deep", "JSON 深度超限：$jsonDepth > $MaxJsonDepth")
        }
        if (selectionRectCount > MaxSelectionRects) {
            return Rejection("too-many-rects", "选区矩形超限：$selectionRectCount > $MaxSelectionRects")
        }
        return null
    }

    /** 一次性校验（解析前 + 解析后）。 */
    fun verify(
        direction: Direction,
        raw: String?,
        token: String?,
        expectedToken: String,
        type: String?,
        fields: Set<String>,
        jsonDepth: Int = 1,
        selectionRectCount: Int = 0
    ): Rejection? {
        verifyRaw(raw, token, expectedToken)?.let { return it }
        return verifyMessage(direction, type, fields, jsonDepth, selectionRectCount)
    }

    /**
     * token 比较：定长比较避免早退时序侧信道（模板不可信，故按不可信输入处理）。
     * 比较前先对齐长度，长度不同直接判否（长度本身不是秘密）。
     */
    private fun constantTimeEquals(a: String, b: String): Boolean {
        if (a.length != b.length) return false
        var diff = 0
        for (index in a.indices) {
            diff = diff or (a[index].code xor b[index].code)
        }
        return diff == 0
    }
}