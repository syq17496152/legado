package io.legado.app.help.http

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * HostAccessStrategy（AD-10 阶段1）：host 维度访问健康单源。
 *
 * 背景（真机日志铁证 2026-09-11）：DoH 解析失败面 73 主机 / UHE×156；"DoH 返回坏 IP → TCP 卡满
 * callTimeout 60s"反复浪费——坏 IP 无记忆是第一痛点。现有状态分散四处（certErrorCache/
 * degradedForSession/lastFailedHostHint/negativeCache）且互不知情，本类统一记账。
 *
 * 阶段1 范围（本批）：DNS 层选路 + 失败上报 + 坏 IP 短 TTL 黑名单 + 探测恢复。
 * stackVerdict 仅定义不消费（选路与恢复随阶段2 启用，Cronet per-host 迁移≈重写规模独立立项）。
 *
 * 铁律：
 * - fail-open：pickChannel/report/probe 全路径 try-catch，异常回落默认通道（Cronet 开关原语义+系统
 *   DNS），禁止健康表故障熔断全 App 网络
 * - 退避期条目淘汰优先保留（LRU 兜底防内存膨胀，不牺牲退避记忆）
 * - 探测请求必须旁路本表选路（专用 client 直连 DoH 查询），防"探测经系统 DNS 假成功→清退避→
 *   真实请求走 DoH 再失败"自证循环（brooks-lint W1）
 * - plainImportClient/videoStreamClient 请求不上报本表（防导入链污染 host 信誉）
 */
object HostAccessStrategy {

    /** DNS 层判定（阶段1 选路唯一依据） */
    enum class DnsVerdict { NONE, DOH_OK, DOH_QUERY_FAIL, DOH_BAD_IP }

    /** 栈判定（阶段2 启用，阶段1 仅记录） */
    enum class StackVerdict { NONE, CRONET_OK, CRONET_BAD, OKHTTP_OK, OKHTTP_BAD }

    /** 单 host 健康记录：可变字段一律经 [HostAccessStrategy] 的 compute 原子读改写 */
    private class HostRecord {
        @Volatile var dnsVerdict = DnsVerdict.NONE
        @Volatile var stackVerdict = StackVerdict.NONE
        /** per-host 连续失败计数（全新行为，非既有 globalFailCount/serverFailCounts 语义） */
        @Volatile var failCount = 0
        /** 退避截止（epoch ms）：期间 pickChannel 返回系统 DNS */
        @Volatile var backoffUntil = 0L
        /** 阶段1 选路依据的最近一次 DoH 解析候选 IP（坏 IP 嫌疑上报的标记范围） */
        @Volatile var lastResolvedIps: List<String> = emptyList()
        /** LRU 访问时间戳 */
        val lastAccess = AtomicLong(System.currentTimeMillis())
    }

    /** 健康表上限兜底（高熵站点防内存膨胀；LRU 淘汰但退避期条目优先保留） */
    private const val MAX_HOSTS = 400

    /** 指数退避：连续失败 1→30s，2→5min，≥3→15min 封顶 */
    private const val BACKOFF_STEP1_MS = 30_000L
    private const val BACKOFF_STEP2_MS = 5 * 60_000L
    private const val BACKOFF_STEP3_MS = 15 * 60_000L

    /** 坏 IP 黑名单 TTL（短 TTL+探测自愈兜底，防误拉黑好 IP 永久化） */
    private const val BAD_IP_TTL_MS = 2 * 60_000L

    /** 坏 IP 黑名单上限（键=host|ip 对，防共享 CDN 段裸 IP 投毒） */
    private const val MAX_BAD_IPS = 50

    /** host → 健康记录 */
    private val hosts = ConcurrentHashMap<String, HostRecord>()

    /** 坏 IP 黑名单：键 "host|ip" → 过期时间戳 */
    private val badIps = ConcurrentHashMap<String, Long>()

    /** 探测执行器（由 DohDns 注册：直接 parallelLookup 旁路 lookup 选路，避免依赖成环） */
    @Volatile var probeExecutor: ((host: String) -> Boolean)? = null

