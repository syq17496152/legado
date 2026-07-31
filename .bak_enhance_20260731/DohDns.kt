package io.legado.app.help.http

import io.legado.app.constant.AppLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.dnsoverhttps.DnsOverHttps
import java.net.IDN
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * T4.1: DoH（DNS over HTTPS）实现（对齐 Square 官方方案）
 *
 * 核心能力：
 * - 绕过本地 DNS 污染（SNI 阻断/本地 DNS 过滤）
 * - 使用 HTTPS 加密 DNS 查询，防止中间人篡改
 * - 成功结果内存缓存（5 分钟 TTL），避免每个新连接都付出 DoH 往返延迟
 * - 全局熔断：连续全服务器失败达阈值后暂停 DoH 5 分钟（对齐 Cronet 降级模式），
 *   防止 DoH 整体不可达时每个域名都串行等待多服务器超时
 *
 * N-P1-2 增强（真机日志根因：DoH 0/249 成功率、avg 244s 串行等待）：
 * - IDN 旁路：punycode（xn--）域名公共 DoH 收录率极低，直接走系统 DNS
 * - 五服务器并行查询：首个成功返回（对齐浏览器 HostResolver 并行 probe），整体 ≤3s
 * - 负缓存：DoH 解析失败的域名 30s 内直接走系统 DNS（对齐 Chromium HostResolver negative caching）
 * - 成功优先：最近一次成功服务器置顶出发，减少冷启动域名解析延迟
 *
 * 成熟方案参考：Square 官方博客（okhttp-dnsoverhttps）+ Chromium HostResolver
 *
 * 使用场景：
 * - 视频/图片 CDN 域名被本地 DNS 污染时，通过 DoH 解析真实 IP
 * - 提高抓取成功率（用户核心诉求）
 */
object DohDns : Dns {

    /** DoH 服务器配置（查询 URL + bootstrap IP，bootstrap 用 IP 字面量无需 DNS 解析） */
    private data class DohServer(val url: String, val bootstrapIps: List<String>)

    /**
     * DoH 服务器列表（按优先级排序）
     *
     * P0-fix(2026-07-31): 增加国内 DoH 服务器（阿里/腾讯）并置顶，国外服务器作为备用
     * - 根因：真机日志显示 cloudflare/google/quad9 的 bootstrap IP（1.1.1.1/8.8.8.8/9.9.9.9）
     *   在国内网络环境全部 UnknownHostException，导致 DoH 解析成功率 0%
     * - 方案：增加阿里 DNS（223.5.5.5/223.6.6.6）和腾讯 DNS（119.29.29.29/119.28.28.28），
     *   国内优先解析，国外作为境外 CDN 域名备用
     */
    /**
     * sniff-result-pipeline-fix FR-5: DoH 服务器列表精简
     *
     * 根因：真机日志铁证 server#3/4/5（Cloudflare/Google/Quad9）全部 UnknownHostException
     * 方案：移除国外服务器（国内不可达），保留国内双保险（阿里+腾讯）
     * 已知上限：若阿里+腾讯同时故障，DoH 整体不可用，熔断后走系统 DNS（已实现）
     * 升级路径：如需支持境外 CDN 域名解析，可恢复国外服务器并按地理位置选择
     */
    private val DOH_SERVERS = listOf(
        // 国内 DoH 服务器（国内网络环境可达性最高）
        DohServer("https://dns.alidns.com/dns-query", listOf("223.5.5.5", "223.6.6.6")),
        DohServer("https://doh.pub/dns-query", listOf("119.29.29.29", "119.28.28.28"))
    )

    /** 缓存有效期：5 分钟（对齐典型 DNS TTL，平衡命中率与 IP 时效性） */
    private const val CACHE_TTL_MS = 5 * 60 * 1000L

    /** 最大缓存数量（超限先清过期，仍超限全清重建，缓存重建成本低） */
    private const val MAX_CACHE_SIZE = 200

    /** 全局熔断阈值：连续 3 次全服务器失败触发熔断 */
    private const val GLOBAL_FAIL_THRESHOLD = 3

    /** 熔断时长：5 分钟内直接走系统 DNS（与恢复探测节奏一致） */
    private const val DISABLE_DURATION_MS = 5 * 60 * 1000L

