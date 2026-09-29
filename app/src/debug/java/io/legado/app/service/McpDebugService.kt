package io.legado.app.service

import android.content.Context
import android.content.Intent
import io.legado.app.base.BaseService
import io.legado.app.constant.AppLog
import io.legado.app.web.HttpServer
import io.legado.app.web.mcp.McpToolCatalog
import splitties.init.appCtx

/**
 * MCP **内腿**调试服务（web-mcp-productization 二期 · tasks 5.1 / REQ-2-401）。
 *
 * **仅 debug 变体存在**（本文件位于 `src/debug/`）⇒ 开发者 AI 自查面与产品对外面
 * （1122 / 主 `WebService`）**物理隔离**（与 L3 工具同一隔离哲学，见门禁 G-24）。
 *
 * **端口 8765**（REQ-2-402）。接入：`adb reverse tcp:8765 tcp:8765` 后电脑端访问
 * `http://localhost:8765/mcp`。
 *
 * **鉴权复用主体系**：本服务复用主 `WebService` 的 `HttpServer` 实现 ⇒ 同一个
 * `ApiRegistry`（`/mcp` 已在册，`HttpServer.kt` 零改动）与同一套 `WebAuth` 第一闸
 * ⇒ 不新增鉴权分支、不新增第四端口（AD-03）。
 *
 * **已知上限（如实标注，REQ-2-406 的后续项）**：`~/.legado-mcp-token` 这类「本机固定 token
 * 落盘」尚未实现 —— 当前开发者 AI 用 App 内生成的主令牌即可连通 `localhost:8765/mcp`。
 * 升级路径：在 `WebAuth` 增加"本机回环地址 + 文件令牌"支路（需与主令牌体系一并设计，避免第二套鉴权）。
 */
class McpDebugService : BaseService() {

    private var httpServer: HttpServer? = null

    override fun onCreate() {
        super.onCreate()
        startDebugServer()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        startDebugServer()
        return START_STICKY
    }

    override fun onDestroy() {
        stopDebugServer()
        super.onDestroy()
    }

    private fun startDebugServer() {
        if (isRun) return
        try {
            // 与主 WebService 同款实现：NanoHTTPD + ApiRegistry 查表 + WebAuth 鉴权
            val server = HttpServer(PORT)
            server.start(SOCKET_READ_TIMEOUT_MS, false)
            httpServer = server
            isRun = true
            AppLog.putDebugWithTag(
                TAG,
                "MCP 内腿服务已启动：$listenAddress（工具数=${McpToolCatalog.all().size}）",
                level = AppLog.Level.INFO,
            )
        } catch (e: Throwable) {
            AppLog.putError("MCP 内腿服务 $PORT 启动失败", e)
            isRun = false
        }
    }

    private fun stopDebugServer() {
        runCatching { httpServer?.stop() }
        httpServer = null
        isRun = false
    }

    companion object {
        /** 内腿端口（REQ-2-402）。 */
        const val PORT = 8765

        private const val TAG = "McpDebugService"
        private const val SOCKET_READ_TIMEOUT_MS = 10_000

        @Volatile
        var isRun = false
            private set

        /** 监听地址（供日志/调试展示，不含凭据）。 */
        val listenAddress: String get() = "http://127.0.0.1:$PORT/mcp"

        /** 幂等启动（已运行则直接返回）。 */
        fun start(context: Context = appCtx) {
            if (isRun) return
            try {
                context.startService(Intent(context, McpDebugService::class.java))
            } catch (e: Throwable) {
                AppLog.putError("MCP 内腿服务启动失败", e)
            }
        }

        /** 停止服务。 */
        fun stop(context: Context = appCtx) {
            context.stopService(Intent(context, McpDebugService::class.java))
        }
    }
}