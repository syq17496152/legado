package io.legado.app.help.exoplayer

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 磁盘池容量自适应（AD-31 v1.1：整体上调一档、存储封顶 10%）。
 */
class CacheSizePolicyTest {

    @Test
    fun `自动档六档映射上调一档`() {
        assertEquals(4096, CacheSizePolicy.autoMbByRam(16_000L))
        assertEquals(4096, CacheSizePolicy.autoMbByRam(15_000L))
        assertEquals(3072, CacheSizePolicy.autoMbByRam(12_000L))
        assertEquals(2048, CacheSizePolicy.autoMbByRam(8_000L))
        assertEquals(1024, CacheSizePolicy.autoMbByRam(6_000L))
        assertEquals(512, CacheSizePolicy.autoMbByRam(4_000L))
        assertEquals(256, CacheSizePolicy.autoMbByRam(3_000L))
    }

    @Test
    fun `可用存储按10%封顶且落在区间内`() {
        assertEquals(128, CacheSizePolicy.storageCapMb(1_000L))
        assertEquals(800, CacheSizePolicy.storageCapMb(8_000L))
        assertEquals(4096, CacheSizePolicy.storageCapMb(100_000L))
        assertEquals(CacheSizePolicy.MIN_MB, CacheSizePolicy.storageCapMb(0L))
    }

    @Test
    fun `手动配置优先且不超过存储封顶`() {
        // 配置 3000MB，可用存储只允许 800MB
        assertEquals(800, CacheSizePolicy.resolve(3000, 16_000L, 8_000L))
        // 配置 1MB，下限 50MB
        assertEquals(CacheSizePolicy.MIN_MB, CacheSizePolicy.resolve(1, 16_000L, 100_000L))
        // 自动档：8GB 内存 → 2048MB
        assertEquals(2048, CacheSizePolicy.resolve(0, 8_000L, 100_000L))
    }
}
