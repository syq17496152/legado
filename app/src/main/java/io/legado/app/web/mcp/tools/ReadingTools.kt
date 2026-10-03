package io.legado.app.web.mcp.tools

import io.legado.app.service.kernel.BookKernel
import io.legado.app.service.kernel.BookshelfKernel
import io.legado.app.service.kernel.ContentKernel
import io.legado.app.web.TokenManager
import io.legado.app.web.mcp.MCP_DOMAIN_READING
import io.legado.app.web.mcp.McpJsonSchema
import io.legado.app.web.mcp.McpJsonSchema.TYPE_BOOLEAN
import io.legado.app.web.mcp.McpJsonSchema.TYPE_INTEGER
import io.legado.app.web.mcp.McpJsonSchema.TYPE_STRING
import io.legado.app.web.mcp.McpTool

/**
 * ② 阅读域工具声明（web-mcp-productization 二期 · tasks 2.9）。
 *
 * 红线（AD-10）：`invoke` **只调 `service/kernel`**；阅读配置与 REST `/getReadConfig` 同源
 * （保证 MCP ↔ REST 对拍一致，REQ-2-205）。
 */
object ReadingTools {

    val tools: List<McpTool> = listOf(
        McpTool(
            name = "book_get_chapters",
            title = "书籍目录",
            description = "读取指定书籍的已落库目录（章节列表）并落库。返回章节数组：" +
                "index/title/url/start/end…；空数组表示尚未拉取过目录，可先调 book_refresh_toc。",
            domain = MCP_DOMAIN_READING,
            level = TokenManager.Level.READONLY,
            readOnlyHint = true,
            inputSchema = McpJsonSchema.singleRequiredString("bookUrl", "书籍唯一地址"),
        ) { args ->
            BookKernel.chapterList(args.requireStr("bookUrl"))
        },

        McpTool(
            name = "book_refresh_toc",
            title = "刷新目录",
            description = "从书源重新拉取目录并落库，返回刷新后的章节列表（写操作）。书籍不存在或书源缺失时返回结构化错误。",
            domain = MCP_DOMAIN_READING,
            level = TokenManager.Level.MANAGE,
            inputSchema = McpJsonSchema.singleRequiredString("bookUrl", "书籍唯一地址"),
        ) { args ->
            BookKernel.refreshToc(args.requireStr("bookUrl"))
        },

        McpTool(
            name = "book_get_content",
            title = "章节正文",
            description = "读取指定章节正文（已过替换规则 / 简繁 / 重新分段的内容处理链）。" +
                "可用 offset+length 分页取长章节；返回 null 表示书籍或章节不存在。",
            domain = MCP_DOMAIN_READING,
            level = TokenManager.Level.READONLY,
            readOnlyHint = true,
            inputSchema = McpJsonSchema.of(
                required = mapOf("bookUrl" to TYPE_STRING, "chapterIndex" to TYPE_INTEGER),
                optional = mapOf("offset" to TYPE_INTEGER, "length" to TYPE_INTEGER),
                descriptions = mapOf(
                    "bookUrl" to "书籍唯一地址",
                    "chapterIndex" to "章节序号（从 0 开始）",
                    "offset" to "起始字符偏移（默认 0，即整章）",
                    "length" to "取多少字符（默认 -1 = 不限）"
                )
            ),
        ) { args ->
            BookKernel.bookContent(
                bookUrl = args.requireStr("bookUrl"),
                index = args.requireInt("chapterIndex"),
                offset = args.int("offset", 0),
                length = args.int("length", BookKernel.CONTENT_NO_LIMIT)
            )
        },

        McpTool(
            name = "book_save_progress",
            title = "保存阅读进度",
            description = "保存书籍阅读进度（章节序号 + 章内位置）并同步到云端。返回 false 表示书不在书架。",
            domain = MCP_DOMAIN_READING,
            level = TokenManager.Level.MANAGE,
            inputSchema = McpJsonSchema.of(
                required = mapOf("bookUrl" to TYPE_STRING, "chapterIndex" to TYPE_INTEGER),
                optional = mapOf("chapterPos" to TYPE_INTEGER, "chapterTitle" to TYPE_STRING),
                descriptions = mapOf(
                    "bookUrl" to "书籍唯一地址",
                    "chapterIndex" to "章节序号",
                    "chapterPos" to "章内字符位置（默认 0）",
                    "chapterTitle" to "章节标题（可选，省略沿用当前进度标题）",
                )
            ),
        ) { args ->
            ContentKernel.saveProgress(
                bookUrl = args.requireStr("bookUrl"),
                chapterIndex = args.requireInt("chapterIndex"),
                chapterPos = args.int("chapterPos", 0),
                chapterTitle = args.str("chapterTitle"),
            )
        },

        McpTool(
            name = "read_config_get",
            title = "读取阅读配置",
            description = "读取阅读配置 JSON（与 REST GET /getReadConfig **同源**，返回同一份配置串；未设置过返回 null）。",
            domain = MCP_DOMAIN_READING,
            level = TokenManager.Level.READONLY,
            readOnlyHint = true,
            inputSchema = McpJsonSchema.empty(),
        ) { _ ->
            ContentKernel.readConfigGet()
        },

        McpTool(
            name = "read_config_save",
            title = "保存阅读配置",
            description = "保存阅读配置 JSON（与 REST POST /saveReadConfig **同源**）。json 传空串表示清除。",
            domain = MCP_DOMAIN_READING,
            level = TokenManager.Level.MANAGE,
            inputSchema = McpJsonSchema.of(
                required = mapOf("json" to TYPE_STRING),
                descriptions = mapOf("json" to "阅读配置 JSON 串（空串 = 清除）"),
            ),
        ) { args ->
            ContentKernel.readConfigSave(args.requireStr("json").takeIf { it.isNotEmpty() })
            mapOf("saved" to true)
        },

        McpTool(
            name = "read_menu_buttons_get",
            title = "读取阅读菜单按钮",
            description = "读取阅读菜单自定义按钮：layout（两行按钮布局 JSON）+ buttons（自定义按钮实体数组）。",
            domain = MCP_DOMAIN_READING,
            level = TokenManager.Level.READONLY,
            readOnlyHint = true,
            inputSchema = McpJsonSchema.empty(),
        ) { _ ->
            ContentKernel.menuButtonsGet()
        },

        McpTool(
            name = "read_menu_buttons_save",
            title = "保存阅读菜单按钮",
            description = "保存阅读菜单布局与自定义按钮。layoutJson 覆盖布局偏好；buttonsJson 用 JSON 数组整体替换自定义按钮" +
                "（先删后插）。两者至少给一个。",
            domain = MCP_DOMAIN_READING,
            level = TokenManager.Level.MANAGE,
            inputSchema = McpJsonSchema.of(
                optional = mapOf("layoutJson" to TYPE_STRING, "buttonsJson" to TYPE_STRING),
                descriptions = mapOf(
                    "layoutJson" to "两行按钮布局 JSON（firstRow/secondRow）",
                    "buttonsJson" to "自定义按钮 JSON 数组（整体替换）",
                )
            ),
        ) { args ->
            val layout = args.str("layoutJson")
            val buttons = args.str("buttonsJson")
            if (layout == null && buttons == null) {
                throw io.legado.app.web.mcp.McpParamException("layoutJson 与 buttonsJson 至少给一个")
            }
            ContentKernel.menuButtonsSave(layout, buttons)
        },

        McpTool(
            name = "auto_read_config_get",
            title = "读取自动阅读设置",
            description = "读取自动阅读设置：autoReadSpeed（速度）/ autoReadMode（0 滚动 / 1 定时）。",
            domain = MCP_DOMAIN_READING,
            level = TokenManager.Level.READONLY,
            readOnlyHint = true,
            inputSchema = McpJsonSchema.empty(),
        ) { _ ->
            ContentKernel.autoReadConfigGet()
        },

        McpTool(
            name = "auto_read_config_save",
            title = "保存自动阅读设置",
            description = "保存自动阅读设置（只写传入项），返回保存后的完整设置。",
            domain = MCP_DOMAIN_READING,
            level = TokenManager.Level.MANAGE,
            inputSchema = McpJsonSchema.of(
                optional = mapOf("autoReadSpeed" to TYPE_INTEGER, "autoReadMode" to TYPE_INTEGER),
                descriptions = mapOf("autoReadSpeed" to "自动阅读速度", "autoReadMode" to "0 滚动 / 1 定时"),
            ),
        ) { args ->
            ContentKernel.autoReadConfigSave(
                speed = if (args.raw().has("autoReadSpeed")) args.int("autoReadSpeed") else null,
                mode = if (args.raw().has("autoReadMode")) args.int("autoReadMode") else null,
            )
        },

        McpTool(
            name = "book_change_source",
            title = "换源",
            description = "换源 / 自动换源：在目标书源内精确搜索同一本书 → 拉取新目录 → 迁移阅读进度 → 落库。" +
                "**只改书籍的 sourceUrl 与目录，不修改任何书源规则**。",
            domain = MCP_DOMAIN_READING,
            level = TokenManager.Level.MANAGE,
            inputSchema = McpJsonSchema.of(
                required = mapOf("bookUrl" to TYPE_STRING, "sourceUrl" to TYPE_STRING),
                optional = mapOf("keyword" to TYPE_STRING, "author" to TYPE_STRING),
                descriptions = mapOf(
                    "bookUrl" to "书籍唯一地址",
                    "sourceUrl" to "目标书源地址",
                    "keyword" to "搜索关键词（默认用当前书名）",
                    "author" to "作者（默认用当前书籍作者，用于精确匹配）",
                )
            ),
        ) { args ->
            ContentKernel.changeSource(
                bookUrl = args.requireStr("bookUrl"),
                sourceUrl = args.requireStr("sourceUrl"),
                keyword = args.str("keyword"),
                author = args.str("author"),
            )
        },

        McpTool(
            name = "book_search_for_change",
            title = "换源候选搜索",
            description = "换源前跨源搜同书：在各书源中精确搜索当前书籍，返回候选（源地址 / 源名 / 章节数 / 新 bookUrl）。",
            domain = MCP_DOMAIN_READING,
            level = TokenManager.Level.READONLY,
            readOnlyHint = true,
            inputSchema = McpJsonSchema.of(
                required = mapOf("bookUrl" to TYPE_STRING),
                optional = mapOf("sourceUrls" to McpJsonSchema.TYPE_ARRAY, "maxSources" to TYPE_INTEGER),
                descriptions = mapOf(
                    "bookUrl" to "书籍唯一地址",
                    "sourceUrls" to "候选书源地址数组（省略 = 全部启用源）",
                    "maxSources" to "最多搜索多少个源（默认 8）",
                )
            ),
        ) { args ->
            ContentKernel.searchForChange(
                bookUrl = args.requireStr("bookUrl"),
                sourceUrls = args.strList("sourceUrls"),
                maxSources = args.int("maxSources", 8),
            )
        },

        McpTool(
            name = "book_search_content",
            title = "书内全文搜索",
            description = "书内全文搜索：先查目录标题命中（0 网络），再按 maxChapters 逐章取正文匹配。" +
                "返回 titleHits（章节目录命中）+ contentHits（正文命中含片段）+ scanned/truncated。",
            domain = MCP_DOMAIN_READING,
            level = TokenManager.Level.READONLY,
            readOnlyHint = true,
            inputSchema = McpJsonSchema.of(
                required = mapOf("bookUrl" to TYPE_STRING, "key" to TYPE_STRING),
                optional = mapOf("maxChapters" to TYPE_INTEGER, "snippetLength" to TYPE_INTEGER),
                descriptions = mapOf(
                    "bookUrl" to "书籍唯一地址",
                    "key" to "搜索关键词",
                    "maxChapters" to "最多扫描章节数（默认 50，0 = 不限）",
                    "snippetLength" to "命中片段长度（默认 80，范围 20-500）",
                )
            ),
        ) { args ->
            ContentKernel.searchContent(
                bookUrl = args.requireStr("bookUrl"),
                key = args.requireStr("key"),
                maxChapters = args.int("maxChapters", 50),
                snippetLength = args.int("snippetLength", 80),
            )
        },

        McpTool(
            name = "book_refresh",
            title = "刷新书籍",
            description = "刷新书籍（三档）：mode=content 清当前章缓存后重取正文（强制走网络）；" +
                "mode=cache 清整书正文/图片缓存；mode=purify 对当前章重跑替换规则并覆写缓存。",
            domain = MCP_DOMAIN_READING,
            level = TokenManager.Level.MANAGE,
            inputSchema = McpJsonSchema.of(
                required = mapOf("bookUrl" to TYPE_STRING),
                optional = mapOf("mode" to TYPE_STRING, "chapterIndex" to TYPE_INTEGER),
                descriptions = mapOf(
                    "bookUrl" to "书籍唯一地址",
                    "mode" to "content / cache / purify（默认 content）",
                    "chapterIndex" to "章节序号（默认用当前阅读进度章节）",
                )
            ),
        ) { args ->
            ContentKernel.refresh(
                bookUrl = args.requireStr("bookUrl"),
                mode = args.str("mode")?.takeIf { it.isNotBlank() } ?: "content",
                chapterIndex = args.int("chapterIndex", -1),
            )
        },

        McpTool(
            name = "book_cloud_progress_get",
            title = "拉取云端进度",
            description = "拉取该书的云端（WebDAV）阅读进度；未配置云端或云端无记录时返回 null。",
            domain = MCP_DOMAIN_READING,
            level = TokenManager.Level.READONLY,
            readOnlyHint = true,
            inputSchema = McpJsonSchema.singleRequiredString("bookUrl", "书籍唯一地址"),
        ) { args ->
            ContentKernel.cloudProgressGet(args.requireStr("bookUrl"))
        },

        McpTool(
            name = "book_cloud_progress_save",
            title = "上传云端进度",
            description = "把当前书籍阅读进度上传到云端（覆盖），返回云端读回的进度对象。",
            domain = MCP_DOMAIN_READING,
            level = TokenManager.Level.MANAGE,
            inputSchema = McpJsonSchema.singleRequiredString("bookUrl", "书籍唯一地址"),
        ) { args ->
            ContentKernel.cloudProgressSave(args.requireStr("bookUrl"))
        },

        McpTool(
            name = "book_toc_update",
            title = "更新目录",
            description = "更新目录：从头重拉目录后可选**同题去重**与**反转**，重建序号并落库。返回处理后的章节列表。",
            domain = MCP_DOMAIN_READING,
            level = TokenManager.Level.MANAGE,
            inputSchema = McpJsonSchema.of(
                required = mapOf("bookUrl" to TYPE_STRING),
                optional = mapOf("deDuplicate" to TYPE_BOOLEAN, "reverse" to TYPE_BOOLEAN),
                descriptions = mapOf(
                    "bookUrl" to "书籍唯一地址",
                    "deDuplicate" to "是否同题去重（默认 false）",
                    "reverse" to "是否反转章节顺序（默认 false）",
                )
            ),
        ) { args ->
            ContentKernel.tocUpdate(
                bookUrl = args.requireStr("bookUrl"),
                deDuplicate = args.bool("deDuplicate"),
                reverse = args.bool("reverse"),
            )
        },

        McpTool(
            name = "book_purify",
            title = "净化正文",
            description = "按该书内容域的启用替换规则清洗给定文本（仅 isEnabled 且 scopeContent 的规则，按 order 升序）。" +
                "返回清洗后文本。",
            domain = MCP_DOMAIN_READING,
            level = TokenManager.Level.MANAGE,
            inputSchema = McpJsonSchema.of(
                required = mapOf("bookUrl" to TYPE_STRING, "text" to TYPE_STRING),
                descriptions = mapOf("bookUrl" to "书籍唯一地址（决定用哪套书内规则）", "text" to "待净化文本"),
            ),
        ) { args ->
            ContentKernel.purify(args.requireStr("bookUrl"), args.requireStr("text"))
        },

        McpTool(
            name = "epub_toc",
            title = "EPUB 目录",
            description = "读取本地书（含 EPUB）的目录。非本地书返回结构化错误。",
            domain = MCP_DOMAIN_READING,
            level = TokenManager.Level.READONLY,
            readOnlyHint = true,
            inputSchema = McpJsonSchema.singleRequiredString("bookUrl", "书籍唯一地址（本地书）"),
        ) { args ->
            ContentKernel.epubToc(args.requireStr("bookUrl"))
        },

        McpTool(
            name = "epub_content",
            title = "EPUB 正文",
            description = "读取本地书（含 EPUB）指定章节正文。非本地书返回结构化错误。",
            domain = MCP_DOMAIN_READING,
            level = TokenManager.Level.READONLY,
            readOnlyHint = true,
            inputSchema = McpJsonSchema.of(
                required = mapOf("bookUrl" to TYPE_STRING, "chapterIndex" to TYPE_INTEGER),
                descriptions = mapOf("bookUrl" to "书籍唯一地址（本地书）", "chapterIndex" to "章节序号"),
            ),
        ) { args ->
            ContentKernel.epubContent(args.requireStr("bookUrl"), args.requireInt("chapterIndex"))
        },

        McpTool(
            name = "book_delete",
            title = "删除书籍",
            description = "从书架删除书籍（admin）。deleteOriginal=true 时本地书连源文件一起删。" +
                "**危险操作**：批量 > 3 本需端侧确认。",
            domain = MCP_DOMAIN_READING,
            level = TokenManager.Level.ADMIN,
            dangerous = true,
            readOnlyHint = false,
            inputSchema = McpJsonSchema.of(
                required = mapOf("bookUrls" to McpJsonSchema.TYPE_ARRAY),
                optional = mapOf("deleteOriginal" to TYPE_BOOLEAN),
                descriptions = mapOf("bookUrls" to "书籍 bookUrl 数组", "deleteOriginal" to "本地书是否删除源文件（默认 false）"),
            ),
        ) { args ->
            BookshelfKernel.deleteBooks(args.strList("bookUrls"), args.bool("deleteOriginal"))
        },

        McpTool(
            name = "book_edit_content",
            title = "编辑章节正文",
            description = "手动改写指定章节正文并覆写缓存（阅读页「编辑内容」的同一写回链）。返回写入长度。",
            domain = MCP_DOMAIN_READING,
            level = TokenManager.Level.MANAGE,
            inputSchema = McpJsonSchema.of(
                required = mapOf("bookUrl" to TYPE_STRING, "chapterIndex" to TYPE_INTEGER, "content" to TYPE_STRING),
                descriptions = mapOf(
                    "bookUrl" to "书籍唯一地址",
                    "chapterIndex" to "章节序号",
                    "content" to "新正文内容（会覆盖该章缓存）",
                )
            ),
        ) { args ->
            ContentKernel.editContent(
                bookUrl = args.requireStr("bookUrl"),
                chapterIndex = args.requireInt("chapterIndex"),
                content = args.requireStr("content"),
            )
        },
    )
}
