package io.legado.app.help.dlna

import android.content.Context
import android.net.ConnectivityManager
import io.legado.app.utils.NetworkUtils
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * add-dlna-cast：候选网卡地址。
 *
 * @property address 点分十进制 IPv4
 * @property interfaceName 网卡名（`wlan0` / `eth0` / `rmnet_data0` / `tun0` …）
 * @property isActiveNetwork 是否属于当前活动网络（ConnectivityManager 视角）
 */
data class LanCandidate(
    val address: String,
    val interfaceName: String,
    val isActiveNetwork: Boolean
)

/**
 * 选择结果。
 *
 * @property address 选中的投递地址；**null 表示彻底拿不到可用地址，投屏必须前置拦下**
 * @property ambiguous 存在多个可用地址（无法确定唯一）→ UI 需显式提示实际使用的地址
 */
data class LanPickResult(
    val address: String?,
    val ambiguous: Boolean
)

/**
 * add-dlna-cast AD-13：投屏投递地址的网卡选择策略。
 *
 * **为什么必须有这个类**（红队第 2 轮 P0-1 击穿点）：
 * `NetworkUtils.getLocalIPAddress()`（`utils/NetworkUtils.kt:231`）会把**所有网卡**的非回环
 * IPv4 一股脑返回，**没有排序保证、也没有 WLAN 偏好**；既有 `WebService.kt:104` 直接取
 * `.first()`。Web 服务场景下取错只是"地址显示不对"，但投屏场景下取错等于给电视一个
 * 它永远连不上的地址 —— 投屏 100% 失败且**没有任何报错**。
 *
 * 2026-09-12 本机实测就存在 4 个非回环 IPv4（含两个虚拟网卡），见
 * `docs/specs/add-dlna-cast/issues-found.md` IF-2，因此这不是理论风险。
 *
 * 选择优先级（[selectFrom]，纯函数 → 可 JVM 单测）：
 *   ① 属于活动网络且非链路本地 —— **首选**（用户手动选了哪张网卡就是哪张）
 *   ② 网卡名以 `wlan` / `eth` / `ap` / `wifi` 开头且非链路本地 —— 家庭与实体局域网的主流形态
 *   ③ 其余非链路本地、且排除 `rmnet` / `tun` / `ppp` / `clat` 开头者（蜂窝与 VPN，电视必然不可达）
 *   ④ 全部落空 → null（**不允许"猜一个"**，由调用方前置拦下并提示）
 */
object CastNetworkHelper {

    /** 链路本地地址前缀（`169.254.x.x`，自动配置失败时出现，不可用于局域网通信） */
    private const val LINK_LOCAL_PREFIX = "169.254."

    /** 有线/无线局域网网卡名前缀 */
    private val LAN_INTERFACE_REGEX = Regex("^(wlan|eth|ap|wifi)\\d*", RegexOption.IGNORE_CASE)

    /** 确定无法被局域网设备回连的网卡名前缀（蜂窝 / VPN / 点对点 / 464XLAT） */
    private val NON_LAN_INTERFACE_REGEX =
        Regex("^(rmnet|tun|ppp|clat|p2p|dummy|sit|ip6tnl)", RegexOption.IGNORE_CASE)

    // ==================== 纯逻辑（可单测）====================

    /** 是否链路本地地址（`169.254.*`） */
    fun isLinkLocal(address: String): Boolean = address.startsWith(LINK_LOCAL_PREFIX)

