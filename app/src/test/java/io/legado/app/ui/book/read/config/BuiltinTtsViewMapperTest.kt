package io.legado.app.ui.book.read.config

import io.legado.app.help.tts.builtin.BuiltinEngineState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 管理页视图映射（T5.5）：无书/无章节时的提示文案（不依赖 Android 运行态）。
 * T3.5：诊断结论渲染（引擎态/内存/RTF → 可操作文案）。
 */
class BuiltinTtsViewMapperTest {

    @Test
    fun `无书时提示先打开一本书`() {
        assertEquals("请先打开一本书", BuiltinTtsViewMapper.storyboardHint(null))
        assertEquals("本章测试文案", BuiltinTtsViewMapper.roleSummary(null, "本章测试文案"))
    }

    @Test
    fun `无书时分镜为空`() {
        assertEquals(0, BuiltinTtsViewMapper.storyboard(null).size)
    }

    @Test
    fun `模型缺失时诊断为错误并带处置`() {
        val text = BuiltinTtsViewMapper.findings(
            profiles = listOf(ProfileRow("fast", "极速单声", BuiltinEngineState.NONE, true, 24000)),
            abiSupported = true,
            memoryMb = 2000,
            lastRtf = 0f,
            lastError = ""
        )
        assertEquals("[错误] 未下载离线语音包 → 在引擎页下载或导入语音包", text)
    }

    @Test
    fun `克隆档内存偏紧给警告`() {
        val text = BuiltinTtsViewMapper.findings(
            profiles = listOf(ProfileRow("clone", "高质克隆", BuiltinEngineState.READY, true, 24000)),
            abiSupported = true,
            memoryMb = 200,
            lastRtf = 0f,
            lastError = ""
        )
        assertTrue(text.startsWith("[警告] 可用内存 200MB"))
    }

    @Test
    fun `就绪且速度正常显示运行正常`() {
        val text = BuiltinTtsViewMapper.findings(
            profiles = listOf(ProfileRow("fast", "极速单声", BuiltinEngineState.READY, true, 24000)),
            abiSupported = true,
            memoryMb = 2000,
            lastRtf = 0.6f,
            lastError = ""
        )
        assertEquals("[正常] 运行正常", text)
    }

    @Test
    fun `连续合成十分钟渲染温控提示 九分钟不渲染`() {
        val profiles = listOf(ProfileRow("fast", "极速单声", BuiltinEngineState.READY, true, 24000))
        val nine = BuiltinTtsViewMapper.findings(
            profiles = profiles,
            abiSupported = true,
            memoryMb = 2000,
            lastRtf = 0.6f,
            lastError = "",
            continuousMinutes = 9
        )
        assertEquals("[正常] 运行正常", nine)
        val ten = BuiltinTtsViewMapper.findings(
            profiles = profiles,
            abiSupported = true,
            memoryMb = 2000,
            lastRtf = 0.6f,
            lastError = "",
            continuousMinutes = 10
        )
        assertTrue("十分钟必须出现温控提示：$ten", ten.contains("发热降频"))
        assertTrue("必须给出可操作处置：$ten", ten.contains("暂停"))
    }

    @Test
    fun `首选档损坏时提示已回落`() {
        val text = BuiltinTtsViewMapper.findings(
            profiles = listOf(
                ProfileRow("fast", "极速单声档", BuiltinEngineState.READY, true, 24000),
                ProfileRow("clone", "高质克隆档", BuiltinEngineState.CORRUPT, false, 24000)
            ),
            abiSupported = true,
            memoryMb = 2000,
            lastRtf = 0f,
            lastError = "",
            preferredId = "clone"
        )
        assertTrue(text.contains("[警告] 首选档「高质克隆档」不可用，已回落到「极速单声档」"))
    }
}
