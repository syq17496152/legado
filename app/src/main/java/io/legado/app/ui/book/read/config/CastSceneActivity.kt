package io.legado.app.ui.book.read.config

import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import androidx.viewbinding.ViewBinding
import io.legado.app.base.BaseActivity
import io.legado.app.data.appDb
import io.legado.app.data.entities.ReadAloudBgmTrack
import io.legado.app.help.book.characterBookKey
import io.legado.app.help.config.AppConfig
import io.legado.app.help.readaloud.bgm.ReadAloudBgmPlayer
import io.legado.app.help.readaloud.casting.BuiltinCastingSetup
import io.legado.app.help.readaloud.casting.AiTaskRouter
import io.legado.app.help.readaloud.casting.CharacterVoiceAssignService
import io.legado.app.help.readaloud.casting.EffectiveReadAloudConfig
import io.legado.app.help.readaloud.casting.ReadAloudOneTapFlow
import io.legado.app.help.readaloud.casting.TtsCastingStore
import io.legado.app.ui.book.read.config.casting.TtsCastingManageFragment
import io.legado.app.help.readaloud.role.BookAliasStore
import io.legado.app.help.readaloud.role.OfflineCharacterDiscovery
import io.legado.app.help.readaloud.role.OfflineRoleResolver
import io.legado.app.help.readaloud.role.ScriptPhase
import io.legado.app.help.readaloud.role.ScriptStatusBus
import io.legado.app.help.tts.builtin.BuiltinTtsRuntime
import io.legado.app.model.ReadAloud
import io.legado.app.model.ReadBook
import io.legado.app.service.BaseReadAloudService
import io.legado.app.service.ScriptGenerationPlan
import io.legado.app.service.ScriptGenerationService
import io.legado.app.ui.theme.LegadoTheme
import io.legado.app.utils.LogUtils
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.startActivity
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 听书中心（T5.1 / ui-design §2/§8）：阅读页唯一听书入口，三卡选演播方式 + 一键开始听书。
 * 4.3/4.4：阅读菜单的三个并列入口收敛到本页；卡片 4→3，「自定义模板/预合成/引擎管理」进「高级」区。
 *
 * 口径（AD-09）：卡=已有内置模板（单声/双声/沉浸多角色），不自造第二套模板；
 * 激活仍走唯一收敛点 [TtsCastingStore.setActiveTemplateId]；多角色就绪沿用
 * 角色发现 → 离线分镜 → 配声 → 激活模板（与开书向导同一套服务，避免双实现）。
 */
class CastSceneActivity : BaseActivity<ViewBinding>() {

    private var snapshot by mutableStateOf(
        SceneSnapshot(emptyList(), "", "", false, false, CastSceneState.CARD_MONO)
    )
    private var busy by mutableStateOf(false)
    private var message by mutableStateOf("")
    /** T8.4 配乐开关：真实状态来自用户偏好（与朗读服务读同一处） */
    private var bgmEnabled by mutableStateOf(AppConfig.aiReadAloudBgmEnabled)
    private var bgmTrackCount by mutableStateOf(0)
    /** 3.5：开页自动生成剧本只排一次（用户重复进出页面不再重复烧电） */
    private var autoScriptPending = true

