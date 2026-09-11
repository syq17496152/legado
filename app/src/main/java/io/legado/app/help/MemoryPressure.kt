package io.legado.app.help

import android.content.ComponentCallbacks2
import android.os.Handler
import android.os.Looper
import io.legado.app.constant.AppLog
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

    val maxMemory: Long
        get() = Runtime.getRuntime().maxMemory()

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
