package io.legado.app.ui.book.read.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * IF-14-G 回归：预合成门禁必须与发起侧同口径 ——
 * 内置离线引擎就绪即可用（离线不需要网络），多角色模板不再是阻断项；
 * 流式限制只作用于 HTTP 通道。
 */
class TtsPrebuildGateTest {

    @Test
    fun `离线引擎就绪即可用且不需要网络`() {
        assertNull(prebuildGateBlock(builtinReady = true, httpTtsAvailable = false, streamReadAloud = false, networkAvailable = false))
        // 离线引擎不受流式开关影响
        assertNull(prebuildGateBlock(builtinReady = true, httpTtsAvailable = false, streamReadAloud = true, networkAvailable = false))
    }

    @Test
    fun `引擎不可用则阻断`() {
        assertEquals(
            PrebuildGateBlock.ENGINE,
            prebuildGateBlock(builtinReady = false, httpTtsAvailable = false, streamReadAloud = false, networkAvailable = true)
        )
    }

    @Test
    fun `HTTP引擎开启流式收听则阻断`() {
        assertEquals(
            PrebuildGateBlock.STREAM,
            prebuildGateBlock(builtinReady = false, httpTtsAvailable = true, streamReadAloud = true, networkAvailable = true)
        )
    }

    @Test
    fun `HTTP引擎无网络则阻断为网络原因`() {
        assertEquals(
            PrebuildGateBlock.NETWORK,
            prebuildGateBlock(builtinReady = false, httpTtsAvailable = true, streamReadAloud = false, networkAvailable = false)
        )
        assertNull(
            prebuildGateBlock(builtinReady = false, httpTtsAvailable = true, streamReadAloud = false, networkAvailable = true)
        )
    }

    @Test
    fun `多角色模板不再是阻断项`() {
        // 该函数没有模板参数：只要引擎可用就放行（T3.3 已支持按段+角色音色预合成）
        assertNull(
            prebuildGateBlock(builtinReady = true, httpTtsAvailable = true, streamReadAloud = false, networkAvailable = true)
        )
    }
}
