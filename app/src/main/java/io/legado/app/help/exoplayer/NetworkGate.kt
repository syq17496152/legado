package io.legado.app.help.exoplayer

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import io.legado.app.constant.AppLog
import splitties.init.appCtx

/**
 * 网络门禁（计量网络判定）。
 *
 * 设计依据：docs/specs/video-buffer-prefetch-v2/design.md §1.4「前置总闸」
 *
 * 约定：
 * - 判定失败时**按计量网络处理**（宁可少预取，也不要在用户流量上偷跑）；
 * - 预取器只读上层注入的快照，不直接持有系统服务。
 */
object NetworkGate {

    /**
     * 纯函数：根据能力快照判定是否计量网络。
     *
     * @param unmeteredTransport true=WiFi/以太网等不限量传输
     * @param meteredCapability 系统声明的计量能力；null 表示未知
     */
    fun isMetered(unmeteredTransport: Boolean, meteredCapability: Boolean?): Boolean {
        if (meteredCapability == true) return true
        return !unmeteredTransport
    }

    /** 运行时判定（失败按计量处理） */
    fun isMeteredNow(context: Context = appCtx): Boolean {
        return kotlin.runCatching {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return true
            val network = cm.activeNetwork ?: return true
            val caps = cm.getNetworkCapabilities(network) ?: return true
            val unmetered = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
            val metered = !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
            isMetered(unmeteredTransport = unmetered, meteredCapability = metered)
        }.getOrElse {
            AppLog.putDebug("NetworkGate: detect failed, treat as metered, error=${it.javaClass.simpleName}")
            true
        }
    }
}
