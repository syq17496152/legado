package io.legado.app.model.localBook.epubcore.template

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `asset://alias` 安全策略单测（epub-md-rich-rendering 阶段 4.4 配对；AD-23）。
 *
 * 覆盖【验证标准】「越权路径用例被拒；合法别名可用」——这是模板系统最大越权面
 * （读取宿主任意文件 / 网络请求），故对每类绕过手法逐条断言。
 */
class ReaderAssetPathPolicyTest {

    private val declarations = listOf(
        ReaderAssetPathPolicy.ResourceDeclaration("bg", "assets/bg.webp", "image"),
        ReaderAssetPathPolicy.ResourceDeclaration("cover", "images/cover.png", "image"),
        ReaderAssetPathPolicy.ResourceDeclaration("font", "assets/fonts/f.ttf", "font"),
        ReaderAssetPathPolicy.ResourceDeclaration("data", "assets/data.json", "json")
    )

    private fun resolve(url: String) = ReaderAssetPathPolicy.resolve(url, declarations)

    @Test
    fun `合法别名可解析且返回声明`() {
        val result = resolve("asset://bg")
        assertTrue(result.isSuccess)
        assertEquals("assets/bg.webp", result.getOrThrow().declaration.path)
        assertTrue("Lottie 的 images/ 根须兼容", resolve("asset://cover").isSuccess)
        assertTrue(resolve("asset://font").isSuccess)
    }

    @Test
    fun `大小写 scheme 容错`() {
        assertTrue(resolve("ASSET://bg").isSuccess)
        assertTrue(resolve("Asset://bg").isSuccess)
    }

    @Test
    fun `未声明的别名被拒`() {
        assertEquals(
            "unknown-alias",
            reason(resolve("asset://missing"))
        )
    }

    @Test
    fun `别名携带路径或编码一律被拒`() {
        // 这是最关键的绕过手法：URL 自带路径 ⇒ 从根上拒绝
        listOf(
            "asset://bg/../../databases/legado.db",
            "asset://../databases/legado.db",
            "asset://bg%2F..%2Fdb",
            "asset://bg?x=1",
            "asset://bg#frag",
            "asset://bg\\..\\db"
        ).forEach { url ->
            assertTrue("$url 必须被拒", resolve(url).isFailure)
        }
    }

    @Test
    fun `非 asset scheme 被拒`() {
        listOf(
            "file:///data/data/x/files/a.webp",
            "content://media/external/images/1",
            "https://example.com/a.webp",
            "http://example.com/a.webp",
            "//example.com/a.webp",
            "/data/local/a.webp",
            "assets/bg.webp"
        ).forEach { url ->
            assertTrue("$url 必须被拒", resolve(url).isFailure)
        }
    }

    @Test
    fun `空引用与空别名被拒`() {
        assertTrue(resolve("").isFailure)
        assertTrue(resolve("asset://").isFailure)
    }

    @Test
    fun `声明路径的越权形态被拒`() {
        listOf(
            "assets/../../databases/legado.db",
            "assets/./a.webp",
            "/assets/a.webp",
            "assets/a.webp\\x",
            "assets/a%2Fb.webp",
            "file:///assets/a.webp",
            "content://assets/a.webp",
            "http://x/assets/a.webp",
            "other/a.webp"
        ).forEach { path ->
            val rejection = ReaderAssetPathPolicy.validateDeclaration(
                ReaderAssetPathPolicy.ResourceDeclaration("a", path, "image")
            )
            assertTrue("$path 必须被拒（实际：${rejection?.code}）", rejection != null)
        }
    }

