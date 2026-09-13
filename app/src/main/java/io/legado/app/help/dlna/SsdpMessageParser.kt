package io.legado.app.help.dlna

/**
 * add-dlna-cast：一条 SSDP 响应（M-SEARCH 的 unicast 200 OK）。
 *
 * @property location 设备描述文档地址（DESIGN AD-01 的下一步入口），必填
 * @property usn      形如 `uuid:xxxx::urn:schemas-upnp-org:device:MediaRenderer:1`
 * @property st       响应的搜索目标
 * @property server   设备自报的 SERVER 头（诊断用）
 * @property headers  原始头（键统一小写），保留给诊断日志
 */
data class SsdpResponse(
    val location: String,
    val usn: String,
    val st: String,
    val server: String?,
    val headers: Map<String, String>
) {
    /**
     * 设备唯一标识，用于去重与「记住上次设备」。
     *
     * USN 缺失时退化用 LOCATION 兜底：宁可换成"地址即身份"（设备换 IP 会重复出现），
     * 也不能因为缺 USN 就丢掉一台真实可用的设备。
     */
    val udn: String
        get() = usn.substringBefore("::").trim().ifBlank { location }
}

/**
 * add-dlna-cast：SSDP 响应报文解析（纯函数，无 Android 依赖 → 可 JVM 单测）。
 *
 * 宽进严出：头名大小写不敏感（各厂商大小写千奇百怪），但 **LOCATION 缺失即判无效**——
 * 没有 LOCATION 就没有下一步（拿不到设备描述与控制地址），留着只会污染设备列表。
 */
object SsdpMessageParser {

    /**
     * @param raw SSDP 响应原文（HTTP 风格头 + 空行）
     * @return 解析成功返回 [SsdpResponse]；非 200 响应或缺 LOCATION 返回 null
     */
    fun parse(raw: String): SsdpResponse? {
        if (raw.isBlank()) return null
        // 状态行与头之间用 CRLF，但少数实现在行尾只给 LF，统一按 LF 切分再 trim
        val lines = raw.replace("\r\n", "\n").trim().split('\n')
        if (lines.isEmpty()) return null
        // 状态行校验：必须是 "HTTP/1.x 200 ..."（有些设备写成 "HTTP/1.0 200 OK"）
        val statusLine = lines.first()
        if (!statusLine.startsWith("HTTP/", ignoreCase = true)) return null
        if (!statusLine.contains("200")) return null

        val headers = HashMap<String, String>(lines.size)
        for (i in 1 until lines.size) {
            val line = lines[i].trim()
            if (line.isEmpty()) continue
            val idx = line.indexOf(':')
            if (idx <= 0) continue // 无冒号或冒号在开头 → 丢弃畸形行
            val key = line.substring(0, idx).trim().lowercase()
            val value = line.substring(idx + 1).trim()
            // 同名头保留首次出现（SSDP 场景重复头无意义）
            headers.putIfAbsent(key, value)
        }

        val location = headers["location"]?.trim().orEmpty()
        if (location.isBlank()) return null

        return SsdpResponse(
            location = location,
            usn = headers["usn"]?.trim().orEmpty(),
            st = headers["st"]?.trim().orEmpty(),
            server = headers["server"],
            headers = headers
        )
    }
}
