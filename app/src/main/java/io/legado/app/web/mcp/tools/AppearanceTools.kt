package io.legado.app.web.mcp.tools

import com.google.gson.JsonElement
import io.legado.app.help.config.BubblePackageManager
import io.legado.app.help.config.NavigationBarIconConfig
import io.legado.app.help.config.TopBarConfig
import io.legado.app.model.BookCover
import io.legado.app.service.kernel.AppearanceKernel
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import io.legado.app.web.TokenManager
import io.legado.app.web.mcp.MCP_DOMAIN_APPEARANCE
import io.legado.app.web.mcp.McpJsonSchema
import io.legado.app.web.mcp.McpJsonSchema.TYPE_ARRAY
import io.legado.app.web.mcp.McpJsonSchema.TYPE_BOOLEAN
import io.legado.app.web.mcp.McpJsonSchema.TYPE_OBJECT
import io.legado.app.web.mcp.McpJsonSchema.TYPE_STRING
import io.legado.app.web.mcp.McpParamException
import io.legado.app.web.mcp.McpTool

/**
 * ㉑ 外观资源域工具声明（web-mcp-productization 二期 · tasks 2.24）。
 *
 * 红线（AD-10）：`invoke` **只调 `service/kernel`**（[AppearanceKernel]），不内联业务、不 import 控制器层。
 * 红线（AD-17）：本域**只暴露「数据定义源」**（主题包 / 应用套件 / 顶栏包 / 底栏包 / 气泡模板 /
 * 分享模板 / 封面图集 / 书籍展示侧封面规则）。**不声明任何渲染实现**（状态栏落地、沉浸开关、启动图标、
 * Compose 渲染骨架、主题刷新链路、气泡渲染消费点）。
 *
 * 与内核的口径差（**已核实，非笔误**）：[AppearanceKernel] 当前公开 **16** 个方法；本文件 **18** 个工具中的
 * `ai_theme_generate` / `ai_theme_preview` **内核暂无对应方法**，故降级为回读主题包定义源
 * （`themePackList`）并在返回值中以 `degraded` + `note` 显式说明"生成 / 预览能力尚未落地"。
 * 其余 16 个工具与内核方法一一对应（工具名 `appearance_kit_*` 对应内核 `kitList` / `kitSave`）。
 *
 * 危险标记口径：内核 KDoc「已知上限」列出 `themePackInstall` / `kitSave`（import）/ `bookInfoLayoutSave`
 * 为写盘 / 导入类操作，危险属性由工具层标记 —— 此处按"涉及导入 / 删除 / 写入全局规则"的保守口径标注。
 */
object AppearanceTools {

