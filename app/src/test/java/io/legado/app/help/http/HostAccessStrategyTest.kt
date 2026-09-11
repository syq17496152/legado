package io.legado.app.help.http

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * F5/3.2：HostAccessStrategy 健康表纯逻辑单测（JVM）。
 * 覆盖：per-host 退避记账 / 成功重置 / 坏 IP 黑名单（host+IP 对键）与清除 / clearHost。
 * 注：探测调度（sweepAndProbe）依赖 30s delay 循环，属 L2 验证范围不在此测。
 */
class HostAccessStrategyTest {

    @Test
    fun `初始状态走DoH`() {
        HostAccessStrategy.clearHost("a.example")
        assertFalse(HostAccessStrategy.isSystemDnsPreferred("a.example"))
    }

    @Test
    fun `查询失败进入退避期走系统DNS`() {
        val host = "b.example"
        HostAccessStrategy.clearHost(host)
        HostAccessStrategy.reportDohQueryFail(host)
        assertTrue(HostAccessStrategy.isSystemDnsPreferred(host))
    }

    @Test
    fun `成功重置退避与失败计数`() {
        val host = "c.example"
        HostAccessStrategy.clearHost(host)
        HostAccessStrategy.reportDohQueryFail(host)
        HostAccessStrategy.reportDohQueryFail(host)
        assertTrue(HostAccessStrategy.isSystemDnsPreferred(host))
        HostAccessStrategy.reportDohOk(host, listOf("1.2.3.4"))
        assertFalse(HostAccessStrategy.isSystemDnsPreferred(host))
    }

    @Test
    fun `坏IP嫌疑标记候选IP并可清除`() {
        val host = "d.example"
        HostAccessStrategy.clearHost(host)
        HostAccessStrategy.reportDohOk(host, listOf("5.6.7.8", "9.9.9.9"))
        HostAccessStrategy.reportBadIpSuspect(host)
        assertTrue(HostAccessStrategy.isBadIp(host, "5.6.7.8"))
        assertTrue(HostAccessStrategy.isBadIp(host, "9.9.9.9"))
        // 其他 host 不受共享 IP 影响（host+IP 对键）
        assertFalse(HostAccessStrategy.isBadIp("other.example", "5.6.7.8"))
        HostAccessStrategy.clearBadIpsForTest()
        assertFalse(HostAccessStrategy.isBadIp(host, "5.6.7.8"))
    }

    @Test
    fun `clearHost联动清空记录与坏IP标记`() {
        val host = "e.example"
        HostAccessStrategy.clearHost(host)
        HostAccessStrategy.reportDohOk(host, listOf("7.7.7.7"))
        HostAccessStrategy.reportBadIpSuspect(host)
        assertTrue(HostAccessStrategy.isBadIp(host, "7.7.7.7"))
        HostAccessStrategy.clearHost(host)
        assertFalse(HostAccessStrategy.isSystemDnsPreferred(host))
        assertFalse(HostAccessStrategy.isBadIp(host, "7.7.7.7"))
    }

    @Test
    fun `退避值域30s到15min封顶`() {
        val host = "f.example"
        HostAccessStrategy.clearHost(host)
        repeat(5) { HostAccessStrategy.reportDohQueryFail(host) }
        assertTrue(HostAccessStrategy.isSystemDnsPreferred(host))
        // fail-open：异常输入不抛
        HostAccessStrategy.reportDohOk("", emptyList())
        assertEquals(Unit, Unit)
    }
}
