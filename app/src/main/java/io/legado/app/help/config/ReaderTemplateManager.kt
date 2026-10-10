package io.legado.app.help.config

import io.legado.app.constant.AppLog
import io.legado.app.model.localBook.epubcore.template.EpubReaderTemplate
import io.legado.app.model.localBook.epubcore.template.ReaderBuiltinTemplateCatalog
import io.legado.app.model.localBook.epubcore.template.ReaderTemplateImportMapper
import io.legado.app.model.localBook.epubcore.template.ReaderTemplatePreferences
import io.legado.app.model.localBook.epubcore.template.ReaderTemplateRepository
import io.legado.app.model.localBook.epubcore.template.ReaderTemplateSelection
import io.legado.app.utils.externalFiles
import io.legado.app.utils.getFile
import splitties.init.appCtx
import java.io.File

/**
 * 阅读页面模板管理器（epub-md-rich-rendering 阶段 4.8a 的数据面 / UI 门面）。
 *
 * 职责边界：
 * - **本对象只做「Android 侧装配 + 用户动作编排」**：常量目录、assets 读取、偏好写入；
 * - 业务判据（能不能用 / 选定哪套 / 翻页记忆）全部留在 `model.localBook.epubcore.template` 的纯策略里，
 *   避免同一规则出现两份（先例教训：口径分裂必然失守）；
 * - [assembleEntries] / [resolveEffectiveId] 为**纯函数**，可在 JVM 单测逐格断言。
 *
 * 内置与用户库的关系（4.5 定下的契约）：
 * - 内置模板来自 `assets/reader/<id>/`（**只读**，不占用户库 id 空间）；
 * - 用户库是 `<根目录>/readerTemplates.json`；用户库中与内置 **同 id** 的条目覆盖内置展示，
 *   其余用户条目排在内置之后（按名称排序）。
 */
object ReaderTemplateManager {

    /** 模板来源。 */
    enum class Source { BUILTIN, USER }

    /** 报告级别（与导入映射的报告口径一致）。 */
    enum class Level { INFO, WARNING, ERROR }

    data class Issue(val level: Level, val code: String, val message: String)

    data class Entry(
        val template: EpubReaderTemplate,
        val source: Source,
        /** 素材与许可声明（`sources.json` 原文；内置模板随包，用户模板为空串）。 */
        val sourcesManifest: String = "",
        /** 导入/升级期的忽略与降级报告（供审计与"导入报告"展示）。 */
        val issues: List<Issue> = emptyList()
    ) {
        val id: String get() = template.id
        val name: String get() = template.name
        val isScroll: Boolean get() = template.isScrolling
    }

    /** 目录快照：合并后的条目 + 加载期诊断（内置缺文件 / 用户库损坏原因）。 */
    data class Catalog(
        val entries: List<Entry>,
        val errors: List<String>
    )

    data class ImportOutcome(val templateId: String?, val issues: List<Issue>) {
        val ok: Boolean get() = templateId != null

        /** 失败原因（无错误项时给通用文案）。 */
        val failureMessage: String
            get() = issues.firstOrNull { it.level == Level.ERROR }?.message ?: "模板导入失败"
    }

    /** 模板库根目录（与摘录分享模板同为用户可见目录，便于用户自行备份）。 */
    val rootDir: File
        get() = appCtx.externalFiles.getFile("readerTemplates").apply { mkdirs() }

    private val repository by lazy { ReaderTemplateRepository(rootDir) }
    private val preferences by lazy { ReaderTemplatePreferences(rootDir) }

    private val assetReader = ReaderBuiltinTemplateCatalog.AssetReader { path ->
        runCatching {
            appCtx.assets.open(path).bufferedReader().use { it.readText() }
        }.getOrNull()
    }

    // === 读取 ===

    /** 合并目录（内置 + 用户）。IO 操作，调用方需在 IO 线程执行。 */
    fun loadCatalog(): Catalog {
        val builtinLoad = ReaderBuiltinTemplateCatalog.load(assetReader)
        val builtin = builtinLoad.templates.map { item ->
            Entry(
                template = item.template,
                source = Source.BUILTIN,
                sourcesManifest = item.sources,
                issues = item.report.map { it.toIssue() }
            )
        }
        val user = repository.list().map { Entry(template = it, source = Source.USER) }
        val errors = builtinLoad.errors.toMutableList()
        repository.lastError?.let { errors += it }
        val entries = assembleEntries(builtin, user)
        // 诊断留痕（blueprint §诊断 Tag 约定：ReaderTemplate）：只记数量与 id/哨兵，
        // 不记名称 —— 用户导入的模板名可能含任意文本，日志不得成为其载体。
        AppLog.putDebugWithTag(
            AppLog.TAG_READER_TEMPLATE,
            "catalog: builtin=${builtin.size} user=${user.size} applied=${appliedId().ifBlank { "follow-theme" }} " +
                "available=${entries.size} errors=${errors.size}"
        )
        return Catalog(entries, errors)
    }

    /** 当前可用模板 id 全集（顺序即「最后兜底」优先级）。 */
    fun availableIds(): List<String> = loadCatalog().entries.map { it.id }

