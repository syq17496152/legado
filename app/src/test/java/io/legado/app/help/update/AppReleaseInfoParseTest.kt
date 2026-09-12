package io.legado.app.help.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 应用内检查更新资产解析回归测试（app-update-variant-fix）。
 *
 * 回归背景：发布脚本资产命名（legado_miss_app_debug_/legado_miss_app_/legado_legacy_app_）
 * 与旧解析逻辑（认 "release" 子串 + split("_")[2].dropLast(2)）不匹配，导致：
 * 三包全解析为 OFFICIAL（测试包永远无更新）、versionName 解析出 "a"（正式包误更新成测试包）。
 * 断言数据取自真实 release 3.26.090820 的资产名。
 */
class AppReleaseInfoParseTest {

    // === 新命名（publish_release.py get_upload_name）变体映射 ===

    @Test
    fun `新命名 测试包 asset 映射为 BETA_RELEASE`() {
        val variant = resolveAppVariant("legado_miss_app_debug_3.26.090820.apk", preRelease = false)
        assertEquals(AppVariant.BETA_RELEASE, variant)
    }

    @Test
    fun `新命名 正式包 asset 映射为 OFFICIAL`() {
        val variant = resolveAppVariant("legado_miss_app_3.26.090820.apk", preRelease = false)
        assertEquals(AppVariant.OFFICIAL, variant)
    }

    @Test
    fun `新命名 共存包 asset 映射为 BETA_RELEASEA`() {
        val variant = resolveAppVariant("legado_legacy_app_3.26.090820.apk", preRelease = false)
        assertEquals(AppVariant.BETA_RELEASEA, variant)
    }

    // === 旧命名（legado-E 风格滚动 beta）兼容 ===

    @Test
    fun `旧命名 release 滚动包 preRelease 映射为 BETA_RELEASE`() {
        val variant = resolveAppVariant("legado_release_3.24.01010820.apk", preRelease = true)
        assertEquals(AppVariant.BETA_RELEASE, variant)
    }

    @Test
    fun `旧命名 releaseA releaseS 映射保持`() {
        assertEquals(
            AppVariant.BETA_RELEASEA,
            resolveAppVariant("legado_releaseA_3.24.01010820.apk", preRelease = true)
        )
        assertEquals(
            AppVariant.BETA_RELEASES,
            resolveAppVariant("legado_releaseS_3.24.01010820.apk", preRelease = true)
        )
    }

    // === versionName 解析 ===

    @Test
    fun `新命名 版本号取最后一个下划线段`() {
        assertEquals(
            "3.26.090820",
            AppReleaseInfo.parseVersionName("legado_miss_app_debug_3.26.090820.apk")
        )
        assertEquals(
            "3.26.090820",
            AppReleaseInfo.parseVersionName("legado_miss_app_3.26.090820.apk")
        )
        assertEquals(
            "3.26.090820",
            AppReleaseInfo.parseVersionName("legado_legacy_app_3.26.090820.apk")
        )
    }

    @Test
    fun `旧命名 8位日期尾部2位冗余自动去除`() {
        assertEquals(
            "3.24.010108",
            AppReleaseInfo.parseVersionName("legado_release_3.24.01010820.apk")
        )
    }

    @Test
    fun `版本号与本地格式可直接字符串比较 方向正确`() {
        // 防 "a" 回归：解析结果必须是版本号而非垃圾段
        val parsed = AppReleaseInfo.parseVersionName("legado_miss_app_3.26.090820.apk")
        assertTrue(parsed > "3.26.090520")
        assertTrue(parsed < "3.26.09121530")
    }

    @Test
    fun `奇异输入不崩溃`() {
        assertEquals("abc", AppReleaseInfo.parseVersionName("abc.apk"))
        assertEquals("", AppReleaseInfo.parseVersionName(""))
    }
}
