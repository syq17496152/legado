package io.legado.app.help.dlna

import org.jsoup.Jsoup
import org.jsoup.parser.Parser

/**
 * add-dlna-cast：一台可投屏的 UPnP AV 设备（MediaRenderer）。
 *
 * @property udn 设备唯一标识（来自 SSDP 的 USN，或描述文档的 UDN）
 * @property friendlyName 展示名（电视上设置的名字）
 * @property modelName 型号名（展示名缺失时兜底）
 * @property location 描述文档地址
 * @property controlUrlAvTransport AVTransport 服务的控制地址（**已归一化为绝对地址**）
 * @property controlUrlRenderingControl RenderingControl 控制地址；**为 null 表示设备不支持音量控制**
 */
data class DlnaDevice(
    val udn: String,
    val friendlyName: String,
    val modelName: String?,
    val location: String,
    val controlUrlAvTransport: String,
    val controlUrlRenderingControl: String?
) {
    /** 列表中展示的名字：友好名 → 型号名 → 兜底文案 */
    val displayName: String
        get() = friendlyName.ifBlank { modelName?.ifBlank { null } ?: DlnaConstants.DEFAULT_TITLE }
}

/**
 * add-dlna-cast：设备描述文档（device description XML）解析（纯函数 → 可 JVM 单测）。
 *
 * 解析器选择：用 **jsoup 的 XML 解析器**而非 Android 专属的 `XmlPullParser`。
 * 理由（对 design.md AD-01 的一次实施期修正，已回写 AD-01 ChangeLog）：
 *  1. `XmlPullParser` 只在 Android 运行时可用，纯逻辑就无法进 JVM 单测，与 AD-01
 *     "把解析逻辑纳入自动化验证" 的目标自相矛盾；
 *  2. jsoup 1.16.2 是项目**已锁定**的依赖（Landmines 清单），不引入任何新东西；
 *  3. jsoup 的 XML 解析器不会解析外部实体，天然规避 XXE，比 `DocumentBuilderFactory`
 *     少一堆安全配置。
 *
 * 命名空间策略：UPnP 元素一律带 `urn:schemas-upnp-org:device-1-0` 默认命名空间，
 * jsoup 的 XML 模式会保留前缀但默认命名空间不产生前缀，因此按**标签名后缀**匹配
 * （`endsWith(":controlURL")` 或纯 `controlURL`）最稳，不依赖命名空间解析。
 */
object DeviceDescriptionParser {

    /** 判定是否为 MediaRenderer（只认渲染端，MediaServer / 网关设备一律排除） */
    private const val MEDIA_RENDERER_MARK = "MediaRenderer"

    /**
     * @param xml 描述文档内容
     * @param location 该文档的 URL（用于把相对 controlURL 归一化为绝对地址）
     * @param expectedUdn SSDP 阶段的 UDN；描述文档缺 UDN 时用它兜底
     * @return 解析成功且具备 AVTransport 服务时返回设备，否则 null
     */
    fun parse(xml: String, location: String, expectedUdn: String? = null): DlnaDevice? {
        if (xml.isBlank()) return null
        val doc = kotlin.runCatching { Jsoup.parse(xml, "", Parser.xmlParser()) }.getOrNull() ?: return null
        // 不用 select()/child() —— 它们对标签名大小写敏感，而厂商写法五花八门
        // （`deviceType` / `devicetype` 都出现过），统一按大小写不敏感遍历。
        val device = doc.allElements.firstOrNull { it.tagName().equals("device", true) } ?: return null

        // 设备类型过滤：非 MediaRenderer（如 MediaServer）直接淘汰
        val deviceType = device.childOf("deviceType")?.text().orEmpty()
        if (!deviceType.contains(MEDIA_RENDERER_MARK, ignoreCase = true)) return null

        val friendlyName = device.childOf("friendlyName")?.text().orEmpty()
        val modelName = device.childOf("modelName")?.text()?.ifBlank { null }
        val udn = device.childOf("UDN")?.text()?.ifBlank { null }
            ?: expectedUdn?.ifBlank { null }
            ?: return null // 无身份标识 → 无法去重与记忆，丢弃

        // 遍历所有 service 节点，按 serviceType 关键字挑出两个我们要用的服务
        var avTransport: String? = null
        var renderingControl: String? = null
        for (service in doc.allElements) {
            if (!service.tagName().equals("service", true)) continue
            val serviceType = service.childOf("serviceType")?.text().orEmpty()
            val rawControlUrl = service.childOf("controlURL")?.text().orEmpty()
            if (rawControlUrl.isBlank()) continue
            val absolute = resolveUrl(location, rawControlUrl) ?: continue
            when {
                serviceType.contains("AVTransport", ignoreCase = true) -> avTransport = absolute
                serviceType.contains("RenderingControl", ignoreCase = true) -> renderingControl = absolute
            }
        }
        // 没有 AVTransport 就投不了屏，这种"渲染端"对我们无意义
        val avt = avTransport ?: return null

        return DlnaDevice(
            udn = udn,
            friendlyName = friendlyName,
            modelName = modelName,
            location = location,
            controlUrlAvTransport = avt,
            controlUrlRenderingControl = renderingControl
        )
    }

    /** 按标签名（大小写不敏感）取第一个直接子元素 */
    private fun org.jsoup.nodes.Element.childOf(tag: String): org.jsoup.nodes.Element? =
        children().firstOrNull { it.tagName().equals(tag, true) }

    /**
     * 相对 controlURL 按文档地址归一化。
     *
     * 注意：必须按 `java.net.URL(base, relative)` 的**URL 解析规则**处理，而不是简单的
     * 字符串拼目录 —— 它要正确处理 `/绝对路径`、`../上级路径`、`./同级` 三种形态。
     */
    fun resolveUrl(base: String, relative: String): String? {
        if (relative.isBlank()) return null
        if (relative.startsWith("http://", true) || relative.startsWith("https://", true)) {
            return relative
        }
        return kotlin.runCatching {
            java.net.URL(java.net.URL(base), relative).toString()
        }.getOrNull()
    }
}
