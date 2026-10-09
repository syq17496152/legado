package io.legado.app.model.localBook.epubcore.template

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 模板包编解码单测（epub-md-rich-rendering 阶段 4.13 配对）。
 *
 * 覆盖【验证标准】「作者可复制→编辑→预览→导出→异机导入闭环通过」的**导出/导入链**：
 * ①打包再解包得到等价模板与素材（真 ZIP 往返）；②确定性输出（同输入同字节）；
 * ③清单结构校验（版本过高拒绝、模板路径必须为包根、别名重复拒绝、声明走 asset 白名单）；
 * ④包不完整/缺素材/缺清单/缺模板均拒绝；⑤解包条目**越界防御**（`../`、绝对路径）；
 * ⑥文件头与扩展名不符拒绝；⑦高版本包整体拒绝不留半成品。
 */
class ReaderTemplatePackageTest {

    private fun template(id: String = "user.a", name: String = "甲") = EpubReaderTemplate(
        schemaVersion = 2,
        id = id,
        name = name,
        description = "说明",
        firstPageHtml = "<article data-reader-flow=\"body\"></article>",
        otherPageHtml = "<article data-reader-flow=\"body\"></article>",
        css = "body{color:#333}",
        javascript = "",
        type = EpubReaderTemplate.TYPE_PAGED,
        scrollHtml = ""
    )

