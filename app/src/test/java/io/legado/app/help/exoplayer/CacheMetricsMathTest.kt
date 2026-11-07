package io.legado.app.help.exoplayer

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * tasks §13.1：缓存命中率口径（`ExoPlayerHelper.cacheDataSourceFactory` 埋点使用）。
 */
class CacheMetricsMathTest {

    @Test
    fun `命中率按缓存与网络字节占比计算`() {
        assertEquals(75, cacheHitRatePercent(cachedBytes = 750L, networkBytes = 250L))
        assertEquals(50, cacheHitRatePercent(cachedBytes = 500L, networkBytes = 500L))
    }

    @Test
    fun `无缓存读取时命中率为零`() {
        assertEquals(0, cacheHitRatePercent(cachedBytes = 0L, networkBytes = 1_000L))
    }

    @Test
    fun `两者皆零时不臆造命中率`() {
        assertEquals(0, cacheHitRatePercent(cachedBytes = 0L, networkBytes = 0L))
    }
}
