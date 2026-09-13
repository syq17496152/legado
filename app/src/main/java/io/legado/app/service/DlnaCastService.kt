package io.legado.app.service

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import androidx.core.app.NotificationCompat
import io.legado.app.R
import io.legado.app.base.BaseService
import io.legado.app.constant.AppConst
import io.legado.app.constant.NotificationId
import io.legado.app.help.dlna.CastNetworkHelper
import io.legado.app.help.dlna.CastServiceCommand
import io.legado.app.help.dlna.DlnaCastManager
import io.legado.app.utils.LogUtils
import io.legado.app.utils.servicePendingIntent
import io.legado.app.utils.startForegroundServiceCompat
import io.legado.app.utils.stopService
import io.legado.app.help.dlna.DlnaConstants

/**
 * add-dlna-cast：投屏会话前台服务（design AD-05 / AD-15、REQ-09）。
 *
 * 存在理由：息屏/切后台后，代理要继续转发、轮询要继续刷新状态，
 * 纯 Activity 内实现会被系统限制。
 *
 * `foregroundServiceType` 用 **mediaPlayback 而不是 dataSync**：
 * Android 15（本项目 targetSdk 36）对 `dataSync` 施加了每 24 小时累计 6 小时的上限，
 * 长投屏会被系统强停；`mediaPlayback` 没有该限制，语义上也是"控制一路媒体播放会话"。
 *
 * 网络监听**自行注册 `NetworkCallback`**（AD-15）：既有
 * `receiver/NetworkChangedListener` 只在 `onAvailable` 触发，**纯断网不会回调**——
 * 恰恰是最需要 teardown 的情况会被漏掉。
 */
class DlnaCastService : BaseService() {

    companion object {
        private const val ACTION_PAUSE_TOGGLE = "dlnaCastTogglePause"
        private const val ACTION_STOP_CAST = "dlnaCastStop"
        private const val ACTION_OPEN_APP = "dlnaCastOpenApp"

        fun start(context: Context) {
            context.startForegroundServiceCompat(Intent(context, DlnaCastService::class.java))
        }

        fun stop(context: Context) {
            context.stopService<DlnaCastService>()
        }
    }

    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    /**
     * 用户从最近任务划掉 App 后投屏继续：通知是回入口，代理与轮询不该被一起杀掉。
     */
    override val stopSelfOnTaskRemoved: Boolean = false

    override fun onCreate() {
        super.onCreate()
        // 单例跨服务重启复位（红队第 5 轮）：object 会残留 registry/token/状态
        DlnaCastManager.resetOnServiceCreate()
        DlnaCastManager.serviceCommand = { command ->
            if (command == CastServiceCommand.STOP_SELF) stopSelf()
        }
        registerNetworkCallback()
        startForegroundNotification()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PAUSE_TOGGLE -> DlnaCastManager.togglePause()
            ACTION_STOP_CAST -> DlnaCastManager.stopByUser()
            ACTION_OPEN_APP -> openApp()
        }
        startForegroundNotification()
        return super.onStartCommand(intent, flags, startId)
    }

    override fun onDestroy() {
        unregisterNetworkCallback()
        DlnaCastManager.serviceCommand = null
        // 兜底：任何路径都要释放代理端口（红队第 5 轮）
        DlnaCastManager.teardown()
        super.onDestroy()
    }

    // ==================== 通知 ====================

    override fun startForegroundNotification() {
        val state = DlnaCastManager.state.value
        val deviceName = state.currentDevice?.displayName
        // 注意：**不能**在非投屏态直接 return —— 服务是被 startForegroundService 拉起的，
        // 系统要求 5 秒内必须 startForeground，否则抛 ForegroundServiceDidNotStartInTime。
        // 因此没有设备时也发一条摘要通知（会话结束由 Manager 调 STOP_SELF 收尾）。
        val title = if (deviceName.isNullOrBlank()) {
            getString(R.string.dlna_cast)
        } else {
            getString(R.string.dlna_casting_to, deviceName)
        }

        val builder = NotificationCompat.Builder(this, AppConst.channelIdCast)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setSmallIcon(R.drawable.ic_web_service_noti)
            // setOngoing(true) 使通知不可划掉 —— 防"通知没了但会话还在"的孤儿会话（AD-05）
            .setOngoing(true)
            .setContentTitle(title)
            // 通知文案只放标题与设备名，不含任何 URL 或 token（REQ-14 脱敏）
            .setContentText(state.title.orEmpty())
            .setContentIntent(servicePendingIntent<DlnaCastService>(ACTION_OPEN_APP))
        builder.addAction(
            if (state.paused) R.drawable.ic_play_24dp else R.drawable.ic_pause_24dp,
            getString(if (state.paused) R.string.dlna_resume else R.string.dlna_pause),
            servicePendingIntent<DlnaCastService>(ACTION_PAUSE_TOGGLE)
        )
        builder.addAction(
            R.drawable.ic_stop_black_24dp,
            getString(R.string.dlna_stop_cast),
            servicePendingIntent<DlnaCastService>(ACTION_STOP_CAST)
        )
        startForeground(NotificationId.DlnaCastService, builder.build())
    }

    private fun openApp() {
        kotlin.runCatching {
            val launch = packageManager.getLaunchIntentForPackage(packageName) ?: return
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(launch)
        }.onFailure {
            LogUtils.d(DlnaConstants.TAG) { "打开 App 失败: ${it.message}" }
        }
    }

    // ==================== 网络监听（AD-15）====================

    private fun registerNetworkCallback() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return // API 23/24 退化靠轮询兜底
        val manager = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onLost(network: Network) {
                onNetworkMaybeLost()
            }

            override fun onUnavailable() {
                onNetworkMaybeLost()
            }
        }
        kotlin.runCatching {
            manager.registerDefaultNetworkCallback(callback)
            networkCallback = callback
        }.onFailure {
            LogUtils.d(DlnaConstants.TAG) { "网络回调注册失败，退化靠轮询兜底: ${it.message}" }
        }
    }

    private fun unregisterNetworkCallback() {
        val callback = networkCallback ?: return
        networkCallback = null
        val manager = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        kotlin.runCatching { manager.unregisterNetworkCallback(callback) }
    }

    /**
     * 掉网回调后**复核**是否还有可用的局域网地址 ——
     * 多网卡设备上丢一个网络不等于彻底没网，宁可多一次判断也不要误杀投屏。
     */
    private fun onNetworkMaybeLost() {
        if (!DlnaCastManager.isCastingActive()) return
        val pick = CastNetworkHelper.pickLanAddress(this)
        if (pick.address == null) {
            LogUtils.d(DlnaConstants.TAG) { "局域网地址已丢失，终止投屏会话" }
            DlnaCastManager.onNetworkLost()
        }
    }
}
