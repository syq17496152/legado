package io.legado.app.ui.widget.compose

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Compose 图标槽资源类型守卫（2026-11-08，compose-page-regression-sweep）。
 *
 * 背景：`painterResource` 只支持 VectorDrawable 与 PNG/JPG/WEBP；若 `iconRes` /
 * `painterResource` 指向 `<selector>` / `<shape>` / `<layer-list>`，运行期会抛
 * `IllegalArgumentException: Only VectorDrawables and rasterized asset types are supported`。
 * 实证：`ReplaceRuleActivity`（`ic_bottom_ai`）打开替换净化页即崩溃。
 *
 * 本用例以「源码引用 → 资源根标签」的结构断言把该模式永久锁死。
 */
class ComposeIconResourceTypeTest {

    /** 判据单源：XML 头部是否为 painterResource 支持的 vector。 */
    internal fun isComposeIconCompatible(xmlHead: String): Boolean =
        xmlHead.contains("<vector")

    private fun appModuleDir(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            if (File(dir, "src/main/res").isDirectory) return dir
            dir = dir.parentFile
        }
        error("未找到 app module 目录（缺少 src/main/res）")
    }

    @Test
    fun judgement_acceptsVectorAndRejectsSelector() {
        // 反例：selector 必须被判为不兼容（否则守卫形同虚设）
        assertFalse(isComposeIconCompatible("""<?xml version="1.0"?><selector xmlns:android="http://schemas.android.com/apk/res/android">"""))
        // 反例：shape / layer-list 同样不支持
        assertFalse(isComposeIconCompatible("""<shape xmlns:android="http://schemas.android.com/apk/res/android">"""))
        assertFalse(isComposeIconCompatible("""<layer-list xmlns:android="http://schemas.android.com/apk/res/android">"""))
        // 正例：vector 支持
        assertTrue(isComposeIconCompatible("""<vector xmlns:android="http://schemas.android.com/apk/res/android">"""))
    }

    @Test
    fun iconSlots_mustNotReferenceNonVectorDrawables() {
        val module = appModuleDir()
        val javaRoot = File(module, "src/main/java")
        val resRoot = File(module, "src/main/res")

        // 与生产代码同口径：iconRes = R.drawable.X 与 painterResource(R.drawable.X) / painterResource(id = R.drawable.X)
        val refRegex = Regex(
            """(?:iconRes\s*=\s*R\.drawable\.|painterResource\(\s*(?:id\s*=\s*)?R\.drawable\.)(\w+)"""
        )
        val refs = linkedMapOf<String, MutableSet<String>>()
        javaRoot.walkTopDown().filter { it.isFile && it.extension == "kt" }.forEach { file ->
            refRegex.findAll(file.readText()).forEach { match ->
                refs.getOrPut(match.groupValues[1]) { linkedSetOf() }.add(file.name)
            }
        }
        assertTrue("应至少扫描到图标引用，扫描到 ${refs.size} 个", refs.isNotEmpty())

        val drawableDirs = resRoot.listFiles().orEmpty()
            .filter { it.isDirectory && it.name.startsWith("drawable") }

        val violations = mutableListOf<String>()
        refs.forEach { (name, users) ->
            val xmlFiles = drawableDirs.map { File(it, "$name.xml") }.filter { it.isFile }
            if (xmlFiles.isEmpty()) return@forEach // 位图或库资源：不在本守卫范围
            xmlFiles.forEach { xml ->
                if (!isComposeIconCompatible(xml.readText().take(400))) {
                    val kind = Regex("""<([\w-]+)""").find(xml.readText())?.groupValues?.get(1) ?: "unknown"
                    violations += "$name ($kind) <- ${users.joinToString()}"
                }
            }
        }
        assertTrue(
            "以下 Compose 图标槽引用了 painterResource 不支持的资源（只能是 vector/位图）：\n" +
                violations.joinToString("\n"),
            violations.isEmpty()
        )
    }
}