    /**
     * V-004-P0-1: 冷启动熔断时长 30s（首次 DoH 失败立即熔断 30s，非 5min）
     *
     * 根因：004 日志 19:16:57.695-697 DoH server#1/#2/#3 全部 UnknownHostException，
     * 原逻辑需 3 次失败才熔断，每次 2-3s，累计 6-9s 首帧延迟（用户感知"第一个视频失败"）。
     *
     * 方案：冷启动场景首次失败立即熔断 30s（非 5min），30s 后异步预热 DoH 探测恢复。
     * - 30s 而非 5min：冷启动场景系统 DNS 立即可用，DoH 不可达多为网络初始化未完成，
     *   30s 足够网络就绪，5min 过长丧失 DoH 优势
     * - 异步预热：30s 后用独立协程尝试 DoH 解析，成功则退出冷启动模式
     */
    private const val COLD_START_DISABLE_MS = 30_000L

    /** N-P1-2: 并行查询整体超时（OkHttp Dns.lookup 为阻塞调用，runBlocking 内挂起等待 ≤3s） */
    private const val PARALLEL_TOTAL_TIMEOUT_MS = 3000L

    /** N-P1-2: 单服务器硬超时 2s（blocking 调用无法被协程取消中断，只能靠 OkHttp 客户端超时兜底） */
    private const val SERVER_TIMEOUT_SEC = 2L

    /** N-P1-2: 负缓存 30s——DoH 失败的域名 30s 内不再重复尝试 DoH，避免每个连接都白费 DoH 往返 */
    private const val NEGATIVE_CACHE_TTL_MS = 30_000L

    /** DNS 解析成功结果缓存（键含记录类型段，见 cacheKey） */
    private val dnsCache = ConcurrentHashMap<String, CacheEntry>()

    /** N-P1-2: 负缓存（键 → 过期时间戳），命中期间 lookup 直接走系统 DNS */
    private val negativeCache = ConcurrentHashMap<String, Long>()

    private data class CacheEntry(val addresses: List<InetAddress>, val timestamp: Long)

    /** 全服务器连续失败计数（熔断判定） */
    private val globalFailCount = AtomicInteger(0)

    /** 熔断截止时间（此前所有 lookup 直接走系统 DNS） */
    @Volatile private var dohDisabledUntil = 0L

    /**
     * V-004-P0-1: 冷启动模式标志位（App 启动后首次 DoH 失败立即熔断 30s，非 5min）
     *
     * - 初始 true：App 启动后第一次 lookup 进入冷启动分支
     * - 首次成功：置 false，后续按常规熔断逻辑（3 次失败才熔断 5min）
     * - 首次失败：立即熔断 30s + 异步预热，置 false（不再走冷启动分支）
     */
    @Volatile private var isColdStart = true

    /**
     * V-004-P0-1: 异步预热协程作用域（独立于 parallelLookup 的 scope，避免互相取消）
     */
    private val preheatScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /** N-P1-2: 最近一次 DoH 成功的服务器索引（成功优先置顶出发） */
    private val lastSuccessServer = AtomicInteger(0)

    /**
     * DoH 客户端列表（每服务器一个，懒加载）
     *
     * 超时 2 秒快速失败：DoH 是连接建立前的延迟敏感路径，
     * 并行查询承担多服务器竞争（retryOnConnectionFailure=false 避免单点多倍延迟）
     */
    private val dohClients: List<DnsOverHttps> by lazy {
        DOH_SERVERS.map { server ->
            val bootstrapClient = OkHttpClient.Builder()
                .connectTimeout(SERVER_TIMEOUT_SEC, TimeUnit.SECONDS)
                .readTimeout(SERVER_TIMEOUT_SEC, TimeUnit.SECONDS)
                .retryOnConnectionFailure(false)
                .build()
            DnsOverHttps.Builder()
                .client(bootstrapClient)
                .url(server.url.toHttpUrl())
                .bootstrapDnsHosts(server.bootstrapIps.map { InetAddress.getByName(it) })
                .build()
        }
    }

