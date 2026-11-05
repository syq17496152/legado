package io.legado.app.help.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 资产包（4.10）：配额/校验/原子替换/卸载 + 公共资源与需备份的分类口径。
 */
class AssetInstallerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun installer(available: Long = Long.MAX_VALUE): AssetInstaller =
        AssetInstaller(tmp.root, availableBytes = { available })

    private fun manifest(id: String, sha: String = "", bytes: Long = 0) = AssetManifest(
        id = id, title = "测试资产 $id", kind = AssetManifest.KIND_MODEL,
        distribution = AssetManifest.DIST_DOWNLOAD, sha256 = sha, sizeBytes = bytes
    )

    @Test
    fun `安装后可从清单读到`() {
        val source = File(tmp.root, "src.bin").apply { writeText("payload") }
        val result = installer().install(source, manifest("voice_a"))
        assertTrue(result.message, result.ok)
        val installed = installer().installed()
        assertEquals(1, installed.size)
        assertEquals("voice_a", installed.first().id)
        assertEquals("download", installed.first().distribution)
        assertTrue(installed.first().installedAt > 0)
        // 内容真的落盘
        assertTrue(File(tmp.root, "voice_a/src.bin").readText().contains("payload"))
    }

    @Test
    fun `sha 不匹配拒绝安装`() {
        val source = File(tmp.root, "src.bin").apply { writeText("payload") }
        val result = installer().install(source, manifest("voice_a", sha = "deadbeef"))
        assertFalse(result.ok)
        assertTrue(result.message.contains("sha256"))
        assertTrue(installer().installed().isEmpty())
    }

    @Test
    fun `空间不足拒绝且不留半装`() {
        val source = File(tmp.root, "src.bin").apply { writeText("x".repeat(500)) }
        val result = installer(available = 100).install(source, manifest("voice_a"))
        assertFalse(result.ok)
        assertTrue(result.message.contains("空间不足"))
        assertTrue(installer().installed().isEmpty())
        // staging 里不得残留 part（半装会占空间且可能被后续误用）
        val staging = File(tmp.root, ".staging")
        assertTrue(staging.listFiles()?.none { it.name.endsWith(".part") } ?: true)
    }

    @Test
    fun `重复安装同 id 走替换且内容更新`() {
        val v1 = File(tmp.root, "v1.bin").apply { writeText("v1") }
        val v2 = File(tmp.root, "v2.bin").apply { writeText("v2") }
        assertTrue(installer().install(v1, manifest("voice_a")).ok)
        assertEquals("v1", File(tmp.root, "voice_a/v1.bin").readText())
        assertTrue(installer().install(v2, manifest("voice_a")).ok)
        assertEquals("v2", File(tmp.root, "voice_a/v2.bin").readText())
        // 旧文件不应残留（替换是整目录）
        assertFalse(File(tmp.root, "voice_a/v1.bin").exists())
        // 旧目录备份也应清掉
        assertFalse(File(tmp.root, "voice_a.bak").exists())
    }

    @Test
    fun `卸载后清单不再列出`() {
        val source = File(tmp.root, "src.bin").apply { writeText("payload") }
        installer().install(source, manifest("voice_a"))
        assertTrue(installer().uninstall("voice_a"))
        assertTrue(installer().installed().isEmpty())
        assertNull(installer().manifestOf("voice_a"))
    }

    @Test
    fun `zip 安装防目录穿越`() {
        val zip = File(tmp.root, "pack.zip")
        ZipOutputStream(zip.outputStream()).use { out ->
            out.putNextEntry(ZipEntry("ok.txt"))
            out.write("safe".toByteArray())
            out.closeEntry()
            out.putNextEntry(ZipEntry("../evil.txt"))
            out.write("evil".toByteArray())
            out.closeEntry()
        }
        val result = installer().install(zip, manifest("pack_a"))
        assertTrue(result.message, result.ok)
        assertTrue(File(tmp.root, "pack_a/ok.txt").isFile)
        // 穿越条目必须被丢弃（不能写到资产根目录之外）
        assertFalse(File(tmp.root, "evil.txt").exists())
    }

    @Test
    fun `无清单目录不进入已安装列表`() {
        File(tmp.root, "legacy").mkdirs()
        File(tmp.root, "legacy/data.bin").writeText("legacy")
        assertTrue(installer().installed().isEmpty())
    }

    @Test
    fun `分类口径区分可重下与需备份`() {
        assertTrue(AssetCatalog.downloadable.isNotEmpty())
        assertTrue(AssetCatalog.mustImport.isNotEmpty())
        // 音色参考音频属于用户私有：删了找不回 ⇒ 必须备份
        assertEquals(AssetManifest.DIST_IMPORT, AssetCatalog.entryOf("voices-imported")?.distribution)
        // 语音包可重下
        assertEquals(AssetManifest.DIST_DOWNLOAD, AssetCatalog.entryOf("tts-fast")?.distribution)
        // 2026-11-05：新增 hq 高质量档 + 眼动模型移出 APK（两者都是"官方源可重下"的公共资源）
        assertEquals(AssetManifest.DIST_DOWNLOAD, AssetCatalog.entryOf("tts-hq")?.distribution)
        assertEquals(AssetManifest.DIST_DOWNLOAD, AssetCatalog.entryOf("eye")?.distribution)
        assertEquals("可重下", AssetCatalog.label(AssetManifest.DIST_DOWNLOAD))
        assertEquals("需备份", AssetCatalog.label(AssetManifest.DIST_IMPORT))
        assertEquals("内置", AssetCatalog.label(AssetManifest.DIST_BUILTIN))
        // 两类 id 不重复（资源页按 id 分组显示，重复会串组）
        val ids = AssetCatalog.all().map { it.id }
        assertEquals(ids.size, ids.distinct().size)
    }
}