    @Test
    fun `扩展名与声明类型必须一致`() {
        assertNull(
            ReaderAssetPathPolicy.validateDeclaration(
                ReaderAssetPathPolicy.ResourceDeclaration("a", "assets/a.png", "image")
            )
        )
        assertEquals(
            "ext-mismatch",
            ReaderAssetPathPolicy.validateDeclaration(
                ReaderAssetPathPolicy.ResourceDeclaration("a", "assets/a.exe", "image")
            )?.code
        )
        assertEquals(
            "ext-mismatch",
            ReaderAssetPathPolicy.validateDeclaration(
                ReaderAssetPathPolicy.ResourceDeclaration("a", "assets/a.ttf", "image")
            )?.code
        )
        assertEquals(
            "unknown-type",
            ReaderAssetPathPolicy.validateDeclaration(
                ReaderAssetPathPolicy.ResourceDeclaration("a", "assets/a.bin", "unknown")
            )?.code
        )
    }

    @Test
    fun `别名不得含路径分隔符`() {
        assertEquals(
            "alias-path",
            ReaderAssetPathPolicy.validateDeclaration(
                ReaderAssetPathPolicy.ResourceDeclaration("a/b", "assets/a.png", "image")
            )?.code
        )
        assertEquals(
            "blank-alias",
            ReaderAssetPathPolicy.validateDeclaration(
                ReaderAssetPathPolicy.ResourceDeclaration("", "assets/a.png", "image")
            )?.code
        )
    }

    @Test
    fun `文件头嗅探拦截改后缀绕过`() {
        val pngHeader = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)
        assertTrue("真 png 声明为 png 通过", ReaderAssetPathPolicy.matchesFileHeader("assets/a.png", pngHeader))
        assertFalse(
            "png 头伪装成 jpg 被拦截",
            ReaderAssetPathPolicy.matchesFileHeader("assets/a.jpg", pngHeader)
        )
        assertTrue(
            "无法识别的类型不误杀（交给渲染层按 MIME 处理）",
            ReaderAssetPathPolicy.matchesFileHeader("assets/a.svg", "<svg".toByteArray())
        )
        val jpegHeader = byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte())
        assertTrue(ReaderAssetPathPolicy.matchesFileHeader("assets/a.jpeg", jpegHeader))
    }

    @Test
    fun `单文件与总量限额生效`() {
        assertNull(
            ReaderAssetPathPolicy.validatePackageSize(declarations) { 1024L }
        )
        assertEquals(
            "单文件优先判定",
            "asset-too-large",
            ReaderAssetPathPolicy.validatePackageSize(
                listOf(declarations.first())
            ) { ReaderAssetPathPolicy.MaxAssetBytes + 1 }?.code
        )
        // 总量判据仅在「单文件都合规」时才可能触发：需足够的声明数使总和越过 MaxPackageBytes。
        val count = (ReaderAssetPathPolicy.MaxPackageBytes / ReaderAssetPathPolicy.MaxAssetBytes).toInt() + 1
        val many = (1..count).map {
            ReaderAssetPathPolicy.ResourceDeclaration("a$it", "assets/a$it.png", "image")
        }
        assertEquals(
            "总量超限",
            "package-too-large",
            ReaderAssetPathPolicy.validatePackageSize(many) { ReaderAssetPathPolicy.MaxAssetBytes }?.code
        )
        assertNull(
            "刚好等于总量上限应放行",
            ReaderAssetPathPolicy.validatePackageSize(many.dropLast(1)) { ReaderAssetPathPolicy.MaxAssetBytes }
        )
    }

    @Test
    fun `允许根与限额常量稳定`() {
        assertTrue("assets/" in ReaderAssetPathPolicy.AllowedRoots)
        assertTrue("images/ 为 Lottie 兼容根", "images/" in ReaderAssetPathPolicy.AllowedRoots)
        assertEquals("asset", ReaderAssetPathPolicy.Scheme)
        assertTrue(ReaderAssetPathPolicy.MaxAssetBytes < ReaderAssetPathPolicy.MaxPackageBytes)
    }

    /** 取失败原因的前缀码。 */
    private fun reason(result: Result<ReaderAssetPathPolicy.Resolved>): String {
        val message = result.exceptionOrNull()?.message.orEmpty()
        return message.substringBefore(':')
    }
}