    /** 探测调度 scope（懒启动，单调度器收编；DohDns preheatScope 模式同构） */
    private val schedulerScope by lazy { CoroutineScope(Dispatchers.IO + SupervisorJob()) }
    @Volatile private var probeSchedulerStarted = false
    private val schedulerLock = Any()

    // ---------------- 选路（DohDns.lookup 开头调用） ----------------

    /**
     * 该 host 是否应直走系统 DNS（退避期内）。
     * fail-open：任何异常返回 false（维持 DoH 原语义）。
     */
    fun isSystemDnsPreferred(host: String): Boolean = runCatching {
        val r = hosts[host] ?: return@runCatching false
        r.lastAccess.set(System.currentTimeMillis())
        r.backoffUntil > System.currentTimeMillis()
    }.getOrDefault(false)

    // ---------------- 失败/成功上报 ----------------

    /** DoH 查询成功（解析层面）：重置失败计数与退避，登记候选 IP 供坏 IP 嫌疑标记 */
    fun reportDohOk(host: String, resolvedIps: List<String>) = runCatching {
        hosts.compute(host) { _, r ->
            val rec = r ?: HostRecord()
            rec.dnsVerdict = DnsVerdict.DOH_OK
            rec.failCount = 0
            rec.backoffUntil = 0L
            rec.lastResolvedIps = resolvedIps
            rec.lastAccess.set(System.currentTimeMillis())
            rec
        }
        // 解析成功说明候选 IP 刷新，清掉该 host 旧坏 IP 标记（短 TTL 自愈主通道）
        if (badIps.isNotEmpty()) {
            badIps.keys.removeAll { it.startsWith("$host|") }
        }
        maybeStartScheduler()
        Unit
    }.getOrDefault(Unit)

    /** DoH 查询失败（全服务器失败/仅返回回环保留地址）：per-host 计数+退避（不影响全局熔断计数） */
    fun reportDohQueryFail(host: String) = runCatching {
        hosts.compute(host) { _, r ->
            val rec = r ?: HostRecord()
            rec.dnsVerdict = DnsVerdict.DOH_QUERY_FAIL
            rec.failCount += 1
            rec.backoffUntil = System.currentTimeMillis() + backoffFor(rec.failCount)
            rec.lastAccess.set(System.currentTimeMillis())
            rec
        }
        maybeStartScheduler()
        Unit
    }.getOrDefault(Unit)

    /**
     * 坏 IP 嫌疑上报（连接层 CONN_REFUSED/timeout，由 Cronet/OkHttp 上报）：
     * 将该 host 最近一次 DoH 解析的候选 IP 全部标记进黑名单（短 TTL），并置 DOH_BAD_IP+退避。
     * 下次 lookup 黑名单过滤后无可用 IP → 自然回落系统 DNS 或重新 DoH 解析。
     */
    fun reportBadIpSuspect(host: String) = runCatching {
        val now = System.currentTimeMillis()
        hosts.compute(host) { _, r ->
            val rec = r ?: HostRecord()
            rec.lastResolvedIps.forEach { ip ->
                if (badIps.size < MAX_BAD_IPS || badIps.containsKey("$host|$ip")) {
                    badIps["$host|$ip"] = now + BAD_IP_TTL_MS
                }
            }
            rec.dnsVerdict = DnsVerdict.DOH_BAD_IP
            rec.failCount += 1
            rec.backoffUntil = now + backoffFor(rec.failCount)
            rec.lastAccess.set(now)
            rec
        }
        sweepExpiredBadIps(now)
        maybeStartScheduler()
        Unit
    }.getOrDefault(Unit)

    /** 该 host|ip 是否在坏 IP 黑名单内（DohDns 返回地址前过滤调用）；fail-open=不视为坏 */
    fun isBadIp(host: String, ip: String): Boolean = runCatching {
        val expiry = badIps["$host|$ip"] ?: return@runCatching false
        if (expiry > System.currentTimeMillis()) return@runCatching true
        badIps.remove("$host|$ip")
        false
    }.getOrDefault(false)

