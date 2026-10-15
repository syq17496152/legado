package io.legado.app.ui.debug

import okhttp3.OkHttpClient

/**
 * 调试屏（HTTP 调试 / cURL 测试）共用的**进程级** OkHttpClient 单例。
 *
 * 为什么必须单例（2026-10-15）：两个调试屏原先各自 `remember { OkHttpClient.Builder().build() }`
 * —— `remember` 只在**当前组合**内缓存，离开屏幕时该 client **不会被关闭**，因此 OkHttp 默认的
 * 线程池（Dispatcher）与连接池（ConnectionPool）**每进一次调试屏就新建一份，且旧的那份不再回收**
 * ⇒ 反复进出会持续累积线程与连接资源（调试屏是高频使用的排查工具）。
 *
 * 口径：只服务调试屏的**裸请求**，不挂业务拦截器（与业务链路隔离）；超时沿用 OkHttp 默认值
 * （与改造前行为一致，不引入行为变化）。
 */
val debugHttpClient: OkHttpClient by lazy { OkHttpClient.Builder().build() }
