package io.legado.app.web.api.routes

import fi.iki.elonen.NanoHTTPD.Method
import io.legado.app.api.ReturnData
import io.legado.app.web.TokenManager.Level
import io.legado.app.web.api.ApiRoute

/**
 * F 组 · 控制台分发路由声明（web-mcp-productization 三期 · spec 4.6，REQ-3-601 ~ REQ-3-604）。
 *
 * ⚠️ **桩实现，待四期替换**（REQ-3-603）：本期只把**接口先定下来**，真正的安装逻辑落在四期
 * `help/web/ConsoleInstaller.kt`（三通道探测 / Ed25519 验签 / sha256 比对 / 原子安装 / 回滚）。
 * 因此本文件的两个端点**不落盘、不下载、不安装**，只保证 boot 页与设置页拿到的**结构正确**：
 * 未装态返回完整的 `ConsoleStatus` 字段骨架，上传端点返回结构化 `{ok:false, error:"四期实现"}`。
 *
 * 鉴权口径（REQ-3-604，**本期落地为 READONLY**）：
 * 1. `GET /consoleStatus` 需**只读令牌**。spec 要求"免令牌"，但一期契约明确「路由级别不得为 NONE」
 *    （`ApiRegistryTest` 硬断言 "级别不得为 NONE"），免令牌须改走 `WebAuth.isWhitelisted(...)` 白名单
 *    —— 那要动鉴权层，属后续项（此处留痕，**不静默偏离**）。
 *    该端点**只出元数据**（状态 / 版本 / 体积 / sha256 / 通道名 / 兼容位），不含令牌、路径与用户数据。
 * 2. `POST /uploadConsoleZip` 定级 [Level.ADMIN]（spec 4.6 / 一期 design §1.2 路由级别表）。
 */
object ConsoleRoutes {

    val routes: Array<ApiRoute> = arrayOf(
        // REQ-3-601 / REQ-3-604：控制台安装状态（未装 / 已装 / 有新版三态；本期只出「未装态」骨架）
        ApiRoute(Method.GET, "/consoleStatus", Level.READONLY) { _ ->
            ReturnData().setData(
                mapOf<String, Any?>(
                    "state" to "NOT_INSTALLED",
                    "installed" to null,
                    "latest" to null,
                    "channel" to "none",
                    "previousAvailable" to false,
                    "minAppApiLevelOk" to true,
                )
            )
        },

        // REQ-3-602 / REQ-3-603：本地上传 zip 安装（桩，四期实现）
        ApiRoute(Method.POST, "/uploadConsoleZip", Level.ADMIN) { _ ->
            ReturnData().setData(
                mapOf(
                    "ok" to false,
                    "error" to "四期实现",
                )
            )
        },
    )
}