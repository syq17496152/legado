package io.legado.app.help.source

import io.legado.app.exception.NoStackTraceException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * 书源弹窗拦截策略（P0-S3，对齐 NG SourceInteractionPolicy 1:1）
 *
 * 协程上下文 Element：随 launch(ctx + policy) 协程树传播，
 * 子协程内通过 context[SourceInteractionPolicy] 均可读取。
 * AtomicBoolean 支持运行中翻转（policy.updateBlockDialogs）。
 */
class SourceInteractionPolicy(blockDialogs: Boolean) : AbstractCoroutineContextElement(Key) {

    private val blockDialogsState = AtomicBoolean(blockDialogs)

    val blockDialogs: Boolean
        get() = blockDialogsState.get()

    fun updateBlockDialogs(blockDialogs: Boolean) {
        blockDialogsState.set(blockDialogs)
    }

    companion object Key : CoroutineContext.Key<SourceInteractionPolicy>
}

/**
 * 书源弹窗被拦截异常（P0-S3）：批量流程中验证码/验证网页无法输入，中断为必要语义
 */
class SourceInteractionBlockedException(action: String) :
    NoStackTraceException("已禁止书源弹窗：$action")