    override val binding: ViewBinding by lazy {
        val view = ComposeView(this).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                LegadoTheme {
                    CastSceneScreen(
                        snapshot = snapshot,
                        busy = busy,
                        message = message,
                        bgmEnabled = bgmEnabled,
                        bgmTrackCount = bgmTrackCount,
                        onSelect = ::selectCard,
                        onToggleBgm = ::toggleBgm,
                        onOpenVoices = { startActivity<BuiltinTtsActivity>() },
                        onOpenBgm = { startActivity<ReadAloudBgmManageActivity>() },
                        onOpenTemplates = { showDialogFragment(TtsCastingManageFragment.newInstance()) },
                        onOpenPrebuild = { showDialogFragment(TtsPrebuildDialog.newInstance()) },
                        onOpenAssets = { startActivity<VoiceAssetCenterActivity>() },
                        onOpenStoryboard = {
                            startActivity<BuiltinTtsActivity> {
                                putExtra(BuiltinTtsActivity.EXTRA_TAB, BuiltinTtsActivity.TAB_STORYBOARD)
                            }
                        },
                        onOpenTuning = { showDialogFragment(ReadAloudConfigDialog()) },
                        onOpenDiagnostics = {
                            startActivity<BuiltinTtsActivity> {
                                putExtra(BuiltinTtsActivity.EXTRA_TAB, BuiltinTtsActivity.TAB_ENGINE)
                            }
                        },
                        // 4.6：角色配音 / 开书向导入口收敛到听书中心高级区（深链二级页页签，不复制实现）
                        onOpenPage = { tabKey ->
                            startActivity<BuiltinTtsActivity> {
                                putExtra(BuiltinTtsActivity.EXTRA_TAB, tabKey)
                            }
                        },
                        onGenerateScript = { requestScript(force = true) },
                        onPrimary = ::primaryAction
                    )
                }
            }
        }
        object : ViewBinding {
            override fun getRoot(): View = view
        }
    }

    override fun onActivityCreated(savedInstanceState: android.os.Bundle?) {
        BuiltinTtsRuntime.init(this)
        // 3.7/3.5：剧本状态由生产者推送（生成中/已生成/降级），页面订阅后自动刷新，不做轮询
        statusObserver = ScriptStatusBus.observe {
            runCatching { reload() }
        }
        reload()
    }

    private var statusObserver: (() -> Unit)? = null

    override fun onDestroy() {
        runCatching { statusObserver?.invoke() }
        statusObserver = null
        super.onDestroy()
    }

    /** 3.5 生成本章剧本：手动触发只做当前章（force 重算）；自动触发连同下一章预取 */
    private fun requestScript(force: Boolean) {
        val book = ReadBook.book ?: return
        val chapter = ReadBook.curTextChapter ?: return
        val index = chapter.chapter.index
        val chapters = ScriptGenerationPlan.chaptersFor(index, force)
        if (chapters.isEmpty()) return
        ScriptGenerationService.request(this, book.bookUrl, chapters, force)
        message = if (force) "已开始重新生成第 $index 章剧本" else "已开始生成第 $index 章剧本"
    }

    private fun reload() {
        lifecycleScope.launch { reloadState() }
    }

    private data class Facts(
        val engineReady: Boolean,
        val characterCount: Int,
        val assignedCount: Int,
        val storyboardSegments: Int,
        val activeTemplateId: String?,
        val customName: String,
        val hasBook: Boolean,
        val bgmTrackCount: Int,
        val voiceCount: Int = 0,
        val templateCount: Int = 0,
        val storageBytes: Long = 0L,
        /** 3.7 本章剧本状态（未生成/生成中/已生成/降级），无书为空串 */
        val scriptStatusLabel: String = "",
        /** 1.7 顶部胶囊：档位名 + 剧本两态 */
        val engineTitle: String = "",
        val scriptReady: Boolean = false,
        val scriptDegraded: Boolean = false,
        /** 3.5 剧本状态机原值（自动触发只用 NONE 判定，避免 BUILDING 时重复排队） */
        val scriptPhase: ScriptPhase = ScriptPhase.NONE,
        /** 3.5 本地 AI 路由可用 ⇒ 本章准备卡出现「生成剧本」按钮 */
        val canGenerateScript: Boolean = false,
        /** 1.7 本书是否有书级覆盖（模板/语速）⇒ 顶部「本书已覆盖」徽标 */
        val bookOverride: Boolean = false
    )

    private suspend fun reloadState() {
        // G-43：DAO/文件读取一律在 IO（主线程只做状态赋值）
        val facts = withContext(Dispatchers.IO) { loadFacts() }
        snapshot = CastSceneState.evaluate(
            engineReady = facts.engineReady,
            characterCount = facts.characterCount,
            assignedCount = facts.assignedCount,
            storyboardSegments = facts.storyboardSegments,
            activeTemplateId = facts.activeTemplateId,
            customName = facts.customName,
            hasBook = facts.hasBook,
            voiceCount = facts.voiceCount,
            templateCount = facts.templateCount,
            bgmCount = facts.bgmTrackCount,
            storageBytes = facts.storageBytes,
            scriptStatusLabel = facts.scriptStatusLabel,
            engineTitle = facts.engineTitle,
            scriptReady = facts.scriptReady,
            scriptDegraded = facts.scriptDegraded,
            canGenerateScript = facts.canGenerateScript,
            bookOverride = facts.bookOverride
        )
        // 3.5 自动生成：开页时当前章还没剧本且本地 AI 可用 ⇒ 排一次 P0（只排一次，不反复触发）
        if (autoScriptPending && facts.canGenerateScript && facts.scriptPhase == ScriptPhase.NONE) {
            autoScriptPending = false
            requestScript(force = false)
        }
        bgmEnabled = AppConfig.aiReadAloudBgmEnabled
        bgmTrackCount = facts.bgmTrackCount
    }

    private suspend fun loadFacts(): Facts {
        runCatching { BuiltinTtsRuntime.ensureSeeded() }
        // 开箱即用（spec R2 / 4.11）：引擎就绪只看档位 READY，**不再要求音色数 > 0**
        // —— 单声档（vits 内置默认音色）本来就没有音色条目，旧判据会让它永远显示"未就绪"。
        val activeProfile = runCatching { BuiltinTtsRuntime.activeProfile() }.getOrNull()
        val engineReady = activeProfile != null && BuiltinTtsRuntime.isReady()
        val book = ReadBook.book
        val chapter = ReadBook.curTextChapter
        val bookKey = book?.characterBookKey()
        val characters = bookKey?.let {
            runCatching { CharacterVoiceAssignService.roleCharacters(it) }.getOrDefault(emptyList())
        }.orEmpty()
        val assigned = bookKey?.let {
            runCatching { CharacterVoiceAssignService.rows(it) }.getOrDefault(emptyList())
        }.orEmpty().count { it.configured }
        val storyboard = if (bookKey != null && chapter != null) {
            runCatching { OfflineRoleResolver.cached(bookKey, chapter.chapter.index)?.size ?: 0 }
                .getOrDefault(0)
        } else {
            0
        }
        // 3.7 剧本状态：有推送用推送，否则按磁盘剧本推导（已生成/降级/未生成）
        val scriptStatus = if (bookKey != null && chapter != null) {
            runCatching { ScriptStatusBus.resolve(bookKey, chapter.chapter.index) }.getOrNull()
        } else {
            null
        }
        val scriptStatusLabel = scriptStatus?.let {
            runCatching { ScriptStatusBus.labelOf(it) }.getOrDefault("")
        }.orEmpty()
        val activeId = TtsCastingStore.activeTemplateId()
        // G-43：DAO 读取必须显式 IO 包裹（静态门禁按函数体扫描，不能只依赖外层 withContext）
        val customName = activeId?.let { id ->
            withContext(Dispatchers.IO) {
                runCatching { appDb.ttsCastingTemplateDao.get(id)?.name }.getOrNull()
            }
        }.orEmpty()
        // T8.4：可用配乐数量（决定开关能否打开 / 摘要文案）
        val bgmTrackCount = withContext(Dispatchers.IO) {
            runCatching {
                appDb.readAloudBgmDao.enabledTracksByType(ReadAloudBgmTrack.TYPE_BGM).size
            }.getOrDefault(0)
        }
        // 资源概览（spec R2 三步引导第②步 + 4.9 资源中心的最小可见面）：
        // 音色数取当前档位可用音色；单声档按引擎自带默认音色记 1（否则引导第②步永远不亮）
        val voiceCount = runCatching {
            when {
                activeProfile == null -> 0
                activeProfile.requiresReference || activeProfile.multiSpeaker ->
                    BuiltinTtsRuntime.voices().size
                else -> 1
            }
        }.getOrDefault(0)
        val templateCount = runCatching { TtsCastingStore.all().size }.getOrDefault(0)
        val storageBytes = runCatching { BuiltinTtsRuntime.storageBreakdown().totalBytes }.getOrDefault(0L)
        return Facts(
            engineReady = engineReady,
            characterCount = characters.size,
            assignedCount = assigned,
            storyboardSegments = storyboard,
            activeTemplateId = activeId,
            customName = customName,
            hasBook = book != null,
            bgmTrackCount = bgmTrackCount,
            voiceCount = voiceCount,
            templateCount = templateCount,
            storageBytes = storageBytes,
            scriptStatusLabel = scriptStatusLabel,
            engineTitle = activeProfile?.title.orEmpty(),
            scriptReady = scriptStatus?.phase == ScriptPhase.READY ||
                    scriptStatus?.phase == ScriptPhase.DEGRADED,
            scriptDegraded = scriptStatus?.phase == ScriptPhase.DEGRADED,
            scriptPhase = scriptStatus?.phase ?: ScriptPhase.NONE,
            canGenerateScript = book != null && chapter != null &&
                    AiTaskRouter.routeFor(onlineConfigured = false).executor == AiTaskRouter.Executor.LOCAL,
            // 1.7：书级覆盖徽标（只读 prefs，无挂起点）
            bookOverride = EffectiveReadAloudConfig.hasBookOverride(bookKey)
        )
    }

    /**
     * T8.4 配乐开关：无可用配乐时不写偏好、直接引导去配乐库添加；
     * 正在朗读时立即生效（开=起播、关=停播），避免"改了要等下次播放"。
     */
    private fun toggleBgm(enabled: Boolean) {
        if (enabled && !CastSceneState.canEnableBgm(bgmTrackCount)) {
            toastOnUi("配乐库还没有已启用的配乐，先添加一首")
            startActivity<ReadAloudBgmManageActivity>()
            return
        }
        AppConfig.aiReadAloudBgmEnabled = enabled
        bgmEnabled = enabled
        message = if (enabled) "已开启配乐：朗读时与语音混音" else "已关闭配乐"
        if (BaseReadAloudService.isRun) {
            if (enabled) {
                ReadAloudBgmPlayer.start(applicationContext)
            } else {
                ReadAloudBgmPlayer.stop()
            }
        }
    }

    /**
     * 选卡：幂等导入内置模板并激活对应模板。
     * 4.4 起卡片只有单声/双声/多角色三张；「自定义模板」入口改由高级区 chip 触发
     * （[CastSceneState.templateOf] 对未知 cardId 返回 null ⇒ 这里直接返回，不会误激活）。
     */
    private fun selectCard(cardId: String) {
        val template = CastSceneState.templateOf(cardId) ?: return
        lifecycleScope.launch {
            busy = true
            message = ""
            importBuiltinTemplates()
            TtsCastingStore.setActiveTemplateId(template)
            busy = false
            val title = snapshot.cards.firstOrNull { it.id == cardId }?.title.orEmpty()
            // 1.7：朗读中换演播方式不热切当前段（避免半句换声），明确告知从下一段生效
            message = if (BaseReadAloudService.isRun) {
                "已切换到「$title」，从下一段起生效"
            } else {
                "已切换到「$title」"
            }
            reloadState()
        }
    }

    /**
     * 主按钮（4.11 开箱即用）：
     * 未就绪 → **直接开始听书**（朗读服务对未安装的引擎本来就会回退系统语音），
     * 只提示一句去哪里启用离线；多角色未准备 → 先识别/配声；其余 → 直接开始听书。
     */
    private fun primaryAction() {
        if (!snapshot.engineReady) {
            toastOnUi("离线引擎未就绪：本次用系统语音朗读，可在「引擎与音色」里启用离线")
            startReading()
            return
        }
        if (snapshot.selectedCard == CastSceneState.CARD_MULTI &&
            CastSceneState.needsOneTapPrepare(
                snapshot.selectedCard, snapshot.characterCount, snapshot.assignedCount
            )
        ) {
            lifecycleScope.launch {
                busy = true
                message = "正在识别角色并配声…"
                val result = prepareMultiRole()
                busy = false
                message = result.message
                reloadState()
                // 4.7：只有硬缺料（无书/无章节/无音色）才阻断；其余按提示继续起播
                if (!result.blocked) startReading()
            }
            return
        }
        startReading()
    }

    private fun startReading() {
        ReadAloud.play(this)
        finish()
    }

    /**
     * 智能多角色一键编排（4.7）：识别角色 → 离线分镜（失败不阻断）→ 配声 → 激活内置模板，
     * 再由 [ReadAloudOneTapFlow.plan] 统一翻译成「能起播」还是「先补哪一步」。
     */
    private suspend fun prepareMultiRole(): ReadAloudOneTapFlow.Result {
        // Compose 状态只在主线程读，进 IO 前先取值
        val engineReady = snapshot.engineReady
        return withContext(Dispatchers.IO) {
            val book = ReadBook.book
                ?: return@withContext ReadAloudOneTapFlow.plan(
                    ReadAloudOneTapFlow.Facts(engineReady, false, false, 0, 0, 0, 0)
                )
            val chapter = ReadBook.curTextChapter
                ?: return@withContext ReadAloudOneTapFlow.plan(
                    ReadAloudOneTapFlow.Facts(engineReady, true, false, voiceCountOf(), 0, 0, 0)
                )
            val bookKey = book.characterBookKey()
            val paragraphs = chapter.paragraphs.map { it.text }
            var characters = runCatching { CharacterVoiceAssignService.roleCharacters(bookKey) }
                .getOrDefault(emptyList())
            if (characters.isEmpty()) {
                OfflineCharacterDiscovery.ensureCharacters(bookKey, paragraphs)
                characters = runCatching { CharacterVoiceAssignService.roleCharacters(bookKey) }
                    .getOrDefault(emptyList())
            }
            runCatching {
                OfflineRoleResolver.ensure(
                    bookKey = bookKey,
                    chapterIndex = chapter.chapter.index,
                    chapterTitle = chapter.chapter.title,
                    paragraphs = paragraphs,
                    characters = characters,
                    aliasStore = BookAliasStore(BuiltinTtsRuntime.paths().rulesRoot)
                )
            }.onFailure {
                LogUtils.e("CastScene", "离线分镜失败：${it.stackTraceToString()}")
            }
            val assigned = runCatching {
                CharacterVoiceAssignService.assignAll(this@CastSceneActivity, book)
            }.getOrDefault(CharacterVoiceAssignService.AssignResult(emptyList()))
            val activated = runCatching { BuiltinCastingSetup.ensureAndActivate(assets) }
                .getOrDefault(false)
            val storyboard = runCatching {
                OfflineRoleResolver.cached(bookKey, chapter.chapter.index)?.size ?: 0
            }.getOrDefault(0)
            ReadAloudOneTapFlow.plan(
                facts = ReadAloudOneTapFlow.Facts(
                    engineReady = engineReady,
                    hasBook = true,
                    hasChapter = true,
                    voiceCount = voiceCountOf(),
                    characterCount = characters.size,
                    assignedCount = assigned.assignments.size,
                    storyboardSegments = storyboard
                ),
                templateActivated = activated
            )
        }
    }

    /** 可用音色数口径与三步引导一致：单声档按 1 计（引擎自带默认音，没有音色条目） */
    private fun voiceCountOf(): Int = runCatching {
        val active = BuiltinTtsRuntime.activeProfile() ?: return@runCatching 0
        if (active.requiresReference || active.multiSpeaker) BuiltinTtsRuntime.voices().size else 1
    }.getOrDefault(0)

    private suspend fun importBuiltinTemplates() {
        runCatching {
            assets.open("defaultData/tts/castingTemplates.json").bufferedReader().use { it.readText() }
        }.getOrNull()?.let { json ->
            runCatching { TtsCastingStore.importBuiltinTemplates(json) }
        }
    }
}

