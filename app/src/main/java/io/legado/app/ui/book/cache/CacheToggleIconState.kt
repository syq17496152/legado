package io.legado.app.ui.book.cache

/**
 * 离线缓存页行尾「开始 / 停止」开关的状态派生（单源纯函数）。
 *
 * 语义与旧表达式 `CacheBook.cacheBookMap[bookUrl]?.isStop() == false` 严格等价：
 * - `null`：该书籍当前无缓存任务 ⇒ 非运行态 ⇒ 显示「开始」（三角）
 * - `true`：任务已停止 / 未在运行 ⇒ 非运行态 ⇒ 显示「开始」（三角）
 * - `false`：任务运行中 ⇒ 显示「停止」（方块）
 *
 * 【为什么必须抽成纯函数并放在行级 body 读取】
 * `CacheBook.cacheBookMap` 是普通 `ConcurrentHashMap`（**不是** Compose 快照状态），其变化只能靠
 * 宿主 `CacheActivity` 的 `refreshTicks`（`mutableStateMapOf`）触发局部重组。若把本函数的调用写在
 * `IconButton` 等**可跳过子组件**的 content lambda 内部，Kotlin 2.x 的强跳过（strong skipping）会
 * 记忆化该 lambda：tick 变化只让外层项重组，而 `IconButton` 的参数（记忆化 lambda）未变 ⇒ 子组件被
 * 跳过 ⇒ content 不再执行 ⇒ 状态永不重算（真机实证症状：点「开始」确实开始缓存，但图标一直是三角）。
 * ⇒ **读取必须置于行级 body**，使结果变量成为 content lambda 的捕获值，状态变化即令其参数改变。
 *
 * 【为什么与点击分支共用】
 * 图标显示与点击后的动作必须读同一真值，否则会出现「图标显示停止、点击却又启动」的错配。
 */
internal fun isCacheRunning(isStop: Boolean?): Boolean = isStop == false

/** 行尾开关点击后的动作。 */
internal enum class CacheToggleAction { Start, Stop }

/**
 * 由同一真值推导点击动作：运行中 ⇒ 停止；否则 ⇒ 开始。
 * 与 [isCacheRunning] 同源，保证「图标语义 = 点击语义」。
 */
internal fun cacheToggleAction(isStop: Boolean?): CacheToggleAction =
    if (isCacheRunning(isStop)) CacheToggleAction.Stop else CacheToggleAction.Start