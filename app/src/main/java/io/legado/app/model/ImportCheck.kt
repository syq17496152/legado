package io.legado.app.model

import io.legado.app.help.CacheManager

/**
 * 导入校验配置单例（模式仿 CheckSource，CacheManager 持久化，键前缀 importCheck*）
 *
 * 主界面仅 3 项（P1 收敛）：总开关 / 校验深度三选一 / 严格度三选一；
 * concurrency/timeout/retry 为高级折叠项。
 * 探测参数统一映射 ProbeOptions（AD-09：禁止两份探测字段独立定义）。
 */
object ImportCheck {
    var enabled = CacheManager.get("importCheckEnabled")?.toBoolean() ?: false

    // 校验深度：L1 仅结构（秒级不联网）/ L2 快速（默认）/ L3 深度
    var depth = CacheManager.get("importCheckDepth")?.let {
        kotlin.runCatching { CheckDepth.valueOf(it) }.getOrNull()
    } ?: CheckDepth.L2

    // 严格度三档（宽松默认）
    var strictness = CacheManager.get("importCheckStrictness")?.let {
        kotlin.runCatching { CheckStrictness.valueOf(it) }.getOrNull()
    } ?: CheckStrictness.LENIENT

    // 高级折叠项
    var concurrency = CacheManager.get("importCheckConcurrency")?.toIntOrNull() ?: 8
    var timeout = CacheManager.getLong("importCheckTimeout") ?: 10_000L
    var retry = CacheManager.get("importCheckRetry")?.toBoolean() ?: true

    fun putConfig() {
        CacheManager.put("importCheckEnabled", enabled)
        CacheManager.put("importCheckDepth", depth.name)
        CacheManager.put("importCheckStrictness", strictness.name)
        CacheManager.put("importCheckConcurrency", concurrency)
        CacheManager.put("importCheckTimeout", timeout)
        CacheManager.put("importCheckRetry", retry)
    }

    /**
     * 映射为公共组件探测参数（AD-09 单一权威源）
     */
    fun toProbeOptions(): ProbeOptions {
        return ProbeOptions(
            concurrency = concurrency.coerceIn(4, 16),
            timeout = timeout,
            retry = retry,
            depth = depth,
            strictness = strictness
        )
    }
}
