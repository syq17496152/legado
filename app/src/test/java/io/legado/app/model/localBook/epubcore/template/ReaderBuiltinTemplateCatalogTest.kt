package io.legado.app.model.localBook.epubcore.template

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 内置模板资产校验单测（epub-md-rich-rendering 阶段 4.12 配对）。
 *
 * 直接把 `src/main/assets/reader/` 下的**真实资产**读进来逐项校验——这是"内置模板"唯一
 * 能在 JVM 侧拿到强证据的手段（真机只验证观感与交互）。
 *
 * 覆盖：①目录与文件一一对应且 id 一致；②每套通过结构校验（走与用户导入**同一条**路径）；
 * ③**作者契约**在场（`data-reader-flow="body"` 必需、字段槽位合法、滚动模板必须有
 * `data-reader-scroll-viewport`）；④**合规红线**：零外部网络依赖、零外部字体/图片文件、
 * 无第三方 IP 关键词、`sources.json` 在场；⑤日/夜双主题变量在场（可读性底线）；
 * ⑥首批数量与类型覆盖（paged + scroll 两条路径都被覆盖）。
 */
class ReaderBuiltinTemplateCatalogTest {

    private val assetsRoot = File("src/main/assets")

    /** 注入文件读取器：把 `assets/` 相对路径映射到仓库内真实文件。 */
    private val reader = ReaderBuiltinTemplateCatalog.AssetReader { path ->
        val file = File(assetsRoot, path)
        if (file.isFile) file.readText() else null
    }

    private fun load() = ReaderBuiltinTemplateCatalog.load(reader)

    @Test
    fun `目录与资产可加载且无错误`() {
        val result = load()
        assertEquals("内置模板加载不得有错误：${result.errors}", emptyList<String>(), result.errors)
        assertTrue("首批至少应交付 2 套（MVP）", result.templates.size >= 2)
    }

    @Test
    fun `目录清单 id 与文件 id 一一对应`() {
        val ids = ReaderBuiltinTemplateCatalog.catalogIds(reader).getOrThrow()
        val loaded = load().templates
        assertEquals("目录声明数量与加载数量一致", ids.size, loaded.size)
        ids.forEach { id -> assertTrue("目录 id $id 必须加载成功", loaded.any { it.id == id }) }
        assertEquals("id 不得重复", ids.size, ids.toSet().size)
    }

    @Test
    fun `每套模板通过结构校验`() {
        load().templates.forEach { builtin ->
            val errors = builtin.template.validate()
            assertEquals("${builtin.id} 结构必须合法：$errors", emptyList<String>(), errors)
            assertTrue("${builtin.id} 名称不得为空", builtin.template.name.isNotBlank())
        }
    }

    @Test
    fun `作者契约在场`() {
        load().templates.forEach { builtin ->
            val source = builtin.template.resourceSource()
            assertTrue("${builtin.id} 必须声明正文槽位 data-reader-flow=body", source.contains("data-reader-flow=\"body\""))
            assertTrue("${builtin.id} 必须使用合法字段槽位", source.contains("data-reader-field=\""))
            val fields = FIELD.findAll(source).map { it.groupValues[1] }.toSet()
            assertTrue("${builtin.id} 字段槽位不得越界：$fields", ALLOWED_FIELDS.containsAll(fields))
            if (builtin.isScroll) {
                assertTrue(
                    "${builtin.id} 滚动模板必须声明唯一天地画框",
                    source.contains("data-reader-scroll-viewport")
                )
                assertEquals(
                    "${builtin.id} 滚动画框必须唯一",
                    1,
                    Regex("data-reader-scroll-viewport").findAll(source).count()
                )
            } else {
                assertFalse("${builtin.id} 分页模板不应出现滚动画框", source.contains("data-reader-scroll-viewport"))
            }
        }
    }

    @Test
    fun `合规红线 零外部依赖`() {
        load().templates.forEach { builtin ->
            val source = builtin.template.resourceSource()
            assertFalse("${builtin.id} 不得引用网络资源", source.contains("http://") || source.contains("https://"))
            assertFalse("${builtin.id} 不得使用 @import", source.contains("@import"))
            assertFalse("${builtin.id} 不得有 fetch/XHR", source.contains("fetch(") || source.contains("XMLHttpRequest"))
            assertFalse("${builtin.id} 不得内联 data: 素材（本批为纯 CSS 零素材）", source.contains("data:"))
            assertEquals("${builtin.id} 不应含 javascript（纯 CSS 模板）", "", builtin.template.javascript)
        }
    }

    @Test
    fun `合规红线 无第三方 IP 关键词且来源声明在场`() {
        load().templates.forEach { builtin ->
            val lower = builtin.template.resourceSource().lowercase()
            FORBIDDEN_IP_KEYWORDS.forEach { keyword ->
                assertFalse("${builtin.id} 不得出现第三方 IP 素材关键词：$keyword", lower.contains(keyword))
            }
            assertTrue("${builtin.id} 必须附 sources.json", builtin.sources.isNotBlank())
            assertTrue("${builtin.id} 来源声明须含许可字段", builtin.sources.contains("license"))
            assertTrue("${builtin.id} 来源声明须声明零外部素材", builtin.sources.contains("\"assets\": []"))
        }
    }

