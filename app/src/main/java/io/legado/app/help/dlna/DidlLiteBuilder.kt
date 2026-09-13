package io.legado.app.help.dlna

/**
 * add-dlna-cast：DIDL-Lite 元数据构造（纯逻辑 → 可 JVM 单测）。
 *
 * `SetAVTransportURI` 的 `CurrentURIMetaData` 参数要求一段 DIDL-Lite XML。
 * 严格设备完全依赖它来判定"能不能播"：
 *  - 缺 `<upnp:class>` → 被归为未知对象而拒播（design 铁律表已写死必须带）
 *  - 缺 `res@protocolInfo` → 无法判定容器/编码，部分设备直接忽略
 *
 * 注意：本字符串最终会作为**外层 SOAP 的文本节点**嵌入，所以内部所有文本
 * （标题、URL）必须做 XML 实体转义 —— 但**只需单层**，不要二次转义。
 */
object DidlLiteBuilder {

    private const val DIDL_NS = "urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/"
    private const val DC_NS = "http://purl.org/dc/elements/1.1/"
    private const val UPNP_NS = "urn:schemas-upnp-org:metadata-1-0/upnp/"

    /**
     * 交给 `SetAVTransportURI` 的空元数据。
     *
     * 用途见 AD-11 降级链：老设备拒收 DIDL（SOAP Fault 402/714）时，用空串重试一次；
     * 成功则把该设备 UDN 记入"无元数据兼容"名单，后续直接跳过元数据。
     */
    const val EMPTY_METADATA = ""

    /**
     * 构造 DIDL-Lite。
     *
     * @param castUrl 投递地址（直投原地址或本机代理地址）
     * @param protocolInfo 形如 `http-get:*:video/mp4:DLNA.ORG_OP=01;...`，见 [MimeSniffer.protocolInfo]
     * @param title 标题；空白时用 [DlnaConstants.DEFAULT_TITLE] 兜底
     */
    fun build(castUrl: String, protocolInfo: String, title: String?): String {
        val safeTitle = SoapEnvelope.escapeText(
            title?.takeIf { it.isNotBlank() } ?: DlnaConstants.DEFAULT_TITLE
        )
        val safeUrl = SoapEnvelope.escapeText(castUrl)
        val safeProtocolInfo = SoapEnvelope.escapeAttribute(protocolInfo)
        return buildString {
            append("<DIDL-Lite xmlns=\"$DIDL_NS\" xmlns:dc=\"$DC_NS\" xmlns:upnp=\"$UPNP_NS\">")
            append("<item id=\"0\" parentID=\"-1\" restricted=\"1\">")
            append("<dc:title>$safeTitle</dc:title>")
            // 视频项 class：缺失会让严格设备判定为未知对象
            append("<upnp:class>${DlnaConstants.DIDL_CLASS_VIDEO}</upnp:class>")
            append("<res protocolInfo=\"$safeProtocolInfo\">$safeUrl</res>")
            append("</item>")
            append("</DIDL-Lite>")
        }
    }
}
