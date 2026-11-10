package io.legado.app.ui.book.read.config

import android.app.ActivityManager
import android.view.View
import androidx.appcompat.app.AlertDialog
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
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.LocalLlmPrefs
import io.legado.app.help.readaloud.casting.CharacterVoiceAssignService
import io.legado.app.help.readaloud.casting.TtsCastingStore
import io.legado.app.help.readaloud.role.ChapterScriptStore
import io.legado.app.help.readaloud.role.ScriptStatusBus
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
import java.io.File

/**
 * 声音资源中心（offline-tts-llm-productization 4.9 首批）：
 * 把「离线语音 / 音色 / 理解模型 / 模板 / 配乐 / 文本资产 / 缓存」集中到一页，
 * 并给每一项标注**来源**（内置 / 可重下 / 需备份）—— 用户能一眼看出"删了会丢什么"。
 *
 * 入口：听书中心「离线资产」行、以及后续设置页一级入口。
 */
class VoiceAssetCenterActivity : BaseActivity<ViewBinding>() {

    private var ui by mutableStateOf(VoiceAssetCenterUi("", emptyList(), false))

    /**
     * NER 模型导入（2026-11-10）：原来这里只跳内置 TTS 页，不落任何选择器
     * ⇒ 用户点「导入 NER 模型」什么也没发生，模型一直没导进来。这里直接开文件选择器。
     */
    private val pickNerZip = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        lifecycleScope.launch {
            toastOnUi("导入 NER 模型…")
            val result = withContext(Dispatchers.IO) { NerModelManager.importZip(this@VoiceAssetCenterActivity, uri) }
            toastOnUi(result.message)
            reload()
        }
    }

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
        // 4.9：剧本草案与本地模型的占用（AI 数据可一键清除，音色/绑定保留）
        val scriptBytes = dirBytes(paths?.scriptsDir)
        val aiModelBytes = dirBytes(paths?.filesRoot?.let { File(it, "models/llm") })
        // 5.1 覆盖率：性别/年龄有标注的比例（决定性别硬约束的实际效果，用户需要知道）
        val coverage = runCatching { CharacterVoiceAssignService.coverage(voices) }
            .getOrDefault(CharacterVoiceAssignService.VoiceCoverage(0, 0, 0))
        // 4.1/7.7：低端机（RAM < 8GB）不显示 AI 入口；读取失败按"非低端"处理，避免误伤中高端机
        val totalRamMb = runCatching {
            val info = ActivityManager.MemoryInfo()
            getSystemService(ActivityManager::class.java).getMemoryInfo(info)
            info.totalMem / (1024L * 1024L)
        }.getOrDefault(CastSceneState.LOW_END_RAM_MB)
        val lowEndDevice = CastSceneState.isLowEndDevice(totalRamMb)
        // 本地 AI 入口还需要用户真的开了本地档且选过模型，否则就是点了没反应的死入口
        val localAiEnabled = LocalLlmPrefs.policyOf(AppConfig.aiTaskPolicy) == LocalLlmPrefs.POLICY_LOCAL &&
            LocalLlmPrefs.enabledOrDefault(AppConfig.localLlmEnabled, AppConfig.localLlmProfileId)
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
            scriptBytes = scriptBytes,
            aiModelBytes = aiModelBytes,
            voiceGenderPercent = coverage.genderPercent,
            voiceAgePercent = coverage.agePercent,
            lowEndDevice = lowEndDevice,
            localAiEnabled = localAiEnabled
        )
    }

    /** 目录递归占用（不存在/无权限 ⇒ 0，资源页不能因此崩） */
    private fun dirBytes(dir: File?): Long {
        if (dir == null || !dir.isDirectory) return 0L
        return runCatching {
            dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        }.getOrDefault(0L)
    }

    private fun handleAction(action: AssetAction) {
        when (action) {
            AssetAction.MANAGE_ENGINE,
            AssetAction.MANAGE_VOICE,
            AssetAction.MANAGE_TEXT -> startActivity<BuiltinTtsActivity>()

            // NER 模型：直接开选择器（原先只跳页面，等于没有入口）
            AssetAction.IMPORT_NER ->
                pickNerZip.launch(arrayOf("application/zip", "application/octet-stream"))

            AssetAction.MANAGE_TEMPLATE ->
                showDialogFragment(TtsCastingManageFragment.newInstance())

            AssetAction.MANAGE_BGM -> startActivity<ReadAloudBgmManageActivity>()

            // 4.9 分组连播试听：走与单条试听同一套「先切音色再播」，最多 5 个
            AssetAction.PREVIEW_VOICES -> {
                val voices = runCatching { BuiltinTtsRuntime.voices() }.getOrDefault(emptyList())
                val count = BuiltinVoiceCompare.previewGroup(this, voices)
                toastOnUi(
                    if (count > 0) "开始连播试听 $count 个音色" else "还没有可用音色：先导入音色包或录制"
                )
            }

            // 删除前影响提示（4.9）：先讲清"删什么/不影响什么/下次会怎样"，再执行
            AssetAction.CLEAR_CACHE -> confirmThen(
                title = "清理合成缓存",
                message = VoiceAssetCenterState.clearCacheImpactText(
                    runCatching { BuiltinTtsRuntime.storageBreakdown().cacheBytes }.getOrDefault(0L)
                )
            ) {
                lifecycleScope.launch {
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

            // 清除 AI 数据：只删剧本草案与本地模型，音色/角色绑定/模板/配乐全部保留
            AssetAction.CLEAR_AI_DATA -> confirmThen(
                title = "清除 AI 数据",
                message = VoiceAssetCenterState.clearAiImpactText(
                    runCatching {
                        dirBytes(runCatching { BuiltinTtsRuntime.paths() }.getOrNull()?.scriptsDir)
                    }.getOrDefault(0L),
                    runCatching {
                        dirBytes(
                            runCatching { BuiltinTtsRuntime.paths() }.getOrNull()?.filesRoot
                                ?.let { File(it, "models/llm") }
                        )
                    }.getOrDefault(0L)
                )
            ) {
                lifecycleScope.launch {
                    val removedScripts = withContext(Dispatchers.IO) {
                        runCatching {
                            val paths = BuiltinTtsRuntime.paths()
                            // retention=0 ⇒ 删除全部章节剧本/索引，但**不动 overrides.json**（用户手改）
                            val removed = ChapterScriptStore(paths.scriptsDir)
                                .cleanup(retentionMs = 0L, now = System.currentTimeMillis())
                            val models = File(paths.filesRoot, "models/llm")
                            if (models.isDirectory) models.deleteRecursively()
                            removed
                        }.getOrDefault(0)
                    }
                    ScriptStatusBus.clearAll()
                    toastOnUi("已清除 $removedScripts 章剧本与本地模型（音色与绑定已保留）")
                    reload()
                }
            }
        }
    }

    /** 危险动作统一入口：先弹影响提示，用户确认后才执行 */
    private fun confirmThen(title: String, message: String, action: () -> Unit) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setNegativeButton("取消", null)
            .setPositiveButton("确定") { _, _ -> action() }
            .show()
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
        Row(
            modifier = Modifier.padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            AssistChip(
                onClick = { onAction(group.action) },
                label = { Text(group.actionLabel) }
            )
            // 4.9：次要动作（音色分组「连播试听」），与主操作并列但不抢视觉
            group.secondaryAction?.let { secondary ->
                AssistChip(
                    onClick = { onAction(secondary) },
                    label = { Text(group.secondaryLabel) }
                )
            }
        }
    }
}
