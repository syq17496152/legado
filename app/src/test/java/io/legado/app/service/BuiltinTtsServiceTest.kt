package io.legado.app.service

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 内置 TTS 引擎服务（T1.5）纯逻辑：语速百分比换算 + 回调分块（G-01 配对内置 TTS 服务与朗读服务改动）。
 */
class BuiltinTtsServiceTest {

    @Test
    fun `语速百分比换算为倍率`() {
        assertEquals(1.0f, BuiltinTtsSynthesisMath.speedFromPercent(100), 0.001f)
        assertEquals(1.5f, BuiltinTtsSynthesisMath.speedFromPercent(150), 0.001f)
        assertEquals(0.5f, BuiltinTtsSynthesisMath.speedFromPercent(50), 0.001f)
    }

    @Test
    fun `缺省或非法语速回落 1x`() {
        assertEquals(1.0f, BuiltinTtsSynthesisMath.speedFromPercent(0), 0.001f)
        assertEquals(1.0f, BuiltinTtsSynthesisMath.speedFromPercent(-1), 0.001f)
    }

    @Test
    fun `超范围语速被夹紧`() {
        assertEquals(2.0f, BuiltinTtsSynthesisMath.speedFromPercent(300), 0.001f)
    }

    @Test
    fun `回调分块约 200ms 且有下限`() {
        assertEquals(9600, BuiltinTtsSynthesisMath.chunkBytes(24000, 200))
        assertEquals(8820, BuiltinTtsSynthesisMath.chunkBytes(22050, 200))
        assertEquals(2048, BuiltinTtsSynthesisMath.chunkBytes(1000, 200))
    }
}
