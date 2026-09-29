package io.legado.app.service.kernel

import io.legado.app.data.appDb
import io.legado.app.data.entities.HttpTTS
import io.legado.app.data.entities.ReadAloudBgmGroup
import io.legado.app.data.entities.ReadAloudBgmTrack
import io.legado.app.data.entities.ReadAloudSpeakerGroup
import io.legado.app.data.entities.ReadAloudSpeakerGroupItem
import io.legado.app.data.entities.TtsCastingTemplate
import io.legado.app.help.config.AppConfig
import io.legado.app.help.readaloud.casting.TtsCastingStore
import io.legado.app.help.readaloud.speech.SpeechRoute
import io.legado.app.help.readaloud.speech.TtsEngineParamsStore
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.withContext

/**
 * ⑦ TTS 听书域**配置侧**内核（web-mcp-productization 二期 · tasks 2.13 / 2.28）。
 *
 * 契约同 [BookKernel]：只返回结构化 Map、全链挂起、零 `runBlocking`、失败抛异常。
 *
 * 覆盖：引擎清单 / HTTP TTS CRUD / 朗读参数 / 实际试听目标校验 / 说话人分组 / 配音模板 / BGM。
 * **播放控制**（听书 + 有声书）在 [AudioKernel]（同域拆两文件：配置 vs 控制，职责单一）。
 *
 * 两个**刻意的降级**（已记 AOAdapt，均为既有能力缺口所致）：
 * 1. `tts_test`：真实试听由 UI 层 `TtsVoicePreviewController` 驱动（需 Context + Compose 状态回调 +
 *    会真实发声），无法在无界面上下文安全构造 ⇒ 本工具只做**试听目标校验与回告**；
 * 2. `read_aloud_bgm_save`：ZIP 音频包导入的实现是
 *    `ui/book/read/config/ReadAloudBgmManageActivity` 的**私有**方法（不可复用）⇒
 *    本工具只做**分组/音轨配置保存**，不接 ZIP 导入。
 */
object TtsKernel {

    /** 内置引擎（`ENGINE_DEFAULT` = 跟随系统设置，`ENGINE_SYSTEM` = 系统 TTS）。 */
    private val BUILTIN_ENGINES = listOf(
        mapOf("engineValue" to SpeechRoute.ENGINE_DEFAULT, "name" to "跟随系统设置"),
        mapOf("engineValue" to SpeechRoute.ENGINE_SYSTEM, "name" to "系统 TTS"),
    )

    private fun currentEngine(): String = AppConfig.ttsEngine?.takeIf { it.isNotBlank() } ?: SpeechRoute.ENGINE_DEFAULT

    // ============================================================ 引擎 / HTTP TTS

    private fun HttpTTS.toBrief(): Map<String, Any?> = mapOf(
        "id" to id,
        "name" to name,
        "type" to type,
        "enabledCookieJar" to (enabledCookieJar == true),
        "lastUpdateTime" to lastUpdateTime,
    )

    private fun HttpTTS.toDetail(): Map<String, Any?> = toBrief() + mapOf(
        "concurrentRate" to concurrentRate,
        "synthesisThreadCount" to synthesisThreadCount,
        // URL 属配置数据，但可能含敏感 query ⇒ 统一打码（与诊断域同一实现）
        "url" to DiagKernel.maskUrl(url),
    )

    /** `tts_engines_get`：引擎清单（内置 + HTTP TTS）+ 当前引擎。 */
    suspend fun engines(): Map<String, Any?> = withContext(IO) {
        val httpList = appDb.httpTTSDao.all
        mapOf(
            "current" to currentEngine(),
            "builtin" to BUILTIN_ENGINES,
            "httpEngines" to httpList.map { it.toBrief() },
            "count" to (BUILTIN_ENGINES.size + httpList.size),
        )
    }

    /** `http_tts_list`：HTTP TTS 全量（含类型与配置摘要）。 */
    suspend fun httpTtsList(): Map<String, Any?> = withContext(IO) {
        val all = appDb.httpTTSDao.all
        mapOf("total" to all.size, "engines" to all.map { it.toDetail() })
    }

