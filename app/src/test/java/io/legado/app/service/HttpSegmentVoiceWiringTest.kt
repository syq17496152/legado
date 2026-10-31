package io.legado.app.service

import io.legado.app.help.readaloud.http.HttpSegmentVoicePlan
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T8.6 HTTP 通道段级换声接线（改码配对：`HttpReadAloudService`）。
 *
 * 1) 行为断言：非末段（l0）不推进段落指针、末段（l1）推进；
 * 2) 接线断言：服务必须保留"段级音色透传 + 段级缓存键 + 段级非末段判定"三处接线，
 *    防止后续重构悄悄退回成"整段一个音色"（那样多角色在 HTTP 通道就名存实亡）。
 */
class HttpSegmentVoiceWiringTest {

    @Test
    fun `非末段不推进段落 末段才推进`() {
        assertTrue(HttpSegmentVoicePlan.isNonLastSegment("sv_2_l0_abc"))
        assertFalse(HttpSegmentVoicePlan.isNonLastSegment("sv_2_l1_abc"))
        // 带期望值的行为断言：段落号可从段级文件名回解（推进段落指针时依赖它）
        assertEquals(2, HttpSegmentVoicePlan.paragraphIndexOf("sv_2_l0_abc")!!)
        assertEquals(0, HttpSegmentVoicePlan.paragraphIndexOf("sv_0_l1_abc")!!)
        assertEquals("sv_2_l1_abc", HttpSegmentVoicePlan.fileName(2, last = true, baseName = "abc"))
    }

    @Test
    fun `服务保留段级换声三处接线`() {
        val source = File(
            "src/main/java/io/legado/app/service/HttpReadAloudService.kt"
        ).let { if (it.exists()) it else File("app/src/main/java/io/legado/app/service/HttpReadAloudService.kt") }
        assertTrue("源文件不存在：${source.absolutePath}", source.exists())
        val text = source.readText()
        // ①段级入队器
        assertTrue("缺少段级入队器接线", text.contains("HttpSegmentEnqueuer("))
        // ②播放推进：非末段必须提前返回（不推进段落指针）
        assertTrue("缺少非末段判定", text.contains("HttpSegmentVoicePlan.isNonLastSegment"))
        // ③音色透传：段级 toneID 必须进 URL 模板/脚本引擎（且缓存键带音色维度）
        assertTrue("缺少段级音色透传", text.contains("voiceId = voiceId"))
        assertTrue("缺少 toneID 兜底", text.contains("toneID.ifBlank"))
    }
}