    /**
     * 解析域名（IDN 旁路 → 成功缓存 → 负缓存 → 熔断 → DoH 并行 → 系统 DNS 兜底）
     *
     * @param hostname 域名
     * @return IP 地址列表
     * @throws java.net.UnknownHostException 所有路径均失败时由系统 DNS 抛出
     */
    override fun lookup(hostname: String): List<InetAddress> {
        // 0. N-P1-2: IDN 旁路——punycode（xn--）域名公共 DoH 收录率极低（真机日志 0/249 实证），
        //    直接走系统 DNS，避免无效 DoH 往返（avg 244s 串行等待的根因）
        if (isIdnHost(hostname)) {
            AppLog.putDebug("DohDns: IDN bypass, host=${maskHost(hostname)}")
            return Dns.SYSTEM.lookup(hostname)
        }
        val key = cacheKey(hostname)
        val now = System.currentTimeMillis()
        // 1. 成功缓存命中直接返回（0 延迟）
        dnsCache[key]?.let { entry ->
            if (now - entry.timestamp <= CACHE_TTL_MS) {
                return entry.addresses
            }
            dnsCache.remove(key)
        }
        // 2. N-P1-2: 负缓存命中直接走系统 DNS（30s 内不为该域名重复尝试 DoH）
        // BUG7-V2: 校验 TTL 上限——过期时间距今超过 NEGATIVE_CACHE_TTL_MS 视为异常，清除并走 DoH
        negativeCache[key]?.let { expiry ->
            val ttlRemaining = expiry - now
            if (ttlRemaining in 1..NEGATIVE_CACHE_TTL_MS) {
                AppLog.putDebug("DohDns: negative cache hit, host=${maskHost(hostname)}")
                return Dns.SYSTEM.lookup(hostname)
            }
            // 过期或 TTL 异常（>30s），清除负缓存允许重新尝试 DoH
            negativeCache.remove(key)
        }
        // 3. 熔断期直接走系统 DNS（DoH 整体不可达时避免无效等待）
        if (now < dohDisabledUntil) {
            return Dns.SYSTEM.lookup(hostname)
        }
        // 4. DoH 三服务器并行（lazy 初始化异常兜底，不逃逸到 OkHttp 连接流程）
        val clients = kotlin.runCatching { dohClients }.getOrElse {
            AppLog.put("DohDns: dohClients init failed, fallback system DNS, error=${it.javaClass.simpleName}")
            return Dns.SYSTEM.lookup(hostname)
        }
        val result = parallelLookup(clients, hostname)
        if (result != null) {
            // BUG9-V2: 过滤回环/保留地址（0.0.0.0/[::]/127.x），这些地址无意义且可能导致连接失败
            val validAddresses = result.addresses.filter { addr ->
                val hostAddr = addr.hostAddress ?: return@filter false
                !(hostAddr == "0.0.0.0" || hostAddr == "::" || hostAddr.startsWith("127.") || hostAddr == "::1")
            }
            if (validAddresses.isEmpty()) {
                AppLog.put("DohDns: DoH returned only loopback/reserved addresses, fallback system DNS, host=${maskHost(hostname)}")
                // 视为 DoH 解析失败，走负缓存 + 系统DNS
                negativeCachePut(key)
                return Dns.SYSTEM.lookup(hostname)
            }
            globalFailCount.set(0)
            // V-004-P0-1: 首次 DoH 成功，退出冷启动模式
            if (isColdStart) {
                isColdStart = false
                AppLog.put("DohDns: cold start ended, DoH success server#${result.serverIndex + 1}")
            }
            lastSuccessServer.set(result.serverIndex)
            negativeCache.remove(key)
            cachePut(key, validAddresses)
            AppLog.putDebug(
                "DohDns: parallel success server#${result.serverIndex + 1}, " +
                    "elapsed=${result.elapsedMs}ms, host=${maskHost(hostname)}, ips=${validAddresses.size}"
            )
            return validAddresses
        }
        // 5. 全服务器失败：写负缓存 30s + 累计熔断计数，达阈值暂停 DoH 5 分钟
        negativeCachePut(key)
        if (isColdStart) {
            // V-004-P0-1: 冷启动场景——首次失败立即熔断 30s（非 5min），异步预热 DoH
            // 根因：004 日志 DoH 3 次失败累计 6-9s 首帧延迟，用户感知"第一个视频失败"
            // 方案：冷启动首次失败立即熔断 30s 走系统 DNS，30s 后异步探测 DoH 恢复
            dohDisabledUntil = System.currentTimeMillis() + COLD_START_DISABLE_MS
            globalFailCount.set(0)
            isColdStart = false
            AppLog.put("DohDns: cold start DoH failure, disable DoH ${COLD_START_DISABLE_MS / 1000}s, async preheat, host=${maskHost(hostname)}")
            asyncPreheatDoh()
        } else if (globalFailCount.incrementAndGet() >= GLOBAL_FAIL_THRESHOLD) {
            dohDisabledUntil = System.currentTimeMillis() + DISABLE_DURATION_MS
            globalFailCount.set(0)
            AppLog.put("DohDns: consecutive DoH failures, disable DoH ${DISABLE_DURATION_MS / 60000}min, fallback system DNS")
        } else {
            AppLog.put("DohDns: all DoH servers failed, fallback system DNS, host=${maskHost(hostname)}")
        }
        return Dns.SYSTEM.lookup(hostname)
    }