    /**
     * 按三级策略从候选列表中选择。
     * 传入空列表或全部不可用时返回 `address = null`。
     */
    fun selectFrom(candidates: List<LanCandidate>): LanPickResult {
        // 先剔除绝对不可用的：链路本地 / 回环 / 通配
        val usable = candidates.filter {
            it.address.isNotBlank() &&
                !isLinkLocal(it.address) &&
                !it.address.startsWith("127.") &&
                it.address != "0.0.0.0"
        }
        if (usable.isEmpty()) return LanPickResult(null, false)

        // ① 活动网络优先
        usable.firstOrNull { it.isActiveNetwork }?.let {
            return LanPickResult(it.address, usable.size > 1)
        }
        // ② 局域网网卡名
        usable.firstOrNull { LAN_INTERFACE_REGEX.containsMatchIn(it.interfaceName) }?.let {
            return LanPickResult(it.address, usable.size > 1)
        }
        // ③ 排除蜂窝/VPN 后的首个
        usable.firstOrNull { !NON_LAN_INTERFACE_REGEX.containsMatchIn(it.interfaceName) }?.let {
            return LanPickResult(it.address, usable.size > 1)
        }
        // ④ 拿不准就不猜
        return LanPickResult(null, usable.size > 1)
    }

    /**
     * 判断两个 IPv4 是否在同一 /24 网段。
     *
     * 用途：投递前校验本机地址与渲染端地址（来自 SSDP 的 LOCATION）是否同网段。
     * 不同网段不阻断投屏（存在双层 NAT 等合法场景），只用于给出可诊断的提示。
     */
    fun sameSubnet24(a: String?, b: String?): Boolean {
        val pa = parseIpv4Prefix(a) ?: return false
        val pb = parseIpv4Prefix(b) ?: return false
        return pa == pb
    }

    private fun parseIpv4Prefix(address: String?): String? {
        if (address.isNullOrBlank()) return null
        val parts = address.split('.')
        if (parts.size != 4) return null
        return parts.take(3).joinToString(".")
    }

    // ==================== Android 侧（构建候选 + 取用）====================

    /**
     * 收集候选地址。
     *
     * 活动网络的地址来自 ConnectivityManager（最可信）；网卡层面再从 NetworkInterface 补齐，
     * 这样即使 ConnectivityManager 因权限/ROM 差异拿不到，也还有兜底数据。
     */
    fun buildCandidates(context: Context): List<LanCandidate> {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val activeAddresses = HashSet<String>()
        kotlin.runCatching {
            val active = manager?.activeNetwork ?: return@runCatching
            val props = manager.getLinkProperties(active) ?: return@runCatching
            props.linkAddresses.forEach { link ->
                val host = link.address
                if (host is Inet4Address) activeAddresses.add(host.hostAddress.orEmpty())
            }
        }

        val result = ArrayList<LanCandidate>()
        val seen = HashSet<String>()
        // ① NetworkInterface 遍历（含网卡名，供策略② ③ 使用）
        kotlin.runCatching {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return@runCatching
            for (nif in interfaces) {
                if (!nif.isUp || nif.isLoopback) continue
                val name = nif.name.orEmpty()
                for (addr in nif.inetAddresses) {
                    if (addr !is Inet4Address || addr.isLoopbackAddress) continue
                    val host = addr.hostAddress ?: continue
                    if (!seen.add(host)) continue
                    result.add(LanCandidate(host, name, activeAddresses.contains(host)))
                }
            }
        }
        // ② 补上 ConnectivityManager 独有、NetworkInterface 没枚举到的地址
        for (host in activeAddresses) {
            if (seen.add(host)) {
                result.add(LanCandidate(host, "", true))
            }
        }
        return result
    }

    /**
     * 取投屏用的本机地址。实现按 [selectFrom] 的策略，并在策略① 之外补一层
     * `NetworkUtils.getLocalIPAddress()` 兜底（ROM 差异下 NetworkInterface 可能枚举不全）。
     */
    fun pickLanAddress(context: Context): LanPickResult {
        val candidates = buildCandidates(context)
        val picked = selectFrom(candidates)
        if (picked.address != null) return picked
        // 兜底：既有工具方法（无网卡名信息，只能作为最后一档）
        val fallback = kotlin.runCatching {
            NetworkUtils.getLocalIPAddress()
                .map { it.hostAddress.orEmpty() }
                .filter { it.isNotBlank() && !isLinkLocal(it) }
        }.getOrNull().orEmpty()
        if (fallback.isEmpty()) return picked
        return LanPickResult(fallback.first(), fallback.size > 1)
    }
}
