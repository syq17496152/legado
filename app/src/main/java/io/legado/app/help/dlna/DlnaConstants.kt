package io.legado.app.help.dlna

/**
 * add-dlna-cast：DLNA/UPnP 投屏的集中常量表。
 *
 * 铁律（design.md AD-11「统一超时常量表」）：**所有超时值必须在此定义，禁止散落魔数**。
 * 实施时若发现需要新增超时，先加到这里再引用。
 */
object DlnaConstants {

    // ==================== SSDP 发现 ====================

    /** SSDP 组播地址（UPnP 规范固定值） */
    const val SSDP_GROUP = "239.255.255.250"

    /** SSDP 端口（UPnP 规范固定值；Windows 上被 SSDPSRV 服务占用，见 issues-found.md IF-1） */
    const val SSDP_PORT = 1900

    /** M-SEARCH 的 MX：请求设备在 0~MX 秒内随机延迟响应，避免响应风暴 */
    const val SSDP_MX = 2

    /** 发现等待窗口（毫秒）：发出 M-SEARCH 后收集 unicast 响应的时长 */
    const val SSDP_SEARCH_WINDOW_MS = 4_000L

    /** MediaRenderer 的设备类型（用于 M-SEARCH 的 ST 与描述文档过滤） */
    const val DEVICE_TYPE_MEDIA_RENDERER = "urn:schemas-upnp-org:device:MediaRenderer:1"

    /** 其他常见的 SSDP 搜索目标 */
    const val ST_SSDP_ALL = "ssdp:all"
    const val ST_ROOT_DEVICE = "upnp:rootdevice"

    // ==================== UPnP 服务 URN ====================

    // 注意：控制点必须用 :1（不是 :2）——设备描述里暴露的多为 :1，用 :2 会得到 401/500
    const val SERVICE_AV_TRANSPORT = "urn:schemas-upnp-org:service:AVTransport:1"
    const val SERVICE_RENDERING_CONTROL = "urn:schemas-upnp-org:service:RenderingControl:1"

    /** SOAP 1.1 信封命名空间 */
    const val SOAP_ENVELOPE_NS = "http://schemas.xmlsoap.org/soap/envelope/"

    /** SOAP 编码风格命名空间 */
    const val SOAP_ENCODING_STYLE = "http://schemas.xmlsoap.org/soap/encoding/"

    /** UPnP 控制错误命名空间 */
    const val UPNP_CONTROL_NS = "urn:schemas-upnp-org:control-1-0"

    /** AVTransport 的 InstanceID（单实例场景恒为 0） */
    const val INSTANCE_ID = 0

    // ==================== 超时（毫秒）====================

    /** 拉取设备描述文档的超时 */
    const val TIMEOUT_DESCRIPTION_MS = 5_000L

    /** 单次 SOAP 调用（AVTransport / RenderingControl）超时 */
    const val TIMEOUT_SOAP_MS = 10_000L

    /** MIME 推断用的 HEAD 探测超时；失败即降级为扩展名推断，不阻塞投屏 */
    const val TIMEOUT_HEAD_PROBE_MS = 5_000L

    /** 上游取流「首包」超时；超时回 504 */
    const val TIMEOUT_UPSTREAM_FIRST_BYTE_MS = 15_000L

    /**
     * 首帧判定窗口：SetAVTransportURI 成功后，若此时长内 GetTransportInfo 仍为
     * STOPPED，判定为拉流失败并走降级链。
     */
    const val TIMEOUT_FIRST_FRAME_MS = 8_000L

    /** 轮询间隔（播放中）：驱动进度条与状态刷新 */
    const val POLL_INTERVAL_PLAYING_MS = 2_000L

    /** 轮询间隔（暂停中）：只探状态，降频省电 */
    const val POLL_INTERVAL_PAUSED_MS = 10_000L

    /** 连续失败判定阈值：达到即判设备离线并结束会话 */
    const val POLL_FAILURE_THRESHOLD = 3

    // ==================== 拉流代理 ====================

    /** 代理 URL 路径前缀：/cast/{token}/... */
    const val PROXY_PATH_PREFIX = "/cast"

    /** 短 ID 路径段（HLS 重写时惰性登记，避免 URL 长度膨胀） */
    const val PROXY_SHORT_ID_SEGMENT = "s"

    /** token 长度（字符数，SecureRandom 生成） */
    const val PROXY_TOKEN_LENGTH = 32

    /** 代理并发上限（NanoHTTPD 默认每请求一线程且无上限，必须自行约束） */
    const val PROXY_MAX_CONCURRENT_REQUESTS = 16

    /** 代理监听端口：0 = 由系统分配，启动后回读 listeningPort */
    const val PROXY_PORT_AUTO = 0

    /** NanoHTTPD 的 socket 读超时覆盖值（默认 5000ms，对大文件/长连接过于激进） */
    const val PROXY_SOCKET_READ_TIMEOUT_MS = 60_000

    /** 代理路径末段文件名的最大长度（超出截断，且不参与任何上游地址拼接） */
    const val PROXY_NAME_MAX_LENGTH = 64

    // ==================== DLNA / DIDL ====================

    /**
     * DLNA 扩展特性串（protocolInfo 的第 4 段）。
     * OP=01 表示支持字节级 seek（HTTP Range），本功能的 Range 代理正是为此。
     */
    const val DLNA_FLAGS = "01700000000000000000000000000000"

    /** protocolInfo 组装模板 */
    fun protocolInfo(mime: String): String =
        "http-get:*:$mime:DLNA.ORG_OP=01;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=$DLNA_FLAGS"

    /** DIDL-Lite 的 UPnP class：视频项（严格设备按 class 判定可否播放） */
    const val DIDL_CLASS_VIDEO = "object.item.videoItem"

    /** MIME 推断全部失败时的兜底值 */
    const val MIME_FALLBACK = "video/mp4"

    /** DIDL 标题为空时的兜底展示名 */
    const val DEFAULT_TITLE = "视频"

    // ==================== 偏好键（存 video_config，AD-10）====================

    /** 启用投屏功能（默认开）：关闭后播放器菜单不显示「投屏」 */
    const val PREF_CAST_ENABLED = "dlnaCastEnabled"

    /** 强制走代理（默认关）：所有流经手机转发，仅用于排查 */
    const val PREF_FORCE_PROXY = "dlnaForceProxy"

    /** 上次成功投屏设备的 UDN（用于「上次：XXX」快捷入口） */
    const val PREF_LAST_DEVICE_UDN = "dlnaLastDeviceUdn"

    /** 上次成功投屏设备的显示名 */
    const val PREF_LAST_DEVICE_NAME = "dlnaLastDeviceName"

    /** 已知「无元数据兼容」设备的 UDN 列表（逗号分隔，LRU 上限 10） */
    const val PREF_NO_META_DEVICES = "dlnaNoMetaDevices"

    /** 「无元数据兼容」名单的最大条数（超出按最近使用淘汰） */
    const val NO_META_DEVICES_MAX = 10

    // ==================== 日志 TAG ====================

    const val TAG = "DlnaCast"
}
