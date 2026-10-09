package io.legado.app.model.localBook.epubcore.template

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * 模板素材库单测（epub-md-rich-rendering 阶段 4.3 配对）。
 *
 * 覆盖【验证标准】「素材增删改查正确；损坏不变成空库」：
 * ①原子写后立即可读且无 staging 残留；②越权 templateId/alias 被拒（不落盘）；
 * ③超限拒写（不静默截断）；④单条损坏不影响同模板其它素材；⑤删单条/删模板/清空；
 * ⑥容量统计。
 */
class ReaderAssetStoreTest {

    private lateinit var root: File

    @Before
    fun setUp() {
        root = Files.createTempDirectory("reader-asset-test").toFile()
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    private fun store(maxBytes: Long = ReaderAssetStore.DefaultMaxBytesPerTemplate) = ReaderAssetStore(root, maxBytes)

    @Test
    fun `写入后可读且无 staging 残留`() {
        val assets = store()
        val bytes = byteArrayOf(1, 2, 3, 4)
        assertTrue(assets.writeAsset("user.night", "bg", "webp", bytes))
        assertArrayEquals(bytes, assets.readAsset("user.night", "bg", "webp"))
        val leftovers = root.walkTopDown().filter { it.name.startsWith(".") }.toList()
        assertTrue("原子写不得残留 staging：$leftovers", leftovers.isEmpty())
        assertEquals(listOf("bg.webp"), assets.listAliases("user.night"))
    }

    @Test
    fun `覆盖写不残留旧内容且无临时文件`() {
        val assets = store()
        assertTrue(assets.writeAsset("t", "bg", "png", byteArrayOf(1, 2, 3)))
        assertTrue(assets.writeAsset("t", "bg", "png", byteArrayOf(9)))
        assertArrayEquals(byteArrayOf(9), assets.readAsset("t", "bg", "png"))
        assertEquals(1L, assets.templateBytes("t"))
    }

    @Test
    fun `越权标识与别名被拒且不落盘`() {
        val assets = store()
        listOf("../escape", "a/b", "..", ".", "", "a\\b", "a%2Fb").forEach { bad ->
            assertFalse("非法模板 id 必须拒写：$bad", assets.writeAsset(bad, "bg", "png", byteArrayOf(1)))
            assertFalse("非法别名必须拒写：$bad", assets.writeAsset("t", bad, "png", byteArrayOf(1)))
            assertFalse("非法扩展名必须拒写：$bad", assets.writeAsset("t", "bg", bad, byteArrayOf(1)))
        }
        assertEquals("不得落下任何文件", 0, assets.clear())
    }

    @Test
    fun `超限拒写且不产生半成品`() {
        val assets = store(maxBytes = 4)
        assertFalse(assets.writeAsset("t", "big", "png", ByteArray(5)))
        assertNull(assets.readAsset("t", "big", "png"))
        assertTrue("恰好等于上限应写入", assets.writeAsset("t", "ok", "png", ByteArray(4)))
    }

    @Test
    fun `单条损坏不影响同模板其它素材`() {
        val assets = store()
        assertTrue(assets.writeAsset("t", "good", "png", byteArrayOf(7, 7)))
        assertTrue(assets.writeAsset("t", "broken", "png", byteArrayOf(8)))
        // 模拟外部损坏：把其中一条替换成目录（读取必然失败）
        val brokenDir = File(root, "t/broken.png")
        brokenDir.delete()
        brokenDir.mkdirs()
        assertNull("损坏条目返回 null", assets.readAsset("t", "broken", "png"))
        assertArrayEquals(
            "同模板其它素材不得受影响（损坏不空库）",
            byteArrayOf(7, 7),
            assets.readAsset("t", "good", "png")
        )
    }

    @Test
    fun `删除单条与删除模板`() {
        val assets = store()
        assertTrue(assets.writeAsset("t", "a", "png", byteArrayOf(1, 2, 3)))
        assertTrue(assets.writeAsset("t", "b", "png", byteArrayOf(4)))
        assertEquals(3L, assets.deleteAsset("t", "a", "png"))
        assertNull(assets.readAsset("t", "a", "png"))
        assertArrayEquals(byteArrayOf(4), assets.readAsset("t", "b", "png"))
        assertEquals(1, assets.deleteTemplate("t"))
        assertTrue(assets.listAliases("t").isEmpty())
    }

    @Test
    fun `清空返回模板目录数`() {
        val assets = store()
        assertTrue(assets.writeAsset("t1", "a", "png", byteArrayOf(1)))
        assertTrue(assets.writeAsset("t2", "b", "png", byteArrayOf(2)))
        assertEquals(2, assets.clear())
        assertTrue(assets.listAliases("t1").isEmpty())
    }

    @Test
    fun `容量统计与缺失目录容错`() {
        val assets = store()
        assertTrue(assets.writeAsset("t", "a", "png", ByteArray(10)))
        assertTrue(assets.writeAsset("t", "b", "png", ByteArray(5)))
        assertEquals(15L, assets.templateBytes("t"))
        assertEquals(0L, assets.templateBytes("missing"))
        assertNull(assets.readAsset("missing", "a", "png"))
        assertEquals(0L, assets.deleteAsset("missing", "a", "png"))
        assertEquals(0, assets.deleteTemplate("missing"))
    }

    @Test
    fun `缺失根目录时清空返回零`() {
        val missing = File(root, "not-created")
        val assets = ReaderAssetStore(missing)
        assertEquals(0, assets.clear())
        assertEquals(0L, assets.templateBytes("t"))
    }
}