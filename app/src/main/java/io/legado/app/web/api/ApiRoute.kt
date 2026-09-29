package io.legado.app.web.api

import fi.iki.elonen.NanoHTTPD
import io.legado.app.api.ReturnData
import io.legado.app.web.TokenManager

/**
 * 路由处理体（web-mcp-productization 一期 · 5.1）。
 *
 * 契约：
 * - 返回 [ReturnData] ⇒ 由 [ApiEnvelope] 装成 JSON 信封（常规路径）；
 * - 返回 [NanoHTTPD.Response] ⇒ **逃生舱**，直接返回该响应（如 `/backup` 需回 ZIP 文件流）；
 * - 抛 [IllegalArgumentException] ⇒ 400；抛其他异常 ⇒ 500（由 [ApiEnvelope] 统一映射）。
 *
 * 之所以是 `suspend`：Kernel 全链挂起（REQ-1-202），HTTP 层不再用 `runBlocking` 占死工作线程。
 */
fun interface ApiHandler {
    suspend fun handle(ctx: ApiContext): Any
}

/**
 * 声明式路由（web-mcp-productization 一期 · 5.1 / REQ-1-501）。
 *
 * **[ApiRoute.level] 是"所需令牌级别"的唯一真源** —— 鉴权层直接读它，
 * 不得另建"URL 前缀 → 级别"的独立路由表（REQ-1-106，AD-1-01）。
 *
 * @param method HTTP 方法
 * @param path 精确路径（如 `/saveBookSource`）；不做前缀匹配，未注册即不可达（REQ-1-506）
 * @param level 访问所需最低令牌级别
 * @param mcpToolName 二期 MCP 投影用工具名；`null` = 本期不投影。**仅作 REST ↔ MCP 对拍**，
 *        工具元数据的唯一真源仍按域放在 `web/mcp/tools/` 下的域文件（总纲 AD-10 / REQ-2-201）
 * @param mcpTitle MCP 侧人类可读标题（可选；不填时由域文件声明提供，见 tasks 2.1）
 * @param mcpDescription MCP 侧给 AI 看的一句话说明（可选，同上）
 * @param mcpDangerous MCP 侧危险标记（可选，默认 `false`；`true` 触发端侧确认闸门）
 * @param handler 处理体。**必须是最后一个参数** —— 声明处用「命名传可选参数 + 尾部 lambda」书写，
 *                而 Kotlin 的尾部 lambda 只绑定**最后一个**形参。
 */
data class ApiRoute(
    val method: NanoHTTPD.Method,
    val path: String,
    val level: TokenManager.Level,
    val mcpToolName: String? = null,
    val mcpTitle: String? = null,
    val mcpDescription: String? = null,
    val mcpDangerous: Boolean = false,
    val handler: ApiHandler,
) {
    /** 注册表的唯一键（方法 + 路径）。 */
    val key: String get() = "${method.name} $path"

    /** 是否为写操作（HTTP 语义层；供审计与 MCP 只读提示使用）。 */
    val isWrite: Boolean get() = method != NanoHTTPD.Method.GET && method != NanoHTTPD.Method.OPTIONS

    /**
     * `webAuthStrict = false`（过渡期）时该路由**是否仍强制令牌**。
     *
     * **判据是「级别」而不是「读写」** —— 若按读写判会漏掉高敏**读**端点：
     * `GET /backup` / `GET /backupPreview` 声明为 [TokenManager.Level.ADMIN]（可一次性拉走全部数据），
     * 按"读放行"会**未授权即可整包导出**，与本项目要修的痛点（同 WiFi 任何人 GET /backup 拉走全部数据）
     * 直接冲突。故口径为：**只要 `level != READONLY`，过渡期也必须带令牌**；
     * 仅 [TokenManager.Level.READONLY] 的只读端点才在 `strict=false` 时放行（保老 vue 页零破坏）。
     */
    val requiresAuthWhenNonStrict: Boolean get() = level != TokenManager.Level.READONLY
}