@Composable
private fun CastSceneScreen(
    snapshot: SceneSnapshot,
    busy: Boolean,
    message: String,
    bgmEnabled: Boolean,
    bgmTrackCount: Int,
    onSelect: (String) -> Unit,
    onToggleBgm: (Boolean) -> Unit,
    onOpenVoices: () -> Unit,
    onOpenBgm: () -> Unit,
    onOpenTemplates: () -> Unit,
    onOpenPrebuild: () -> Unit,
    onOpenAssets: () -> Unit,
    onOpenStoryboard: () -> Unit,
    onOpenTuning: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    /** 4.6：按 `PageTab.key` 深链到二级页（角色配音 / 开书向导） */
    onOpenPage: (String) -> Unit,
    /** 3.5：本地 AI 可用时的「生成本章剧本」（手动重算） */
    onGenerateScript: () -> Unit,
    onPrimary: () -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(start = 20.dp, end = 20.dp, top = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            ScreenHeader(
                title = "听书中心",
                subtitle = "选一种演播方式，一次点开就能听；更多设置收在下面「高级」",
                trailing = {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        // 1.7 胶囊：引擎档位 + 剧本来源（未开书/无剧本时给引擎结论，不留空胶囊）
                        StatusPill(
                            text = snapshot.capsule.ifBlank {
                                if (snapshot.engineReady) "离线就绪" else "未就绪"
                            },
                            tone = if (snapshot.engineReady) StatusTone.POSITIVE else StatusTone.WARNING
                        )
                        // 1.7 书级覆盖徽标：只在本书确实改过模板/语速时出现
                        snapshot.bookOverrideBadge?.let {
                            StatusPill(text = it, tone = StatusTone.NEUTRAL)
                        }
                    }
                }
            )
            // 三步引导（spec R2）：只表达"现在在哪一步"，不把资产当听书门槛（系统语音始终可听）
            if (snapshot.guideSteps.isNotEmpty()) {
                AppCard {
                    SectionTitle("开通三步")
                    snapshot.guideSteps.forEach { step ->
                        Row(
                            modifier = Modifier.padding(top = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            StatusDot(
                                if (step.done) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.25f),
                                size = 7
                            )
                            Text(
                                text = step.title,
                                style = MaterialTheme.typography.bodySmall,
                                color = if (step.done) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(start = 8.dp)
                            )
                        }
                    }
                }
            }
            // 两列网格：三卡（后一格留白）一屏可见，窄屏也不用来回滚动找选项
            snapshot.cards.chunked(2).forEach { pair ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    pair.forEach { card ->
                        SceneCard(
                            card = card,
                            badge = CastSceneState.badgeOf(snapshot.cards.indexOf(card)),
                            modifier = Modifier.weight(1f),
                            onClick = { onSelect(card.id) }
                        )
                    }
                    if (pair.size == 1) Spacer(Modifier.weight(1f))
                }
            }
            // 本章准备度：把"角色/配声/分镜"三个真实数字摆出来，省得开书后才发现问题
            AppCard {
                SectionTitle("本章准备")
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    SceneStat("${snapshot.characterCount}", "识别角色", Modifier.weight(1f))
                    SceneStat("${snapshot.assignedCount}", "已配音色", Modifier.weight(1f))
                    SceneStat("${snapshot.storyboardSegments}", "本章分镜", Modifier.weight(1f))
                }
                // 3.7 剧本状态：未生成/生成中/已生成/降级 —— 不改变起播能力，只把"AI 有没有接管"说清楚
                if (snapshot.scriptLine.isNotBlank()) {
                    Text(
                        text = snapshot.scriptLine,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
                // 3.5 剧本生成入口：本地 AI 可用才显示（不可用时不是灰按钮，而是整块不出现）
                if (snapshot.canGenerateScript) {
                    AssistChip(
                        onClick = onGenerateScript,
                        label = { Text("生成本章剧本") },
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
                // 资源概览：让用户在本页就知道"有什么、占多少"，并可一键进「声音资源」看清来源分类
                if (snapshot.resourceLine.isNotBlank()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = snapshot.resourceLine,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f)
                        )
                        AssistChip(
                            onClick = onOpenAssets,
                            label = { Text("声音资源") }
                        )
                    }
                }
            }
            // 使用提示：把首次使用要知道的三条说完，页面不至于下半屏全空
            AppCard {
                SectionTitle("使用提示")
                CastSceneState.usageTips().forEach { tip ->
                    Row(
                        modifier = Modifier.padding(top = 6.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        StatusDot(
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.55f),
                            size = 6
                        )
                        Text(
                            text = tip,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 8.dp)
                        )
                    }
                }
            }
            // T8.4 配乐开关：卡 C 联动 BGM（开关打开且库里有可用配乐，朗读时才会真的混音）
            AppCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "配 BGM",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = CastSceneState.bgmSummary(bgmEnabled, bgmTrackCount),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 2.dp)
                        )
                    }
                    Switch(checked = bgmEnabled, onCheckedChange = onToggleBgm)
                }
            }
            // 高级区（4.1/4.4/4.5/4.6）：入口由状态机按「有无书 / 引擎是否就绪」收敛，
            // 保证「入口收敛」不丢能力的同时，也不给用户看点进去做不了事的死入口。
            if (snapshot.advancedEntries.isNotEmpty()) {
                AppCard {
                    SectionTitle("高级")
                    snapshot.advancedEntries.chunked(3).forEach { rowEntries ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            rowEntries.forEach { entry ->
                                AssistChip(
                                    onClick = {
                                        when (entry.id) {
                                            "voices" -> onOpenVoices()
                                            "templates" -> onOpenTemplates()
                                            "prebuild" -> onOpenPrebuild()
                                            "bgm" -> onOpenBgm()
                                            "storyboard" -> onOpenStoryboard()
                                            "tuning" -> onOpenTuning()
                                            "diagnostics" -> onOpenDiagnostics()
                                            // roles / wizard：key 与 PageTab.key 单源，深链二级页
                                            else -> onOpenPage(entry.id)
                                        }
                                    },
                                    label = { Text(entry.label) }
                                )
                            }
                        }
                    }
                }
            }
            if (busy) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            Spacer(Modifier.height(8.dp))
        }
        // 底部区：状态说明 + 主按钮一起固定在底部，避免中间出现大块空白
        Column(
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f))
                    .padding(horizontal = 12.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                StatusDot(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f), size = 7)
                Text(
                    text = snapshot.statusLine,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 8.dp)
                )
            }
            if (message.isNotBlank()) {
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Button(
                onClick = onPrimary,
                enabled = snapshot.primaryEnabled && !busy,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp),
                shape = RoundedCornerShape(14.dp)
            ) {
                Text(snapshot.primaryLabel, style = MaterialTheme.typography.titleSmall)
            }
        }
    }
}
