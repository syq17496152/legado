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
        if (!shouldTrimNow()) {
            logDebug("throttle skip reason=avail")
            return
        }
        val current = now()
        if (current - lastTrimTime < 1500L) {
            logDebug("throttle skip reason=interval")
            return
        }
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

    private fun logDebug(message: String) {
        kotlin.runCatching {
            AppLog.putDebugWithTag(AppLog.TAG_MEMORY_PRESSURE, message, level = AppLog.Level.DEBUG)
        }
    }
}
