package io.legado.app.service.kernel

import io.legado.app.data.appDb
import io.legado.app.data.entities.BookCharacter
import io.legado.app.data.entities.BookCharacterRelation
import io.legado.app.help.readaloud.speech.SpeechRoute
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.withContext

/**
 * ⑲ 角色域业务内核（web-mcp-productization 二期 · tasks 2.23 / 2.28）。
 *
 * 契约同 [BookKernel]：**只返回领域对象 / 结构化 Map**、**全链挂起**、**零 `runBlocking`**、失败抛异常。
 *
 * 数据源（全部既有能力，不新增存储）：
 * - 角色卡 / 关系图：`appDb.bookCharacterDao`（`book_characters` / `book_character_relations` 两表）；
 * - 配音路由：**`BookCharacter.speechRouteJson`**（报告 §⑲ 已确认**不存在** `character_voice_route`
 *   实体/表，配音路由即该 String 字段），解析/序列化用 `SpeechRoute.fromJson` / `toJson`。
 *
 * 已知上限 / 降级（如实声明）：
 * 1. **不存在 `character_voice_route` 实体**（全仓 0 命中，报告 §⑲）⇒ [voiceRoute] 直接
 *    `updateCharacter(copy(speechRouteJson = …))`；无独立路由表可写，亦无「按线路/说话人反查角色」能力；
 * 2. 路由 JSON 一律**归一化**为 `SpeechRoute.toJson()` 的规范串（非法/空串 ⇒ 存空串 = 清除路由）；
 *    归一化后 `isConfigured = false` 时**仍按用户输入落库**（不静默丢弃输入），并在返回值里回告 `configured`；
 * 3. `save()` 走 Room 实体语义：`id = 0` 用 `insertCharacter`（`bookUrl + name` 唯一索引
 *    ⇒ 同名同书为 REPLACE 覆盖），`id > 0` 用 `updateCharacter`；
 * 4. `relationSave()` 不做关系图**去重合并**（同源唯一索引 `bookUrl+from+to+relationName` 由 Room 保证）；
 * 5. 角色 `updatedAt` 由本层统一刷新为当前时间；`createdAt` 保持调用方给值（0 则补当前时间）。
 */
object CharacterKernel {

    private fun BookCharacter.toCard(): Map<String, Any?> = mapOf(
        "id" to id,
        "bookUrl" to bookUrl,
        "name" to name,
        "displayName" to displayName(),
        "avatar" to avatar,
        "gender" to gender,
        "genderLabel" to genderLabel(),
        "identity" to identity,
        "skills" to skills,
        "attributes" to attributes,
        "appearance" to appearance,
        "personality" to personality,
        "biography" to biography,
        "roleLevel" to roleLevel,
        "roleLabel" to roleLabel(),
        "autoCreated" to autoCreated,
        "source" to source,
        "lastDetectedAt" to lastDetectedAt,
        "sortOrder" to sortOrder,
        "speechRouteJson" to speechRouteJson,
        "speechConfigured" to SpeechRoute.fromJson(speechRouteJson).isConfigured,
        "createdAt" to createdAt,
        "updatedAt" to updatedAt,
    )

    private fun BookCharacterRelation.toEdge(): Map<String, Any?> = mapOf(
        "id" to id,
        "bookUrl" to bookUrl,
        "fromCharacterId" to fromCharacterId,
        "toCharacterId" to toCharacterId,
        "relationName" to relationName,
        "relationType" to relationType,
        "displayName" to displayName(),
        "description" to description,
        "strength" to strength,
        "sortOrder" to sortOrder,
        "updatedAt" to updatedAt,
    )

    // ============================================================ 角色

    /** `character_list`：角色列表 / 角色卡（按 `roleLevel DESC, sortOrder ASC, id ASC`）。 */
    suspend fun list(bookUrl: String): Map<String, Any?> {
        require(bookUrl.isNotBlank()) { "bookUrl 不能为空" }
        return withContext(IO) {
            val characters = appDb.bookCharacterDao.characters(bookUrl)
            mapOf(
                "bookUrl" to bookUrl,
                "total" to characters.size,
                "characters" to characters.map { it.toCard() },
            )
        }
    }

    /**
     * `character_save`：保存角色卡（`id = 0` 新增 / `id > 0` 更新）。
     *
     * @throws IllegalArgumentException `bookUrl` 或 `name` 为空
     */
    suspend fun save(character: BookCharacter): Map<String, Any?> {
        require(character.bookUrl.isNotBlank()) { "bookUrl 不能为空" }
        require(character.name.isNotBlank()) { "角色名不能为空" }
        return withContext(IO) {
            val now = System.currentTimeMillis()
            val normalized = character.copy(
                gender = BookCharacter.normalizeGender(character.gender),
                createdAt = if (character.createdAt <= 0) now else character.createdAt,
                updatedAt = now,
            )
            val dao = appDb.bookCharacterDao
            val id = if (normalized.id == 0L) {
                dao.insertCharacter(normalized)
            } else {
                dao.updateCharacter(normalized)
                normalized.id
            }
            val saved = dao.getCharacter(id) ?: normalized
            mapOf("id" to id, "character" to saved.toCard())
        }
    }