    /**
     * V-004-P0-1: 异步预热 DoH（冷启动熔断 30s 后尝试探测恢复）
     *
     * - 30s 后用独立协程尝试 DoH 解析常见域名（cloudflare-dns.com）
     * - 探测成功：清除熔断状态（dohDisabledUntil=0），下次 lookup 重新尝试 DoH
     * - 探测失败：保持熔断态，等待常规 5min 熔断逻辑接管或下次 lookup 触发
     *
     * 注：用 preheatScope（独立 SupervisorJob），避免 parallelLookup 的 scope.cancel() 影响预热协程
     */
    private fun asyncPreheatDoh() {
        preheatScope.launch {
            kotlinx.coroutines.delay(COLD_START_DISABLE_MS)
            val probeHost = "cloudflare-dns.com"
            val clients = kotlin.runCatching { dohClients }.getOrElse {
                AppLog.put("DohDns: asyncPreheat dohClients init failed, error=${it.javaClass.simpleName}")
                return@launch
            }
            val result = parallelLookup(clients, probeHost)
            if (result != null) {
                // 探测成功：清除熔断状态，下次 lookup 重新尝试 DoH
                dohDisabledUntil = 0L
                AppLog.put("DohDns: asyncPreheat success, DoH recovered, server#${result.serverIndex + 1}")
            } else {
                AppLog.put("DohDns: asyncPreheat failed, DoH still unreachable, keep disabled")
            }
        }
    }

    /**
     * N-P1-2: 三服务器并行查询，首个成功结果返回（对齐浏览器 HostResolver 并行 probe 模式）
     *
     * - 出发顺序：最近一次成功服务器置顶（其余保持原优先级顺序）
     * - 非结构化出发：不等待全部子任务完成，首个成功即返回（latency = 最快成功者，
     *   而非最慢者；supervisorScope 会等全部子任务结束，违背首成功低延迟语义，故用独立 scope）
     * - 子任务泄漏窗口有界：底层 blocking 调用由 dohClients 的 2s 硬超时兜底，自然结束
     * - 整体超时 PARALLEL_TOTAL_TIMEOUT_MS：Channel.receive 为挂起点，超时可靠触发
     */
    private fun parallelLookup(clients: List<DnsOverHttps>, hostname: String): QueryResult? {
        val startMs = System.currentTimeMillis()
        val order = serverOrder(clients.size)
        val channel = Channel<QueryResult?>(capacity = clients.size)
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        order.forEach { idx ->
            scope.launch {
                val result = kotlin.runCatching { clients[idx].lookup(hostname) }
                    .getOrElse {
                        AppLog.putDebug(
                            "DohDns: DoH server#${idx + 1} failed, " +
                                "host=${maskHost(hostname)}, error=${it.javaClass.simpleName}"
                        )
                        null
                    }
                    ?.takeIf { it.isNotEmpty() }
                    ?.let { QueryResult(idx, it, System.currentTimeMillis() - startMs) }
                channel.send(result)
            }
        }
        val winner = runBlocking {
            withTimeoutOrNull(PARALLEL_TOTAL_TIMEOUT_MS) {
                var result: QueryResult? = null
                var received = 0
                while (result == null && received < clients.size) {
                    val r = channel.receive()
                    received++
                    if (r != null) result = r
                }
                result
            }
        }
        // 协作式取消未完成的子任务；底层 blocking 调用随自身 2s 超时自然结束
        scope.cancel()
        return winner
    }