    /** 栈判定记录（阶段1 仅记录不消费，阶段2 启用选路） */
    fun reportStack(host: String, verdict: StackVerdict) = runCatching {
        hosts.compute(host) { _, r ->
            val rec = r ?: HostRecord()
            rec.stackVerdict = verdict
            rec.lastAccess.set(System.currentTimeMillis())
            rec
        }
        Unit
    }.getOrDefault(Unit)

    /** 手动重置 host 记录（对齐 DohDns.clearNegativeCache 的 NAME_NOT_RESOLVED 联动语义） */
    fun clearHost(host: String) = runCatching {
        hosts.remove(host)
        badIps.keys.removeAll { it.startsWith("$host|") }
        Unit
    }.getOrDefault(Unit)

    /** 测试钩子：强制清空坏 IP 黑名单（JVM 单测无法等 2min TTL） */
    internal fun clearBadIpsForTest() {
        badIps.clear()
    }

    // ---------------- 探测恢复（单调度器） ----------------

    private fun backoffFor(failCount: Int): Long = when {
        failCount <= 1 -> BACKOFF_STEP1_MS
        failCount == 2 -> BACKOFF_STEP2_MS
        else -> BACKOFF_STEP3_MS
    }

    /** 懒启动单调度器：每 30s 扫一次退避到期条目做 DoH 直连探测（旁路选路） */
    private fun maybeStartScheduler() {
        if (probeSchedulerStarted) return
        synchronized(schedulerLock) {
            if (probeSchedulerStarted) return
            probeSchedulerStarted = true
        }
        schedulerScope.launch {
            while (true) {
                delay(30_000L)
                runCatching { sweepAndProbe() }
            }
        }
    }

    /** 扫描退避到期条目 → 直连 DoH 探测（不经 lookup 选路）：成功清记录，失败退避翻倍 */
    private fun sweepAndProbe() {
        val now = System.currentTimeMillis()
        val due = hosts.entries.mapNotNull { (host, r) ->
            if (r.backoffUntil in 1..now && r.dnsVerdict != DnsVerdict.DOH_OK &&
                r.dnsVerdict != DnsVerdict.NONE
            ) host else null
        }
        if (due.isEmpty()) return
        val executor = probeExecutor ?: return
        for (host in due) {
            val ok = runCatching { executor(host) }.getOrDefault(false)
            if (ok) {
                // 探测成功仅清 DNS 层判定（探测经专用 client 直连 DoH，旁路本表，无自证环）
                hosts.compute(host) { _, r ->
                    r?.apply {
                        dnsVerdict = DnsVerdict.DOH_OK
                        failCount = 0
                        backoffUntil = 0L
                        lastAccess.set(System.currentTimeMillis())
                    } ?: HostRecord().apply { dnsVerdict = DnsVerdict.DOH_OK }
                }
            } else {
                hosts.compute(host) { _, r ->
                    r?.apply {
                        failCount += 1
                        backoffUntil = System.currentTimeMillis() + backoffFor(failCount)
                    } ?: r
                }
            }
        }
        evictIfNeeded()
    }

    /** LRU 兜底淘汰：超限时优先淘汰非退避期条目（退避期记忆优先保留） */
    private fun evictIfNeeded() {
        if (hosts.size <= MAX_HOSTS) return
        val now = System.currentTimeMillis()
        val candidates = hosts.entries
            .filter { it.value.backoffUntil <= now }
            .sortedBy { it.value.lastAccess.get() }
        val need = hosts.size - MAX_HOSTS
        candidates.take(need).forEach { hosts.remove(it.key) }
        // 仍超限（退避条目占比过高）：允许淘汰最旧的退避条目，保内存安全优先
        if (hosts.size > MAX_HOSTS) {
            hosts.entries.sortedBy { it.value.lastAccess.get() }
                .take(hosts.size - MAX_HOSTS)
                .forEach { hosts.remove(it.key) }
        }
    }

    /** 坏 IP 黑名单过期清扫（主动清理防 key 无界） */
    private fun sweepExpiredBadIps(now: Long = System.currentTimeMillis()) {
        if (badIps.isEmpty()) return
        badIps.entries.removeIf { it.value <= now }
    }
}
