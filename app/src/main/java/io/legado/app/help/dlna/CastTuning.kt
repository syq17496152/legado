package io.legado.app.help.dlna

/**
 * dlna-cast-cache-unify AD-04：**投屏档位解析 + 按设备内存推荐档**（纯逻辑，可 JVM 单测）。
 *
 * 存在理由：性能参数（内存缓存 / 预取窗口 / 预取并发）此前写死保守值，12G/16G 机型
 * 也只能跑最低档；档位列表又硬编码在设置面板里，改档要动 UI（AD-08 已单源化）。
 *
 * 铁律：
 *  1. **纯函数**：不依赖任何 Android API —— 设备总内存与共享缓存容量由调用方注入，
 *     因此本文件可直接进 JVM 单测（AD-04 要求）
 *  2. **档位只从 [DlnaConstants] 取**：本文件不出现任何档位字面量列表（AD-08）
 *  3. **用户选择优先**：自定义模式下推荐档一律不生效（只用于面板展示）
 *  4. **容量反推窗口**：`窗口 × MAX_SEGMENT_BYTES` 不得超过共享缓存容量，超限即按容量收敛
 *     —— 否则预取会"边写边被 LRU 淘汰"，白耗流量且不提升流畅度
 */

/** 一组生效档位（内存缓存 MB / 预取窗口片数 / 预取并发） */
data class CastTuning(
    val cacheMb: Int,
    val prefetchWindow: Int,
    val prefetchConcurrency: Int
)

object CastTuner {

    /** 兜底档（设备内存不可知时使用，等于最小档） */
    val FALLBACK = CastTuning(
        DlnaConstants.DEFAULT_CACHE_MB,
        DlnaConstants.DEFAULT_PREFETCH_WINDOW,
        DlnaConstants.PREFETCH_CONCURRENCY_TIERS.first()
    )

    /**
     * 按设备总内存给推荐档。
     *
     * 区间（按整数 GB 截断后判定）：`≥12GB → 512/15/6`、`8–11GB → 256/10/4`、`≤7GB → 兜底(128/5/3)`。
     *
     * @param totalMemoryMb 设备总内存（MB）；≤0 表示不可知 → [FALLBACK]
     */
    fun recommend(totalMemoryMb: Long): CastTuning = when {
        totalMemoryMb <= 0L -> FALLBACK
        totalMemoryMb >= DlnaConstants.RAM_TIER_HIGH_MB ->
            CastTuning(512, 15, 6)

        totalMemoryMb >= DlnaConstants.RAM_TIER_MID_MB ->
            CastTuning(256, 10, 4)

        else -> FALLBACK
    }

    /**
     * 把任意数值归并到最近合法档（**向上取**：旧值 32→64、3→5、2→3），
     * 超过最大档取最大档。
     */
    fun nearestTier(value: Int, tiers: List<Int>): Int {
        val sorted = tiers.sorted()
        return sorted.firstOrNull { it >= value } ?: sorted.last()
    }

    /** 共享缓存容量能支撑的窗口上限（片）；容量未知（≤0）返回 0 表示"不限制" */
    fun maxWindowByCapacity(sharedCapacityBytes: Long): Int {
        if (sharedCapacityBytes <= 0L) return 0
        return (sharedCapacityBytes / DlnaConstants.MAX_SEGMENT_BYTES).toInt()
    }

    /**
     * 按共享缓存容量收敛窗口：返回 **≤ wanted 且 ≤ 容量上限** 的最大合法档。
     *
     * 容量极小时（连最小档都放不下）直接落到容量上限（至少 1 片）——
     * 宁可窗口小，也不做"写完即被淘汰"的无用预取。
     */
    fun clampWindow(wanted: Int, sharedCapacityBytes: Long): Int {
        val cap = maxWindowByCapacity(sharedCapacityBytes)
        if (cap <= 0) return wanted
        val tiers = DlnaConstants.PREFETCH_WINDOW_TIERS
        return tiers.filter { it <= wanted && it <= cap }.maxOrNull()
            ?: cap.coerceAtLeast(1)
    }

    /** 窗口是否因容量不足而被收敛（供面板提示用） */
    fun windowClamped(wanted: Int, sharedCapacityBytes: Long): Boolean =
        clampWindow(wanted, sharedCapacityBytes) != wanted

    /**
     * 解析**生效档**。
     *
     * @param mode 档位模式（[DlnaConstants.TUNING_MODE_CUSTOM] 为自定义；其余一律走推荐档）
     * @param manual 自定义模式下的用户值（会自动归并到合法档）
     * @param totalMemoryMb 设备总内存（MB）
     * @param sharedCapacityBytes 共享缓存容量（字节）；≤0 表示不限制/未知
     */
    fun resolve(
        mode: String?,
        manual: CastTuning,
        totalMemoryMb: Long,
        sharedCapacityBytes: Long
    ): CastTuning {
        val base = if (mode == DlnaConstants.TUNING_MODE_CUSTOM) {
            CastTuning(
                cacheMb = nearestTier(manual.cacheMb, DlnaConstants.CACHE_MB_TIERS),
                prefetchWindow = nearestTier(manual.prefetchWindow, DlnaConstants.PREFETCH_WINDOW_TIERS),
                prefetchConcurrency = nearestTier(
                    manual.prefetchConcurrency,
                    DlnaConstants.PREFETCH_CONCURRENCY_TIERS
                )
            )
        } else {
            recommend(totalMemoryMb)
        }
        return base.copy(prefetchWindow = clampWindow(base.prefetchWindow, sharedCapacityBytes))
    }
}