package io.legado.app.help.video

import android.os.SystemClock
import io.legado.app.BuildConfig
import io.legado.app.constant.AppLog
import splitties.init.appCtx
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.min

/**
 * 全链路端到端诊断日志（video-player-redguo-alignment design §1.16）
 *
 * 目标：一次出包即可还原「嗅探 → 采集 → setUp → 起播 → 缓冲 → 切集 → 再嗅探 → 再起播 → 缓冲」。
 *
 * 约束：
 * - 仅 debug 包 + 偏好开关（默认开）输出，release 零开销（enabled() 首判短路）
 * - 统一 tag [TAG]，格式 `VbsTrace | stage=x | t=.. | dt=.. | k=v`
 * - 禁止完整 URL：对外只暴露 [urlPath] 结果（host 哈希 + path 尾段），不含 query/cookie/token
 * - 任何异常都不得影响播放：所有输出走 runCatching
 */
object VideoTrace {

    const val TAG = "VbsTrace"

    /** 单条日志上限，避免超长行干扰 logcat */
    private const val MAX_LEN = 512

    private const val PREFS = "video_trace"
    private const val KEY_ENABLED = "enabled"

    /** 各 stage 上次输出时间（用于 dt） */
    private val lastAt = ConcurrentHashMap<String, Long>()

    /** 采样类 stage 上次输出时间 */
    private val lastSampledAt = ConcurrentHashMap<String, Long>()

    /** 测试注入开关：null=按正式门禁；true/false=强制 */
    @Volatile
    internal var forceEnabled: Boolean? = null

    fun enabled(): Boolean {
        forceEnabled?.let { return it }
        return runCatching {
            BuildConfig.BUILD_DEBUG &&
                appCtx.getSharedPreferences(PREFS, 0).getBoolean(KEY_ENABLED, true)
        }.getOrDefault(false)
    }

    /**
     * 事件类日志（全量输出）
     *
     * @param stage 阶段名，如 sniff_start / pipe_start / player_acquire / switch_done
     * @param kv 键值对（值会自动 toString，URL 请先经 [urlPath] 脱敏）
     */
    fun event(stage: String, vararg kv: Pair<String, Any?>) {
        if (!enabled()) return
        runCatching {
            val now = SystemClock.uptimeMillis()
            val last = lastAt.put(stage, now) ?: now
            AppLog.putDebugWithTag(TAG, format(stage, now, now - last, kv), level = AppLog.Level.INFO)
        }
    }

    /**
     * 采样类日志（同一 stage 内按 [intervalMs] 限频，默认 1s）
     * 用于 buffer / bandwidth 等高频指标。
     */
    fun sampled(stage: String, intervalMs: Long = 1000L, vararg kv: Pair<String, Any?>) {
        if (!enabled()) return
        runCatching {
            val now = SystemClock.uptimeMillis()
            val last = lastSampledAt[stage] ?: 0L
            if (!shouldSample(last, now, intervalMs)) return
            lastSampledAt[stage] = now
            AppLog.putDebugWithTag(TAG, format(stage, now, delta(last, now), kv), level = AppLog.Level.INFO)
        }
    }

    /** 纯函数：采样限频判定（first=true 时 last=0 → 必放行） */
    internal fun shouldSample(last: Long, now: Long, intervalMs: Long): Boolean {
        if (last <= 0L) return true
        return now - last >= intervalMs
    }

    /** 纯函数：与上次输出的间隔；首次调用返回 0 */
    internal fun delta(last: Long?, now: Long): Long {
        if (last == null || last <= 0L) return 0L
        return (now - last).coerceAtLeast(0L)
    }

    /** 记录某阶段耗时（自动带 costMs） */
    fun cost(stage: String, costMs: Long, vararg kv: Pair<String, Any?>) {
        event(stage, *kv, "costMs" to costMs)
    }

    /**
     * 纯函数：拼装日志行（可单测）
     * 格式固定为 `VbsTrace | stage=x | t=.. | dt=.. | k=v ...`
     */
    internal fun format(
        stage: String,
        t: Long,
        dt: Long,
        kv: Array<out Pair<String, Any?>>
    ): String {
        val sb = StringBuilder(96)
        sb.append(TAG).append(" | stage=").append(stage)
        sb.append(" | t=").append(t)
        sb.append(" | dt=").append(dt)
        for ((k, v) in kv) {
            if (k.isBlank()) continue
            sb.append(" | ").append(k).append('=').append(clean(v))
        }
        val text = sb.toString()
        return if (text.length <= MAX_LEN) text else text.substring(0, MAX_LEN) + "…"
    }

    /**
     * 纯函数：URL 脱敏（可单测）
     * 规则：host → `h<8位哈希>`，path → 仅保留最后两段（去 query/fragment）。
     * 保证不含域名、query、cookie 等敏感内容，同时保留可关联性。
     */
    fun urlPath(url: String?): String {
        if (url.isNullOrBlank()) return "-"
        val noQuery = url.substringBefore('?').substringBefore('#')
        val schemeIdx = noQuery.indexOf("://")
        val afterScheme = if (schemeIdx >= 0) noQuery.substring(schemeIdx + 3) else noQuery
        val slash = afterScheme.indexOf('/')
        val host = if (slash >= 0) afterScheme.substring(0, slash) else afterScheme
        val path = if (slash >= 0) afterScheme.substring(slash) else "/"
        val segs = path.split('/').filter { it.isNotBlank() }
        val tail = segs.takeLast(2).joinToString("/")
        return "h${hash8(host)}/$tail"
    }

    /** 稳定 8 位哈希（仅用于日志关联，不可逆）；取低位 8 位避免负数高位全 f 导致碰撞 */
    internal fun hash8(text: String): String {
        if (text.isBlank()) return "0"
        var h = 1125899906842597L
        for (c in text) {
            h = 31 * h + c.code
        }
        val hex = java.lang.Long.toHexString(h)
        val low = if (hex.length > 8) hex.substring(hex.length - 8) else hex
        return low.padStart(8, '0')
    }

    /** 清理值中的换行，避免破坏单行日志 */
    private fun clean(value: Any?): String {
        val text = value?.toString() ?: "null"
        return if (text.indexOf('\n') >= 0 || text.indexOf('\r') >= 0) {
            text.replace('\n', ' ').replace('\r', ' ')
        } else {
            text
        }
    }

    /** 供测试复位内部计时 */
    internal fun resetForTest() {
        lastAt.clear()
        lastSampledAt.clear()
    }

    /** 兜底截断（保留给外部复用的多行场景） */
    @Suppress("unused")
    internal fun clamp(text: String): String = if (text.length <= MAX_LEN) text else text.substring(0, min(text.length, MAX_LEN))
}
