package io.legado.app.ui.book.read.config.casting

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.constant.AppLog
import io.legado.app.data.appDb
import io.legado.app.data.entities.TtsCastingTemplate
import io.legado.app.help.readaloud.casting.CastingMatchType
import io.legado.app.help.readaloud.casting.CastingRule
import io.legado.app.help.readaloud.casting.CastingRuleSet
import io.legado.app.help.readaloud.casting.CastingTag
import io.legado.app.help.readaloud.casting.TtsCastingStore
import io.legado.app.help.readaloud.speech.SpeechRoute
import io.legado.app.model.ReadAloud
import io.legado.app.ui.widget.compose.showComposeConfirmDialog
import io.legado.app.ui.theme.LegadoTheme
import io.legado.app.ui.widget.compose.AppDialogSize
import io.legado.app.ui.widget.compose.ComposeDialogFragment
import io.legado.app.help.readaloud.casting.ReadAloudDelegate
import io.legado.app.utils.GSON
import io.legado.app.utils.sendToClip
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 选角模板管理（AD-10 单 Fragment 三态路由 + AD-11 revision 串行保存）
 * 宿主容器=ComposeDialogFragment 全屏弹窗（对齐 SpeakEngineDialog 先例；纯 Compose 弹窗组件族门禁）
 * 状态机：LIST 模板列表 / EDITOR 规则编辑器 / IMPORT JSON 导入导出；backDestination：EDITOR/IMPORT→LIST
 */
class TtsCastingManageFragment : ComposeDialogFragment() {

    override val dialogSize: AppDialogSize = AppDialogSize.Management

    enum class Route { LIST, EDITOR, IMPORT }

    companion object {
        fun newInstance(): TtsCastingManageFragment = TtsCastingManageFragment()
    }

    /** 编辑器状态（直接操作 CastingRule 模型，无 DTO 转换层，AD-11） */
    data class EditorState(
        val templateId: String,
        val name: String,
        val builtin: Boolean,
        val enabled: Boolean = true,
        val readOnly: Boolean = false,
        val rules: List<CastingRule> = emptyList(),
        val fallbackSourceJson: String = ""
    )

    /** 便捷构造：实体→编辑器状态 */
    private fun EditorState(template: TtsCastingTemplate, readOnly: Boolean): EditorState {
        val ruleSet = CastingRuleSet.fromEntity(template)
        return EditorState(
            templateId = template.id,
            name = template.name,
            builtin = template.builtin,
            enabled = template.enabled,
            readOnly = readOnly,
            rules = ruleSet?.rules ?: emptyList(),
            fallbackSourceJson = template.fallbackSourceJson
        )
    }

    private var route by mutableStateOf(Route.LIST)
    private var templates by mutableStateOf<List<TtsCastingTemplate>>(emptyList())
    private var httpTtsList by mutableStateOf<List<io.legado.app.data.entities.HttpTTS>>(emptyList())
    private var editor by mutableStateOf<EditorState?>(null)

    /** revision 快照修订号：仅最新代结果回写落库（旧代出队后丢弃，防乱序覆盖） */
    private var revision = 0
    private val saveMutex = Mutex()

    /** 校验错误提示（编辑器保存链失败明示，定位到具体规则行） */
    private var editorError by mutableStateOf<String?>(null)

    /** 导入结果提示（IMPORT 态呈现） */
    private var importMessage by mutableStateOf<String?>(null)
    private var importBusy by mutableStateOf(false)

    /** 导出内容（剪贴板/文件双通道承接） */
    private var exportText by mutableStateOf<String?>(null)

    /** 试听控制器（编辑器唯一试听入口；宿主 dismiss/onDestroyView 调 release）
     *  snapshot state：编辑器组合读取 controller.state 获得重组失效（修复试听状态三重断链） */
    private var previewController: TtsVoicePreviewController? by mutableStateOf(null)

