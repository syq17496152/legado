package io.legado.app.service.kernel

import io.legado.app.data.appDb
import io.legado.app.data.entities.AiGeneratedImage
import io.legado.app.data.entities.AiReadAloudUsageRecord
import io.legado.app.help.ai.AiImageGalleryManager
import io.legado.app.help.ai.AiWorldBookManager
import io.legado.app.help.config.AppConfig
import io.legado.app.ui.main.ai.AiAgentMode
import io.legado.app.ui.main.ai.AiChatCompanionConfig
import io.legado.app.ui.main.ai.AiChatMessage
import io.legado.app.ui.main.ai.AiChatSession
import io.legado.app.ui.main.ai.AiImageProviderConfig
import io.legado.app.ui.main.ai.AiProviderConfig
import io.legado.app.ui.main.ai.AiWorldBookConfig
import io.legado.app.ui.main.ai.AiWorldBookEntry
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.withContext

/**
 * ⑳ AI 智能域业务内核（web-mcp-productization 二期 · tasks 2.24 / 2.28）。
 *
 * 契约同 [BookKernel]：只返回领域对象 / 结构化 Map、全链挂起、零阻塞调用（DAO 走 `withContext(IO)`）、失败抛异常。
 *
 * 数据源（全部为既有能力，不新增存储）：
 * - 会话 / 世界书 / Provider：`AppConfig`（SharedPreferences + GSON），**无 `ai_chat_sessions` Room 表**；
 * - 图片库：`AiImageGalleryManager`（Room `ai_image_groups` / `ai_generated_images`）；
 * - 朗读用量：`appDb.aiReadAloudUsageRecordDao`；
 * - Agent 行为配置：`AppConfig` 的 `aiChatAgentMode` / `aiAgent*` / `aiEnabledToolNames` / `aiReadToolMode`。
 *
 * ## AD-18 边界声明（AI 域只暴露「无凭据子集」）
 * Provider 类方法（[providerList] / [imageProviderList]）**只返回** `id/name/baseUrl/apiMode/balanceUrl`
 * （图片 Provider 为 `id/name/type/baseUrl/model/enabled/order`）——**绝不返回**
 * `apiKey` / `headers` / `script` / `jsLib` / `loginUi` / `loginUrl`（凭据或可携带凭据的字段）。
 * 本内核不 import `web` 层，也不做输出脱敏（脱敏由工具层统一处理）。
 *
 * ## 已知上限 / 降级
 * 1. [chatSend] **降级为只读**：报告已确认无独立会话 Store、会话读写入口在 `ui/main/ai/AiChatViewModel`（ViewModel，需端侧 UI），
 *    故本方法不代发消息、不写库，仅回读会话消息并在 `note` 中说明「助手回复需端侧 UI 触发」。
 * 2. [characters] **降级**：勘查报告未核实「书籍角色卡」（`book_characters` 表 / `BookCharacterDao`）签名，
 *    本方法只返回已核实的「AI 角色助手」子集（`AiChatCompanionConfig.type == "character"`），**不含书籍角色卡本身**。
 * 3. [galleryList] / [usageQuery] 输出侧裁剪：省略 `sourceText` / `originalSource`（原始来源与正文，可能含业务敏感文本）；
 *    `providerName` 属业务数据，按 AD-18 由工具层按需脱敏。
 */
object AiKernel {

    // ---------------------------------------------------------------- 会话

    private fun AiChatSession.toBrief(): Map<String, Any?> = mapOf(
        "id" to id,
        "title" to title,
        "companionId" to companionId,
        "updatedAt" to updatedAt,
        "messageCount" to messages.size,
        "hasSummary" to (contextSummary?.isValid == true),
    )

    private fun AiChatMessage.toMap(): Map<String, Any?> = mapOf(
        "id" to id,
        "role" to role.name.lowercase(),
        "kind" to (kind?.name?.lowercase() ?: "text"),
        "content" to content,
        "createdAt" to createdAt,
        "updatedAt" to updatedAt,
        "statusName" to statusName,
        "statusSuccess" to statusSuccess,
    )

