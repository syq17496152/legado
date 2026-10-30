package io.legado.app.help.readaloud

import android.app.Application
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files

/**
 * R17：TTS 混合缓存清扫判据（`HttpReadAloudService.removeCacheFile` 与 App 启动兜底共用）。
 *
 * 红线：预合成保留名单（用户主动生成的资产）任何情况不得被删；正在写入的 `.part` 不得被删；
 * 当前章产物（`keepStems` 命中）不得被删。其余陈旧文件必须被驱逐，否则缓存无限增长。
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class TtsCacheSweepTest {

    private val now = 1_800_000_000_000L
    private val ttl = TtsCacheSweep.TTL_MS

    @Test
    fun staleUnknownFile_isDeleted() {
        assertTrue(
            TtsCacheSweep.shouldDelete(
                name = "abc123.mp3", length = 4096L, lastModified = now - ttl - 1,
                now = now, keepStems = emptySet(), reserved = emptySet()
            )
        )
    }

    @Test
    fun freshUnknownFile_isKept() {
        assertFalse(
            TtsCacheSweep.shouldDelete(
                name = "abc123.mp3", length = 4096L, lastModified = now - 1000,
                now = now, keepStems = emptySet(), reserved = emptySet()
            )
        )
    }

    @Test
    fun keepStem_isKeptRegardlessOfAge() {
        assertFalse(
            TtsCacheSweep.shouldDelete(
                name = "chapterMd5_deadbeef.mp3", length = 4096L, lastModified = now - ttl * 10,
                now = now, keepStems = setOf("chapterMd5"), reserved = emptySet()
            )
        )
    }

    @Test
    fun reservedFile_isNeverDeleted() {
        assertFalse(
            TtsCacheSweep.shouldDelete(
                name = "prebuilt01.mp3", length = 4096L, lastModified = now - ttl * 100,
                now = now, keepStems = emptySet(), reserved = setOf("prebuilt01")
            )
        )
    }

    @Test
    fun silentSound_isDeletedEvenWhenFresh() {
        assertTrue(
            TtsCacheSweep.shouldDelete(
                name = "tiny.mp3", length = TtsCacheSweep.SILENT_SOUND_LENGTH,
                lastModified = now, now = now, keepStems = emptySet(), reserved = emptySet()
            )
        )
    }

    @Test
    fun partialFile_onlyWhenStale() {
        assertTrue(
            TtsCacheSweep.shouldDelete(
                name = "abc.mp3.part", length = 10L, lastModified = now - ttl - 1,
                now = now, keepStems = emptySet(), reserved = emptySet()
            )
        )
        assertFalse(
            TtsCacheSweep.shouldDelete(
                name = "abc.mp3.part", length = 10L, lastModified = now - 5,
                now = now, keepStems = emptySet(), reserved = emptySet()
            )
        )
    }

    @Test
    fun sweep_deletesOnlyEligibleFiles() {
        val dir = Files.createTempDirectory("tts-sweep").toFile()
        try {
            val stale = File(dir, "stale.mp3").apply {
                writeText("x")
                setLastModified(now - ttl - 60_000)
            }
            val fresh = File(dir, "fresh.mp3").apply {
                writeText("x")
                setLastModified(now - 1_000)
            }
            val reserved = File(dir, "prebuild.mp3").apply {
                writeText("x")
                setLastModified(now - ttl * 50)
            }
            val deleted = TtsCacheSweep.sweep(
                cacheDir = dir,
                keepStems = emptySet(),
                reserved = setOf("prebuild"),
                now = now
            )
            assertEquals(1, deleted)
            assertFalse("陈旧未保留文件应被删除", stale.exists())
            assertTrue("新文件应保留", fresh.exists())
            assertTrue("预合成保留名单必须存活", reserved.exists())
        } finally {
            dir.deleteRecursively()
        }
    }
}
