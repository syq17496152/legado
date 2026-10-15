package io.legado.app.help

import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * 业务执行器（模型层）：正文解析/加载、朗读、漫画等**重任务**的串行队列。
 *
 * ⚠️ 三者必须分开（2026-10-15）：此前只有一个单线程池，**日志写盘 / Glide 位图回收 /
 * 正文解析共用同一条队列** ⇒ 列表快速滑动时的位图回收与高频日志写盘会把正文解析任务
 * 排在后面（用户可感：打开书 / 翻页偶发变慢）。拆开后三类任务互不阻塞。
 */
val globalExecutor: ExecutorService by lazy { Executors.newSingleThreadExecutor() }

/** 日志写盘执行器：AppLog 落盘、网络日志、`java.util.logging` 文件写 —— 与业务隔离 */
val logExecutor: ExecutorService by lazy { Executors.newSingleThreadExecutor() }

/** Glide 位图回收执行器：滑动时回收任务密集 —— 与业务/日志隔离，不阻塞正文解析 */
val glideExecutor: ExecutorService by lazy { Executors.newSingleThreadExecutor() }