    /** `http_tts_save`：新增 / 覆盖 HTTP TTS（`id` 相同即覆盖，DAO 为 REPLACE 语义）。 */
    suspend fun httpTtsSave(spec: HttpTTS): HttpTTS = withContext(IO) {
        require(spec.name.isNotBlank()) { "HTTP TTS 名称不能为空" }
        require(spec.url.isNotBlank()) { "HTTP TTS url 不能为空" }
        appDb.httpTTSDao.insert(spec)
        spec
    }

    /** `http_tts_delete`：按 id 删除 HTTP TTS。 */
    suspend fun httpTtsDelete(id: Long): Map<String, Any?> = withContext(IO) {
        val exist = appDb.httpTTSDao.get(id) ?: throw NoSuchElementException("HTTP TTS 不存在：$id")
        appDb.httpTTSDao.delete(exist)
        mapOf("deleted" to id, "name" to exist.name)
    }

    // ============================================================ 朗读参数

    /** `tts_config_get`：朗读参数（全局 + 当前引擎级参数）。 */
    suspend fun configGet(): Map<String, Any?> = withContext(IO) {
        val engine = currentEngine()
        val params = TtsEngineParamsStore.get(engine)
        mapOf(
            "engine" to engine,
            "followSystem" to AppConfig.ttsFlowSys,
            "speechRate" to AppConfig.ttsSpeechRate,
            "speechRatePlay" to AppConfig.speechRatePlay,
            "timerMinutes" to AppConfig.ttsTimer,
            "timerMode" to AppConfig.ttsTimerMode,
            "timerChapters" to AppConfig.ttsTimerChapters,
            "paragraphPauseMs" to AppConfig.ttsParagraphPauseMs,
            "engineParams" to mapOf(
                "speechRate" to params.speechRate,
                "pitch" to params.pitch,
                "volume" to params.volume,
            ),
        )
    }

    /**
     * `tts_config_save`：保存朗读参数（**只覆盖传入的字段**，其余沿用现值 —— 防"改语速把定时清空"）。
     * 引擎级参数（语速/音调/音量）落在 `engine` 指定的引擎上（缺省 = 当前引擎）；结束时回读落地结果。
     */
    suspend fun configSave(
        engine: String? = null,
        followSystem: Boolean? = null,
        speechRate: Int? = null,
        timerMinutes: Int? = null,
        timerMode: Int? = null,
        timerChapters: Int? = null,
        paragraphPauseMs: Int? = null,
        engineSpeechRate: Float? = null,
        enginePitch: Float? = null,
        engineVolume: Float? = null,
    ): Map<String, Any?> = withContext(IO) {
        engine?.takeIf { it.isNotBlank() }?.let { AppConfig.ttsEngine = it }
        followSystem?.let { AppConfig.ttsFlowSys = it }
        speechRate?.let { AppConfig.ttsSpeechRate = it }
        timerMinutes?.let { AppConfig.ttsTimer = it }
        timerMode?.let { AppConfig.ttsTimerMode = it }
        timerChapters?.let { AppConfig.ttsTimerChapters = it }
        paragraphPauseMs?.let { AppConfig.ttsParagraphPauseMs = it }

        if (engineSpeechRate != null || enginePitch != null || engineVolume != null) {
            val target = engine?.takeIf { it.isNotBlank() } ?: currentEngine()
            val cur = TtsEngineParamsStore.get(target)
            TtsEngineParamsStore.save(
                target,
                engineSpeechRate ?: cur.speechRate,
                enginePitch ?: cur.pitch,
                engineVolume ?: cur.volume,
            )
        }
        configGet()
    }

