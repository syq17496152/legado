package io.legado.app.model.reader.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 渲染后端契约单测（epub-md-rich-rendering 阶段 2.1/2.5）。
 *
 * 口径（AD-12 修订）：**只覆盖可 JVM 化的部分**——契约数据结构不变量、能力矩阵、
 * 代际令牌与状态机、分流判据、坐标换算。WebView 渲染行为本身改为真机 L2 对照清单，不进 commit 门禁。
 */
class RendererContractTest {

    // === 契约数据结构不变量 ===

    @Test
    fun `文本区间拒绝负偏移与倒序`() {
        assertThrows(IllegalArgumentException::class.java) { TextRange(0, -1, 2) }
        assertThrows(IllegalArgumentException::class.java) { TextRange(0, 5, 3) }
        assertEquals(3, TextRange(0, 1, 3).endOffset)
    }

    @Test
    fun `渲染配置要求正尺寸`() {
        assertThrows(IllegalArgumentException::class.java) {
            RenderConfig(0, 100, 16f, 24f, 0)
        }
        assertTrue(RenderConfig(100, 200, 16f, 24f, 0).preservePublisherLayout.not())
    }

    // === 失败档（WebView 后端上报 → 宿主按降级链处理） ===

    @Test
    fun `失败档覆盖引擎不可用结构损坏与布局类失败`() {
        val failures = RenderFailure.entries.map { it.name }.toSet()
        assertEquals(
            "WebView 后端需要上报的失败档不得删减（含结构损坏 MALFORMED_CONTENT）",
            setOf("ENGINE_UNAVAILABLE", "LAYOUT_ERROR", "EMPTY_RESULT", "TIMEOUT", "MALFORMED_CONTENT"),
            failures
        )
    }

    // === 能力矩阵（AD-30 必须实现）===

    @Test
    fun `能力矩阵符合设计文档明示`() {
        val canvas = BackendCapabilities.Canvas
        assertFalse("canvas 不支撑富渲染（降级代码块）", canvas.supportsRichRender)
        assertFalse(canvas.supportsFixedLayout)
        assertFalse(canvas.supportsInteractive)
        assertFalse("canvas 不支撑模板外观", canvas.supportsTemplates)
        assertTrue(canvas.supportsTextSelection)
        assertTrue(canvas.supportsReadAloudAnchor)

        val web = BackendCapabilities.DirectWeb
        assertTrue(web.supportsRichRender)
        assertTrue(web.supportsFixedLayout)
        assertTrue(web.supportsInteractive)
        assertTrue(web.supportsTemplates)
    }

    // === 代际令牌语义 ===

    @Test
    fun `切换递增代际且旧代际结果被判定为过期`() {
        val machine = RenderBackendStateMachine(clock = { 0L })
        val first = machine.currentToken()
        assertTrue(machine.isCurrent(first))

        machine.beginPrepare()
        machine.markRendered()
        val switched = machine.beginSwitch()
        assertFalse("切换后旧代际必须失效", machine.isCurrent(first))
        assertTrue(machine.isCurrent(switched))
        assertEquals(RenderBackendStateMachine.State.Switching, machine.state)
    }

    @Test
    fun `降级递增代际并受冷却门限制`() {
        var now = 1_000L
        val machine = RenderBackendStateMachine(clock = { now }, degradeCooldownMs = 15_000L)
        val before = machine.currentToken()
        val degraded = machine.beginDegrade(RenderFailure.LAYOUT_ERROR)
        assertFalse(machine.isCurrent(before))
        assertTrue(machine.isCurrent(degraded))
        assertEquals(RenderBackendStateMachine.State.Degraded, machine.state)
        assertEquals(RenderFailure.LAYOUT_ERROR, machine.lastFailure)

        assertFalse("冷却未到不得重试", machine.canRetryAfterDegrade())
        assertFalse(machine.retryIfCooledDown())
        now += 15_000L
        assertTrue(machine.canRetryAfterDegrade())
        assertTrue(machine.retryIfCooledDown())
        assertEquals(RenderBackendStateMachine.State.Rendered, machine.state)
        assertNull(machine.lastFailure)
    }

    @Test
    fun `状态机主流程与失败分支`() {
        val machine = RenderBackendStateMachine(clock = { 0L })
        assertEquals(RenderBackendStateMachine.State.Idle, machine.state)
        machine.beginPrepare()
        assertEquals(RenderBackendStateMachine.State.Preparing, machine.state)
        machine.markRendered()
        machine.beginRemeasure()
        assertEquals(RenderBackendStateMachine.State.Measuring, machine.state)
        machine.markRemeasured()
        assertEquals(RenderBackendStateMachine.State.Rendered, machine.state)
        machine.markFailed(RenderFailure.TIMEOUT)
        assertEquals(RenderBackendStateMachine.State.Failed, machine.state)
        machine.dispose()
        assertEquals(RenderBackendStateMachine.State.Idle, machine.state)
    }

    @Test
    fun `dispose 使在途结果全部失效`() {
        val machine = RenderBackendStateMachine(clock = { 0L })
        machine.beginPrepare()
        val inFlight = machine.currentToken()
        machine.dispose()
        assertFalse(machine.isCurrent(inFlight))
    }

    // === 统一坐标（AD-14）===

    @Test
    fun `坐标类型拒绝非法取值`() {
        assertThrows(IllegalArgumentException::class.java) { ReadingPosition.Text(0, -1) }
        assertThrows(IllegalArgumentException::class.java) { ReadingPosition.Page(0, -1) }
        assertThrows(IllegalArgumentException::class.java) { ReadingPosition.Page(0, 0, 1.5f) }
        assertThrows(IllegalArgumentException::class.java) { ReadingPosition.Media(0, 2f) }
    }

    @Test
    fun `文本位置落到不支持字符偏移的后端时降级为章内锚点并标记`() {
        val text = ReadingPosition.Text(chapterIndex = 4, charOffset = 1200)
        val lost = ReadingPositionPolicy.resolveFor(text, targetSupportsTextOffset = false)
        assertTrue("必须如实标记降级", lost.degraded)
        assertEquals(ReadingPosition.Page(4, 0, 0f), lost.position)

        val kept = ReadingPositionPolicy.resolveFor(text, targetSupportsTextOffset = true)
        assertFalse(kept.degraded)
        assertEquals(text, kept.position)
    }

    @Test
    fun `页与媒体锚点在任何后端均无损`() {
        val page = ReadingPosition.Page(1, 3, 0.5f)
        val media = ReadingPosition.Media(2, 0.25f)
        assertFalse(ReadingPositionPolicy.resolveFor(page, targetSupportsTextOffset = false).degraded)
        assertFalse(ReadingPositionPolicy.resolveFor(media, targetSupportsTextOffset = false).degraded)
    }

    @Test
    fun `章内比例计算与兜底锚点构造`() {
        assertEquals(
            0.25f,
            ReadingPositionPolicy.chapterRatio(ReadingPosition.Text(0, 250), chapterCharCount = 1000)!!
        )
        assertEquals(
            null,
            ReadingPositionPolicy.chapterRatio(ReadingPosition.Text(0, 250), chapterCharCount = 0)
        )
        assertEquals(
            ReadingPosition.Page(chapterIndex = 7, pageIndex = 0, inPageRatio = 1f),
            ReadingPositionPolicy.anchorFromRatio(chapterIndex = 7, ratio = 2f)
        )
    }
}