    @Test
    fun `日与夜双主题均被定义`() {
        load().templates.forEach { builtin ->
            val css = builtin.template.css
            assertTrue("${builtin.id} 必须有日间基线（:root 变量）", css.contains(":root"))
            assertTrue(
                "${builtin.id} 必须定义夜间主题（[data-reader-theme=\"night\"]）",
                css.contains("[data-reader-theme=\"night\"]")
            )
        }
    }

    @Test
    fun `正文安全区与测量无关的装饰约束`() {
        load().templates.forEach { builtin ->
            val css = builtin.template.css
            // 装饰必须脱离正文测量（蓝图硬约束：装饰区 ∩ 正文安全区 = ∅）。
            // 判据按类型分流，避免拿分页口径套滚动模板：
            // - 分页：装饰用绝对定位/伪元素（不参与正文流）
            // - 滚动：页眉/页脚为固定尺寸外壳（flex:0 0 auto 或 fixed/absolute），正文独占滚动画框
            val decorationsDetached = if (builtin.isScroll) {
                (css.contains("flex:0 0 auto") || css.contains("position:fixed") || css.contains("position:absolute")) &&
                    css.contains("overflow-y:auto")
            } else {
                css.contains("position:absolute") || css.contains("::after") || css.contains("::before")
            }
            assertTrue(
                "${builtin.id} 装饰须脱离正文流（分页用绝对定位/伪元素；滚动用固定页眉页脚外壳）",
                decorationsDetached
            )
            // 装饰带高度预算（蓝图：页眉/页脚各 ≤15% 视口高）：除满高容器（100vh）外，
            // 不得出现 >15vh 的装饰块——插画/装饰带挤占正文正是 archive 观感最大的失分点。
            VH_LENGTH.findAll(css).forEach { match ->
                val value = match.groupValues[1].toFloat()
                assertTrue(
                    "${builtin.id} 装饰带高度 ${value}vh 超出 15% 预算（100vh 满高容器除外）",
                    value <= 15f || value == 100f
                )
            }
            // 尊重减少动效（无障碍/省电）：**声明了动效的模板必须给出静默分支**。
            // 零动效模板（本批为纯静态 CSS）不强制写空分支——空 @media 是死 CSS，不构成证据；
            // 该断言随模板引入动效后自动收紧（4.15 内容感知运行时 / 4.16 成套美术）。
            val declaresMotion = MOTION_DECLARATIONS.any { css.contains(it) }
            if (declaresMotion) {
                assertTrue(
                    "${builtin.id} 声明了动效就必须提供 prefers-reduced-motion 静默分支",
                    css.contains("prefers-reduced-motion")
                )
            }
            // 正文区下限可用 [ReaderTemplateDecorationPolicy] 判据（此处校验字号/行高变量被使用）
            assertTrue(
                "${builtin.id} 须支持宿主下发字号/行高",
                css.contains("--md-font-size") && css.contains("--md-line-height")
            )
        }
    }

    @Test
    fun `paged 与 scroll 两条渲染路径均被覆盖`() {
        val templates = load().templates
        assertTrue("至少一套分页模板", templates.any { !it.isScroll })
        assertTrue("至少一套滚动模板（验证 scroll 契约）", templates.any { it.isScroll })
    }

    @Test
    fun `缺失模板文件时给出可诊断错误`() {
        val broken = ReaderBuiltinTemplateCatalog.load { path ->
            if (path == ReaderBuiltinTemplateCatalog.CatalogAsset) {
                "{\"schemaVersion\":1,\"templates\":[\"builtin.missing\"]}"
            } else {
                null
            }
        }
        assertEquals(0, broken.templates.size)
        assertTrue(broken.errors.single().contains("缺少内置模板文件"))
    }

    @Test
    fun `id 不一致时拒绝并入并报错`() {
        val mismatched = ReaderBuiltinTemplateCatalog.load { path ->
            when (path) {
                ReaderBuiltinTemplateCatalog.CatalogAsset ->
                    "{\"schemaVersion\":1,\"templates\":[\"builtin.a\"]}"
                ReaderBuiltinTemplateCatalog.templatePath("builtin.a") -> """
                    {"schemaVersion":2,"type":"paged","id":"builtin.b","name":"错配",
                     "firstPageHtml":"<div data-reader-flow=\"body\"></div>",
                     "otherPageHtml":"<div data-reader-flow=\"body\"></div>",
                     "scrollHtml":"","css":":root{}[data-reader-theme=\"night\"]{}","javascript":""}
                """.trimIndent()
                else -> null
            }
        }
        assertEquals(0, mismatched.templates.size)
        assertTrue(mismatched.errors.single().contains("与目录不一致"))
    }

    private companion object {
        val FIELD = Regex("""data-reader-field="([^"]+)"""")
        val ALLOWED_FIELDS = setOf(
            "bookName", "chapterTitle", "progress", "page", "time", "battery"
        )

        /** 动效声明特征：出现任一项即必须提供 `prefers-reduced-motion` 静默分支。 */
        val MOTION_DECLARATIONS = listOf("@keyframes", "animation:", "transition:", "scroll-behavior: smooth")

        /** 视口高尺寸声明（用于装饰带高度预算校验）。 */
        val VH_LENGTH = Regex("""(\d+(?:\.\d+)?)vh""")

        /** 第三方 IP 关键词（合规红线：不得内置 IP 原作素材）。 */
        val FORBIDDEN_IP_KEYWORDS = listOf(
            "asuka", "eva", "evangelion", "doraemon", "minecraft", "pokemon", "naruto", "hatsune"
        )
    }
}