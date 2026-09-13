package io.legado.app.help.dlna

import android.content.Context
import android.net.wifi.WifiManager
import android.os.SystemClock
import io.legado.app.utils.LogUtils
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException

/**
 * add-dlna-cast：SSDP 设备发现（design AD-01 / AD-13）。
 *
 * 流程：发 M-SEARCH → 收 unicast 200 OK → 拉设备描述文档 → 过滤出 MediaRenderer。
 *
 * 关于 [MulticastLock]：本功能只做**主动发现**（M-SEARCH 的响应是 **unicast 回到请求方源端口**），
 * 严格来说不需要组播锁。但部分 Wi-Fi 芯片在未持锁时会过滤 UDP 广播/组播流量，
 * 因此**在发现窗口内持锁、窗口结束立即释放**（成本可忽略）。
 * 拿不到锁时 `runCatching` 静默降级 —— 部分设备/模拟器的 `WifiManager` 为 null 或抛异常，
 * 不允许因此崩溃或阻断发现（REQ-13 Scenario「权限被系统拒绝」）。
 */
object SsdpDiscovery {

    /** 单次 receive 的阻塞上限；用轮询式 receive 以便按时退出发现窗口 */
    private const val RECEIVE_TIMEOUT_MS = 500

    /** 主发现无结果时的兜底窗口（用 `ssdp:all` 再问一次，过滤逻辑不变） */
    private const val FALLBACK_WINDOW_MS = 2_000L

    /**
     * 执行一次发现。
     *
     * @param context 用于取 MulticastLock
     * @return 去重（按 UDN）后的可用设备列表；任何失败都返回空列表而非抛异常
     */
    fun discover(context: Context): List<DlnaDevice> {
        val lock = acquireMulticastLock(context)
        try {
            // ① 主发现：直接问 MediaRenderer
            var responses = search(
                types = listOf(DlnaConstants.DEVICE_TYPE_MEDIA_RENDERER),
                windowMs = DlnaConstants.SSDP_SEARCH_WINDOW_MS
            )
            // ② 兜底：部分设备只认 ssdp:all，主发现空手时再问一次（过滤逻辑完全一致，
            //    不会把非渲染端放进来）
            if (responses.isEmpty()) {
                LogUtils.d(DlnaConstants.TAG) { "SSDP 主发现无结果，回退 ssdp:all 重试" }
                responses = search(
                    types = listOf(DlnaConstants.ST_SSDP_ALL),
                    windowMs = FALLBACK_WINDOW_MS
                )
            }
            if (responses.isEmpty()) return emptyList()

            // ③ 逐个拉描述文档并解析（失败的设备静默跳过，不影响其它设备）
            val devices = LinkedHashMap<String, DlnaDevice>()
            for (response in responses) {
                val device = fetchDevice(response) ?: continue
                devices.putIfAbsent(device.udn, device)
            }
            LogUtils.d(DlnaConstants.TAG) {
                "SSDP 响应 ${responses.size} 台，解析出 MediaRenderer ${devices.size} 台"
            }
            return devices.values.toList()
        } catch (e: Exception) {
            LogUtils.d(DlnaConstants.TAG) { "SSDP 发现异常: ${e.message}" }
            return emptyList()
        } finally {
            releaseMulticastLock(lock)
        }
    }

    /**
     * 发 M-SEARCH 并收集 unicast 响应，按 UDN 去重。
     *
     * 实现要点：用一个绑定到**临时端口**的 `DatagramSocket` 发包（而不是绑定 1900）。
     * 设备会把 200 OK 单播回这个源端口，因此无需加入组播组、也无需监听 1900——
     * 这也顺带规避了"1900 被系统 SSDP 服务占用"的冲突。
     */
    fun search(types: List<String>, windowMs: Long): List<SsdpResponse> {
        val socket = DatagramSocket()
        socket.soTimeout = RECEIVE_TIMEOUT_MS
        val results = LinkedHashMap<String, SsdpResponse>()
        val deadline = SystemClock.elapsedRealtime() + windowMs
        try {
            val group = InetAddress.getByName(DlnaConstants.SSDP_GROUP)
            for (st in types) {
                val payload = buildMSearch(st).toByteArray(Charsets.UTF_8)
                socket.send(
                    DatagramPacket(payload, payload.size, group, DlnaConstants.SSDP_PORT)
                )
            }
            val buffer = ByteArray(4096)
            while (SystemClock.elapsedRealtime() < deadline) {
                val packet = DatagramPacket(buffer, buffer.size)
                try {
                    socket.receive(packet)
                } catch (e: SocketTimeoutException) {
                    continue // 正常：窗口内没包也要继续等
                }
                val raw = String(packet.data, 0, packet.length, Charsets.UTF_8)
                val parsed = SsdpMessageParser.parse(raw) ?: continue
                results.putIfAbsent(parsed.udn, parsed)
            }
        } catch (e: Exception) {
            LogUtils.d(DlnaConstants.TAG) { "M-SEARCH 失败: ${e.message}" }
        } finally {
            kotlin.runCatching { socket.close() }
        }
        return results.values.toList()
    }

    /**
     * 构造 M-SEARCH 报文。
     *
     * `MAN: "ssdp:discover"` 的引号是 UPnP 规范要求，**不能省**；
     * `HOST` 必须是 `组播地址:1900` 字面量。
     */
    fun buildMSearch(st: String): String = buildString {
        append("M-SEARCH * HTTP/1.1\r\n")
        append("HOST: ${DlnaConstants.SSDP_GROUP}:${DlnaConstants.SSDP_PORT}\r\n")
        append("MAN: \"ssdp:discover\"\r\n")
        append("MX: ${DlnaConstants.SSDP_MX}\r\n")
        append("ST: $st\r\n")
        append("\r\n")
    }

    /** 拉取并解析单台设备的描述文档 */
    fun fetchDevice(response: SsdpResponse): DlnaDevice? {
        val xml = DlnaHttp.fetchText(
            url = response.location,
            timeoutMs = DlnaConstants.TIMEOUT_DESCRIPTION_MS
        ) ?: return null
        return DeviceDescriptionParser.parse(
            xml = xml,
            location = response.location,
            expectedUdn = response.udn
        )
    }

    // ==================== 组播锁（尽力而为）====================

    private fun acquireMulticastLock(context: Context): WifiManager.MulticastLock? {
        return kotlin.runCatching {
            val manager = context.applicationContext
                .getSystemService(Context.WIFI_SERVICE) as? WifiManager
            manager?.createMulticastLock("legado:dlna")?.apply {
                setReferenceCounted(false)
                acquire()
            }
        }.onFailure {
            // 无 CHANGE_WIFI_MULTICAST_STATE 权限或设备不支持 → 降级为无锁发现
            LogUtils.d(DlnaConstants.TAG) { "MulticastLock 获取失败，降级无锁发现: ${it.message}" }
        }.getOrNull()
    }

    private fun releaseMulticastLock(lock: WifiManager.MulticastLock?) {
        if (lock == null) return
        kotlin.runCatching {
            if (lock.isHeld) lock.release()
        }
    }
}