    /** `ai_chat_sessions`：AI 会话列表（`AppConfig.aiChatSessionList`，按 `updatedAt` 倒序）。 */
    suspend fun chatSessions(): Map<String, Any?> = withContext(IO) {
        val sessions = AppConfig.aiChatSessionList
        mapOf(
            "total" to sessions.size,
            "currentSessionId" to AppConfig.aiCurrentChatSessionId,
            "sessions" to sessions.map { it.toBrief() },
        )
    }

    /** `ai_chat_history`：会话历史消息（`limit <= 0` 表示不限；取末 `limit` 条，按时间正序返回）。 */
    suspend fun chatHistory(sessionId: String, limit: Int = 50): Map<String, Any?> = withContext(IO) {
        require(sessionId.isNotBlank()) { "sessionId 不能为空" }
        val session = AppConfig.aiChatSessionList.firstOrNull { it.id == sessionId }
            ?: throw NoSuchElementException("AI 会话不存在：$sessionId")
        val all = session.messages
        val picked = if (limit > 0) all.takeLast(limit) else all
        mapOf(
            "sessionId" to session.id,
            "title" to session.title,
            "companionId" to session.companionId,
            "total" to all.size,
            "returned" to picked.size,
            "messages" to picked.map { it.toMap() },
        )
    }

    /**
     * `ai_chat_send`：发送消息（**降级为只读**，见类 KDoc 上限 1）。
     *
     * 报告已确认无「发送并等待回复」的可直接调用入口 ⇒ 本方法只回读该会话消息，
     * 并在 `note` 中说明助手回复需端侧 UI（`AiChatViewModel` / `AiChatService`）触发；`pendingContent` 原样回显待发内容。
     */
    suspend fun chatSend(sessionId: String, content: String): Map<String, Any?> = withContext(IO) {
        require(sessionId.isNotBlank()) { "sessionId 不能为空" }
        require(content.isNotBlank()) { "content 不能为空" }
        val session = AppConfig.aiChatSessionList.firstOrNull { it.id == sessionId }
            ?: throw NoSuchElementException("AI 会话不存在：$sessionId")
        mapOf(
            "sessionId" to session.id,
            "sent" to false,
            "pendingContent" to content,
            "messages" to session.messages.takeLast(20).map { it.toMap() },
            "note" to "已确认无独立会话 Store：会话读写入口在端侧 AiChatViewModel，助手回复需端侧 UI 触发；本内核不代发",
        )
    }

    // ---------------------------------------------------------------- 角色

    private fun AiChatCompanionConfig.toMap(): Map<String, Any?> = mapOf(
        "id" to id,
        "type" to type,
        "name" to name,
        "avatar" to avatar,
        "bookKey" to bookKey,
        "characterId" to characterId,
        "prompt" to prompt,
        "worldBookIds" to worldBookIds,
        "order" to order,
        "enabled" to enabled,
        "updatedAt" to updatedAt,
    )

    /**
     * `ai_characters`：AI 角色助手（**降级**，见类 KDoc 上限 2）。
     *
     * 只返回 `type == "character"` 的 AI 角色助手；书籍角色卡（`book_characters` 表）未在报告中核实，本方法不覆盖。
     */
    suspend fun characters(): Map<String, Any?> = withContext(IO) {
        val list = AppConfig.aiChatCompanionList
            .filter { it.type == AiChatCompanionConfig.TYPE_CHARACTER }
        mapOf(
            "total" to list.size,
            "scope" to "ai_companion_character_only",
            "note" to "书籍角色卡（book_characters 表）未在勘查报告中核实，本方法仅返回由角色卡派生的 AI 角色助手",
            "characters" to list.map { it.toMap() },
        )
    }

    // ---------------------------------------------------------------- 世界书

    private fun AiWorldBookEntry.toMap(): Map<String, Any?> = mapOf(
        "id" to id,
        "title" to title,
        "name" to name,
        "content" to content.take(4_000),
        "keys" to keys,
        "keywords" to keywords,
        "secondaryKeys" to secondaryKeys,
        "excludeKeys" to excludeKeys,
        "useRegex" to useRegex,
        "caseSensitive" to caseSensitive,
        "enabled" to enabled,
        "constant" to constant,
        "priority" to priority,
        "position" to position,
        "injectDepth" to injectDepth,
        "role" to role,
        "scanDepth" to scanDepth,
        "maxMatches" to maxMatches,
        "order" to order,
    )

