package io.legado.app.service

import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Collections

/**
 * 合成串行器行为测试（T8.8 真机铁证 2026-11-01）：
 * 系统 TTS 框架 QUEUE_ADD 会连续下发多段合成请求，引擎侧必须排队，
 * 不能「新请求取消上一段」，否则连续试听/对比试听只出声第一段。
 */
class BuiltinSynthQueueTest {

    @Test
    fun `两段合成串行不重叠`() = runBlocking {
        val events = Collections.synchronizedList(mutableListOf<String>())
        val queue = BuiltinSynthQueue()
        val first = launch {
            queue.serialized {
                events += "A-start"
                delay(150)
                events += "A-end"
            }
        }
        delay(40)
        val second = launch {
            queue.serialized {
                events += "B-start"
                events += "B-end"
            }
        }
        first.join()
        second.join()
        assertEquals(listOf("A-start", "A-end", "B-start", "B-end"), events.toList())
    }

    @Test
    fun `前一段异常后仍能继续下一段`() = runBlocking {
        val queue = BuiltinSynthQueue()
        runCatching { queue.serialized { throw IllegalStateException("合成失败") } }
        val next = queue.serialized { 42 }
        assertEquals(42, next)
    }
}