    // ============================================================ 关系图

    /** `character_relation_get`：角色关系图（节点 + 边）。 */
    suspend fun relationGet(bookUrl: String): Map<String, Any?> {
        require(bookUrl.isNotBlank()) { "bookUrl 不能为空" }
        return withContext(IO) {
            val dao = appDb.bookCharacterDao
            val characters = dao.characters(bookUrl)
            val relations = dao.relations(bookUrl)
            mapOf(
                "bookUrl" to bookUrl,
                "nodes" to characters.map {
                    mapOf(
                        "id" to it.id,
                        "name" to it.name,
                        "displayName" to it.displayName(),
                        "genderLabel" to it.genderLabel(),
                        "roleLabel" to it.roleLabel(),
                        "avatar" to it.avatar,
                    )
                },
                "edges" to relations.map { it.toEdge() },
                "nodeCount" to characters.size,
                "edgeCount" to relations.size,
            )
        }
    }

    /**
     * `character_relation_save`：保存关系（`id = 0` 新增 / `id > 0` 更新）。
     *
     * @throws IllegalArgumentException `bookUrl` / `relationName` 为空，或两端角色 id 非法
     */
    suspend fun relationSave(relation: BookCharacterRelation): Map<String, Any?> {
        require(relation.bookUrl.isNotBlank()) { "bookUrl 不能为空" }
        require(relation.fromCharacterId > 0 && relation.toCharacterId > 0) {
            "fromCharacterId / toCharacterId 必须为正数"
        }
        require(relation.relationName.isNotBlank()) { "relationName 不能为空" }
        return withContext(IO) {
            val dao = appDb.bookCharacterDao
            val normalized = relation.copy(updatedAt = System.currentTimeMillis())
            val id = if (normalized.id == 0L) {
                dao.insertRelation(normalized)
            } else {
                dao.updateRelation(normalized)
                normalized.id
            }
            val saved = dao.getRelation(id) ?: normalized
            mapOf("id" to id, "relation" to saved.toEdge())
        }
    }

    /** `character_relation_delete`：按 id 删除关系（不存在时 `deleted = false`，不抛异常）。 */
    suspend fun relationDelete(relationId: Long): Map<String, Any?> {
        require(relationId > 0) { "relationId 必须为正数" }
        return withContext(IO) {
            val dao = appDb.bookCharacterDao
            val exists = dao.getRelation(relationId) != null
            if (exists) dao.deleteRelationById(relationId)
            mapOf("relationId" to relationId, "deleted" to exists)
        }
    }

    // ============================================================ 配音路由

    /**
     * `character_voice_route`：设置角色配音路由（持久化到 `BookCharacter.speechRouteJson`）。
     *
     * 报告 §⑲ 已确认**不存在 `character_voice_route` 实体** ⇒ 本方法即 `BookCharacterDao.updateCharacter`
     * 对该字段的更新（见类注释「已知上限 1」）。
     *
     * @param speechRouteJson 路由 JSON（`SpeechRoute` 结构）；**空白 = 清除路由**（存空串）
     * @throws NoSuchElementException 角色不存在
     * @throws IllegalArgumentException `bookUrl` 与角色所属书不一致
     */
    suspend fun voiceRoute(
        characterId: Long,
        bookUrl: String,
        speechRouteJson: String?,
    ): Map<String, Any?> {
        require(characterId > 0) { "characterId 必须为正数" }
        return withContext(IO) {
            val dao = appDb.bookCharacterDao
            val character = dao.getCharacter(characterId)
                ?: throw NoSuchElementException("角色不存在：id=$characterId")
            require(bookUrl.isBlank() || character.bookUrl == bookUrl) {
                "bookUrl 与角色所属书不一致：$bookUrl"
            }
            val raw = speechRouteJson?.trim().orEmpty()
            // 归一化：空串 = 清除；非空则解析后重序列化为规范串（解析失败回落默认路由 → toJson 为空串）
            val stored = if (raw.isEmpty()) "" else SpeechRoute.fromJson(raw).toJson()
            dao.updateCharacter(
                character.copy(speechRouteJson = stored, updatedAt = System.currentTimeMillis())
            )
            mapOf(
                "characterId" to characterId,
                "bookUrl" to character.bookUrl,
                "speechRouteJson" to stored,
                "configured" to SpeechRoute.fromJson(stored).isConfigured,
                "cleared" to stored.isEmpty(),
            )
        }
    }
}