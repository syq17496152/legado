package io.legado.app.help.exoplayer

import android.app.ActivityManager
import android.content.Context
import android.os.StatFs
import io.legado.app.constant.AppLog
import io.legado.app.utils.externalCache
import splitties.init.appCtx
import java.io.File

/**
 * 视频磁盘缓存容量自适应（video-buffer-prefetch-v2 §1.18 / AD-16）。
 *
 * 背景：中端机普遍 8G+ 内存、高端 16G+，固定 100MB 缓存会让「回看/预取/连播」频繁淘汰。
 * 方案：`videoCacheSize=0` 时按设备内存分档给出默认容量，并用「可用存储 5%」封顶；
 * 淘汰仍由 `LeastRecentlyUsedCacheEvictor` 负责（写入量与回看窗口另有预算）。
 *
 * 不变式：
 * - 最终容量 ∈ [MIN_MB, MAX_MB]；
 * - 绝不占用超过可用存储的 5%（避免把用户存储写满）。
 */
object CacheSizePolicy {

    /** 0 = 自动（按设备） */
    const val AUTO_MB = 0

    const val MIN_MB = 50
    const val MAX_MB = 4096

    /** 可用存储只使用 5% 作为缓存上限 */
    const val STORAGE_RATIO = 0.05

    /**
     * 按物理内存自动分档（MB）：
     * ≥15GB→2048 / ≥11GB→1536 / ≥7GB→1024 / ≥5.5GB→512 / ≥3.5GB→256 / 其余 128。
     */
    fun autoMbByRam(totalRamMb: Long): Int = when {
        totalRamMb >= 15_000L -> 2048
        totalRamMb >= 11_000L -> 1536
        totalRamMb >= 7_000L -> 1024
        totalRamMb >= 5_500L -> 512
        totalRamMb >= 3_500L -> 256
        else -> 128
    }

    /** 可用存储封顶（5%，最少 128MB，最多 [MAX_MB]） */
    fun storageCapMb(freeStorageMb: Long): Int {
        if (freeStorageMb <= 0L) return MIN_MB
        val cap = (freeStorageMb * STORAGE_RATIO).toInt()
        return cap.coerceIn(128, MAX_MB)
    }

    /** 计算最终缓存容量（MB） */
    fun resolve(configuredMb: Int, totalRamMb: Long, freeStorageMb: Long): Int {
        val desired = if (configuredMb > 0) {
            configuredMb.coerceIn(MIN_MB, MAX_MB)
        } else {
            autoMbByRam(totalRamMb)
        }
        val cap = storageCapMb(freeStorageMb)
        return minOf(desired, cap).coerceAtLeast(MIN_MB)
    }

    /** 运行时读取物理内存（MB） */
    fun totalRamMb(context: Context = appCtx): Long = runCatching {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val info = ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        info.totalMem / (1024L * 1024L)
    }.getOrDefault(0L)

    /** 运行时读取指定目录可用存储（MB） */
    fun freeStorageMb(dir: File): Long = runCatching {
        StatFs(dir.absolutePath).availableBytes / (1024L * 1024L)
    }.getOrDefault(0L)

    /** 运行时解析（含日志） */
    fun resolveNow(context: Context = appCtx, storageDir: File): Int {
        val ram = totalRamMb(context)
        val free = freeStorageMb(storageDir)
        val resolved = resolve(io.legado.app.model.VideoPlay.videoCacheSize, ram, free)
        AppLog.put(
            "[BufferSpeed] cache_size configured=${io.legado.app.model.VideoPlay.videoCacheSize} " +
                "resolved=${resolved}MB ram=${ram}MB free=${free}MB"
        )
        return resolved
    }

    /** 当前实际生效容量（MB，UI 展示用；不输出日志） */
    fun effectiveMb(context: Context = appCtx): Int = resolve(
        configuredMb = io.legado.app.model.VideoPlay.videoCacheSize,
        totalRamMb = totalRamMb(context),
        freeStorageMb = freeStorageMb(appCtx.externalCache),
    )
}
