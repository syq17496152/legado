package io.legado.app.help.rhino

import io.legado.app.constant.AppLog
import io.legado.app.help.config.AppConfig
import java.util.LinkedHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * 类导入策略观察日志通道（P0-S4，对齐 NG BookSourceGuardLog V6 重设计，本期仅类策略事件）
 *
 * - observeClass（D5 观察档）：AtomicLong 计数化——二期白名单排序需要频次数据而非"是否出现过"；
 *   首次记 1 条，此后每满 10 的幂次记累计频次；去重键走 LRU 上限防长跑内存无界。
 * - blockedClass（D11 实拦）：实拦事件不去重（去重会漏报持续命中），每键每分钟 1 条限流采样。
 * - 日志脱敏铁律：源标识统一 ns 短码（namespace.take(8)），不记源名称/URL；只含类名等技术结构。
 * - 开关 bookSourceClassPolicyLog 仅门控 observeClass 观察日志；blockedClass 属安全实拦事件始终记录。
 * - reset() 供单测/L2 用例间清态（进程级单例状态）。
 */
object BookSourceGuardLog {

    private const val MAX_REPORTED_KEYS = 512

    // 观察计数（V6）：key=label+className
    private val observeCounters = ConcurrentHashMap<String, AtomicLong>()

    // 去重键 LRU（V6）：accessOrder + removeEldestEntry，synchronized 保护
    private val reportedKeys = object : LinkedHashMap<String, Boolean>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>): Boolean {
            return size > MAX_REPORTED_KEYS
        }
    }

    // 实拦限流（V6）：每键每分钟 1 条采样，value=分钟序号
    private val lastLoggedMinute = ConcurrentHashMap<String, Long>()

    /**
     * D5 观察档：书源模式命中 App 类（放行）时计数+按去重/幂次节流记录
     */
    fun observeClass(sourceLabel: String?, className: String) {
        if (!AppConfig.bookSourceClassPolicyLog) {
            return
        }
        val label = sourceLabel ?: "-"
        val key = "$label|$className"
        val count = observeCounters.computeIfAbsent(key) { AtomicLong(0) }.incrementAndGet()
        val isFirstReport = synchronized(reportedKeys) {
            val had = reportedKeys.containsKey(key)
            reportedKeys[key] = true
            !had
        }
        if (isFirstReport || isPowerOfTen(count)) {
            AppLog.putDebugWithTag(
                AppLog.TAG_SOURCE_GUARD,
                "observeClass ns=$label class=$className count=$count (allowed)",
                null,
                AppLog.Level.INFO
            )
        }
    }

    /**
     * D11 实拦：书源模式命中保护类（CookieManager 集）时记录，每键每分钟 1 条限流采样
     */
    fun blockedClass(sourceLabel: String?, className: String) {
        val label = sourceLabel ?: "-"
        val key = "$label|$className"
        val nowMinute = System.currentTimeMillis() / 60000L
        val last = lastLoggedMinute[key]
        if (last != null && last == nowMinute) {
            return
        }
        lastLoggedMinute[key] = nowMinute
        AppLog.putDebugWithTag(
            AppLog.TAG_SOURCE_GUARD,
            "blockedClass ns=$label class=$className (blocked, sampled 1/min)",
            null,
            AppLog.Level.WARN
        )
    }

    /**
     * 测试用：清空计数/去重/限流三张表（L2 用例间约定，用例间 adb 重启 App 亦可清态）
     */
    fun reset() {
        observeCounters.clear()
        synchronized(reportedKeys) { reportedKeys.clear() }
        lastLoggedMinute.clear()
    }

    private fun isPowerOfTen(value: Long): Boolean {
        if (value <= 0L) {
            return false
        }
        var x = value
        while (x % 10L == 0L && x > 1L) {
            x /= 10L
        }
        return x == 1L
    }
}