    /**
     * `tts_test`：试听目标校验（**不发声** —— 见类注释的降级说明）。
     *
     * 返回解析后的语音路由 + 该引擎的参数，供 AI 判断"要试听什么"。
     */
    suspend fun test(
        engineValue: String? = null,
        speakerName: String? = null,
        toneId: String? = null,
    ): Map<String, Any?> = withContext(IO) {
        val route = SpeechRoute.resolveSpeechRoute(engineValue ?: AppConfig.ttsEngine)
        val params = TtsEngineParamsStore.get(route.engineValue.ifBlank { SpeechRoute.ENGINE_DEFAULT })
        mapOf(
            "engineType" to route.engineType,
            "engineValue" to route.engineValue,
            "speakerName" to (speakerName ?: route.speakerName),
            "toneId" to (toneId ?: route.toneID),
            "isConfigured" to route.isConfigured,
            "params" to mapOf(
                "speechRate" to params.speechRate,
                "pitch" to params.pitch,
                "volume" to params.volume,
            ),
            "previewed" to false,
            "note" to "试听需端侧发声（UI 层控制器）；本工具只回告校验后的目标参数。",
        )
    }

    // ============================================================ 说话人分组

    private fun ReadAloudSpeakerGroupItem.toBrief(): Map<String, Any?> = mapOf(
        "id" to id,
        "groupId" to groupId,
        "engineType" to engineType,
        "engineValue" to engineValue,
        "engineName" to engineName,
        "speakerName" to speakerName,
        "toneID" to toneID,
        "sortOrder" to sortOrder,
    )

    private fun ReadAloudSpeakerGroup.toBrief(dao: io.legado.app.data.dao.ReadAloudSpeakerGroupDao): Map<String, Any?> =
        mapOf(
            "id" to id,
            "name" to name,
            "displayName" to displayName(),
            "enabled" to enabled,
            "sortOrder" to sortOrder,
            "items" to dao.itemsByGroup(id).map { it.toBrief() },
        )

    /** `speaker_groups_get`：朗读角色分组（含组内成员）。 */
    suspend fun speakerGroupsGet(): Map<String, Any?> = withContext(IO) {
        val dao = appDb.readAloudSpeakerGroupDao
        val groups = dao.groups()
        mapOf("total" to groups.size, "groups" to groups.map { it.toBrief(dao) })
    }

    /**
     * `speaker_group_save`：保存分组（`groupId <= 0` 新增，否则按 id 更新）+ 可选覆盖组内成员。
     *
     * 实体字段全为 `val` ⇒ 更新走 `copy`（Room 实体不可变，与 App 内同口径）。
     */
    suspend fun speakerGroupSave(
        groupId: Long = 0,
        name: String? = null,
        enabled: Boolean? = null,
        items: List<ReadAloudSpeakerGroupItem>? = null,
    ): Map<String, Any?> = withContext(IO) {
        val dao = appDb.readAloudSpeakerGroupDao
        val now = System.currentTimeMillis()
        val savedId: Long = if (groupId <= 0) {
            val groupName = name?.takeIf { it.isNotBlank() }
                ?: throw IllegalArgumentException("新增分组需提供 name")
            val sort = (dao.maxGroupOrder() ?: 0) + 1
            dao.insertGroup(
                ReadAloudSpeakerGroup(name = groupName, enabled = enabled ?: true, sortOrder = sort, updatedAt = now)
            )
        } else {
            val exist = dao.groups().firstOrNull { it.id == groupId }
                ?: throw NoSuchElementException("分组不存在：$groupId")
            dao.updateGroup(
                exist.copy(
                    name = name?.takeIf { it.isNotBlank() } ?: exist.name,
                    enabled = enabled ?: exist.enabled,
                    updatedAt = now,
                )
            )
            groupId
        }

        if (items != null) {
            dao.deleteItemsByGroup(savedId)
            if (items.isNotEmpty()) {
                dao.insertItems(items.mapIndexed { index, item ->
                    item.copy(groupId = savedId, sortOrder = index, updatedAt = now)
                })
            }
        }
        mapOf("groupId" to savedId, "itemCount" to (items?.size ?: -1))
    }

    // ============================================================ 配音模板

