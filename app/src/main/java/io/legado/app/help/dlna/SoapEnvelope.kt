package io.legado.app.help.dlna

/**
 * add-dlna-cast：SOAP 1.1 信封的构造与解析（纯逻辑 → 可 JVM 单测）。
 *
 * UPnP 的"XML 里套 XML"是这里最容易写错的地方，两条规则务必分清：
 *  1. 交给 `SetAVTransportURI` 的 DIDL 是**外层 SOAP 的文本节点**，因此 DIDL 内部
 *     文本（标题、URL）里的 `&` `<` `>` 必须转义成实体 —— 见 [DidlLiteBuilder]，
 *     **只需单层转义**，不存在"双重转义"的需求。
 *  2. SOAP 响应解析面对的是带命名空间前缀的 `u:`、`s:` 标签，因此按**标签名后缀**匹配
 *     而不是按前缀，避免被不同厂商的前缀写法（`u:` / `m:` / 无前缀）搞死。
 *
 * 解析用正则而非 XML 解析器：SOAP 响应体只有几百字节、结构固定，
 * 引一个解析器换不来收益，还要处理命名空间与 XXE。
 */
object SoapEnvelope {

    /** SOAP 解析失败或响应无 `errorCode` 时使用的兜底码（UPnP 的 "Invalid Action"） */
    const val ERROR_CODE_UNKNOWN = 401

    /**
     * 构造 SOAP 请求体。
     *
     * @param service 服务 URN，必须是 `:1` 版本（见 [DlnaConstants.SERVICE_AV_TRANSPORT]）
     * @param action 动作名，如 `SetAVTransportURI`
     * @param body 动作参数（已拼好的 `<InstanceID>0</InstanceID>...`）
     */
    fun build(service: String, action: String, body: String): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n")
        append("<s:Envelope xmlns:s=\"${DlnaConstants.SOAP_ENVELOPE_NS}\" ")
        append("s:encodingStyle=\"${DlnaConstants.SOAP_ENCODING_STYLE}\">\n")
        append("<s:Body>\n")
        append("<u:$action xmlns:u=\"$service\">$body</u:$action>\n")
        append("</s:Body>\n")
        append("</s:Envelope>")
    }

    /**
     * `SOAPACTION` 头值。
     *
     * **必须带双引号**（UPnP 规范要求，老三星/松下收到无引号值会直接忽略请求），
     * 这是 design「协议与 HTTP 实现铁律」表里写死的一条。
     */
    fun soapActionHeader(service: String, action: String): String = "\"$service#$action\""

    /** XML 文本节点转义：`&` 必须最先替换，否则会把后续生成的实体再次转义 */
    fun escapeText(raw: String): String = buildString(raw.length + 16) {
        for (ch in raw) {
            when (ch) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                // XML 1.0 不允许的控制字符：直接丢弃，否则整个信封被设备判为非法 XML
                else -> if (ch.code >= 0x20 || ch == '\n' || ch == '\r' || ch == '\t') append(ch)
            }
        }
    }

    /** XML 属性值转义：在文本转义基础上再处理引号 */
    fun escapeAttribute(raw: String): String =
        escapeText(raw).replace("\"", "&quot;").replace("'", "&apos;")

    /**
     * 提取首个同名标签的文本内容。
     *
     * 同时兼容三种写法：`<Tag>`、`<ns:Tag>`、`<Tag attr="x">`。
     */
    fun extractTag(xml: String, tag: String): String? {
        val regex = Regex(
            "<(?:[A-Za-z0-9_]+:)?$tag(?:\\s[^>]*)?>(.*?)</(?:[A-Za-z0-9_]+:)?$tag>",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
        )
        val match = regex.find(xml) ?: return null
        return unescape(match.groupValues[1].trim())
    }

    /** 提取全部同名标签的文本内容（如 `Track` 列表场景） */
    fun extractAllTags(xml: String, tag: String): List<String> {
        val regex = Regex(
            "<(?:[A-Za-z0-9_]+:)?$tag(?:\\s[^>]*)?>(.*?)</(?:[A-Za-z0-9_]+:)?$tag>",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
        )
        return regex.findAll(xml).map { unescape(it.groupValues[1].trim()) }.toList()
    }

    /**
     * 从 SOAP 响应中解析 UPnP 错误。
     *
     * @return 有错误时返回 [SoapFault]；响应正常（无 `errorCode`）返回 null
     */
    fun parseFault(xml: String): SoapFault? {
        val codeText = extractTag(xml, "errorCode") ?: return null
        val code = codeText.toIntOrNull() ?: ERROR_CODE_UNKNOWN
        return SoapFault(code, extractTag(xml, "errorDescription"))
    }

    /** 反转义 XML 实体（响应里的标题、描述可能带实体） */
    fun unescape(raw: String): String = raw
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&apos;", "'")
        .replace("&amp;", "&")

    /**
     * 把 `HH:MM:SS` 解析为毫秒。
     *
     * AVTransport 的 `REL_TIME` **上限 24 小时且无毫秒精度**（design Drawbacks 已记录），
     * 因此写侧要钳制、读侧只需按 `HH:MM:SS` 解析。
     */
    fun parseHms(text: String?): Long {
        if (text.isNullOrBlank()) return 0L
        val parts = Regex("\\d+").findAll(text).mapNotNull { it.value.toLongOrNull() }.toList()
        if (parts.isEmpty()) return 0L
        var seconds = 0L
        for (p in parts) seconds = seconds * 60 + p
        return seconds * 1000L
    }

    /**
     * 把毫秒格式化为 `HH:MM:SS`，并钳制到 `23:59:59`。
     *
     * 钳制原因：`REL_TIME` 表达不了超过 24 小时的目标值，钳制至少保证不发出非法值。
     */
    fun formatHms(positionMs: Long): String {
        var totalSeconds = (positionMs / 1000L).coerceAtLeast(0L)
        val maxSeconds = 23 * 3600L + 59 * 60L + 59L
        if (totalSeconds > maxSeconds) totalSeconds = maxSeconds
        return "%02d:%02d:%02d".format(totalSeconds / 3600, (totalSeconds % 3600) / 60, totalSeconds % 60)
    }
}

/** add-dlna-cast：UPnP 控制错误 */
data class SoapFault(
    val code: Int,
    val description: String?
) {
    override fun toString(): String = "UPnPError($code): ${description ?: "unknown"}"
}
