package io.legado.app

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * REQ-04：`AndroidManifest.xml` 的**前台服务类型合规**不变量。
 *
 * 背景：Android 14+ 要求前台服务显式声明 `android:foregroundServiceType`，缺失会在启动时抛
 * `MissingForegroundServiceTypeException`。本项目实测 17 个 `<service>` **全部已声明**（故原需求
 * 文档所称「缺 type」不成立）；本测试把「不得再出现未声明 type 的 service」固化为回归防线。
 *
 * 另注（已知上限，见 `docs/project-rules/foreground-service-type-policy.md`）：
 * Android 15 对 `dataSync` 有每 24h 累计 6h 上限 ⇒ 长任务服务需保持「粒度可续跑」语义，
 * 不得依赖单次连续运行完成全量工作。
 */
class AndroidManifestServiceTypeTest {

    /**
     * 纯 `bindService` 型服务**不适用**前台服务类型（它们从不 `startForeground`，
     * 也就不可能触发 `MissingForegroundServiceTypeException`）。
     * 登记即豁免，新增必须在此写明原因，禁止用 `dataSync` 之类占用额度糊弄门禁。
     */
    private val boundOnlyServices = mapOf(
        // 端侧 LLM 推理宿主（design AD-11）：仅应用内 bound，无线程内 startForeground
        ".help.llm.LlmService" to "bound-only，:llm 进程内推理，从不 startForeground"
    )

    private val manifest by lazy {
        listOf(
            File("src/main/AndroidManifest.xml"),
            File("../app/src/main/AndroidManifest.xml"),
            File("app/src/main/AndroidManifest.xml")
        ).first { it.isFile }.readText()
    }

    /** 按 `<service ... />` 或 `<service ...> ... </service>` 切出每个 service 声明块。 */
    private fun serviceBlocks(): List<String> =
        Regex("""<service\b[\s\S]*?(?:/>|</service>)""").findAll(manifest)
            .map { it.value }
            .toList()

    @Test
    fun everyServiceDeclaresForegroundServiceType() {
        val blocks = serviceBlocks()
        assertTrue("未解析到任何 <service> 声明（manifest 结构可能已变）", blocks.isNotEmpty())
        val offenders = blocks.filterNot { it.contains("foregroundServiceType") }
            .filterNot { block -> boundOnlyServices.keys.any { nameOf(block) == it } }
        assertEquals(
            "以下 service 未显式声明 foregroundServiceType（Android 14+ 会启动失败）：$offenders",
            0,
            offenders.size
        )
    }

    /** 取出 `<service android:name="...">` 的类名；解析不出返回空串（一律按违规处理，不放宽） */
    private fun nameOf(block: String): String =
        Regex("""android:name="([^"]*)"""").find(block)?.groupValues?.get(1).orEmpty()

    @Test
    fun serviceDeclarationsAreStillExplicitTypeOnly() {
        // 反向守卫：不得用 `specialUse` 之外的占位值或空字符串糊弄门禁
        val values = Regex("""foregroundServiceType="([^"]*)"""")
            .findAll(manifest).map { it.groupValues[1] }.toList()
        values.forEach { value ->
            assertTrue("foregroundServiceType 不得为空：`$value`", value.isNotBlank())
        }
        assertTrue("dataSync 型服务仍应存在（评估结论以此为基线）", values.any { it.contains("dataSync") })
    }
}