    private val png = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 1, 2, 3)
    private val resources = listOf(ReaderTemplatePackage.Resource("bg", "assets/bg.png", "image"))

    @Test
    fun `打包再解包得到等价模板与素材`() {
        val bytes = ReaderTemplatePackage.zip(
            template = template(),
            resources = resources,
            assets = mapOf("assets/bg.png" to png)
        ).getOrThrow()

        val pkg = ReaderTemplatePackage.unzip(bytes).getOrThrow()
        assertEquals("user.a", pkg.template.id)
        assertEquals("甲", pkg.template.name)
        assertEquals("body{color:#333}", pkg.template.css)
        assertEquals(1, pkg.manifest.resources.size)
        assertEquals("bg", pkg.manifest.resources.single().alias)
        assertTrue("素材字节须一致", pkg.assets.getValue("assets/bg.png").contentEquals(png))
        assertEquals(ReaderTemplatePackage.FormatVersion, pkg.manifest.formatVersion)
    }

    @Test
    fun `确定性输出同输入同字节`() {
        val first = ReaderTemplatePackage.zip(template(), resources, mapOf("assets/bg.png" to png)).getOrThrow()
        val second = ReaderTemplatePackage.zip(template(), resources, mapOf("assets/bg.png" to png)).getOrThrow()
        assertTrue("打包必须确定性（条目时间归零）", first.contentEquals(second))
    }

    @Test
    fun `无素材的纯内联模板也可打包`() {
        val bytes = ReaderTemplatePackage.zip(template()).getOrThrow()
        val pkg = ReaderTemplatePackage.unzip(bytes).getOrThrow()
        assertTrue(pkg.manifest.resources.isEmpty())
        assertTrue(pkg.assets.isEmpty())
    }

    @Test
    fun `结构非法的模板拒绝打包`() {
        assertTrue(ReaderTemplatePackage.zip(template().copy(name = "")).isFailure)
    }

    @Test
    fun `声明了但不随包即包不完整拒绝`() {
        val result = ReaderTemplatePackage.zip(template(), resources, emptyMap())
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("包不完整"))
    }

    @Test
    fun `素材声明走 asset 白名单`() {
        val bad = listOf(ReaderTemplatePackage.Resource("bg", "other/bg.png", "image"))
        val result = ReaderTemplatePackage.zip(template(), bad, mapOf("other/bg.png" to png))
        assertTrue("包外路径必须被拒", result.isFailure)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("outside-root"))

        val mismatch = listOf(ReaderTemplatePackage.Resource("bg", "assets/bg.exe", "image"))
        assertTrue(ReaderTemplatePackage.zip(template(), mismatch, mapOf("assets/bg.exe" to png)).isFailure)
    }

    @Test
    fun `清单版本过高整体拒绝`() {
        val manifest = ReaderTemplatePackage.buildManifestJson(resources)
            .replace("\"formatVersion\":$", "\"formatVersion\":$") // 占位，避免误改
        val higher = manifest.replace("\"formatVersion\":2", "\"formatVersion\":9")
        val result = ReaderTemplatePackage.parseManifest(higher)
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("高于当前支持版本"))
    }

    @Test
    fun `清单模板路径必须为包根模板文件`() {
        assertTrue(
            ReaderTemplatePackage.parseManifest(
                ReaderTemplatePackage.buildManifestJson(emptyList(), templatePath = "sub/readerTemplate.json")
            ).isFailure
        )
        assertTrue(
            "绝对路径与点段须拒",
            ReaderTemplatePackage.parseManifest(
                ReaderTemplatePackage.buildManifestJson(emptyList(), templatePath = "/etc/readerTemplate.json")
            ).isFailure
        )
        assertTrue(
            ReaderTemplatePackage.buildManifestJson(emptyList()).let {
                ReaderTemplatePackage.parseManifest(it).isSuccess
            }
        )
    }

    @Test
    fun `清单别名重复被拒`() {
        val duplicated = ReaderTemplatePackage.buildManifestJson(
            listOf(
                ReaderTemplatePackage.Resource("bg", "assets/a.png", "image"),
                ReaderTemplatePackage.Resource("bg", "assets/b.png", "image")
            )
        )
        val result = ReaderTemplatePackage.parseManifest(duplicated)
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("别名重复"))
    }

    @Test
    fun `缺清单或缺模板拒绝`() {
        // 只放模板，无 package.json
        val onlyTemplate = zipOf(mapOf(ReaderTemplatePackage.TemplateEntryName to template().toJson()))
        assertTrue(ReaderTemplatePackage.unzip(onlyTemplate).isFailure)

        // 只放清单，无模板
        val onlyManifest = zipOf(
            mapOf(ReaderTemplatePackage.ManifestName to ReaderTemplatePackage.buildManifestJson(emptyList()))
        )
        assertTrue(ReaderTemplatePackage.unzip(onlyManifest).isFailure)
    }

    @Test
    fun `缺少声明素材拒绝`() {
        val bytes = zipOf(
            mapOf(
                ReaderTemplatePackage.TemplateEntryName to template().toJson(),
                ReaderTemplatePackage.ManifestName to ReaderTemplatePackage.buildManifestJson(resources)
            )
        )
        val result = ReaderTemplatePackage.unzip(bytes)
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("缺少声明素材"))
    }

    @Test
    fun `素材内容与扩展名不符拒绝`() {
        // 声明 png 但内容为 JPEG 魔数
        val jpeg = byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 1, 2)
        val bytes = zipOf(
            mapOf(
                ReaderTemplatePackage.TemplateEntryName to template().toJson(),
                ReaderTemplatePackage.ManifestName to ReaderTemplatePackage.buildManifestJson(resources),
                "assets/bg.png" to jpeg
            )
        )
        val result = ReaderTemplatePackage.unzip(bytes)
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("不符"))
    }

    @Test
    fun `解包条目越界名被拒`() {
        val bytes = zipOf(
            mapOf(
                ReaderTemplatePackage.TemplateEntryName to template().toJson(),
                ReaderTemplatePackage.ManifestName to ReaderTemplatePackage.buildManifestJson(emptyList()),
                "../escape.txt" to "x"
            )
        )
        val result = ReaderTemplatePackage.unzip(bytes)
        assertTrue("点段条目名必须被拒", result.isFailure)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("解压失败"))
    }

    @Test
    fun `包内模板走 archive 兼容映射`() {
        val legacy = """
            {"schemaVersion":1,"type":"paged","id":"builtin.x","name":"旧包模板",
             "firstPageHtml":"<div data-reader-flow=\"body\"></div>",
             "otherPageHtml":"<div data-reader-flow=\"body\"></div>","scrollHtml":"","css":"","javascript":""}
        """.trimIndent()
        val bytes = zipOf(
            mapOf(
                ReaderTemplatePackage.TemplateEntryName to legacy,
                ReaderTemplatePackage.ManifestName to ReaderTemplatePackage.buildManifestJson(emptyList())
            )
        )
        val pkg = ReaderTemplatePackage.unzip(bytes).getOrThrow()
        assertEquals("须升级到当前 schema", 2, pkg.template.schemaVersion)
        assertTrue("内置 id 须改名", pkg.template.id.startsWith("user."))
        assertTrue("须带回导入报告", pkg.report.any { it.code == "schema-upgraded" })
    }

    @Test
    fun `损坏 ZIP 优雅失败`() {
        val result = ReaderTemplatePackage.unzip("not a zip".toByteArray())
        assertTrue(result.isFailure)
    }

    /** 直接按条目名写 ZIP（绕过 zip() 的校验，用于构造敌意包）。 */
    private fun zipOf(entries: Map<String, Any>): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(output).use { zip ->
            entries.forEach { (name, content) ->
                zip.putNextEntry(java.util.zip.ZipEntry(name))
                zip.write(if (content is ByteArray) content else content.toString().toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
        return output.toByteArray()
    }
}