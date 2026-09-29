package io.legado.app.web.api.routes

import fi.iki.elonen.NanoHTTPD.Method
import io.legado.app.api.ReturnData
import io.legado.app.help.web.ConsoleInstaller
import io.legado.app.help.web.ConsoleInstaller.Channel
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import io.legado.app.web.TokenManager.Level
import io.legado.app.web.api.ApiRoute
import java.io.File

/**
 * F 组 · 控制台分发路由（web-mcp-productization 三期定契约 · 四期接真实实现）。
 *
 * **三期 → 四期的关系**：三期把三个端点的**契约**先定下来（`ConsoleStatus` 字段骨架 + `{ok,error}` 形状），
 * 让 boot 页与设置页能先对接；四期把执行体从"桩"换成 [ConsoleInstaller]（三通道探测 / Ed25519 验签 /
 * sha256 比对 / 原子安装 + 回滚）。**契约未变**，故前端零改动。
 *
 * 鉴权口径（REQ-3-604 的落地取舍，**留痕不静默偏离**）：`/consoleStatus` 定为 [Level.READONLY] 而非
 * spec 设想的"免令牌" —— 一期契约硬断言「路由级别不得为 NONE」（`ApiRegistryTest`），免令牌须改走
 * `WebAuth.isWhitelisted(...)` 白名单（要动鉴权层，属后续项）。该端点只出元数据，不含令牌/路径/用户数据。
 *
 * **安全**：`/consoleInstall` 与 `/uploadConsoleZip` 均 [Level.ADMIN]；安装链的验签/完整性/穿越防护
 * 全部在 [ConsoleInstaller] 内闭环，本层只做**取参 + 委派 + 结果信封化**，不重复也不绕过校验。
 */
object ConsoleRoutes {

    val routes: Array<ApiRoute> = arrayOf(
        // REQ-3-601 / REQ-4-107：控制台安装状态（未装 / 已装 / 有新版 + 可回退位）
        ApiRoute(Method.GET, "/consoleStatus", Level.READONLY) { _ ->
            ReturnData().setData(ConsoleInstaller.status())
        },

        // REQ-4-108：按通道安装（`channel` ∈ relay / github；缺省按 status() 的探测结果）
        ApiRoute(Method.POST, "/consoleInstall", Level.ADMIN) { ctx ->
            val raw = channelOf(ctx.postData) ?: ctx.param("channel")
            val channel = when (raw?.trim()?.lowercase()) {
                "relay" -> Channel.RELAY
                "github" -> Channel.GITHUB
                null, "" -> ConsoleInstaller.status().channel
                else -> throw IllegalArgumentException("未知 channel：$raw（支持 relay / github）")
            }
            ReturnData().setData(
                ConsoleInstaller.install(channel).fold(
                    onSuccess = { mapOf("ok" to true, "channel" to channel.name) },
                    onFailure = { mapOf("ok" to false, "channel" to channel.name, "error" to it.message) },
                )
            )
        },

        // REQ-4-109：本地上传 zip 安装（multipart，字段名 `file`；ADMIN）
        ApiRoute(Method.POST, "/uploadConsoleZip", Level.ADMIN) { ctx ->
            // NanoHTTPD 的 files 值即**临时文件路径**（ConsoleInstaller 直接以 File 处理）
            val tmpPath = ctx.requireFile("file")
            ReturnData().setData(
                ConsoleInstaller.uploadFromFile(File(tmpPath), confirm = true).fold(
                    onSuccess = { mapOf("ok" to true) },
                    onFailure = { mapOf("ok" to false, "error" to it.message) },
                )
            )
        },
    )

    /** 从 JSON 体里取 `channel` 字段（体可能为空或是表单式 ⇒ 返回 null 让调用方回落到 query 参数）。 */
    private fun channelOf(postData: String?): String? {
        val body = postData?.takeIf { it.isNotBlank() } ?: return null
        if (!body.trimStart().startsWith("{")) return null
        return runCatching {
            GSON.fromJsonObject<Map<String, String>>(body).getOrNull()?.get("channel")
        }.getOrNull()
    }
}