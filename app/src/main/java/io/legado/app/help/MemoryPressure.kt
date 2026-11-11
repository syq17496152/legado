package io.legado.app.help

import android.content.ComponentCallbacks2
import android.os.Handler
import android.os.Looper
import io.legado.app.constant.AppLog
import io.legado.app.help.config.AppConfig
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

object MemoryPressure {

    private const val M = 1024 * 1024L
    private var lastTrimTime = 0L
    private var trimCallback: ((Int) -> Unit)? = null
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    // 测试注入点：仅 JVM 单测 MemoryPressureTest 使用，生产环境为 null 走真实 Runtime
    internal var availableMemoryProvider: (() -> Long)? = null
    internal var currentTimeProvider: (() -> Long)? = null
    /** 测试注入点：堆上限（四档阈值按比例判定，单测必须能固定 maxMemory） */
    internal var maxMemoryProvider: (() -> Long)? = null

    val maxMemory: Long
        get() = maxMemoryProvider?.invoke() ?: Runtime.getRuntime().maxMemory()

    val isSmallHeap: Boolean
        get() = maxMemory <= 320L * M

    fun usedMemory(): Long {
        val runtime = Runtime.getRuntime()
        return runtime.totalMemory() - runtime.freeMemory()
    }

    fun availableMemory(): Long {
        availableMemoryProvider?.let { return it() }
        return maxMemory - usedMemory()
    }

    fun shouldTrimNow(): Boolean {
        val available = availableMemory()
        val max = maxMemory
        return available < 24L * M || available < max / 10
    }

    @Suppress("DEPRECATION")
    fun trimLevelForCurrentState(): Int {
        val available = availableMemory()
        return when {
            available < 8L * M -> ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL
            available < 16L * M -> ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW
            else -> ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE
        }
    }

    private fun now(): Long = currentTimeProvider?.invoke() ?: System.currentTimeMillis()

    fun throttleTrim(block: (Int) -> Unit) {
        // F9/2.18：高频周期打点删除（原每 3 秒 skip 日志 = 真机日志 5521 条/15% 洪水），
        // 改为压力级别跃迁时输出单条（normal/low/critical 迁移才打，<10 条/会话）
        logPressureTransition()
        if (!shouldTrimNow()) return
        val current = now()
        if (current - lastTrimTime < 1500L) return
        lastTrimTime = current
        block(trimLevelForCurrentState())
    }

    fun setTrimCallback(callback: (Int) -> Unit) {
        trimCallback = callback
    }

    fun trimNow(level: Int, waitForCompletion: Boolean = false) {
        lastTrimTime = now()
        dispatchTrim(level, waitForCompletion)
    }

    fun trimIfNeeded() {
        throttleTrim { level ->
            dispatchTrim(level, waitForCompletion = false)
        }
    }

    // 测试辅助：仅 JVM 单测 MemoryPressureTest 使用
    internal fun resetForTest() {
        lastTrimTime = 0L
        lastPressureLevel = -1
    }

    // ---------------- F9/2.18：压力级别跃迁打点 ----------------

    private const val PRESSURE_NORMAL = 0
    private const val PRESSURE_LOW = 1
    private const val PRESSURE_CRITICAL = 2

    @Volatile private var lastPressureLevel = -1

    private fun currentPressureLevel(): Int {
        val available = availableMemory()
        return when {
            available < 8L * M -> PRESSURE_CRITICAL
            available < 24L * M -> PRESSURE_LOW
            else -> PRESSURE_NORMAL
        }
    }

    private fun levelName(level: Int): String = when (level) {
        PRESSURE_LOW -> "low"
        PRESSURE_CRITICAL -> "critical"
        else -> "normal"
    }

    /** 仅级别迁移时输出一条（可用内存 8MB/24MB 两档阈值），替代原周期 skip 洪水 */
    private fun logPressureTransition() {
        val level = currentPressureLevel()
        if (level == lastPressureLevel) return
        val from = if (lastPressureLevel == -1) "init" else levelName(lastPressureLevel)
        lastPressureLevel = level
        kotlin.runCatching {
            AppLog.putDebugWithTag(
                AppLog.TAG_MEMORY_PRESSURE,
                "内存压力级别迁移: $from → ${levelName(level)} (avail=${availableMemory() / M}MB)",
                level = AppLog.Level.INFO
            )
        }
    }

    // ---------------- crash-hardening：四档压力前馈（AD-01 / AD-03 / AD-09） ----------------

    enum class PressureLevel { NORMAL, MODERATE, LOW, CRITICAL }

    /**
     * 降载能力位快照（只读）：
     * - [imageDecodeScale] 图片解码目标尺寸缩放（1.0 正常 / 0.75 / 0.5）
     * - [prefetchAllowed] 是否允许"非当前内容"的预取（视频/听书）
     * - [animationAllowed] 是否允许非必要动效（沉浸舞台粒子/流体等）
     */
    data class ThrottleState(
        val level: PressureLevel = PressureLevel.NORMAL,
        val imageDecodeScale: Float = 1f,
        val prefetchAllowed: Boolean = true,
        val animationAllowed: Boolean = true
    )

