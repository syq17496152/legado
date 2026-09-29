package io.legado.app.web.mcp.tools

import com.google.gson.JsonElement
import io.legado.app.data.entities.Book
import io.legado.app.service.kernel.BookKernel
import io.legado.app.service.kernel.BookshelfKernel
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import io.legado.app.web.TokenManager
import io.legado.app.web.mcp.MCP_DOMAIN_BOOKSHELF
import io.legado.app.web.mcp.McpJsonSchema
import io.legado.app.web.mcp.McpJsonSchema.TYPE_BOOLEAN
import io.legado.app.web.mcp.McpJsonSchema.TYPE_INTEGER
import io.legado.app.web.mcp.McpJsonSchema.TYPE_STRING
import io.legado.app.web.mcp.McpParamException
import io.legado.app.web.mcp.McpTool

/**
 * ① 书架与书籍域工具声明（web-mcp-productization 二期 · tasks 2.8）。
 *
 * 红线（AD-10）：`invoke` **只调 `service/kernel`**，不内联业务、不 import `api.controller`（REQ-2-305）。
 * 元数据按域单源声明；执行体一律调 Kernel（[BookshelfKernel] / [BookKernel]）。
 */
object BookshelfTools {

    val tools: List<McpTool> = listOf(
        McpTool(
            name = "bookshelf_list",
            title = "书架列表",
            description = "读取书架全部书籍（按 App 当前书架排序模式排序）。返回书籍数组：" +
                "bookUrl/name/author/origin/group/latestChapterTitle/durChapterIndex 等字段。",
            domain = MCP_DOMAIN_BOOKSHELF,
            level = TokenManager.Level.READONLY,
            readOnlyHint = true,
            inputSchema = McpJsonSchema.of(
                optional = mapOf("limit" to TYPE_INTEGER, "keyword" to TYPE_STRING),
                descriptions = mapOf(
                    "limit" to "最多返回条数（默认全部，0 或省略 = 不限）",
                    "keyword" to "按书名/作者模糊过滤（可选）"
                )
            ),
        ) { args ->
            val books = BookKernel.bookshelf()
            val keyword = args.str("keyword")?.trim().orEmpty()
            val filtered = if (keyword.isEmpty()) {
                books
            } else {
                books.filter { it.name.contains(keyword, true) || it.author.contains(keyword, true) }
            }
            val limit = args.int("limit", 0)
            if (limit > 0 && filtered.size > limit) {
                mapOf("total" to filtered.size, "truncated" to true, "books" to filtered.take(limit))
            } else {
                filtered
            }
        },

        McpTool(
            name = "bookshelf_search",
            title = "书架内搜索",
            description = "在**已加入书架**的书籍里按书名/作者搜索（不发起网络请求）。返回命中的书籍数组。",
            domain = MCP_DOMAIN_BOOKSHELF,
            level = TokenManager.Level.READONLY,
            readOnlyHint = true,
            inputSchema = McpJsonSchema.singleRequiredString("keyword", "搜索关键词（书名或作者）"),
        ) { args ->
            val keyword = args.requireStr("keyword")
            BookKernel.bookshelf().filter {
                it.name.contains(keyword, true) || it.author.contains(keyword, true)
            }
        },

        McpTool(
            name = "bookshelf_groups_get",
            title = "书架分组列表",
            description = "读取书架分组（含内置分组：全部/本地/音频/视频/未分组等）。返回分组数组：" +
                "groupId/groupName/show/bookSort/order。",
            domain = MCP_DOMAIN_BOOKSHELF,
            level = TokenManager.Level.READONLY,
            readOnlyHint = true,
            inputSchema = McpJsonSchema.empty(),
        ) { _ ->
            BookshelfKernel.groups()
        },

        McpTool(
            name = "bookshelf_group_save",
            title = "保存书架分组",
            description = "新增 / 重命名书架分组：groupId ≤ 0 或省略表示新增（自动分配 id）。返回保存后的分组对象。" +
                "删除分组请用 deleteGroupId 参数（内置分组不可删）。",
            domain = MCP_DOMAIN_BOOKSHELF,
            level = TokenManager.Level.MANAGE,
            inputSchema = McpJsonSchema.of(
                optional = mapOf(
                    "groupId" to TYPE_INTEGER,
                    "groupName" to TYPE_STRING,
                    "show" to TYPE_BOOLEAN,
                    "bookSort" to TYPE_INTEGER,
                    "deleteGroupId" to TYPE_INTEGER,
                ),
                descriptions = mapOf(
                    "groupId" to "分组 id（≤0 / 省略 = 新增）",
                    "groupName" to "分组名（新增或重命名必填）",
                    "show" to "是否在书架显示",
                    "bookSort" to "该分组的排序模式（-1 = 跟随全局）",
                    "deleteGroupId" to "传该值表示删除此分组（分组内书籍归入「全部」）",
                )
            ),
        ) { args ->
            val deleteId = args.int("deleteGroupId", 0).toLong()
            if (deleteId != 0L) {
                BookshelfKernel.deleteGroup(deleteId)
                mapOf("deleted" to deleteId)
            } else {
                val groupId = args.int("groupId", 0).toLong()
                val name = args.str("groupName")
                    ?: throw McpParamException("缺少必填参数：groupName")
                val show = if (args.raw().has("show")) args.bool("show") else null
                val bookSort = if (args.raw().has("bookSort")) args.int("bookSort") else null
                BookshelfKernel.saveGroup(groupId, name, show, bookSort)
            }
        },

        McpTool(
            name = "book_set_group",
            title = "设置书籍分组",
            description = "把一本书移动到指定书架分组（groupId 取 bookshelf_groups_get 的返回值）。返回更新后的书籍。",
            domain = MCP_DOMAIN_BOOKSHELF,
            level = TokenManager.Level.MANAGE,
            inputSchema = McpJsonSchema.of(
                required = mapOf("bookUrl" to TYPE_STRING, "groupId" to TYPE_INTEGER),
                descriptions = mapOf("bookUrl" to "书籍唯一地址", "groupId" to "目标分组 id"),
            ),
        ) { args ->
            BookshelfKernel.setBookGroup(args.requireStr("bookUrl"), args.requireInt("groupId").toLong())
        },

        McpTool(
            name = "book_get_info",
            title = "书籍详情",
            description = "读取书籍详情（作者/简介/封面/分组/阅读进度/最新章节）。返回书籍对象；不存在返回错误。",
            domain = MCP_DOMAIN_BOOKSHELF,
            level = TokenManager.Level.READONLY,
            readOnlyHint = true,
            inputSchema = McpJsonSchema.singleRequiredString("bookUrl", "书籍唯一地址"),
        ) { args ->
            BookshelfKernel.bookInfo(args.requireStr("bookUrl"))
        },

        McpTool(
            name = "book_save_info",
            title = "保存书籍详情",
            description = "保存书籍详情编辑（书名/作者/简介/自定义封面/自定义标签等）。" +
                "入参 book 可以是书籍对象或书籍 JSON 串；返回保存后的书籍。",
            domain = MCP_DOMAIN_BOOKSHELF,
            level = TokenManager.Level.MANAGE,
            inputSchema = McpJsonSchema.of(
                required = mapOf("book" to McpJsonSchema.TYPE_OBJECT),
                descriptions = mapOf("book" to "书籍对象（Book 字段子集；bookUrl 必填）"),
            ),
        ) { args ->
            val book = parseBook(args.raw().get("book"))
                ?: throw McpParamException("参数 book 格式不对（须为书籍对象或 JSON 字符串）")
            if (book.bookUrl.isBlank()) throw McpParamException("书籍 bookUrl 不能为空")
            BookKernel.saveBook(book)
            book
        },

        McpTool(
            name = "book_set_order",
            title = "调整书籍排序",
            description = "调整书籍在书架中的位置：order 为绝对序号；move 为相对移动（top/up/down/bottom，限同分组内）。" +
                "两者至少给一个。返回更新后的书籍。",
            domain = MCP_DOMAIN_BOOKSHELF,
            level = TokenManager.Level.MANAGE,
            inputSchema = McpJsonSchema.of(
                required = mapOf("bookUrl" to TYPE_STRING),
                optional = mapOf("order" to TYPE_INTEGER, "move" to TYPE_STRING),
                descriptions = mapOf(
                    "bookUrl" to "书籍唯一地址",
                    "order" to "绝对序号（与 move 二选一）",
                    "move" to "相对移动：top/up/down/bottom",
                )
            ),
        ) { args ->
            val order = if (args.raw().has("order")) args.int("order") else null
            BookshelfKernel.setBookOrder(args.requireStr("bookUrl"), order, args.str("move"))
        },

        McpTool(
            name = "book_add_local",
            title = "添加本地书",
            description = "把设备上的文件（txt/epub/umd…）加入书架。fileName 为显示名，filePath 为设备文件绝对路径。" +
                "返回新书对象。",
            domain = MCP_DOMAIN_BOOKSHELF,
            level = TokenManager.Level.MANAGE,
            inputSchema = McpJsonSchema.of(
                required = mapOf("fileName" to TYPE_STRING, "filePath" to TYPE_STRING),
                descriptions = mapOf(
                    "fileName" to "显示名（含后缀，决定解析方式）",
                    "filePath" to "设备文件绝对路径",
                )
            ),
        ) { args ->
            BookshelfKernel.addLocalBook(args.requireStr("fileName"), args.requireStr("filePath"))
            mapOf("added" to true, "fileName" to args.requireStr("fileName"))
        },

        McpTool(
            name = "search_books",
            title = "跨书源搜书",
            description = "跨书源全局搜书（返回可加入书架的结果集：name/author/origin/bookUrl/intro/coverUrl…）。" +
                "默认取最多 8 个启用书源；慢源受执行超时约束。",
            domain = MCP_DOMAIN_BOOKSHELF,
            level = TokenManager.Level.READONLY,
            readOnlyHint = true,
            inputSchema = McpJsonSchema.of(
                required = mapOf("key" to TYPE_STRING),
                optional = mapOf(
                    "page" to TYPE_INTEGER,
                    "sourceUrls" to McpJsonSchema.TYPE_ARRAY,
                    "maxSources" to TYPE_INTEGER,
                ),
                descriptions = mapOf(
                    "key" to "搜索关键词",
                    "page" to "页码（默认 1）",
                    "sourceUrls" to "指定参与搜索的书源地址数组（省略 = 全部启用源）",
                    "maxSources" to "最多搜索多少个源（默认 8，上限 64）",
                )
            ),
        ) { args ->
            BookshelfKernel.searchBooks(
                key = args.requireStr("key"),
                page = args.int("page", 1),
                sourceUrls = args.strList("sourceUrls"),
                maxSources = args.int("maxSources", 8),
            )
        },

        McpTool(
            name = "book_import_url",
            title = "按 URL 导入书籍",
            description = "按 URL 导入在线书籍文件（txt/umd/epub 直链）：下载 → 落盘 → 建书 → 生成目录。" +
                "return 新书对象。可选 sourceUrl 指定归属书源（用于解密/鉴权头）。",
            domain = MCP_DOMAIN_BOOKSHELF,
            level = TokenManager.Level.MANAGE,
            inputSchema = McpJsonSchema.of(
                required = mapOf("name" to TYPE_STRING, "url" to TYPE_STRING),
                optional = mapOf("sourceUrl" to TYPE_STRING),
                descriptions = mapOf(
                    "name" to "书籍显示名（含后缀）",
                    "url" to "文件直链 URL",
                    "sourceUrl" to "归属书源地址（可选）",
                )
            ),
        ) { args ->
            BookshelfKernel.importByUrl(
                name = args.requireStr("name"),
                url = args.requireStr("url"),
                sourceUrl = args.str("sourceUrl"),
            )
        },

        McpTool(
            name = "bookshelf_batch_op",
            title = "书架批量管理",
            description = "批量管理书架书籍。action 取值：" +
                "update_on/update_off（开启/关闭自动更新）、clear_cache（清正文与图片缓存）、" +
                "set_group（批量改分组，需 targetGroup）、change_source（批量换源，需 targetSource）。" +
                "**危险操作**：批量换源会重拉目录并改写书籍源。",
            domain = MCP_DOMAIN_BOOKSHELF,
            level = TokenManager.Level.MANAGE,
            dangerous = true,
            inputSchema = McpJsonSchema.of(
                required = mapOf("action" to TYPE_STRING, "bookUrls" to McpJsonSchema.TYPE_ARRAY),
                optional = mapOf("targetGroup" to TYPE_INTEGER, "targetSource" to TYPE_STRING),
                descriptions = mapOf(
                    "action" to "update_on/update_off/clear_cache/set_group/change_source",
                    "bookUrls" to "目标书籍 bookUrl 数组",
                    "targetGroup" to "set_group 的目标分组 id",
                    "targetSource" to "change_source 的目标书源地址",
                )
            ),
        ) { args ->
            val targetGroup = if (args.raw().has("targetGroup")) args.int("targetGroup").toLong() else null
            BookshelfKernel.batchOp(
                action = args.requireStr("action"),
                bookUrls = args.strList("bookUrls"),
                targetGroup = targetGroup,
                targetSource = args.str("targetSource"),
            )
        },

        McpTool(
            name = "bookshelf_tags_get",
            title = "读取书架标签",
            description = "读取标签卡数据（书 URL / 书名 / 作者 / 自定义标签 / 类型 / 分组）。",
            domain = MCP_DOMAIN_BOOKSHELF,
            level = TokenManager.Level.READONLY,
            readOnlyHint = true,
            inputSchema = McpJsonSchema.empty(),
        ) { _ ->
            BookshelfKernel.tags()
        },

        McpTool(
            name = "bookshelf_tag_save",
            title = "保存书架标签",
            description = "批量为书籍写自定义标签；tag 传空串或省略表示清除标签。返回受影响条数。",
            domain = MCP_DOMAIN_BOOKSHELF,
            level = TokenManager.Level.MANAGE,
            inputSchema = McpJsonSchema.of(
                required = mapOf("bookUrls" to McpJsonSchema.TYPE_ARRAY),
                optional = mapOf("tag" to TYPE_STRING),
                descriptions = mapOf("bookUrls" to "目标书籍 bookUrl 数组", "tag" to "自定义标签（空 = 清除）"),
            ),
        ) { args ->
            BookshelfKernel.saveTag(args.strList("bookUrls"), args.str("tag"))
        },

        McpTool(
            name = "book_share",
            title = "书籍分享串",
            description = "生成书籍的系统分享文本（`bookUrl#书籍JSON`，与 App「分享书籍」同格式，可直接发给他人导入）。" +
                "只回文本与字节数，不触发系统分享面板。",
            domain = MCP_DOMAIN_BOOKSHELF,
            level = TokenManager.Level.READONLY,
            readOnlyHint = true,
            inputSchema = McpJsonSchema.singleRequiredString("bookUrl", "书籍唯一地址"),
        ) { args ->
            BookshelfKernel.shareText(args.requireStr("bookUrl"))
        },
    )

    /** 与 REST 门面同口径：`book` 既接受对象也接受 JSON 字符串。 */
    private fun parseBook(element: JsonElement?): Book? {
        if (element == null || element.isJsonNull) return null
        val json = if (element.isJsonPrimitive) element.asString else GSON.toJson(element)
        return GSON.fromJsonObject<Book>(json).getOrNull()
    }
}