    /** 编辑器进入时内容快照（未保存拦截脏比对基准） */
    private var editorSnapshot: String? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                LegadoTheme {
                    when (route) {
                        Route.LIST -> TtsCastingListScreen(
                            templates = templates,
                            onDismiss = { dismissAllowingStateLoss() },
                            onOpenEditor = { openEditor(it) },
                            onCopyToCustom = { copyToCustom(it) },
                            onDelete = { confirmDelete(it) },
                            onToggleEnabled = { toggleEnabled(it) },
                            onAdd = { openEditor(null) },
                            onOpenImport = { route = Route.IMPORT }
                        )

                        Route.EDITOR -> {
                            val ed = editor ?: return@LegadoTheme
                            TtsCastingEditorScreen(
                                state = ed,
                                httpTtsList = httpTtsList,
                                error = editorError,
                                previewState = previewController?.state,
                                onNameChange = { editor = ed.copy(name = it) },
                                onRulesChange = { editor = ed.copy(rules = it) },
                                onFallbackChange = { editor = ed.copy(fallbackSourceJson = it) },
                                onSave = { saveEditor(ed) },
                                onBack = { backFromEditor(ed) },
                                onPreview = { preview(ed, it) },
                                onStopPreview = { previewController?.stopActivePreview() }
                            )
                        }

                        Route.IMPORT -> TtsCastingImportScreen(
                            message = importMessage,
                            busy = importBusy,
                            exportText = exportText,
                            onImport = { json, name -> importJson(json, name) },
                            onExport = { exportActiveTemplate() },
                            onBack = { route = Route.LIST },
                            onCopyExport = { text ->
                                requireContext().sendToClip(text)
                                requireContext().toastOnUi(R.string.tts_casting_copied)
                            }
                        )
                    }
                }
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        lifecycleScope.launch {
            appDb.ttsCastingTemplateDao.observeAll()
                .catch { AppLog.put("选角模板列表获取失败：${it.message}") }
                .collect { templates = it }
        }
        lifecycleScope.launch(Dispatchers.IO) {
            httpTtsList = appDb.httpTTSDao.all
        }
    }

    /** 试听控制器惰性创建（编辑器态进入时接线 beforePreview/恢复回调）
     *  onStateChanged 留空：控制器 state 已是 snapshot state，Compose 组合期直读即可失效重组 */
    private fun preview(ed: EditorState, route: SpeechRoute) {
        val controller = previewController ?: TtsVoicePreviewController(
            context = requireContext().applicationContext,
            scope = lifecycleScope,
            beforePreview = { ReadAloud.pause(requireContext()) },
            onResumeReadAloud = { ReadAloud.resume(requireContext()) },
            onStateChanged = { }
        ).also { previewController = it }
        val target = TtsPreviewTarget(
            previewKey = when (route.engineType) {
                SpeechRoute.ENGINE_SYSTEM -> "system|${route.toneID}"
                else -> "${route.engineValue}|${route.toneID}"
            },
            engineType = route.engineType,
            engineValue = route.engineValue,
            voiceId = route.toneID.ifBlank { null }
        )
        controller.preview(target)
    }

    /** LIST→EDITOR：template=null 新建（默认双规则起步）；进入时快照用于未保存拦截 */
    private fun openEditor(template: TtsCastingTemplate?) {
        val state = when (template) {
            null -> EditorState(
                templateId = "custom_${System.currentTimeMillis()}",
                name = "",
                builtin = false,
                rules = listOf(CastingRule(tag = CastingTag.NARRATION))
            )
            else -> EditorState(template, readOnly = template.builtin)
        }
        editor = state
        editorSnapshot = GSON.toJson(state)
        editorError = null
        route = Route.EDITOR
    }

    /** 编辑器返回：脏比对进入时快照，有未保存修改先确认（防静默丢失） */
    private fun backFromEditor(ed: EditorState) {
        if (GSON.toJson(ed) != editorSnapshot) {
            showComposeConfirmDialog(
                title = getString(R.string.tts_casting_discard_confirm),
                message = getString(R.string.tts_casting_discard_confirm_desc),
                positiveText = getString(R.string.yes),
                negativeText = getString(R.string.no),
                onPositive = { route = Route.LIST }
            )
        } else {
            route = Route.LIST
        }
    }

    /** 复制为自定义：新 templateId+名称"副本"后缀+builtin=false，复制后即可编辑 */
    private fun copyToCustom(template: TtsCastingTemplate) {
        val appContext = requireContext().applicationContext
        lifecycleScope.launch(Dispatchers.IO) {
            val copy = template.copy(
                id = "custom_${System.currentTimeMillis()}",
                name = template.name + appContext.getString(R.string.tts_casting_copy_suffix),
                builtin = false,
                enabled = true,
                lastUpdateTime = System.currentTimeMillis()
            )
            TtsCastingStore.save(copy)
            lifecycleScope.launch { appContext.toastOnUi(R.string.tts_casting_copy_done) }
        }
    }

    private fun confirmDelete(template: TtsCastingTemplate) {
        val appContext = requireContext().applicationContext
        showComposeConfirmDialog(
            title = getString(R.string.draw),
            message = getString(R.string.tts_casting_delete_confirm) + "\n" + template.name,
            positiveText = getString(R.string.yes),
            negativeText = getString(R.string.no),
            onPositive = {
                lifecycleScope.launch(Dispatchers.IO) {
                    TtsCastingStore.deleteById(template.id)
                    lifecycleScope.launch { appContext.toastOnUi(R.string.tts_casting_deleted) }
                }
            }
        )
    }

    /** 启停切换：即改即存+失败回滚明示（乐观更新模式） */
    private fun toggleEnabled(template: TtsCastingTemplate) {
        val appContext = requireContext().applicationContext
        lifecycleScope.launch(Dispatchers.IO) {
            runCatching {
                TtsCastingStore.save(template.copy(enabled = !template.enabled))
            }.onFailure {
                AppLog.put("选角模板启停失败：${it.message}")
                lifecycleScope.launch { appContext.toastOnUi("启停失败：${it.message}") }
            }
        }
    }

    /**
     * 编辑器保存链（AD-11）：revision 串行——出队后/落库前校验 revision，旧代丢弃不触库
     * 校验链固定：同通道→保留字→regex 预编译/pattern 限长→schemaVersion 写入
     */
    private fun saveEditor(ed: EditorState) {
        val appContext = requireContext().applicationContext
        lifecycleScope.launch(Dispatchers.IO) {
            val myRevision = ++revision
            saveMutex.withLock {
                if (myRevision != revision) return@withLock
                // 校验链
                if (ed.rules.isEmpty()) {
                    postEditorError(appContext, -1, "至少需要一条分段规则")
                    return@withLock
                }
                for ((index, rule) in ed.rules.withIndex()) {
                    // 非引号规则 pattern 必填（空 pattern=永不命中，保存即失效）
                    if (rule.matchType != CastingMatchType.BUILTIN_QUOTE && rule.pattern.isBlank()) {
                        postEditorError(appContext, index, "匹配内容不能为空")
                        return@withLock
                    }
                    if (rule.pattern.length > TtsCastingStore.MAX_PATTERN_LENGTH) {
                        postEditorError(appContext, index, "pattern 超 ${TtsCastingStore.MAX_PATTERN_LENGTH} 字符限长")
                        return@withLock
                    }
                    if (rule.matchType == CastingMatchType.REGEX) {
                        runCatching { Regex(rule.pattern) }.getOrElse {
                            postEditorError(appContext, index, "非法正则：${it.message}")
                            return@withLock
                        }
                    }
                }
                val ruleSet = CastingRuleSet(
                    templateId = ed.templateId,
                    name = ed.name.ifBlank { appContext.getString(R.string.tts_casting_default_name) },
                    builtin = ed.builtin,
                    rules = ed.rules,
                    fallbackSourceJson = ed.fallbackSourceJson
                )
                if (!ruleSet.sameChannel()) {
                    postEditorError(appContext, -1, getString(R.string.tts_casting_same_channel_error))
                    return@withLock
                }
                val entity = TtsCastingTemplate(
                    id = ed.templateId,
                    name = ruleSet.name,
                    builtin = ed.builtin,
                    enabled = ed.enabled,
                    sortOrder = 0,
                    rulesJson = GSON.toJson(CastingRuleSet.CastingRulesWrapper(CastingRuleSet.SCHEMA_VERSION, ed.rules)),
                    fallbackSourceJson = ed.fallbackSourceJson,
                    lastUpdateTime = System.currentTimeMillis()
                )
                TtsCastingStore.save(entity)
                lifecycleScope.launch {
                    editorError = null
                    // 保存成功后同步快照，避免返回时误触未保存拦截
                    editorSnapshot = GSON.toJson(ed.copy(name = ruleSet.name))
                    route = Route.LIST
                    appContext.toastOnUi(R.string.tts_casting_saved)
                }
            }
        }
    }

    private fun postEditorError(appContext: android.content.Context, ruleIndex: Int, message: String) {
        lifecycleScope.launch {
            editorError = if (ruleIndex >= 0) {
                appContext.getString(R.string.tts_casting_rule_error_prefix, ruleIndex + 1) + message
            } else {
                message
            }
        }
    }

    private fun importJson(json: String, name: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            importBusy = true
            val result = TtsCastingStore.importFromJson(json, name)
            lifecycleScope.launch {
                importBusy = false
                importMessage = result.error
                    ?: getString(R.string.tts_casting_import_done, result.imported, result.pendingBinding)
            }
        }
    }

    /** 导出激活模板（IMPORT 态导出入口；编辑器内导出当前编辑内容场景登记后续） */
    private fun exportActiveTemplate() {
        lifecycleScope.launch {
            val activeId = TtsCastingStore.activeTemplateId()
            val entity = activeId?.let { TtsCastingStore.get(it) }
            if (entity == null) {
                context?.toastOnUi("当前未激活模板，无可导出内容")
                return@launch
            }
            val ruleSet = CastingRuleSet.fromEntity(entity) ?: return@launch
            exportText = TtsCastingStore.exportToJson(ruleSet)
        }
    }

    override fun onDestroyView() {
        previewController?.release()
        previewController = null
        super.onDestroyView()
    }
}