    private fun AiWorldBookConfig.toBrief(): Map<String, Any?> = mapOf(
        "id" to id,
        "name" to name,
        "description" to description.take(200),
        "version" to version,
        "type" to type,
        "scope" to scope,
        "bookKey" to bookKey,
        "enabled" to enabled,
        "maxEntries" to maxEntries,
        "order" to order,
        "entryCount" to entries.size,
        "bindingCount" to bindings.size,
    )

    /** `world_book_list`：世界书列表（简要，不含条目正文）。 */
    suspend fun worldbookList(): Map<String, Any?> = withContext(IO) {
        val list = AppConfig.aiWorldBookList
        mapOf("total" to list.size, "items" to list.map { it.toBrief() })
    }

    /** `world_book_get`：世界书详情（含条目与绑定）。 */
    suspend fun worldbookGet(id: String): Map<String, Any?> = withContext(IO) {
        require(id.isNotBlank()) { "id 不能为空" }
        val item = AppConfig.aiWorldBookList.firstOrNull { it.id == id }
            ?: throw NoSuchElementException("世界书不存在：$id")
        item.toBrief() + mapOf(
            "bindings" to item.bindings.map {
                mapOf(
                    "id" to it.id,
                    "targetType" to it.targetType,
                    "targetKey" to it.targetKey,
                    "enabled" to it.enabled,
                    "order" to it.order,
                )
            },
            "entries" to item.entries.map { it.toMap() },
        )
    }

    /** `world_book_save`：新增 / 更新一本世界书（按 `config.id` 覆盖，缺省由实体生成 UUID）。 */
    suspend fun worldbookSave(config: AiWorldBookConfig): Map<String, Any?> = withContext(IO) {
        require(config.name.isNotBlank()) { "世界书名不能为空" }
        val before = AppConfig.aiWorldBookList
        val isUpdate = before.any { it.id == config.id }
        AppConfig.aiWorldBookList = before.filterNot { it.id == config.id } + config
        mapOf("saved" to true, "updated" to isUpdate, "id" to config.id, "name" to config.name)
    }

    /** `world_book_delete`：按 id 删除。 */
    suspend fun worldbookDelete(id: String): Map<String, Any?> = withContext(IO) {
        require(id.isNotBlank()) { "id 不能为空" }
        val before = AppConfig.aiWorldBookList
        AppConfig.aiWorldBookList = before.filterNot { it.id == id }
        mapOf("deleted" to before.any { it.id == id }, "id" to id)
    }

    /** `world_book_import`：导入标准世界书 JSON（`AiWorldBookManager.parseStandardWorldBook`；同 id 生成副本）。 */
    suspend fun worldbookImport(json: String): Map<String, Any?> = withContext(IO) {
        require(json.isNotBlank()) { "json 不能为空" }
        val parsed = AiWorldBookManager.parseStandardWorldBook(json, AppConfig.aiWorldBookList.size)
        val before = AppConfig.aiWorldBookList
        val existing = before.map { it.id }.toSet()
        val saving = if (parsed.id in existing) {
            parsed.copy(id = AiWorldBookConfig(name = parsed.name).id, name = "${parsed.name} 副本")
        } else {
            parsed
        }
        AppConfig.aiWorldBookList = before + saving
        mapOf(
            "imported" to true,
            "id" to saving.id,
            "name" to saving.name,
            "entryCount" to saving.entries.size,
        )
    }

    // ---------------------------------------------------------------- 图片库

    private fun AiGeneratedImage.toMap(): Map<String, Any?> = mapOf(
        "id" to id,
        "name" to name,
        "prompt" to prompt.take(200),
        "providerId" to providerId,
        "providerName" to providerName,
        "model" to model,
        "localPath" to localPath,
        "bookName" to bookName,
        "bookAuthor" to bookAuthor,
        "chapterIndex" to chapterIndex,
        "chapterTitle" to chapterTitle,
        "characterId" to characterId,
        "characterName" to characterName,
        "sourceType" to sourceType,
        "favorite" to favorite,
        "groupId" to groupId,
        "createdAt" to createdAt,
        "updatedAt" to updatedAt,
    )