    private const val MODERATE_RATIO = 0.25
    private const val LOW_RATIO = 0.15
    private const val CRITICAL_RATIO = 0.08
    private const val MODERATE_FLOOR = 24L * M
    private const val LOW_FLOOR = 16L * M
    private const val CRITICAL_FLOOR = 8L * M

    /** 好转（降档）需持续满足的时长：防在阈值附近抖动 */
    private const val RECOVER_SUSTAIN_MS = 30_000L

    /** 同档动作最小间隔：CRITICAL 更急，间隔更短 */
    private const val ACTION_COOLDOWN_MS = 60_000L
    private const val CRITICAL_COOLDOWN_MS = 20_000L

    @Volatile
    private var level = PressureLevel.NORMAL
    private var recoverySince = 0L
    private var lastActionAt = 0L

    /** 总开关缓存：SharedPreferences 读取不适合在渲染/图片加载路径上每次调用 */
    @Volatile
    private var cachedActionsEnabled = false
    private var cachedActionsEnabledAt = 0L

    val currentLevel: PressureLevel get() = level

    /**
     * 动作总开关：默认关闭（观测模式）。关闭时只算档位、只打点，不执行任何降载/回收，
     * 行为与改造前一致（AD-09）；读取失败一律按关闭处理，避免治理逻辑自身引入风险。
     * 结果缓存 5s，供图片解码/动效/预取等高频调用点使用。
     */
    fun actionsEnabled(): Boolean {
        val current = now()
        if (current - cachedActionsEnabledAt < 5_000L) return cachedActionsEnabled
        cachedActionsEnabled = kotlin.runCatching {
            AppConfig.crashHardeningActionsEnabled
        }.getOrDefault(false)
        cachedActionsEnabledAt = current
        return cachedActionsEnabled
    }

    /** 纯函数：按"可用堆 + 绝对下限"判定档位（下限避免小堆设备被比例阈值过度触发） */
    fun levelOf(available: Long, max: Long): PressureLevel {
        if (max <= 0L) return PressureLevel.NORMAL
        return when {
            available < threshold(CRITICAL_RATIO, CRITICAL_FLOOR, max) -> PressureLevel.CRITICAL
            available < threshold(LOW_RATIO, LOW_FLOOR, max) -> PressureLevel.LOW
            available < threshold(MODERATE_RATIO, MODERATE_FLOOR, max) -> PressureLevel.MODERATE
            else -> PressureLevel.NORMAL
        }
    }

    private fun threshold(ratio: Double, floor: Long, max: Long): Long =
        maxOf((max * ratio).toLong(), floor)

    /**
     * 刷新档位（带迟滞）：恶化立即生效；好转需连续 [RECOVER_SUSTAIN_MS] 才降档。
     */
    fun refreshLevel(now: Long = now()): PressureLevel {
        val raw = levelOf(availableMemory(), maxMemory)
        val current = level
        when {
            raw.ordinal > current.ordinal -> {
                level = raw
                recoverySince = 0L
            }

            raw.ordinal < current.ordinal -> {
                if (recoverySince == 0L) {
                    recoverySince = now
                } else if (now - recoverySince >= RECOVER_SUSTAIN_MS) {
                    level = raw
                    recoverySince = 0L
                }
            }

            else -> recoverySince = 0L
        }
        return level
    }

    /** 同档动作冷却：防止 3s 轮询连续清理造成"清理-占满-再清理"抖动 */
    fun allowActions(now: Long = now()): Boolean {
        val interval =
            if (level == PressureLevel.CRITICAL) CRITICAL_COOLDOWN_MS else ACTION_COOLDOWN_MS
        if (now - lastActionAt < interval) return false
        lastActionAt = now
        return true
    }

    /**
     * 能力位快照。总开关关闭时一律返回"不降载"（业务行为与改造前一致），
     * 但 [ThrottleState.level] 仍反映真实档位，供观测打点与单测使用。
     */
    fun throttleState(
        level: PressureLevel = this.level,
        enabled: Boolean = actionsEnabled()
    ): ThrottleState {
        if (!enabled) return ThrottleState(level = level)
        return when (level) {
            PressureLevel.NORMAL, PressureLevel.MODERATE -> ThrottleState(level)
            PressureLevel.LOW -> ThrottleState(
                level = level,
                imageDecodeScale = 0.75f,
                prefetchAllowed = false
            )

            PressureLevel.CRITICAL -> ThrottleState(
                level = level,
                imageDecodeScale = 0.5f,
                prefetchAllowed = false,
                animationAllowed = false
            )
        }
    }

    /** 测试辅助：重置四档状态（仅 JVM 单测使用） */
    internal fun resetLevelForTest() {
        level = PressureLevel.NORMAL
        recoverySince = 0L
        lastActionAt = 0L
    }

    private fun dispatchTrim(level: Int, waitForCompletion: Boolean) {
        val callback = trimCallback ?: return
        if (Looper.myLooper() == Looper.getMainLooper()) {
            callback(level)
            return
        }
        if (!waitForCompletion) {
            mainHandler.post { callback(level) }
            return
        }
        val latch = CountDownLatch(1)
        mainHandler.post {
            try {
                callback(level)
            } finally {
                latch.countDown()
            }
        }
        latch.await(500L, TimeUnit.MILLISECONDS)
    }
}
