package io.legado.app.ui.book.read.eye

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 眼动模型按需下载（1.5 S3）：完整性校验与来源常量。
 * 无法在 JVM 造出与官方 sha256 完全一致的字节，故正例只验"常量自洽"，负例覆盖全部拒绝路径。
 */
class EyeModelStoreTest {

    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `不存在的文件校验失败`() {
        assertFalse(EyeModelStore.verify(File(temp.root, "missing.task")))
    }

    @Test
    fun `空文件校验失败`() {
        val empty = File(temp.root, "empty.task").apply { createNewFile() }
        assertFalse(EyeModelStore.verify(empty))
    }

    @Test
    fun `内容与官方 sha256 不符一律拒绝`() {
        val bad = File(temp.root, "bad.task").apply { writeBytes(ByteArray(1024) { 7 }) }
        assertFalse(EyeModelStore.verify(bad))
    }

    /** 首源必须是国内可达镜像：官方 storage.googleapis.com 实测设备端 60s 超时（2026-11-05） */
    @Test
    fun `下载源自洽且首选国内镜像`() {
        assertTrue("下载源不能为空", EyeModelStore.SOURCES.isNotEmpty())
        assertEquals(2, EyeModelStore.SOURCES.size)
        assertTrue(EyeModelStore.SOURCES[0].startsWith("https://"))
        assertTrue("首源应为 ModelScope 镜像", EyeModelStore.SOURCES[0].contains("modelscope.cn"))
        EyeModelStore.SOURCES.forEach {
            assertTrue("源必须指向 task 文件：$it", it.endsWith("face_landmarker.task"))
        }
        assertEquals(64, EyeModelStore.SHA256.length)
        assertTrue(EyeModelStore.SHA256.all { it in "0123456789abcdef" })
    }

    /** 校验通过的判定必须"大小 > 0 且 sha 相符"：只有大小没有 hash 等于放行任意下载内容 */
    @Test
    fun `只有大小没有哈希不能算通过`() {
        val bigButWrong = File(temp.root, "wrong.task").apply { writeBytes(ByteArray(3758596) { 0 }) }
        assertFalse(EyeModelStore.verify(bigButWrong))
    }
}
