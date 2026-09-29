package io.legado.app.web.api.routes

import com.google.gson.JsonObject
import fi.iki.elonen.NanoHTTPD.Method
import io.legado.app.api.ReturnData
import io.legado.app.service.kernel.ExploreKernel
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import io.legado.app.web.TokenManager.Level
import io.legado.app.web.api.ApiContext
import io.legado.app.web.api.ApiRoute
import io.legado.app.web.mcp.McpArgs

/**
 * O 发现与探索域路由声明（web-mcp-productization 三期 · design 1.2 · REQ-3-532）。
 *
 * 投影二期 [ExploreKernel]（业务零重复，AD-10）。端点与二期 MCP 工具一一对应：
 * explore_sources / explore_kinds / explore_books / discovery_suite_get /
 * discovery_suite_save / explore_cache_config_get / explore_cache_config_save。
 *
 * 能力边界（与内核一致）：exploreBooks 走 `WebBook.exploreBookAwait`（**与书源规则引擎共用**，
 * 落 `searchBookDao`）；慢源**结构化超时**（success=false + errorType=timeout，不抛出）。
 * exploreCacheConfigSave 只写真实偏好（mergeDiscoveryRss / mergedDiscoveryRssTarget），
 * 体积上限是代码常量 ⇒ 返回值 policyWritable=false。
 */
object DiscoverRoutes {

    val routes: Array<ApiRoute> = arrayOf(
        // O1 发现栏目首页（书源分组）
        ApiRoute(Method.GET, "/exploreSources", Level.READONLY, mcpToolName = "explore_sources") { _ ->
            ReturnData().setData(ExploreKernel.sources())
        },

        // O2 探索分类导航（ExploreKind）
        ApiRoute(Method.GET, "/exploreKinds", Level.READONLY, mcpToolName = "explore_kinds") { ctx ->
            ReturnData().setData(ExploreKernel.kinds(ctx.requireParam("sourceUrl")))
        },

        // O3 探索结果（分页）
        ApiRoute(Method.GET, "/exploreBooks", Level.READONLY, mcpToolName = "explore_books") { ctx ->
            ReturnData().setData(
                ExploreKernel.books(
                    sourceUrl = ctx.requireParam("sourceUrl"),
                    url = ctx.requireParam("exploreUrl"),
                    page = ctx.intParam("page", 1),
                )
            )
        },

        // O4 / O5 发现套件（7 种 widget：随机 / 标签 / 榜单 / 书单 / 横滑 / 排行 / 瀑布）
        ApiRoute(Method.GET, "/discoverySuiteGet", Level.READONLY, mcpToolName = "discovery_suite_get") { _ ->
            ReturnData().setData(ExploreKernel.suiteGet())
        },
        ApiRoute(
            Method.POST, "/discoverySuiteSave", Level.MANAGE, mcpToolName = "discovery_suite_save"
        ) { ctx ->
            ReturnData().setData(ExploreKernel.suiteSave(ctx.bodyArgs().model("suite")))
        },

        // O6 发现缓存策略（DiscoveryCachePolicy）
        ApiRoute(
            Method.GET, "/exploreCacheConfigGet", Level.READONLY,
            mcpToolName = "explore_cache_config_get"
        ) { _ ->
            ReturnData().setData(ExploreKernel.cacheConfigGet())
        },
        ApiRoute(
            Method.POST, "/exploreCacheConfigSave", Level.MANAGE,
            mcpToolName = "explore_cache_config_save"
        ) { ctx ->
            val body = ctx.bodyArgs()
            ReturnData().setData(
                ExploreKernel.cacheConfigSave(
                    mergeDiscoveryRss = body.boolOrNull("mergeDiscoveryRss"),
                    mergedDiscoveryRssTarget = body.strOrNull("mergedDiscoveryRssTarget"),
                )
            )
        },
    )
}

/** 请求体 → 参数访问器（与二期 MCP 工具同口径：类型不匹配按缺失处理）。 */
private fun ApiContext.bodyArgs(): McpArgs =
    McpArgs(GSON.fromJsonObject<JsonObject>(requirePostData()).getOrNull() ?: JsonObject())

/**
 * 取嵌套模型：字段既接受对象也接受 JSON 字符串（与二期 MCP 工具 parseModel 同口径）。
 *
 * @throws IllegalArgumentException 字段缺失 / null（→ HTTP 400），或 JSON 结构非法
 */
private inline fun <reified T> McpArgs.model(key: String): T {
    val element = raw().get(key) ?: throw IllegalArgumentException("参数 $key 不能为空")
    if (element.isJsonNull) throw IllegalArgumentException("参数 $key 不能为空")
    val json = if (element.isJsonPrimitive) element.asString else GSON.toJson(element)
    return GSON.fromJsonObject<T>(json)
        .getOrElse { throw IllegalArgumentException("参数 $key 格式不正确：${it.message}") }
}

/** 取可选布尔：字段缺失或 JSON null ⇒ null（= 内核「沿用现值」语义）。 */
private fun McpArgs.boolOrNull(key: String): Boolean? =
    if (raw().has(key) && !raw().get(key).isJsonNull) bool(key) else null

/** 取可选字符串：字段缺失或 JSON null ⇒ null（空串保留，供「清空字段」用）。 */
private fun McpArgs.strOrNull(key: String): String? =
    if (raw().has(key) && !raw().get(key).isJsonNull) str(key) else null