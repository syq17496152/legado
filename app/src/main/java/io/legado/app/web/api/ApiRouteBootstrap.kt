package io.legado.app.web.api

import io.legado.app.web.api.routes.AiRoutes
import io.legado.app.web.api.routes.AppearanceRoutes
import io.legado.app.web.api.routes.AppSettingsRoutes
import io.legado.app.web.api.routes.BackupRoutes
import io.legado.app.web.api.routes.BookRoutes
import io.legado.app.web.api.routes.BookshelfRoutes
import io.legado.app.web.api.routes.CacheTaskRoutes
import io.legado.app.web.api.routes.CharacterRoutes
import io.legado.app.web.api.routes.ConsoleRoutes
import io.legado.app.web.api.routes.ContentRoutes
import io.legado.app.web.api.routes.DebugRoutes
import io.legado.app.web.api.routes.DiscoverRoutes
import io.legado.app.web.api.routes.LogRoutes
import io.legado.app.web.api.routes.MangaRoutes
import io.legado.app.web.api.routes.McpRoutes
import io.legado.app.web.api.routes.RssRoutes
import io.legado.app.web.api.routes.RuleRoutes
import io.legado.app.web.api.routes.SettingsRoutes
import io.legado.app.web.api.routes.SourceRoutes
import io.legado.app.web.api.routes.StorageRoutes
import io.legado.app.web.api.routes.TtsRoutes
import io.legado.app.web.api.routes.VideoRoutes

/**
 * 路由安装入口（web-mcp-productization 一期 · 5.10）。
 *
 * **跨期扩展的唯一收口点**：新增端点 = ① Kernel 加 `suspend fun` ② 在 `routes/` 下声明一行
 * ③（新域时）在本文件追加一行组 —— **`HttpServer.kt` 零改动**（SC-1-14 / REQ-1-505）。
 *
 * 三期新增的 6 组路由（Debug / Log / Tts / Content / Console / Settings）挂到此处即可。
 */
object ApiRouteBootstrap {

    @Volatile
    private var installed = false

    /**
     * 安装全部路由（**幂等**）：重复调用不会重复注册（`ApiRegistry.register` 对重复键会抛异常，
     * 故这里必须自守，否则二次调用即崩）。
     */
    @Synchronized
    fun install() {
        if (installed) return
        ApiRegistry.registerAll(
            *BookRoutes.routes,
            *SourceRoutes.routes,
            *RuleRoutes.routes,
            *BackupRoutes.routes,
            // 二期：MCP 传输入口（3 条：POST/GET/DELETE 同路径）。**不是新服务**，是一条注册路由，
            // 故 HttpServer.kt 零改动（REQ-2-107 / SC-2-16）。
            *McpRoutes.routes,
            // 三期 §1：后端端点 13 组（B/C/D/E/G/N/O/M/L/AI/外观/缓存/F）。每端点 = Kernel 方法 + 此行一行，
            // `HttpServer.kt` 仍零改动（门禁 G-21）。
            *DebugRoutes.routes,
            *LogRoutes.routes,
            *TtsRoutes.routes,
            *ContentRoutes.routes,
            *MangaRoutes.routes,
            *VideoRoutes.routes,
            *DiscoverRoutes.routes,
            *CharacterRoutes.routes,
            *StorageRoutes.routes,
            *AiRoutes.routes,
            *AppearanceRoutes.routes,
            *CacheTaskRoutes.routes,
            *BookshelfRoutes.routes,
            *SettingsRoutes.routes,
            *ConsoleRoutes.routes,
            // 第 5 轮 UX/IA 重构（IF-20 修复）：订阅管理补全 9 条（收藏/分组/OPML/导入/文章详情）
            // —— 内核与 MCP 工具早已存在，本轮只补 REST 投影；`HttpServer.kt` 仍零改动。
            *RssRoutes.routes,
            // 第 5 轮 UX/IA 重构（IF-22 修复）：应用设置域补全 4 条（应用偏好读/写、设置检索、主题模式）
            // —— 同上，内核与 MCP 工具早已存在，此处只补 REST 投影；`HttpServer.kt` 仍零改动。
            *AppSettingsRoutes.routes,
        )
        installed = true
    }

    /** 是否已安装（单测断言用）。 */
    val isInstalled: Boolean get() = installed

    /**
     * 复位为"未安装"并清空注册表（**仅供单测**）。
     *
     * 存在理由：`install()` 的幂等靠 [installed] 标志位，而单测共享同一 JVM ⇒
     * 需要能把两者一起回到初态，才能反复验证「安装 28 条」与「幂等」。
     */
    internal fun resetForTest() {
        ApiRegistry.clearForTest()
        installed = false
    }
}
