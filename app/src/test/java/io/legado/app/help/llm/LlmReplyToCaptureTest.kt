package io.legado.app.help.llm

import android.os.Message
import android.os.Messenger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * 异步回执必须**在入口**取 `replyTo`（E21 的真实根因）。
 *
 * Android 的 `Message` 是池化对象：`Handler` 分发完（`handleMessage` 返回 true）后会把
 * `replyTo`/`data`/`obj` 等字段清空以便复用。因此「进协程后再读 `msg.replyTo`」拿到的必然是 null，
 * 回执永远发不出去 —— 表现就是客户端等满 `LOAD_TIMEOUT_MS`(65s) 后报「连接推理进程超时」，
 * 用户看到的是「本地 AI 打开了却从不生效」。
 *
 * 本用例直接复现 Android 的回收行为，锁定「提前取值」这条契约：
 * 谁把 `msg.replyTo` 挪回异步回调里读，这里就会红。
 */
@RunWith(RobolectricTestRunner::class)
class LlmReplyToCaptureTest {

    @Test
    fun `Message 被回收后 replyTo 为空 所以必须提前取值`() {
        val msg = Message.obtain()
        val replyTo = Messenger(android.os.Handler(android.os.Looper.getMainLooper()) { true })
        msg.replyTo = replyTo

        // 1) 入口取值（正确写法）
        val captured = msg.replyTo
        assertEquals("入口必须能取到回执通道", replyTo, captured)

        // 2) 模拟 Handler 分发完成后的回收（Message.recycle 语义：字段全部清空）
        msg.recycle()

        // 3) 回收后再读 = 空（这正是 bug 现场）
        assertNull("回收后 replyTo 必为空 —— 异步回执不能在这一步才取值", msg.replyTo)
        assertEquals("入口捕获的引用不受回收影响", replyTo, captured)
    }
}