    /** 服务器出发顺序：最近一次成功者优先，其余按原优先级 */
    private fun serverOrder(clientCount: Int): List<Int> {
        val first = lastSuccessServer.get().coerceIn(0, clientCount - 1)
        val order = ArrayList<Int>(clientCount)
        order.add(first)
        for (i in 0 until clientCount) {
            if (i != first) order.add(i)
        }
        return order
    }

    /**
     * N-P1-2: IDN 域名判定
     * - 已编码 punycode 标签（任一段以 xn-- 开头）
     * - 未编码 unicode 形式（IDN.toASCII 转换后与原文不同）
     */
    private fun isIdnHost(hostname: String): Boolean {
        // 已编码 punycode 标签（任一段以 xn-- 开头）
        if (hostname.split('.').any { it.startsWith("xn--", ignoreCase = true) }) return true
        // 未编码 unicode 形式（IDN.toASCII 转换后与原文不同）
        // toASCII 抛 IllegalArgumentException（如含下划线违反 STD 3）时同样旁路——
        // 此类域名 DoH 不收录 + TLS 层 SNIHostName 构造也会失败，直接走系统 DNS 最优
        // 铁证：003 日志 IDN.toASCII:115 → SNIHostName.<init>:99 IllegalArgumentException
        return kotlin.runCatching { IDN.toASCII(hostname) != hostname }
            .getOrElse { true }  // toASCII 抛异常 → 视为 IDN 旁路
    }

    /**
     * N-P1-2: 缓存键含记录类型维度（A/AAAA 由 DnsOverHttps 一并请求返回，当前固定 "ADDR" 段，
     * 预留多记录类型扩展避免键串扰）
     */
    private fun cacheKey(hostname: String): String = "ADDR:$hostname"

    /**
     * 写入成功缓存（超限先清过期条目，仍超限全清重建）
     */
    private fun cachePut(key: String, addresses: List<InetAddress>) {
        if (dnsCache.size >= MAX_CACHE_SIZE) {
            val now = System.currentTimeMillis()
            dnsCache.entries.removeIf { now - it.value.timestamp > CACHE_TTL_MS }
            if (dnsCache.size >= MAX_CACHE_SIZE) dnsCache.clear()
        }
        dnsCache[key] = CacheEntry(addresses, System.currentTimeMillis())
    }

    /** 写入负缓存（30s 过期；超限全清，负缓存重建成本极低） */
    private fun negativeCachePut(key: String) {
        if (negativeCache.size >= MAX_CACHE_SIZE) negativeCache.clear()
        negativeCache[key] = System.currentTimeMillis() + NEGATIVE_CACHE_TTL_MS
    }

    /** 并行查询结果（服务器索引 + 地址列表 + 耗时） */
    private data class QueryResult(val serverIndex: Int, val addresses: List<InetAddress>, val elapsedMs: Long)

    /**
     * V3-FR-3: host 级负缓存清理 + 熔断状态重置
     *
     * 触发场景：Cronet 遇到 ERR_NAME_NOT_RESOLVED 时调用
     * 原因：DoH 解析失败可能是临时网络问题，清理负缓存允许下次请求重新尝试 DoH
     *
     * V3 关键改进：
     * - host 级清理（非全清）：只清理失败 host 的负缓存，不影响其他域名
     * - 清 dohDisabledUntil：源码 lookup() L189 熔断检查优先于 L179 负缓存检查
     *   熔断期间即使清理了负缓存也无效，必须同时清 dohDisabledUntil
     *
     * @param hostname 失败的域名
     */
    fun clearNegativeCache(hostname: String) {
        val key = cacheKey(hostname)
        negativeCache.remove(key)
        dohDisabledUntil = 0L  // 解决熔断期间清理无效问题
        AppLog.putDebug("DohDns: negative cache cleared for host=${maskHost(hostname)}")
    }

    /**
     * 域名脱敏（日志不输出完整域名，与项目 /path/{hash} 脱敏模式一致）
     */
    private fun maskHost(hostname: String): String {
        return "${hostname.take(2)}***/${hostname.hashCode()}"
    }
}
