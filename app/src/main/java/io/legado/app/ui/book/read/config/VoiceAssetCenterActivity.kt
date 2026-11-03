package io.legado.app.ui.book.read.config

import android.view.View
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import androidx.viewbinding.ViewBinding
import io.legado.app.base.BaseActivity
import io.legado.app.data.appDb
import io.legado.app.data.entities.ReadAloudBgmTrack
import io.legado.app.help.readaloud.casting.CharacterVoiceAssignService
import io.legado.app.help.readaloud.casting.TtsCastingStore
import io.legado.app.help.readaloud.role.ner.NerModelManager
import io.legado.app.help.tts.builtin.BuiltinTtsRuntime
import io.legado.app.ui.book.read.config.casting.TtsCastingManageFragment
import io.legado.app.ui.theme.LegadoTheme
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.startActivity
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 声音资源中心（offline-tts-llm-productization 4.9 首批）：
 * 把「离线语音 / 音色 / 理解模型 / 模板 / 配乐 / 文本资产 / 缓存」集中到一页，
 * 并给每一项标注**来源**（内置 / 可重下 / 需备份）—— 用户能一眼看出"删了会丢什么"。
 *
 * 入口：听书中心「离线资产」行、以及后续设置页一级入口。
 */
class VoiceAssetCenterActivity : BaseActivity<ViewBinding>() {

    private var ui by mutableStateOf(VoiceAssetCenterUi("", emptyList(), false))

    override val binding: ViewBinding by lazy {
        val view = ComposeView(this).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                LegadoTheme {
                    VoiceAssetCenterScreen(
                        ui = ui,
                        onAction = ::handleAction
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
        lifecycleScope.launch {
            val facts = withContext(Dispatchers.IO) { loadFacts() }
            ui = VoiceAssetCenterState.build(facts)
        }
    }

    private suspend fun loadFacts(): AssetFacts {
        runCatching { BuiltinTtsRuntime.ensureSeeded() }
        val profile = runCatching { BuiltinTtsRuntime.activeProfile() }.getOrNull()
        val voices = runCatching { BuiltinTtsRuntime.voices() }.getOrDefault(emptyList())
        val storage = runCatching { BuiltinTtsRuntime.storageBreakdown() }
            .getOrDefault(BuiltinTtsRuntime.StorageBreakdown())
        val templates = runCatching { TtsCastingStore.all().size }.getOrDefault(0)
        // G-43：DAO 读取显式 IO 包裹
        val bgm = withContext(Dispatchers.IO) {
            runCatching {
                appDb.readAloudBgmDao.enabledTracksByType(ReadAloudBgmTrack.TYPE_BGM).size
            }.getOrDefault(0)
        }
        val paths = runCatching { BuiltinTtsRuntime.paths() }.getOrNull()
        // 5.1 覆盖率：性别/年龄有标注的比例（决定性别硬约束的实际效果，用户需要知道）
        val coverage = runCatching { CharacterVoiceAssignService.coverage(voices) }
            .getOrDefault(CharacterVoiceAssignService.VoiceCoverage(0, 0, 0))
        return AssetFacts(
            engineReady = profile != null && runCatching { BuiltinTtsRuntime.isReady() }.getOrDefault(false),
            profileTitle = profile?.title.orEmpty(),
            voiceCount = if (profile == null) 0 else if (profile.requiresReference || profile.multiSpeaker) {
                voices.size
            } else {
                1
            },
            importedVoiceCount = voices.count { it.refAudio.isNotBlank() },
            nerReady = runCatching { NerModelManager.isReady(this) }.getOrDefault(false),
            templateCount = templates,
            bgmCount = bgm,
            dictReady = paths?.pronFile?.isFile == true,
            splitRulesReady = paths?.splitRulesFile?.isFile == true,
            modelBytes = storage.modelBytes,
            voiceBytes = storage.voiceBytes,
            cacheBytes = storage.cacheBytes,
            voiceGenderPercent = coverage.genderPercent,
            voiceAgePercent = coverage.agePercent
        )
    }

    private fun handleAction(action: AssetAction) {
        when (action) {
            AssetAction.MANAGE_ENGINE,
            AssetAction.MANAGE_VOICE,
            AssetAction.IMPORT_NER,
            AssetAction.MANAGE_TEXT -> startActivity<BuiltinTtsActivity>()

            AssetAction.MANAGE_TEMPLATE ->
                showDialogFragment(TtsCastingManageFragment.newInstance())

            AssetAction.MANAGE_BGM -> startActivity<ReadAloudBgmManageActivity>()

            AssetAction.CLEAR_CACHE -> lifecycleScope.launch {
                withContext(Dispatchers.IO) {
                    runCatching { BuiltinTtsRuntime.cache().clear() }
                }.onSuccess {
                    toastOnUi("已清理合成缓存")
                }.onFailure {
                    toastOnUi("清理失败：${it.message}")
                }
                reload()
            }
        }
    }
}

@Composable
private fun VoiceAssetCenterScreen(
    ui: VoiceAssetCenterUi,
    onAction: (AssetAction) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        ScreenHeader(
            title = "声音资源",
            subtitle = "哪些能删可重下、哪些删了就找不回，这里一眼看清",
            trailing = {
                StatusPill("合计", StatusTone.NEUTRAL)
            }
        )
        AppCard {
            Text(
                text = ui.headerLine,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        ui.groups.forEach { group ->
            AssetGroupCard(group = group, onAction = onAction)
        }
    }
}

@Composable
private fun AssetGroupCard(group: AssetGroup, onAction: (AssetAction) -> Unit) {
    AppCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            StatusDot(
                when (group.status) {
                    AssetStatus.READY -> MaterialTheme.colorScheme.primary
                    AssetStatus.PARTIAL -> MaterialTheme.colorScheme.tertiary
                    AssetStatus.MISSING -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
                },
                size = 8
            )
            Text(
                text = group.title,
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(start = 8.dp)
            )
            StatusPill(
                text = group.source.label,
                tone = when (group.source) {
                    AssetSource.BUILTIN -> StatusTone.NEUTRAL
                    AssetSource.DOWNLOAD -> StatusTone.POSITIVE
                    AssetSource.IMPORT -> StatusTone.WARNING
                }
            )
        }
        Text(
            text = group.summary,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp)
        )
        AssistChip(
            onClick = { onAction(group.action) },
            label = { Text(group.actionLabel) },
            modifier = Modifier.padding(top = 8.dp)
        )
    }
}