    /**
     * `ai_gallery_list`：AI 图片库列表。
     *
     * `filter` 取值：`all`（默认）/ `temporary` / `favorite` / `group` / `book` / `chapter` / `source_type` / `search`；
     * 后五者需 `value`（groupId / bookKey / chapterKey / sourceType / 关键词）。
     */
    suspend fun galleryList(
        filter: String? = null,
        value: String? = null,
        limit: Int = 200,
    ): Map<String, Any?> = withContext(IO) {
        val keyword = value?.trim().orEmpty()
        val galleryFilter = when (filter?.trim()?.lowercase()?.ifBlank { "all" } ?: "all") {
            "temporary" -> AiImageGalleryManager.GalleryFilter.TEMPORARY
            "favorite" -> AiImageGalleryManager.GalleryFilter.FAVORITE
            "group" -> AiImageGalleryManager.GalleryFilter.GROUP(requireValue("group", keyword))
            "book" -> AiImageGalleryManager.GalleryFilter.BOOK(requireValue("book", keyword))
            "chapter" -> AiImageGalleryManager.GalleryFilter.CHAPTER(requireValue("chapter", keyword))
            "source_type" -> AiImageGalleryManager.GalleryFilter.SOURCE_TYPE(requireValue("source_type", keyword))
            "search" -> AiImageGalleryManager.GalleryFilter.SEARCH(requireValue("search", keyword))
            else -> AiImageGalleryManager.GalleryFilter.ALL
        }
        val all = AiImageGalleryManager.listImages(galleryFilter)
        val picked = if (limit > 0) all.take(limit) else all
        mapOf(
            "total" to all.size,
            "returned" to picked.size,
            "groups" to AiImageGalleryManager.listGroups().map {
                mapOf("id" to it.id, "name" to it.name, "sortOrder" to it.sortOrder)
            },
            "images" to picked.map { it.toMap() },
        )
    }

    private fun requireValue(kind: String, value: String): String {
        require(value.isNotBlank()) { "filter=$kind 需要 value 参数" }
        return value
    }

    /** `ai_gallery_group`：把图片归入分组（`groupId` 为空表示移出分组）。 */
    suspend fun galleryGroup(imageId: String, groupId: String?): Map<String, Any?> = withContext(IO) {
        require(imageId.isNotBlank()) { "imageId 不能为空" }
        AiImageGalleryManager.moveImagesToGroup(listOf(imageId), groupId?.takeIf { it.isNotBlank() })
        mapOf("imageId" to imageId, "groupId" to groupId)
    }

    /** `ai_gallery_favorite`：收藏 / 取消收藏（收藏时归入默认分组）。 */
    suspend fun galleryFavorite(imageId: String, favorite: Boolean): Map<String, Any?> = withContext(IO) {
        require(imageId.isNotBlank()) { "imageId 不能为空" }
        AiImageGalleryManager.setFavorite(imageId, favorite, null)
        mapOf("imageId" to imageId, "favorite" to favorite)
    }

    // ---------------------------------------------------------------- Provider（AD-18：无凭据子集）

    private fun AiProviderConfig.toPublicMap(): Map<String, Any?> = mapOf(
        "id" to id,
        "name" to name,
        "baseUrl" to baseUrl,
        "apiMode" to apiMode,
        "balanceUrl" to balanceUrl,
    )

    private fun AiImageProviderConfig.toPublicMap(): Map<String, Any?> = mapOf(
        "id" to id,
        "name" to name,
        "type" to type,
        "baseUrl" to baseUrl,
        "model" to model,
        "enabled" to enabled,
        "order" to order,
    )

    /**
     * `ai_provider_list`：AI 文本 Provider 列表。
     *
     * **AD-18**：只返回 `id/name/baseUrl/apiMode/balanceUrl` —— **不含 `apiKey` / `headers`**（凭据字段）。
     */
    suspend fun providerList(): Map<String, Any?> = withContext(IO) {
        val list = AppConfig.aiProviderList
        mapOf(
            "total" to list.size,
            "currentProviderId" to AppConfig.aiCurrentProviderId,
            "providers" to list.map { it.toPublicMap() },
        )
    }

