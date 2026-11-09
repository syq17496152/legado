package io.legado.app.help.tts.builtin

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 运行时空态安全（G-01 配对 BuiltinTtsRuntime 改动）：
 * TextToSpeechService 在 `super.onCreate()` 内就会回调语言查询，运行时空态必须返回 NONE 而非抛异常。
 */
class BuiltinTtsRuntimeTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `未初始化时状态查询不抛异常且返回 NONE`() {
        assertFalse(BuiltinTtsRuntime.isInitialized())
        assertEquals(BuiltinEngineState.NONE, BuiltinTtsRuntime.state())
        assertFalse(BuiltinTtsRuntime.isReady())
        assertEquals(BuiltinEngineState.NONE, BuiltinTtsRuntime.stats().state)
    }

    @Test
    fun `未初始化时存储总览为全零`() {
        val storage = BuiltinTtsRuntime.storageBreakdown()
        assertEquals(0L, storage.modelBytes)
        assertEquals(0L, storage.voiceBytes)
        assertEquals(0L, storage.cacheBytes)
        assertEquals(0L, storage.totalBytes)
    }

    /**
     * 流水线预取的让行路径（2026-11-03）：预取失败必须静默返回 false，
     * 不得抛出打断播放循环——runtime 未就绪是最常见的失败态。
     */
    @Test
    fun `未初始化时预取静默失败`() = runBlocking {
        assertFalse(BuiltinTtsRuntime.prefetch("预取测试", "voice|测试", 1f))
    }

    /**
     * 下载完成后补播多音色索引（真机铁证 2026-11-09）：
     * 未初始化时必须静默返回，不能因空态抛异常打断下载完成回调。
     */
    @Test
    fun `未初始化时补播多音色静默返回`() {
        assertFalse(BuiltinTtsRuntime.isInitialized())
        BuiltinTtsRuntime.ensureSpeakersReady("multi")
        BuiltinTtsRuntime.ensureSpeakersReady("")
    }

    /**
     * 3.6：剧本缓存必须落在 filesDir/scripts（可重建缓存，不进备份），
     * 且与用户手改/固定绑定目录 legado_rules 分离 —— 清缓存不能动用户数据。
     */
    @Test
    fun `剧本目录与规则目录分离`() {
        val paths = BuiltinTtsPaths(
            filesRoot = tmp.root,
            cacheRoot = tmp.newFolder("cache"),
            importRoot = tmp.newFolder("import")
        )
        assertEquals(File(tmp.root, "scripts"), paths.scriptsDir)
        assertNotEquals(paths.rulesRoot, paths.scriptsDir)
        // 剧本属于缓存：不得写进缓存目录（cacheDir 会被系统随时清理）
        assertFalse(paths.scriptsDir.absolutePath.contains("cache"))
    }
}
