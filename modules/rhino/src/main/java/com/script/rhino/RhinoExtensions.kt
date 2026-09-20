package com.script.rhino

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
import org.mozilla.javascript.Context
import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.InvocationKind
import kotlin.contracts.contract
import kotlin.coroutines.ContinuationInterceptor
import kotlin.coroutines.CoroutineContext

val rhinoContext: RhinoContext
    get() = Context.getCurrentContext() as RhinoContext

val rhinoContextOrNull: RhinoContext?
    get() = Context.getCurrentContext() as? RhinoContext

@Suppress("LEAKED_IN_PLACE_LAMBDA", "WRONG_INVOCATION_KIND")
@OptIn(ExperimentalContracts::class)
inline fun <T> suspendContinuation(crossinline block: suspend CoroutineScope.() -> T): T {
    contract {
        callsInPlace(block, InvocationKind.EXACTLY_ONCE)
    }
    val cx = Context.enter()
    try {
        val pending = cx.captureContinuation()
        pending.applicationState = suspend {
            supervisorScope {
                block()
            }
        }
        throw pending
    } catch (e: IllegalStateException) {
        return runBlocking { block() }
    } finally {
        Context.exit()
    }
}

inline fun <T> runScriptWithContext(context: CoroutineContext, block: () -> T): T {
    RhinoScriptEngine
    val rhinoContext = Context.enter() as RhinoContext
    val previousCoroutineContext = rhinoContext.coroutineContext
    rhinoContext.coroutineContext = context.minusKey(ContinuationInterceptor)
    try {
        return block()
    } finally {
        rhinoContext.coroutineContext = previousCoroutineContext
        Context.exit()
    }
}

suspend inline fun <T> runScriptWithContext(block: () -> T): T {
    // 必须与非挂起重载同款：先强制单例初始化（init{} 内 ContextFactory.initGlobal 把全局工厂
    // 换成 RhinoContextFactory）。缺这一句时，**进程内首次走挂起重载**的 JS 调用会由默认工厂
    // 造出 stock Context ⇒ `Context.enter() as RhinoContext` 抛
    // ClassCastException: org.mozilla.javascript.Context cannot be cast to com.script.rhino.RhinoContext
    // （真机复现：书源登录页 SourceLoginViewModel.initData 首次 runScriptWithContext ⇒ 整页被 finish）。
    RhinoScriptEngine
    val rhinoContext = Context.enter() as RhinoContext
    val previousCoroutineContext = rhinoContext.coroutineContext
    rhinoContext.coroutineContext = currentCoroutineContext().minusKey(ContinuationInterceptor)
    try {
        return block()
    } finally {
        rhinoContext.coroutineContext = previousCoroutineContext
        Context.exit()
    }
}
