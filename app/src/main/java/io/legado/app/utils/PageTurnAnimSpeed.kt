package io.legado.app.utils

/**
 * B1 · R5「翻页动画速度四档」的单源映射（纯函数，便于 JVM 单测）。
 *
 * 背景：`ReadView.defaultAnimationSpeed` 原为硬编码 `300`，用户无法调节动画快慢；
 * 本对象把「档位 → 毫秒」固化为一处，配置侧只存**档位**（`PreferKey.pageTurnAnimSpeed`），
 * 避免同一语义在多处写死毫秒值（供 B3/B4 复用）。
 *
 * 语义：
 * - 4 档：慢 450ms / **标准 300ms（默认）** / 快 180ms / 极快 100ms；
 * - 非法档位（越界 / 缺省）一律回落**标准档 300ms**（保证「新装默认 300ms」）。
 */
object PageTurnAnimSpeed {

    const val SLOW = 0
    const val STANDARD = 1
    const val FAST = 2
    const val FASTEST = 3

    /** 默认档位 = 标准（300ms）。 */
    const val DEFAULT_TIER = STANDARD

    private val TIERS_MS = intArrayOf(450, 300, 180, 100)

    /** 标准档毫秒值（对外常量，便于断言与 UI 文案复用）。 */
    val STANDARD_MS: Int get() = TIERS_MS[STANDARD]

    /** 档位总数（UI 做档位选择器时复用）。 */
    val tierCount: Int get() = TIERS_MS.size

    /** 档位 → 毫秒；越界/非法一律回落默认档。 */
    fun msOf(tier: Int): Int = TIERS_MS.getOrElse(tier) { TIERS_MS[DEFAULT_TIER] }
}