    /** `tts_casting_get`：配音模板列表 + 当前激活模板 id。 */
    suspend fun castingGet(): Map<String, Any?> = withContext(IO) {
        val all = TtsCastingStore.all()
        mapOf(
            "total" to all.size,
            "activeTemplateId" to TtsCastingStore.activeTemplateId(),
            "templates" to all.map { it.toBrief() },
        )
    }

    private fun TtsCastingTemplate.toBrief(): Map<String, Any?> = mapOf(
        "id" to id,
        "name" to name,
        "builtin" to builtin,
        "enabled" to enabled,
        "sortOrder" to sortOrder,
        "lastUpdateTime" to lastUpdateTime,
    )

    /** `tts_casting_save`：保存 / 覆盖配音模板。 */
    suspend fun castingSave(template: TtsCastingTemplate): TtsCastingTemplate = withContext(IO) {
        require(template.name.isNotBlank()) { "配音模板名不能为空" }
        TtsCastingStore.save(template)
        template
    }

    // ============================================================ BGM（配乐 / 音效）

    /** `read_aloud_bgm_get`：BGM 分组与音轨（按 `assetType` 过滤：bgm / sfx；空 = 全部）。 */
    suspend fun bgmGet(assetType: String? = null): Map<String, Any?> = withContext(IO) {
        val dao = appDb.readAloudBgmDao
        val type = assetType?.takeIf { it.isNotBlank() }?.let { ReadAloudBgmTrack.normalizeAssetType(it) }
        val groups: List<ReadAloudBgmGroup> = if (type == null) dao.groups() else dao.groupsByType(type)
        val tracks = if (type == null) dao.enabledTracks() else dao.enabledTracksByType(type)
        mapOf(
            "assetType" to (type ?: "all"),
            "groups" to groups.map { g ->
                mapOf(
                    "id" to g.id,
                    "name" to g.name,
                    "displayName" to g.displayName(),
                    "assetType" to g.assetType,
                    "sortOrder" to g.sortOrder,
                )
            },
            "tracks" to tracks.map { it.toBrief() },
        )
    }

    private fun ReadAloudBgmTrack.toBrief(): Map<String, Any?> = mapOf(
        "id" to id,
        "groupId" to groupId,
        "assetType" to normalizedAssetType(),
        "name" to name,
        "displayName" to displayName(),
        "durationMs" to durationMs,
        "defaultVolume" to defaultVolume,
        "enabled" to enabled,
        "sortOrder" to sortOrder,
    )

    /**
     * `read_aloud_bgm_save`：保存 BGM 分组 / 音轨配置（**不接 ZIP 导入** —— 见类注释降级说明）。
     *
     * - `group`：`id <= 0` 新增分组，否则按 id 更新；
     * - `tracks`：非空时按 id upsert（`id <= 0` 新增，否则更新）。
     */
    suspend fun bgmSave(
        group: ReadAloudBgmGroup? = null,
        tracks: List<ReadAloudBgmTrack>? = null,
    ): Map<String, Any?> = withContext(IO) {
        val dao = appDb.readAloudBgmDao
        val now = System.currentTimeMillis()
        var savedGroupId: Long? = null
        group?.let { g ->
            savedGroupId = if (g.id <= 0) {
                require(g.name.isNotBlank()) { "新增 BGM 分组需提供 name" }
                val sort = (dao.maxGroupOrderByType(g.assetType) ?: 0) + 1
                dao.insertGroup(
                    ReadAloudBgmGroup(
                        name = g.name,
                        assetType = ReadAloudBgmTrack.normalizeAssetType(g.assetType),
                        sortOrder = sort,
                        updatedAt = now,
                    )
                )
            } else {
                dao.updateGroup(g.copy(updatedAt = now))
                g.id
            }
        }
        var trackCount = 0
        tracks?.forEach { track ->
            if (track.id <= 0) {
                dao.insertTrack(track.copy(groupId = savedGroupId ?: track.groupId, updatedAt = now))
            } else {
                dao.updateTrack(track.copy(updatedAt = now))
            }
            trackCount++
        }
        mapOf("groupId" to savedGroupId, "savedTracks" to trackCount)
    }
}