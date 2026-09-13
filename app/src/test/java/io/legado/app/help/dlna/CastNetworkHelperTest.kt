package io.legado.app.help.dlna

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * add-dlna-cast：投屏本机 IP 选择策略单测（tasks 2.5 / **AD-13，红队 P0-1 修复点**）。
 *
 * 为什么这个测试重要：`NetworkUtils.getLocalIPAddress()` 返回全部非回环 IPv4 且无排序，
 * 选错就等于给电视一个它永远连不上的地址 —— 投屏 100% 失败且**没有报错**。
 * 2026-09-12 本机实测就有 4 个非回环 IPv4（含两个虚拟网卡），见 issues-found.md IF-2。
 *
 * 验证点：
 * 1. 活动网络（Wi-Fi）优先于 VPN / 蜂窝 / 虚拟网卡
 * 2. 无活动网络信息时，回退到 `wlan` / `eth` / `ap` / `wifi` 开头的网卡名
 * 3. 再回退时排除 `rmnet` / `tun` / `ppp` / `clat` 开头者（电视必然不可达）
 * 4. 链路本地 `169.254.*`、回环 `127.*`、`0.0.0.0` 一律剔除
 * 5. 拿不准时返回 null（**不猜**）
 * 6. 多可用地址时置 `ambiguous = true`（UI 据此提示实际使用的地址）
 *
 * 已知上限：只测纯函数 [CastNetworkHelper.selectFrom] / [CastNetworkHelper.sameSubnet24]；
 * `buildCandidates` / `pickLanAddress` 依赖 ConnectivityManager，靠真机 L2 验证。
 */
class CastNetworkHelperTest {

    private fun candidate(
        address: String,
        iface: String,
        active: Boolean = false
    ) = LanCandidate(address, iface, active)

    // ============ 优先级①：活动网络 ============

    @Test
    fun selectFrom_prefersActiveNetworkOverVirtualAndCellular() {
        val result = CastNetworkHelper.selectFrom(
            listOf(
                candidate("192.168.64.1", "vEthernet (Default Switch)"),
                candidate("10.2.152.167", "rmnet_data0"),
                candidate("192.168.1.7", "wlan0", active = true)
            )
        )
        assertEquals("必须选活动网络地址，而不是列表首项", "192.168.1.7", result.address)
        assertTrue("存在多个可用地址 → 应置 ambiguous", result.ambiguous)
    }

    @Test
    fun selectFrom_activeNetworkWinsEvenWhenNotFirstLanLooking() {
        // 活动网络是 eth0（部分盒子/电视棒直连场景），也要优先
        val result = CastNetworkHelper.selectFrom(
            listOf(
                candidate("192.168.1.7", "wlan0"),
                candidate("10.0.0.5", "eth0", active = true)
            )
        )
        assertEquals("10.0.0.5", result.address)
    }

    // ============ 优先级②：局域网网卡名 ============

    @Test
    fun selectFrom_fallsBackToLanInterfaceName() {
        // 没有任何 active 标记时，按网卡名挑
        val result = CastNetworkHelper.selectFrom(
            listOf(
                candidate("172.16.9.3", "tun0"),
                candidate("192.168.1.7", "wlan0"),
                candidate("10.2.152.167", "rmnet_data0")
            )
        )
        assertEquals("应命中 wlan0", "192.168.1.7", result.address)
    }

    @Test
    fun selectFrom_handlesApAndEthernetInterfaceNames() {
        assertEquals(
            "ap0（热点）也算局域网",
            "192.168.43.1",
            CastNetworkHelper.selectFrom(listOf(candidate("192.168.43.1", "ap0"))).address
        )
        assertEquals(
            "eth0 算局域网",
            "192.168.1.20",
            CastNetworkHelper.selectFrom(listOf(candidate("192.168.1.20", "eth0"))).address
        )
    }

    // ============ 优先级③：排除蜂窝/VPN 后取首个 ============

    @Test
    fun selectFrom_lastResortExcludesCellularVpnAndP2p() {
        val result = CastNetworkHelper.selectFrom(
            listOf(
                candidate("10.2.152.167", "rmnet_data0"),
                candidate("172.16.9.3", "tun0"),
                candidate("10.10.10.2", "ppp0"),
                candidate("192.0.0.4", "custom_nic")
            )
        )
        assertEquals(
            "蜂窝/VPN/点对点必须排除，只剩未知网卡可用",
            "192.0.0.4",
            result.address
        )
    }

    @Test
    fun selectFrom_returnsNullWhenOnlyUnreachableInterfacesExist() {
        val result = CastNetworkHelper.selectFrom(
            listOf(
                candidate("10.2.152.167", "rmnet_data0"),
                candidate("172.16.9.3", "tun0")
            )
        )
        assertNull("只剩蜂窝/VPN 时必须返回 null（不允许猜一个）", result.address)
    }

    // ============ 剔除规则 ============

    @Test
    fun selectFrom_filtersLinkLocalLoopbackAndWildcard() {
        val result = CastNetworkHelper.selectFrom(
            listOf(
                candidate("169.254.13.9", "wlan0"),
                candidate("127.0.0.1", "lo"),
                candidate("0.0.0.0", "wlan0"),
                candidate("192.168.1.7", "wlan0")
            )
        )
        assertEquals("链路本地/回环/通配一律剔除", "192.168.1.7", result.address)
        assertFalse("剔除后只剩一个可用地址 → 不算 ambiguous", result.ambiguous)
    }

    @Test
    fun selectFrom_returnsNullForEmptyOrAllFilteredInput() {
        assertNull("空列表", CastNetworkHelper.selectFrom(emptyList()).address)
        assertNull(
            "全被过滤",
            CastNetworkHelper.selectFrom(
                listOf(candidate("169.254.1.1", "wlan0"), candidate("", "wlan0"))
            ).address
        )
    }

    @Test
    fun isLinkLocal_detectsOnly169254Prefix() {
        assertTrue(CastNetworkHelper.isLinkLocal("169.254.0.1"))
        assertTrue(CastNetworkHelper.isLinkLocal("169.254.255.254"))
        assertFalse("169.253 不是链路本地", CastNetworkHelper.isLinkLocal("169.253.0.1"))
        assertFalse(CastNetworkHelper.isLinkLocal("192.168.1.1"))
    }

    // ============ ambiguous 标记 ============

    @Test
    fun selectFrom_singleCandidateIsNotAmbiguous() {
        val result = CastNetworkHelper.selectFrom(listOf(candidate("192.168.1.7", "wlan0")))
        assertEquals("192.168.1.7", result.address)
        assertFalse("只有一个可用地址时不应提示歧义", result.ambiguous)
    }

    // ============ 同网段校验 ============

    @Test
    fun sameSubnet24_comparesFirstThreeOctets() {
        assertTrue(CastNetworkHelper.sameSubnet24("192.168.1.7", "192.168.1.100"))
        assertFalse("第三段不同即不同网段", CastNetworkHelper.sameSubnet24("192.168.1.7", "192.168.2.100"))
        assertFalse(CastNetworkHelper.sameSubnet24("10.0.0.5", "192.168.1.100"))
    }

    @Test
    fun sameSubnet24_rejectsInvalidInput() {
        assertFalse("null", CastNetworkHelper.sameSubnet24(null, "192.168.1.1"))
        assertFalse("空串", CastNetworkHelper.sameSubnet24("", "192.168.1.1"))
        assertFalse("非 IPv4", CastNetworkHelper.sameSubnet24("fe80::1", "192.168.1.1"))
        assertFalse("段数不足", CastNetworkHelper.sameSubnet24("192.168.1", "192.168.1.1"))
    }
}