    /**
     * `ai_image_provider_list`：AI 图片 Provider 列表。
     *
     * **AD-18**：只返回 `id/name/type/baseUrl/model/enabled/order` —— **不含 `apiKey` / `headers` / `script` / `jsLib` / `loginUi` / `loginUrl`**。
     */
    suspend fun imageProviderList(): Map<String, Any?> = withContext(IO) {
        val list = AppConfig.aiImageProviderList
        mapOf(
            "total" to list.size,
            "currentImageProviderId" to AppConfig.aiCurrentImageProviderId,
            "providers" to list.map { it.toPublicMap() },
        )
    }

    // ---------------------------------------------------------------- 朗读用量

    private fun AiReadAloudUsageRecord.toMap(): Map<String, Any?> = mapOf(
        "id" to id,
        "type" to type,
        "status" to status,
        "modelId" to modelId,
        "providerName" to providerName,
        "elapsedMillis" to elapsedMillis,
        "requestCount" to requestCount,
        "inputTokens" to inputTokens,
        "cachedInputTokens" to cachedInputTokens,
        "outputTokens" to outputTokens,
        "totalTokens" to totalTokens,
        "chapterIndex" to chapterIndex,
        "cacheKey" to cacheKey,
        "summary" to summary.take(200),
        "error" to error.take(300),
        "createdAt" to createdAt,
    )

    /** `ai_usage_query`：朗读用量记录（`type` / `bookUrl` 为空表示不过滤；`limit <= 0` 取 DAO 上限）。 */
    suspend fun usageQuery(
        type: String? = null,
        bookUrl: String? = null,
        limit: Int = 200,
    ): Map<String, Any?> = withContext(IO) {
        val records = appDb.aiReadAloudUsageRecordDao.list(
            type?.trim().orEmpty(),
            bookUrl?.trim().orEmpty(),
            if (limit > 0) limit else 1000,
        )
        mapOf(
            "returned" to records.size,
            "limit" to limit,
            "records" to records.map { it.toMap() },
        )
    }

    // ---------------------------------------------------------------- Agent 行为配置（无凭据字段）

    /** `ai_agent_config_get`：Agent 行为配置（无任何凭据字段）。 */
    suspend fun agentConfigGet(): Map<String, Any?> = withContext(IO) {
        mapOf(
            "agentMode" to AppConfig.aiChatAgentMode.id,
            "toolMaxAttempts" to AppConfig.aiAgentToolMaxAttempts,
            "maxToolRounds" to AppConfig.aiAgentMaxToolRounds,
            "toolRetryBackoffMillis" to AppConfig.aiAgentToolRetryBackoffMillis,
            "readToolMode" to AppConfig.aiReadToolMode,
            "enabledToolNames" to AppConfig.aiEnabledToolNames.toList(),
            "enabledToolNamesVersion" to AppConfig.aiEnabledToolNamesVersion,
        )
    }

    /** `ai_agent_config_save`：保存 Agent 行为配置（可只给部分字段，其余沿用现值）。 */
    suspend fun agentConfigSave(
        agentMode: String? = null,
        toolMaxAttempts: Int? = null,
        maxToolRounds: Int? = null,
        toolRetryBackoffMillis: Int? = null,
        readToolMode: String? = null,
        enabledToolNames: List<String>? = null,
    ): Map<String, Any?> = withContext(IO) {
        agentMode?.takeIf { it.isNotBlank() }?.let { AppConfig.aiChatAgentMode = AiAgentMode.fromId(it) }
        toolMaxAttempts?.let { AppConfig.aiAgentToolMaxAttempts = it }
        maxToolRounds?.let { AppConfig.aiAgentMaxToolRounds = it }
        toolRetryBackoffMillis?.let { AppConfig.aiAgentToolRetryBackoffMillis = it }
        readToolMode?.takeIf { it.isNotBlank() }?.let { AppConfig.aiReadToolMode = it }
        enabledToolNames?.let { AppConfig.aiEnabledToolNames = it.toSet() }
        agentConfigGet()
    }
}