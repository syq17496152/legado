package io.legado.app.help

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import io.legado.app.constant.AppLog
import io.legado.app.help.config.AppConfig
import io.legado.app.utils.LogUtils
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 应用卡顿自诊断（进程冻结 + **主线程** 双通道）
 *
 * - **进程冻结通道**（原逻辑）：心跳线程自测 `SystemClock.uptimeMillis()` 步进是否被拉长 ——
 *   检测的是「整个进程被系统冻结」（后台冻结/待机），不是主线程阻塞。
 * - **主线程通道**（Q2 新增）：心跳线程向主线程投递一个「回执」任务，超过阈值仍未回执即判为主线程
 *   被业务阻塞，并打印**主线程凶手栈**（取前 [CULPRIT_STACK_MAX_LINES] 行）。阈值 1s 远早于系统
 *   5s ANR 判定 ⇒ 用户还没看到「无响应」弹框，日志里已经有定位线索。
 *
 * 幂等与开关：`init` 会被 App 启动与设置页「记录日志」开关各调用一次（历史上每次都会再排一条 3s 循环
 * ⇒ 循环叠加刷日志），故以 [heartbeatGeneration] 代次收口：同一时刻只允许一代心跳存活；
 * 关闭开关后循环自然停摆，再次开启可重新拉起。
 */
object AppFreezeMonitor {

    private const val TAG = "AppFreezeMonitor"

    /** 心跳周期（进程冻结检测与主线程 ping 共用） */
    private const val HEARTBEAT_INTERVAL_MS = 3000L

    /** 进程被冻结判定阈值：心跳实际间隔超出周期该毫秒数即记录（原逻辑保留） */
    private const val FREEZE_EXTRA_THRESHOLD_MS = 300L

    /** 主线程回执等待上限：超过即判为主线程卡顿（ANR 前兆） */
    private const val MAIN_THREAD_STALL_MS = 1000L

    /** 卡顿日志冷却（防抖）：同一波卡顿只打一次，避免刷屏 */
    private const val STALL_LOG_COOLDOWN_MS = 3000L

    /** 凶手栈最大行数：够定位业务入口即可，避免整栈淹没日志 */
    private const val CULPRIT_STACK_MAX_LINES = 45

    val handler by lazy {
        Handler(HandlerThread("AppFreezeMonitor").apply { start() }.looper)
    }

    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    val screenStatusReceiver by lazy {
        ScreenStatusReceiver()
    }

    private var registeredReceiver = false

    /** 心跳代次：`init` 每次开启都递增，旧循环发现代次不符即退出（防循环叠加） */
    private var heartbeatGeneration = 0

    private var heartbeatActive = false

    private var lastFreezeAt = SystemClock.uptimeMillis()

    private var lastStallLogAt = 0L

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    fun init(context: Context) {
        if (!AppConfig.recordLog) {
            if (registeredReceiver) {
                registeredReceiver = false
                context.unregisterReceiver(screenStatusReceiver)
            }
            return
        }

        if (!registeredReceiver) {
            registeredReceiver = true
            context.registerReceiver(screenStatusReceiver, screenStatusReceiver.filter)
        }

        startHeartbeat()
    }

    /**
     * 启动心跳（幂等）：已在跑的心跳不重复启动；关闭后可再次拉起。
     */
    private fun startHeartbeat() {
        if (heartbeatActive) return
        heartbeatActive = true
        val generation = ++heartbeatGeneration
        lastFreezeAt = SystemClock.uptimeMillis()

        val runnable = object : Runnable {
            override fun run() {
                // 开关已关 或 已被新一轮心跳取代 ⇒ 退出且不再排队（后者不回落标记，避免打断新循环）
                if (!AppConfig.recordLog || generation != heartbeatGeneration) {
                    if (generation == heartbeatGeneration) {
                        heartbeatActive = false
                    }
                    return
                }
                checkProcessFreeze()
                pingMainThread()
                handler.postDelayed(this, HEARTBEAT_INTERVAL_MS)
            }
        }
        handler.postDelayed(runnable, HEARTBEAT_INTERVAL_MS)
    }

    /** 通道 1：进程是否被系统整体冻结（心跳线程自身步进被拉长） */
    private fun checkProcessFreeze() {
        val current = SystemClock.uptimeMillis()
        val extra = current - lastFreezeAt - HEARTBEAT_INTERVAL_MS
        if (extra > FREEZE_EXTRA_THRESHOLD_MS) {
            LogUtils.d(TAG, "检测到应用被系统冻结，时长：$extra 毫秒")
        }
        lastFreezeAt = current
    }

    /** 通道 2：主线程是否被业务阻塞（ping-pong 回执超时） */
    private fun pingMainThread() {
        val acked = AtomicBoolean(false)
        val postedAt = SystemClock.uptimeMillis()
        mainHandler.post { acked.set(true) }
        handler.postDelayed({
            if (!acked.get()) {
                reportMainThreadStall(SystemClock.uptimeMillis() - postedAt)
            }
        }, MAIN_THREAD_STALL_MS)
    }

    /**
     * 打印主线程凶手栈（带冷却防抖）。
     *
     * 读取主线程栈是 ANR 取证的标准手段；注意读栈需等待目标线程到达安全点，故本方法只在
     * 已确认超时（主线程确实在阻塞）时调用，不会平白拖慢心跳线程。
     */
    private fun reportMainThreadStall(blockedMs: Long) {
        val now = SystemClock.uptimeMillis()
        if (now - lastStallLogAt < STALL_LOG_COOLDOWN_MS) return
        lastStallLogAt = now
        val stack = Looper.getMainLooper()?.thread?.stackTrace
        AppLog.put(
            "$TAG 主线程卡顿 ${blockedMs}ms，凶手栈:\n" +
                culpritStackTop(stack, CULPRIT_STACK_MAX_LINES)
        )
    }

    class ScreenStatusReceiver : BroadcastReceiver() {

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }

        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_ON -> LogUtils.d(TAG, "SCREEN_ON")
                Intent.ACTION_SCREEN_OFF -> LogUtils.d(TAG, "SCREEN_OFF")
            }
        }
    }

}

/**
 * Q2：主线程卡顿「凶手栈」文本（最多 [limit] 行）
 *
 * 独立为纯函数以便 JVM 单测（真机触发卡顿不可控）：格式逐行 `at pkg.Class.method(File.kt:12)`。
 * 取栈顶若干行即可定位阻塞入口，无需整栈（整栈会把真正的业务帧淹在框架帧里）。
 */
internal fun culpritStackTop(elements: Array<StackTraceElement>?, limit: Int): String {
    if (elements == null || elements.isEmpty() || limit <= 0) return "（无栈信息）"
    return elements.take(limit).joinToString("\n") { "at $it" }
}