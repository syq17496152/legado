package io.legado.app.web.api

/**
 * 声明式路由注册表（web-mcp-productization 一期 · 5.2 / REQ-1-501）。
 *
 * **唯一路由真源**：`HttpServer.serve()` 只做「OPTIONS → 查表 → 鉴权 → 分发 → 装信封 → 审计」，
 * 不得出现端点级 `when (uri)` 分支（REQ-1-502 / SC-1-15，由门禁 `audit_api_registry.py` 断言）。
 *
 * 新增端点 = 在 `web/api/routes/` 下声明一行 + 在 `ApiRouteBootstrap.install()` 追加组（通常是零改动），
 * **不改 `HttpServer.kt`**（SC-1-14）。
 */
object ApiRegistry {

    /** 注册顺序 = 声明顺序（`all()` 稳定有序，便于门禁与测试断言）。 */
    private val routeMap = LinkedHashMap<String, ApiRoute>()

    /**
     * 注册一条路由。
     * @throws IllegalArgumentException 同一「方法 + 路径」重复注册时抛出 —— 这是**编程错误**，
     * 必须在启动期暴露（而非静默覆盖）。
     */
    fun register(route: ApiRoute) {
        val previous = routeMap.putIfAbsent(route.key, route)
        require(previous == null) {
            "路由重复注册：${route.key}（已有 handler=${previous?.handler?.javaClass?.name}）"
        }
    }

    /** 批量注册。 */
    fun registerAll(vararg routes: ApiRoute) {
        routes.forEach { register(it) }
    }

    /** 按「方法 + 路径」精确查表；未注册返回 null（调用方据此落静态资源 / 404，REQ-1-506）。 */
    fun find(method: fi.iki.elonen.NanoHTTPD.Method, path: String): ApiRoute? =
        routeMap[routeKey(method, path)]

    /** 全部已注册路由（声明顺序）。 */
    fun all(): List<ApiRoute> = routeMap.values.toList()

    /** 已注册路由数（一期应为 28；门禁与单测的断言来源）。 */
    val size: Int get() = routeMap.size

    /** 指定路径是否已注册（忽略方法）。 */
    fun hasPath(path: String): Boolean = routeMap.keys.any { it.endsWith(" $path") }

    /** 清空注册表（**仅供单测**使用，生产流程不得调用 ⇒ 会把服务打回"无路由"状态）。 */
    internal fun clearForTest() = routeMap.clear()

    private fun routeKey(method: fi.iki.elonen.NanoHTTPD.Method, path: String) =
        "${method.name} $path"
}
