package io.legado.app.help.exoplayer

import android.app.ActivityManager
import android.content.Context
import android.os.StatFs
import io.legado.app.constant.AppLog
import splitties.init.appCtx

/**
 * R3 设备档位检测器（视频预缓冲激进策略基础组件）
 *
 * 核心策略：
 * - HIGH（默认）：内存≥6GB 且 CPU≥8核 且 磁盘≥10GB → 启用激进策略（120s maxBuffer/10个预加载/10MB/1GB缓存）
 * - MID：内存≥4GB 或 CPU≥8核 → 中等策略（90s maxBuffer/7个预加载/5MB/800MB缓存）
 * - 检测失败降级到 HIGH（用户要求默认中高端机参数）
 *
 * 设计决策（AD-08 R3 修订）：
 * - 移除 LOW 档位（用户要求不用考虑低端机，默认中高端机参数）
 * - 检测失败降级到 HIGH（而非 MID），确保默认体验为激进策略
 * - 结果缓存，避免重复检测（设备能力固定，无需运行时动态检测）
 */
object DeviceInfoHelper {

    enum class DeviceTier { HIGH, MID }

    private var cachedTier: DeviceTier? = null

    fun getDeviceTier(): DeviceTier {
        cachedTier?.let { return it }
        val tier = kotlin.runCatching {
            val totalMemMB = getTotalMemoryMB()
            val cpuCores = getCpuCores()
            val freeDiskMB = getFreeDiskMB()
            AppLog.putDebugWithTag(
                "DeviceInfo",
                "device info: totalMem=${totalMemMB}MB, cpuCores=$cpuCores, freeDisk=${freeDiskMB}MB",
                level = AppLog.Level.INFO
            )
            when {
                totalMemMB >= 6144 && cpuCores >= 8 && freeDiskMB >= 10240 -> DeviceTier.HIGH
                totalMemMB >= 4096 || cpuCores >= 8 -> DeviceTier.MID
                else -> DeviceTier.HIGH  // R3: 默认 HIGH（不降级到 LOW）
            }
        }.getOrElse {
            AppLog.putDebugWithTag(
                "DeviceInfo",
                "detection failed, fallback to HIGH",
                it,
                AppLog.Level.WARN
            )
            DeviceTier.HIGH  // R3: 检测失败降级到 HIGH
        }
        cachedTier = tier
        return tier
    }

    private fun getTotalMemoryMB(): Long {
        val memoryInfo = ActivityManager.MemoryInfo()
        (appCtx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager)
            .getMemoryInfo(memoryInfo)
        return memoryInfo.totalMem / (1024 * 1024)
    }

    private fun getCpuCores(): Int = Runtime.getRuntime().availableProcessors()

    private fun getFreeDiskMB(): Long {
        val statFs = StatFs(appCtx.filesDir.absolutePath)
        return statFs.availableBlocksLong * statFs.blockSizeLong / (1024 * 1024)
    }
}