    val tools: List<McpTool> = listOf(
        McpTool(
            name = "theme_pack_list",
            title = "主题包列表",
            description = "读取已安装的主题包（本地 + 内置）。返回 isNight/total/items 数组" +
                "（dirName/name/isNightTheme/source/updatedAt 等字段）。",
            domain = MCP_DOMAIN_APPEARANCE,
            level = TokenManager.Level.READONLY,
            readOnlyHint = true,
            inputSchema = McpJsonSchema.empty(),
        ) { _ ->
            AppearanceKernel.themePackList()
        },

        McpTool(
            name = "theme_pack_install",
            title = "安装主题包",
            description = "按设备上的主题包文件路径导入并解包，安装后主题即出现在主题包列表。" +
                "**安装/启用主题包属破坏性操作，需端侧确认**。返回 installed/sourceName/themeCount/" +
                "navigationBarCount/coverCollectionCount。",
            domain = MCP_DOMAIN_APPEARANCE,
            level = TokenManager.Level.ADMIN,
            dangerous = true,
            inputSchema = McpJsonSchema.singleRequiredString("filePath", "设备上的主题包文件绝对路径"),
        ) { args ->
            AppearanceKernel.themePackInstall(args.requireStr("filePath"))
        },

        McpTool(
            name = "ai_theme_generate",
            title = "AI 生成主题包",
            description = "AI 生成主题包（自然语言 / 参考图 / 配色偏好 → 主题包 JSON）。" +
                "**当前内核未提供生成能力，本工具降级**：回读现有主题包定义源（含云端主题池）并以 " +
                "degraded/note 显式说明；可选入参 prompt/palette/reference 仅作请求回显。",
            domain = MCP_DOMAIN_APPEARANCE,
            level = TokenManager.Level.MANAGE,
            inputSchema = McpJsonSchema.of(
                optional = mapOf(
                    "prompt" to TYPE_STRING,
                    "palette" to TYPE_ARRAY,
                    "reference" to TYPE_STRING,
                ),
                descriptions = mapOf(
                    "prompt" to "自然语言描述（如「暖色护眼阅读风」）",
                    "palette" to "配色偏好（十六进制色值数组）",
                    "reference" to "参考图 URL",
                )
            ),
        ) { args ->
            val request = mapOf(
                "prompt" to args.str("prompt"),
                "palette" to args.strList("palette"),
                "reference" to args.str("reference"),
            )
            mapOf(
                "generated" to false,
                "degraded" to true,
                "note" to "当前内核未提供主题包生成能力；本工具降级为回读主题包定义源（含云端主题池），" +
                    "请求未被执行，待内核 / AI Provider 基建落地后补齐",
                "request" to request,
                "themePool" to AppearanceKernel.themePackList(includeRemote = true),
            )
        },

        McpTool(
            name = "ai_theme_preview",
            title = "主题预览",
            description = "生成主题预览（预览图 / 色板卡）。**当前内核未提供预览渲染能力，本工具降级**：" +
                "回读本地主题包定义源并以 degraded/note 显式说明；不返回任何渲染结果。",
            domain = MCP_DOMAIN_APPEARANCE,
            level = TokenManager.Level.READONLY,
            readOnlyHint = true,
            inputSchema = McpJsonSchema.empty(),
        ) { _ ->
            mapOf(
                "previewed" to false,
                "degraded" to true,
                "note" to "当前内核未提供主题预览渲染能力；本工具降级为回读本地主题包定义源，" +
                    "不生成预览图 / 色板卡",
                "themes" to AppearanceKernel.themePackList(includeRemote = false),
            )
        },

        McpTool(
            name = "appearance_kit_list",
            title = "外观套件列表",
            description = "读取外观套件（内置 + 已导入）与当前生效套件。" +
                "返回 currentKitId/builtin/imported 数组（含 id/name/summary/type/current）。",
            domain = MCP_DOMAIN_APPEARANCE,
            level = TokenManager.Level.READONLY,
            readOnlyHint = true,
            inputSchema = McpJsonSchema.empty(),
        ) { _ ->
            AppearanceKernel.kitList()
        },

        McpTool(
            name = "appearance_kit_save",
            title = "保存外观套件",
            description = "外观套件操作。action 取值：import（需 filePath，导入套件包）、" +
                "create（需 name，从当前外观创建）、rename（需 kitId + name）、delete（需 kitId，删除已导入套件）。" +
                "**导入 / 删除属写盘类破坏性操作，需端侧确认**。返回结果随 action 而异。",
            domain = MCP_DOMAIN_APPEARANCE,
            level = TokenManager.Level.MANAGE,
            dangerous = true,
            inputSchema = McpJsonSchema.of(
                required = mapOf("action" to TYPE_STRING),
                optional = mapOf(
                    "filePath" to TYPE_STRING,
                    "kitId" to TYPE_STRING,
                    "name" to TYPE_STRING,
                ),
                descriptions = mapOf(
                    "action" to "import/create/rename/delete",
                    "filePath" to "import 的套件包文件绝对路径",
                    "kitId" to "rename/delete 的目标套件 id",
                    "name" to "create/rename 的套件名",
                )
            ),
        ) { args ->
            AppearanceKernel.kitSave(
                action = args.requireStr("action"),
                filePath = args.str("filePath"),
                kitId = args.str("kitId"),
                name = args.str("name"),
            )
        },

        McpTool(
            name = "top_bar_pack_list",
            title = "顶栏包列表",
            description = "读取顶栏包列表（active 为当前生效）。" +
                "返回 isNight/activeDirName/total/items 数组（dirName/name/isNightMode/style/active 等）。",
            domain = MCP_DOMAIN_APPEARANCE,
            level = TokenManager.Level.READONLY,
            readOnlyHint = true,
            inputSchema = McpJsonSchema.empty(),
        ) { _ ->
            AppearanceKernel.topBarPackList()
        },

        McpTool(
            name = "top_bar_pack_save",
            title = "保存顶栏包",
            description = "新增 / 更新顶栏包（oldDirName 给定时按原目录覆盖，否则新建）。" +
                "入参 config 可以是顶栏包配置对象或 JSON 串。返回 saved/dirName/name/isNightMode。",
            domain = MCP_DOMAIN_APPEARANCE,
            level = TokenManager.Level.MANAGE,
            inputSchema = McpJsonSchema.of(
                required = mapOf("config" to TYPE_OBJECT),
                optional = mapOf("oldDirName" to TYPE_STRING),
                descriptions = mapOf(
                    "config" to "顶栏包配置对象（TopBarConfig.Config 字段子集；name 必填）",
                    "oldDirName" to "按原目录覆盖时的目录名（省略 = 新建）",
                )
            ),
        ) { args ->
            val config = parseModel<TopBarConfig.Config>(args.raw().get("config"))
                ?: throw McpParamException("参数 config 格式不对（须为顶栏包配置对象或 JSON 字符串）")
            AppearanceKernel.topBarPackSave(config, args.str("oldDirName"))
        },

        McpTool(
            name = "nav_bar_pack_list",
            title = "底栏包列表",
            description = "读取底栏（导航栏）包列表（active 为当前生效）。" +
                "返回 isNight/activeDirName/total/items 数组（dirName/name/isNightMode/layoutMode/effectMode/active 等）。",
            domain = MCP_DOMAIN_APPEARANCE,
            level = TokenManager.Level.READONLY,
            readOnlyHint = true,
            inputSchema = McpJsonSchema.empty(),
        ) { _ ->
            AppearanceKernel.navBarPackList()
        },

        McpTool(
            name = "nav_bar_pack_save",
            title = "保存底栏包",
            description = "新增 / 更新底栏（导航栏）包（oldDirName 给定时按原目录覆盖，否则新建）。" +
                "入参 config 可以是底栏包配置对象或 JSON 串。返回 saved/dirName/name/isNightMode。",
            domain = MCP_DOMAIN_APPEARANCE,
            level = TokenManager.Level.MANAGE,
            inputSchema = McpJsonSchema.of(
                required = mapOf("config" to TYPE_OBJECT),
                optional = mapOf("oldDirName" to TYPE_STRING),
                descriptions = mapOf(
                    "config" to "底栏包配置对象（NavigationBarIconConfig.Config 字段子集；name 必填）",
                    "oldDirName" to "按原目录覆盖时的目录名（省略 = 新建）",
                )
            ),
        ) { args ->
            val config = parseModel<NavigationBarIconConfig.Config>(args.raw().get("config"))
                ?: throw McpParamException("参数 config 格式不对（须为底栏包配置对象或 JSON 字符串）")
            AppearanceKernel.navBarPackSave(config, args.str("oldDirName"))
        },

        McpTool(
            name = "bubble_template_list",
            title = "气泡模板列表",
            description = "读取段落气泡模板列表（含内置，active 为当前生效）。" +
                "返回 activeDirName/total/items 数组（dirName/name/sizeScale/source/active 等）。",
            domain = MCP_DOMAIN_APPEARANCE,
            level = TokenManager.Level.READONLY,
            readOnlyHint = true,
            inputSchema = McpJsonSchema.empty(),
        ) { _ ->
            AppearanceKernel.bubbleTemplateList()
        },

        McpTool(
            name = "bubble_template_save",
            title = "保存气泡模板",
            description = "新增 / 更新气泡模板（oldDirName 给定时按原目录覆盖，否则新建；内置模板不可覆盖）。" +
                "入参 config 可以是气泡模板配置对象或 JSON 串。返回 saved/dirName/name。",
            domain = MCP_DOMAIN_APPEARANCE,
            level = TokenManager.Level.MANAGE,
            inputSchema = McpJsonSchema.of(
                required = mapOf("config" to TYPE_OBJECT),
                optional = mapOf("oldDirName" to TYPE_STRING),
                descriptions = mapOf(
                    "config" to "气泡模板配置对象（BubblePackageManager.Config 字段子集；name 必填）",
                    "oldDirName" to "按原目录覆盖时的目录名（省略 = 新建）",
                )
            ),
        ) { args ->
            val config = parseModel<BubblePackageManager.Config>(args.raw().get("config"))
                ?: throw McpParamException("参数 config 格式不对（须为气泡模板配置对象或 JSON 字符串）")
            AppearanceKernel.bubbleTemplateSave(config, args.str("oldDirName"))
        },

        McpTool(
            name = "share_template_list",
            title = "分享模板列表",
            description = "读取分享便签模板列表（含内置，active 为当前生效）。" +
                "返回 activeDirName/total/items 数组（dirName/name/canvas/width/height/output/active 等）。",
            domain = MCP_DOMAIN_APPEARANCE,
            level = TokenManager.Level.READONLY,
            readOnlyHint = true,
            inputSchema = McpJsonSchema.empty(),
        ) { _ ->
            AppearanceKernel.shareTemplateList()
        },

        McpTool(
            name = "share_template_save",
            title = "保存分享模板",
            description = "新增 / 更新分享便签模板（传 HTML，元信息由内核解析；oldDirName 给定时按原目录覆盖）。" +
                "返回 saved/dirName/name。",
            domain = MCP_DOMAIN_APPEARANCE,
            level = TokenManager.Level.MANAGE,
            inputSchema = McpJsonSchema.of(
                required = mapOf("html" to TYPE_STRING),
                optional = mapOf("oldDirName" to TYPE_STRING),
                descriptions = mapOf(
                    "html" to "分享模板 HTML 全文",
                    "oldDirName" to "按原目录覆盖时的目录名（省略 = 新建）",
                )
            ),
        ) { args ->
            AppearanceKernel.shareTemplateSave(args.requireStr("html"), args.str("oldDirName"))
        },

        McpTool(
            name = "cover_collection_list",
            title = "封面图集列表",
            description = "读取封面图集列表（selected 为当前生效）。" +
                "返回 isNight/selectedId/total/items 数组（id/name/dirName/mode/imageCount/selected 等）。",
            domain = MCP_DOMAIN_APPEARANCE,
            level = TokenManager.Level.READONLY,
            readOnlyHint = true,
            inputSchema = McpJsonSchema.empty(),
        ) { _ ->
            AppearanceKernel.coverCollectionList()
        },

        McpTool(
            name = "cover_collection_save",
            title = "保存封面图集",
            description = "封面图集操作。action 取值：create（需 name，新建图集）、rename（需 id + name）、" +
                "select（需 id，切换当前图集）、delete（需 id，删除图集，**破坏性操作**）。" +
                "可选 isNight 指定日夜图集（省略 = 内核默认当前日夜）。",
            domain = MCP_DOMAIN_APPEARANCE,
            level = TokenManager.Level.MANAGE,
            inputSchema = McpJsonSchema.of(
                required = mapOf("action" to TYPE_STRING),
                optional = mapOf("id" to TYPE_STRING, "name" to TYPE_STRING, "isNight" to TYPE_BOOLEAN),
                descriptions = mapOf(
                    "action" to "create/rename/select/delete",
                    "id" to "rename/select/delete 的目标图集 id",
                    "name" to "create/rename 的图集名",
                    "isNight" to "是否夜间图集（省略 = 当前日夜）",
                )
            ),
        ) { args ->
            val action = args.requireStr("action")
            val id = args.str("id")
            val name = args.str("name")
            if (args.raw().has("isNight")) {
                AppearanceKernel.coverCollectionSave(action, id, name, args.bool("isNight"))
            } else {
                AppearanceKernel.coverCollectionSave(action, id, name)
            }
        },

        McpTool(
            name = "book_info_layout_get",
            title = "读取书籍展示侧封面规则",
            description = "读取书籍展示侧定义源（全局 BookCover.CoverRule；bookUrl 用于定位书籍上下文）。" +
                "返回 bookUrl/scope/enable/searchUrl/coverRule/concurrentRate/enabledCookieJar。",
            domain = MCP_DOMAIN_APPEARANCE,
            level = TokenManager.Level.READONLY,
            readOnlyHint = true,
            inputSchema = McpJsonSchema.singleRequiredString("bookUrl", "书籍唯一地址（定位书籍上下文）"),
        ) { args ->
            AppearanceKernel.bookInfoLayoutGet(args.requireStr("bookUrl"))
        },

        McpTool(
            name = "book_info_layout_save",
            title = "保存书籍展示侧封面规则",
            description = "保存书籍展示侧定义源（全局 BookCover.CoverRule，**非按书**；写盘类操作）。" +
                "入参 config 可以是封面规则对象或 JSON 串。返回 saved/scope。",
            domain = MCP_DOMAIN_APPEARANCE,
            level = TokenManager.Level.MANAGE,
            inputSchema = McpJsonSchema.of(
                required = mapOf("config" to TYPE_OBJECT),
                descriptions = mapOf("config" to "封面规则对象（BookCover.CoverRule 字段子集；searchUrl/coverRule 必填）"),
            ),
        ) { args ->
            val config = parseModel<BookCover.CoverRule>(args.raw().get("config"))
                ?: throw McpParamException("参数 config 格式不对（须为封面规则对象或 JSON 字符串）")
            AppearanceKernel.bookInfoLayoutSave(config)
        },
    )

    /** 与 REST 门面同口径：复杂入参 `config` 既接受对象也接受 JSON 字符串。 */
    private inline fun <reified T> parseModel(element: JsonElement?): T? {
        if (element == null || element.isJsonNull) return null
        val json = if (element.isJsonPrimitive) element.asString else GSON.toJson(element)
        return GSON.fromJsonObject<T>(json).getOrNull()
    }
}