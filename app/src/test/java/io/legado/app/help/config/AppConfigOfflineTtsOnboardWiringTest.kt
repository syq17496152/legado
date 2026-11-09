package io.legado.app.help.config

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 离线听书引导偏好接线（offline-tts-ux-consolidation 2.7）：
 * `AppConfig.offlineTtsOnboarded` / `offlineTtsOnboardSkipCount` 必须读写同一键，
 * 且默认值分别为 `false` / `0`（老用户首次进入即"未引导"，不能误判为已引导）。
 *
 * 用源码不变量：`AppConfig` 是 object 且类初始化即读 `appCtx` 的 prefs（依赖 ContentProvider），
 * 本项目单测无 Robolectric ⇒ 无法实例化断言（同 `AppConfigAnimSpeedWiringTest` 口径）。
 */
class AppConfigOfflineTtsOnboardWiringTest {

    private fun code(): String {
        val rel = "src/main/java/io/legado/app/help/config/AppConfig.kt"
        val f = listOf(File(rel), File("../app/$rel"), File("app/$rel")).first { it.isFile }
        return f.readLines()
            .filterNot { it.trimStart().let { t -> t.startsWith("*") || t.startsWith("//") || t.startsWith("/*") } }
            .joinToString("\n")
    }

    @Test
    fun `引导完成标记读写同一键且默认未引导`() {
        val text = code()
        val start = text.indexOf("var offlineTtsOnboarded")
        assertTrue("未找到 offlineTtsOnboarded 属性", start >= 0)
        val body = text.substring(start, (start + 400).coerceAtMost(text.length))
        assertTrue("读侧必须用 PreferKey.offlineTtsOnboarded", body.contains("PreferKey.offlineTtsOnboarded"))
        assertTrue("默认值必须为 false（老用户首次进入视为未引导）", body.contains(", false)"))
        assertTrue("写侧必须落同一键", body.contains("putPrefBoolean(PreferKey.offlineTtsOnboarded"))
    }

    @Test
    fun `跳过计数默认为零且不会写入负数`() {
        val text = code()
        val start = text.indexOf("var offlineTtsOnboardSkipCount")
        assertTrue("未找到 offlineTtsOnboardSkipCount 属性", start >= 0)
        val body = text.substring(start, (start + 400).coerceAtMost(text.length))
        assertTrue("读侧必须用 PreferKey.offlineTtsOnboardSkipCount", body.contains("PreferKey.offlineTtsOnboardSkipCount"))
        assertTrue("默认值必须为 0", body.contains(", 0)"))
        assertTrue("写侧必须落同一键", body.contains("putPrefInt(PreferKey.offlineTtsOnboardSkipCount"))
        assertTrue("写入必须 coerceAtLeast(0) 兜底", body.contains("coerceAtLeast(0)"))
    }
}
