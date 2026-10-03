package io.legado.app.web.mcp.tools

import com.google.gson.JsonElement
import io.legado.app.data.entities.Bookmark
import io.legado.app.data.entities.SceneBookmark
import io.legado.app.service.kernel.BookmarkKernel
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import io.legado.app.web.TokenManager
import io.legado.app.web.mcp.MCP_DOMAIN_BOOKMARK
import io.legado.app.web.mcp.McpJsonSchema
import io.legado.app.web.mcp.McpJsonSchema.TYPE_STRING
import io.legado.app.web.mcp.McpParamException
import io.legado.app.web.mcp.McpTool

/**
 * ③ 书签高亮域工具声明（web-mcp-productization 二期 · tasks 2.10）。
 *
 * 红线（AD-10）：`invoke` 只调 [BookmarkKernel]；导出串格式与 `AllBookmarkViewModel` 同构。
 */
object BookmarkTools {

    val tools: List<McpTool> = listOf(
        McpTool(
            name = "bookmark_list",
            title = "书签列表",
            description = "读取书签列表：给 bookName（可选 bookAuthor）查该书书签，否则返回全部书签。" +
                "返回字段：time/bookName/bookAuthor/chapterIndex/chapterName/bookText/content。",
            domain = MCP_DOMAIN_BOOKMARK,
            level = TokenManager.Level.READONLY,
            readOnlyHint = true,
            inputSchema = McpJsonSchema.of(
                optional = mapOf("bookName" to TYPE_STRING, "bookAuthor" to TYPE_STRING),
                descriptions = mapOf("bookName" to "书名（省略 = 全部）", "bookAuthor" to "作者"),
            ),
        ) { args ->
            BookmarkKernel.bookmarks(args.str("bookName"), args.str("bookAuthor"))
        },

        McpTool(
            name = "bookmark_save",
            title = "保存书签",
            description = "新增 / 保存书签（time 即主键，已存在则更新）。入参 bookmark 可为对象或 JSON 串。" +
                "必填 bookName/bookAuthor/chapterIndex/chapterName/bookText。",
            domain = MCP_DOMAIN_BOOKMARK,
            level = TokenManager.Level.MANAGE,
            inputSchema = McpJsonSchema.of(
                required = mapOf("bookmark" to McpJsonSchema.TYPE_OBJECT),
                descriptions = mapOf("bookmark" to "书签对象或书签 JSON 串"),
            ),
        ) { args ->
            val bookmark = parseBookmark(args.raw().get("bookmark"))
                ?: throw McpParamException("参数 bookmark 格式不对")
            if (bookmark.bookName.isBlank()) throw McpParamException("书签 bookName 不能为空")
            BookmarkKernel.saveBookmark(bookmark)
        },

        McpTool(
            name = "bookmark_delete",
            title = "删除书签",
            description = "按主键 time 批量删除书签。返回删除条数。",
            domain = MCP_DOMAIN_BOOKMARK,
            level = TokenManager.Level.MANAGE,
            inputSchema = McpJsonSchema.of(
                required = mapOf("times" to McpJsonSchema.TYPE_ARRAY),
                descriptions = mapOf("times" to "书签 time 数组（bookmark_list 返回的 time）"),
            ),
        ) { args ->
            mapOf("deleted" to BookmarkKernel.deleteBookmark(args.strList("times").mapNotNull { it.toLongOrNull() }))
        },

        McpTool(
            name = "scene_bookmark_list",
            title = "名场面列表",
            description = "读取名场面书签：给 bookUrl 查该书，否则返回全部。返回字段：" +
                "id/time/bookUrl/bookName/chapterIndex/chapterName/contentKind/anchor/text/desc/tags/style。",
            domain = MCP_DOMAIN_BOOKMARK,
            level = TokenManager.Level.READONLY,
            readOnlyHint = true,
            inputSchema = McpJsonSchema.of(
                optional = mapOf("bookUrl" to TYPE_STRING),
                descriptions = mapOf("bookUrl" to "书籍唯一地址（省略 = 全部）"),
            ),
        ) { args ->
            BookmarkKernel.sceneBookmarks(args.str("bookUrl"))
        },

        McpTool(
            name = "scene_bookmark_save",
            title = "保存名场面",
            description = "新增 / 保存名场面书签（id ≤ 0 或省略 = 新增，返回带自增 id 的对象）。" +
                "必填 bookUrl/bookName/chapterIndex/chapterName/text。",
            domain = MCP_DOMAIN_BOOKMARK,
            level = TokenManager.Level.MANAGE,
            inputSchema = McpJsonSchema.of(
                required = mapOf("bookmark" to McpJsonSchema.TYPE_OBJECT),
                descriptions = mapOf("bookmark" to "名场面书签对象或 JSON 串"),
            ),
        ) { args ->
            val bookmark = parseSceneBookmark(args.raw().get("bookmark"))
                ?: throw McpParamException("参数 bookmark 格式不对")
            if (bookmark.bookUrl.isBlank()) throw McpParamException("名场面 bookUrl 不能为空")
            BookmarkKernel.saveSceneBookmark(bookmark)
        },

        McpTool(
            name = "scene_bookmark_delete",
            title = "删除名场面",
            description = "按 id 批量删除名场面书签。返回删除条数。",
            domain = MCP_DOMAIN_BOOKMARK,
            level = TokenManager.Level.MANAGE,
            inputSchema = McpJsonSchema.of(
                required = mapOf("ids" to McpJsonSchema.TYPE_ARRAY),
                descriptions = mapOf("ids" to "名场面书签 id 数组"),
            ),
        ) { args ->
            mapOf("deleted" to BookmarkKernel.deleteSceneBookmark(args.strList("ids").mapNotNull { it.toLongOrNull() }))
        },

        McpTool(
            name = "bookmark_export",
            title = "导出书签",
            description = "导出书签串：format=json（整表 JSON）或 format=md（Markdown 文本）。" +
                "格式与 App「导出书签」逐字节同构。返回 {format,count,content}。",
            domain = MCP_DOMAIN_BOOKMARK,
            level = TokenManager.Level.READONLY,
            readOnlyHint = true,
            inputSchema = McpJsonSchema.of(
                optional = mapOf(
                    "format" to TYPE_STRING,
                    "bookName" to TYPE_STRING,
                    "bookAuthor" to TYPE_STRING,
                ),
                descriptions = mapOf(
                    "format" to "json / md（默认 json）",
                    "bookName" to "限定书名（省略 = 全部书签）",
                    "bookAuthor" to "作者",
                )
            ),
        ) { args ->
            BookmarkKernel.export(
                bookName = args.str("bookName"),
                bookAuthor = args.str("bookAuthor"),
                format = args.str("format")?.takeIf { it.isNotBlank() } ?: "json",
            )
        },
    )

    private fun parseBookmark(element: JsonElement?): Bookmark? {
        if (element == null || element.isJsonNull) return null
        val json = if (element.isJsonPrimitive) element.asString else GSON.toJson(element)
        return GSON.fromJsonObject<Bookmark>(json).getOrNull()
    }

    private fun parseSceneBookmark(element: JsonElement?): SceneBookmark? {
        if (element == null || element.isJsonNull) return null
        val json = if (element.isJsonPrimitive) element.asString else GSON.toJson(element)
        return GSON.fromJsonObject<SceneBookmark>(json).getOrNull()
    }
}
