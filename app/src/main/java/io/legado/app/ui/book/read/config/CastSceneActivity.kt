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
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import io.legado.app.help.book.characterBookKey
import io.legado.app.help.readaloud.casting.BuiltinCastingSetup
import io.legado.app.help.readaloud.casting.CharacterVoiceAssignService
import io.legado.app.help.readaloud.casting.TtsCastingStore
import io.legado.app.ui.book.read.config.casting.TtsCastingManageFragment
import io.legado.app.help.readaloud.role.BookAliasStore
import io.legado.app.help.readaloud.role.OfflineCharacterDiscovery
import io.legado.app.help.readaloud.role.OfflineRoleResolver
import io.legado.app.help.tts.builtin.BuiltinTtsRuntime
import io.legado.app.model.ReadAloud
import io.legado.app.model.ReadBook
import io.legado.app.ui.theme.LegadoTheme
import io.legado.app.utils.LogUtils
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.startActivity
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 听书场景卡（T5.1 / ui-design §2/§8）：阅读页入口，四卡选演播方式 + 一键开始听书。
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

    override val binding: ViewBinding by lazy {
        val view = ComposeView(this).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                LegadoTheme {
                    CastSceneScreen(
                        snapshot = snapshot,
                        busy = busy,
                        message = message,
                        onSelect = ::selectCard,
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
        reload()
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
        val hasBook: Boolean
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
            hasBook = facts.hasBook
        )
    }

    private suspend fun loadFacts(): Facts {
        runCatching { BuiltinTtsRuntime.ensureSeeded() }
        val engineReady = runCatching {
            BuiltinTtsRuntime.isReady() && BuiltinTtsRuntime.voices().isNotEmpty()
        }.getOrDefault(false)
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
        val activeId = TtsCastingStore.activeTemplateId()
        // G-43：DAO 读取必须显式 IO 包裹（静态门禁按函数体扫描，不能只依赖外层 withContext）
        val customName = activeId?.let { id ->
            withContext(Dispatchers.IO) {
                runCatching { appDb.ttsCastingTemplateDao.get(id)?.name }.getOrNull()
            }
        }.orEmpty()
        return Facts(
            engineReady = engineReady,
            characterCount = characters.size,
            assignedCount = assigned,
            storyboardSegments = storyboard,
            activeTemplateId = activeId,
            customName = customName,
            hasBook = book != null
        )
    }

    /** 选卡：自定义 → 模板管理；其余 → 幂等导入内置模板并激活对应模板 */
    private fun selectCard(cardId: String) {
        if (cardId == CastSceneState.CARD_CUSTOM) {
            showDialogFragment(TtsCastingManageFragment.newInstance())
            return
        }
        val template = CastSceneState.templateOf(cardId) ?: return
        lifecycleScope.launch {
            busy = true
            message = ""
            importBuiltinTemplates()
            TtsCastingStore.setActiveTemplateId(template)
            busy = false
            message = "已切换到「${snapshot.cards.firstOrNull { it.id == cardId }?.title.orEmpty()}」"
            reloadState()
        }
    }

    /** 主按钮：未就绪 → 引导到管理页；多角色未准备 → 先识别/配声；其余 → 直接开始听书 */
    private fun primaryAction() {
        if (!snapshot.engineReady) {
            toastOnUi("先下载/导入语音包并一键启用离线引擎")
            startActivity<BuiltinTtsActivity>()
            return
        }
        if (snapshot.selectedCard == CastSceneState.CARD_MULTI &&
            snapshot.primaryLabel == "识别角色并开始"
        ) {
            lifecycleScope.launch {
                busy = true
                message = "正在识别角色并配声…"
                val ok = prepareMultiRole()
                busy = false
                if (!ok) {
                    message = "准备失败：请在阅读页打开一章后再试（或到管理页查看诊断）"
                    reloadState()
                    return@launch
                }
                reloadState()
                startReading()
            }
            return
        }
        startReading()
    }

    private fun startReading() {
        ReadAloud.play(this)
        finish()
    }

    /** 多角色就绪（与开书向导同一套服务）：发现角色 → 离线分镜 → 配声 → 激活内置模板 */
    private suspend fun prepareMultiRole(): Boolean = withContext(Dispatchers.IO) {
        val book = ReadBook.book ?: return@withContext false
        val chapter = ReadBook.curTextChapter ?: return@withContext false
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
        runCatching { CharacterVoiceAssignService.assignAll(this@CastSceneActivity, book) }
        BuiltinCastingSetup.ensureAndActivate(assets)
    }

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
    onSelect: (String) -> Unit,
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
                title = "听书场景卡",
                subtitle = "选一种演播方式，一次点开就能听",
                trailing = {
                    StatusPill(
                        text = if (snapshot.engineReady) "离线就绪" else "未就绪",
                        tone = if (snapshot.engineReady) StatusTone.POSITIVE else StatusTone.WARNING
                    )
                }
            )
            // 两列网格：四卡一屏可见，窄屏也不用来回滚动找选项
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
            // 状态条：用一条中性说明条，不再做第二块卡片抢注意力
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

/** 单张场景卡：A/B/C/D 圆标 + 标题 + 两行副文案；选中显示对勾，推荐显示胶囊 */
@Composable
private fun SceneCard(
    card: SceneCardRow,
    badge: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    AppCard(modifier = modifier, selected = card.selected, onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(24.dp)
                    .clip(CircleShape)
                    .background(
                        if (card.selected) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                        }
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = badge,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = if (card.selected) {
                        MaterialTheme.colorScheme.onPrimary
                    } else {
                        MaterialTheme.colorScheme.primary
                    }
                )
            }
            Spacer(Modifier.weight(1f))
            when {
                card.selected -> Icon(
                    Icons.Filled.Check,
                    contentDescription = "已选",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp)
                )
                card.recommended -> StatusPill("推荐", StatusTone.POSITIVE)
            }
        }
        Text(
            text = card.title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(top = 10.dp)
        )
        Text(
            text = card.subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            minLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 2.dp)
        )
    }
}