    /**
     * 内置模板 id 集合（判定"能否删除/编辑"）。
     *
     * 用**目录声明**而非"加载成功的条目"：声明了但文件损坏的内置模板同样是只读资产，
     * 不应因为它加载失败就变成"可被用户删除"。
     */
    fun builtinIds(): Set<String> =
        ReaderBuiltinTemplateCatalog.catalogIds(assetReader).getOrDefault(emptyList()).toSet()

    /** 用户当前**显式应用**的值（空串 = 跟随主题）。 */
    fun appliedId(): String = AppConfig.readerTemplate

    /** 实际生效的模板 id；无任何可用模板时返回 null。 */
    fun effectiveId(preferNight: Boolean): String? =
        resolveEffectiveId(appliedId(), preferNight, availableIds())

    // === 用户动作 ===

    /** 应用指定模板；id 不在可用集内（且非"跟随主题"哨兵）时拒绝。 */
    fun apply(id: String): Boolean {
        if (!ReaderTemplateSelection.isFollowTheme(id) && id !in availableIds()) return false
        AppConfig.readerTemplate = id
        return true
    }

    /** 恢复「跟随主题」（按日夜取默认款）。 */
    fun applyFollowTheme() {
        AppConfig.readerTemplate = ReaderTemplateSelection.FollowTheme
    }

    /** 复制模板为新用户条目；源不存在或入库失败返回 null。 */
    fun duplicate(id: String): EpubReaderTemplate? = repository.duplicate(id)

    /** 导入单模板 JSON（含 archive v1 兼容）并入库。 */
    fun importJson(source: String): ImportOutcome {
        val result = repository.importJson(source)
        return ImportOutcome(result.template?.id, result.report.map { it.toIssue() })
    }

    /** 导出模板 JSON（内置与用户模板皆可导出）。 */
    fun exportJson(id: String): String? =
        loadCatalog().entries.firstOrNull { it.id == id }?.template?.toJson()

    /**
     * 覆盖写入用户模板（编辑器保存路径）。
     *
     * 内置模板**不允许**原地覆盖（来自 assets，只读）：调用方应先复制再编辑。
     */
    fun saveUserTemplate(template: EpubReaderTemplate): Boolean {
        if (template.id in builtinIds()) return false
        return repository.save(template)
    }

    /** 删除用户模板（内置模板拒绝删除）；同时清理其翻页记忆与选中态。 */
    fun deleteUserTemplate(id: String): Boolean {
        if (id in builtinIds()) return false
        val removed = repository.delete(id)
        if (removed) {
            preferences.forget(id)
            if (AppConfig.readerTemplate == id) applyFollowTheme()
        }
        return removed
    }

    /** 清空用户模板（内置来自 assets，不受影响）。 */
    fun clearUserTemplates(): Int = repository.clearUserTemplates()

    /**
     * 恢复默认：清空用户模板 + 清空模板偏好 + 选中态回到「跟随主题」。
     *
     * 这是"用户库损坏后不空库、但可显式重置"的出口（4.5 契约），也是 4.8a 的「恢复默认」动作。
     */
    fun restoreDefaults(): Int {
        val removed = repository.clearUserTemplates()
        preferences.clear()
        applyFollowTheme()
        return removed
    }

    /** 最近一次模板库操作失败原因（成功为 null；供 UI 显示具体原因而非通用错误）。 */
    fun lastLibraryError(): String? = repository.lastError

    /** 每模板翻页记忆（4.6；无记忆返回 null）。 */
    fun pageTurnMode(id: String): Int? = preferences.pageTurnMode(id)?.value

    // === 纯函数（可 JVM 单测） ===

    /**
     * 合并内置与用户条目。
     *
     * 规则（顺序即语义）：
     * 1. 内置条目**保持目录声明顺序**（目录清单是可被测试逐项校验的单源）；
     * 2. 用户库中与内置**同 id** 的条目就地覆盖内置（避免同名两条）；其余用户条目按名称排序追加。
     */
    fun assembleEntries(builtin: List<Entry>, user: List<Entry>): List<Entry> {
        val userById = user.associateBy { it.id }
        val merged = ArrayList<Entry>(builtin.size + user.size)
        val seen = HashSet<String>(builtin.size + user.size)
        builtin.forEach { entry ->
            if (seen.add(entry.id)) merged += (userById[entry.id] ?: entry)
        }
        user.filter { it.id !in seen }
            .sortedBy { it.name }
            .forEach { entry ->
                if (seen.add(entry.id)) merged += entry
            }
        return merged
    }

    /** 选定生效模板 id（委托纯策略，保持"选定规则只有一份"）。 */
    fun resolveEffectiveId(applied: String, preferNight: Boolean, available: List<String>): String? =
        ReaderTemplateSelection.resolveId(applied, preferNight, available)

    private fun ReaderTemplateImportMapper.ReportEntry.toIssue(): Issue = Issue(
        level = when (level) {
            ReaderTemplateImportMapper.Level.INFO -> Level.INFO
            ReaderTemplateImportMapper.Level.WARNING -> Level.WARNING
            ReaderTemplateImportMapper.Level.ERROR -> Level.ERROR
        },
        code = code,
        message = message
    )
}