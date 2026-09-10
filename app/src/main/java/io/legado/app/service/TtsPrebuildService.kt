package io.legado.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import io.legado.app.R
import io.legado.app.constant.AppLog
import io.legado.app.constant.AppConst
import io.legado.app.help.readaloud.prebuild.TtsPrebuildManager
import io.legado.app.help.readaloud.prebuild.TtsPrebuildState
import io.legado.app.ui.book.cache.CacheManageActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 批量预合成前台服务（P2-7，§3.7.2）：仅作前台存活壳+通知渲染，队列逻辑在 TtsPrebuildManager
 * - foregroundServiceType=dataSync（对齐 CacheBookService 先例；API<29 忽略类型语义）
 * - 通知渠道复用 AppConst.channelIdDownload（完成/失败文案语义兼容），NotificationId 独立常量防并发互踩
 * - 通知栏划掉≠取消任务（任务继续，取消走通知 action）；onDestroy/onTaskRemoved→任务中止
 * - Android 15 dataSync 6h 强制时限：单批 ≤200 章内可控，超时被杀后重发即幂等续跑
 */
class TtsPrebuildService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var pollJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground()
        observeState()
    }

    private fun createChannel() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                AppConst.channelIdDownload,
                getString(R.string.tts_casting_prebuild_menu),
                NotificationManager.IMPORTANCE_DEFAULT
            )
            manager.createNotificationChannel(channel)
        }
    }

    private fun startForeground() {
        val notification = buildNotification(getString(R.string.tts_casting_prebuild_start))
        startForeground(NOTIFICATION_ID, notification)
    }

    /** 每秒轮询 StateFlow 刷新通知进度（对齐 CacheBookService 通知节奏）
     *  P1-8 修复：首轮先宽限 5s——enqueue→ensureWorker(SCANNING) 落地存在异步窗口，
     *  立即判 IDLE 会 stopSelf 自杀（任务裸奔，Android 15 后台易被杀）；
     *  且 IDLE 判定需连续 2 次成立（3s 间隔），防单帧抖动误退 */
    private fun observeState() {
        pollJob = scope.launch {
            delay(GRACE_BEFORE_IDLE_CHECK_MS)
            var idleStrikes = 0
            while (isActive) {
                val state = TtsPrebuildManager.state.value
                if (state.phase == TtsPrebuildState.Phase.IDLE) {
                    idleStrikes++
                    // 无任务：退出前台并停止（全队列空转无意义）；连续确认防竞态误杀
                    if (idleStrikes >= 2) {
                        stopSelf()
                        break
                    }
                } else {
                    idleStrikes = 0
                }
                val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                manager.notify(NOTIFICATION_ID, buildNotification(progressText(state)))
                when (state.phase) {
                    TtsPrebuildState.Phase.DONE, TtsPrebuildState.Phase.FAILED, TtsPrebuildState.Phase.CANCELLED -> {
                        AppLog.put("TTS 预合成结束：${state.phase} failed=${state.failedCount}")
                    }
                    else -> Unit
                }
                delay(1000L)
            }
        }
    }

    private fun progressText(state: TtsPrebuildState): String = when (state.phase) {
        TtsPrebuildState.Phase.SCANNING -> getString(R.string.tts_casting_prebuild_scanning)
        TtsPrebuildState.Phase.RUNNING ->
            getString(R.string.tts_casting_prebuild_progress, state.currentUnit, state.totalUnit) +
                if (state.failedCount > 0) " · ${getString(R.string.tts_casting_prebuild_failed)}${state.failedCount}" else ""
        TtsPrebuildState.Phase.DONE -> getString(R.string.tts_casting_prebuild_done)
        TtsPrebuildState.Phase.FAILED -> getString(R.string.tts_casting_prebuild_failed) + "：" + state.message
        TtsPrebuildState.Phase.CANCELLED -> getString(R.string.tts_casting_prebuild_cancelled)
        else -> state.message
    }

    private fun buildNotification(text: String): Notification {
        val intent = Intent(this, CacheManageActivity::class.java)
        val pending = android.app.PendingIntent.getActivity(
            this, 0, intent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )
        val cancelIntent = Intent(this, TtsPrebuildService::class.java).apply {
            action = ACTION_CANCEL
        }
        val cancelPending = android.app.PendingIntent.getService(
            this, 1, cancelIntent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, AppConst.channelIdDownload)
            .setSmallIcon(R.drawable.ic_volume_up)
            .setContentTitle(getString(R.string.tts_casting_prebuild_menu))
            .setContentText(text)
            .setContentIntent(pending)
            .addAction(0, getString(R.string.tts_preview_stop), cancelPending)
            .setOngoing(true)
            .build()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            AppLog.putDebugWithTag(AppLog.TAG_TTS_TRACE, "prebuildService 通知取消按钮触发", level = AppLog.Level.INFO)
            TtsPrebuildManager.cancelCurrent()
        }
        return START_NOT_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // 用户移除任务（划掉 App）：任务中止（仅进程死亡才允许重发重建语义）
        // P2-17 修复：取消全部任务（原仅取消首个，排队任务继续无前台壳裸奔）
        AppLog.put("TTS 预合成服务 onTaskRemoved，取消全部任务")
        TtsPrebuildManager.cancelAll()
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        // 对齐设计 §3.7.2（onDestroy→Manager.cancel）：服务壳销毁即取消全部任务
        TtsPrebuildManager.cancelAll()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        /** 独立 NotificationId（防与 CacheBookService 并发互踩） */
        private const val NOTIFICATION_ID = 10086
        private const val ACTION_CANCEL = "io.legado.app.tts.prebuild.CANCEL"

        /** IDLE 判定前宽限窗（P1-8）：覆盖 enqueue→SCANNING 异步落地窗口 */
        private const val GRACE_BEFORE_IDLE_CHECK_MS = 5_000L

        fun start(context: Context) {
            val intent = Intent(context, TtsPrebuildService::class.java)
            context.startForegroundService(intent)
        }
    }
}
