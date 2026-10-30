package io.legado.app.ui.book.read.config

import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.speech.tts.TextToSpeech
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import androidx.viewbinding.ViewBinding
import io.legado.app.R
import io.legado.app.base.BaseActivity
import io.legado.app.help.book.characterBookKey
import io.legado.app.help.readaloud.casting.CharacterVoiceAssignService
import io.legado.app.help.readaloud.role.BookAliasStore
import io.legado.app.help.readaloud.role.OfflineRoleCacheStore
import io.legado.app.help.readaloud.role.OfflineRoleResolver
import io.legado.app.help.tts.builtin.BuiltinEngineState
import io.legado.app.help.tts.builtin.BuiltinModelProfile
import io.legado.app.help.tts.builtin.BuiltinTtsRuntime
import io.legado.app.help.tts.builtin.BuiltinVoice
import io.legado.app.help.tts.builtin.ClonettsVoiceImporter
import io.legado.app.help.tts.builtin.VoiceAutoTagger
import io.legado.app.model.ReadBook
import io.legado.app.ui.theme.LegadoTheme
import io.legado.app.utils.LogUtils
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale

/**
 * 内置离线 TTS 管理页（系统 TTS 设置入口 + 阅读页入口）。
 * 四个页签：引擎 / 音色 / 角色配音 / 开书向导（UI 收敛为单 Activity，见 review-final §3.5）。
 */
class BuiltinTtsActivity : BaseActivity<ViewBinding>() {

    private var previewTts: TextToSpeech? = null
    private var pendingProfileId: String = ""

    private val pageState = mutableStateOf(BuiltinTtsPageState())

    private val pickModelFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        val profileId = pendingProfileId.ifBlank { "clone" }
        lifecycleScope.launch {
            pageState.value = pageState.value.copy(busy = true, message = "")
            val ok = runCatching {
                contentResolver.openInputStream(uri)?.use { input ->
                    BuiltinTtsRuntime.modelManager().importArchive(profileId, input, displayName(uri))
                } ?: false
            }.getOrDefault(false)
            pageState.value = pageState.value.copy(
                busy = false,
                message = if (ok) "模型已导入" else "模型导入失败（请确认包内容完整）"
            )
            reload()
        }
    }

    private val pickVoiceZip = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        lifecycleScope.launch {
            pageState.value = pageState.value.copy(busy = true, message = "")
            val report = runCatching {
                val target = File(cacheDir, "voice-import.zip")
                contentResolver.openInputStream(uri)?.use { input ->
                    target.outputStream().use { out -> input.copyTo(out) }
                }
                ClonettsVoiceImporter(BuiltinTtsRuntime.voiceStore()).importZip(target)
            }.getOrNull()
            pageState.value = pageState.value.copy(
                busy = false,
                message = report?.message ?: "音色包导入失败"
            )
            targetCleanup()
            reload()
        }
    }

    override val binding: ViewBinding by lazy {
        val view = ComposeView(this).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                LegadoTheme {
                    BuiltinTtsScreen(
                        state = pageState.value,
                        onPickModel = { profileId ->
                            pendingProfileId = profileId
                            pickModelFile.launch(arrayOf("*/*"))
                        },
                        onPickVoiceZip = { pickVoiceZip.launch(arrayOf("*/*")) },
                        onAction = ::handleAction
                    )
                }
            }
        }
        object : ViewBinding {
            override fun getRoot(): View = view
        }
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        BuiltinTtsRuntime.init(this)
        reload()
        lifecycleScope.launch {
            runCatching { BuiltinTtsRuntime.ensureSeeded() }
            reload()
            // 联调用：adb 传 --ez auto_setup true → 自动扫描导入目录 → 激活引擎 → 自检（供 L2 无 UI 自动化验证）
            if (intent?.getBooleanExtra(EXTRA_AUTO_SETUP, false) == true) {
                val importMessage = runDiscoverImport()
                val activateMessage = activateAsSystemEngine()
                val checkMessage = runSelfCheck()
                pageState.value = pageState.value.copy(
                    message = "$importMessage · $activateMessage · $checkMessage"
                )
                reload()
            }
        }
    }

    /** 扫描外置导入目录并逐项导入（返回结果文案） */
    private suspend fun runDiscoverImport(): String {
        pageState.value = pageState.value.copy(busy = true, message = "扫描中…")
        val found = BuiltinTtsRuntime.modelManager().discoverImports()
        var imported = 0
        found.forEach { file ->
            val profileId = guessProfile(file.name)
            val ok = if (file.isDirectory) {
                BuiltinTtsRuntime.modelManager().importFromDirectory(profileId, file)
            } else {
                runCatching {
                    file.inputStream().use { input ->
                        BuiltinTtsRuntime.modelManager().importArchive(profileId, input, file.name)
                    }
                }.getOrDefault(false)
            }
            if (ok) imported++
        }
        val message = "发现 ${found.size} 项，导入成功 $imported"
        pageState.value = pageState.value.copy(busy = false, message = message)
        return message
    }

    private suspend fun runSelfCheck(): String {
        pageState.value = pageState.value.copy(busy = true, message = "自检中…")
        val report = runCatching { BuiltinTtsRuntime.selfCheck() }
            .onFailure { LogUtils.e("BuiltinTtsUi", "自检异常：${it.stackTraceToString()}") }
            .getOrNull()
        val message = when {
            report == null -> "自检异常（详见日志）"
            report.ok -> "自检通过：${report.audioMs}ms 音频 / 耗时 ${report.costMs}ms / RTF ${"%.2f".format(report.rtf)}"
            else -> "自检失败：${report.message}"
        }
        pageState.value = pageState.value.copy(busy = false, message = message)
        return message
    }

    override fun onDestroy() {
        runCatching { previewTts?.stop(); previewTts?.shutdown() }
        previewTts = null
        super.onDestroy()
    }

    private fun handleAction(action: PageAction) {
        lifecycleScope.launch {
            runCatching { dispatchAction(action) }
                .onFailure {
                    LogUtils.e("BuiltinTtsUi", "动作失败 ${action::class.simpleName}：${it.stackTraceToString()}")
                    pageState.value = pageState.value.copy(busy = false, message = "操作失败：${it.message}")
                }
            reload()
        }
    }

    private suspend fun dispatchAction(action: PageAction) {
        when (action) {
                is PageAction.DownloadModel -> {
                    val profile = BuiltinTtsRuntime.modelManager().profile(action.profileId)
                    if (profile == null) {
                        pageState.value = pageState.value.copy(message = "未知模型档")
                        return
                    }
                    pageState.value = pageState.value.copy(busy = true, message = "下载中…")
                    val ok = BuiltinTtsRuntime.modelManager().download(profile) { progress ->
                        pageState.value = pageState.value.copy(progress = progress.percent)
                    }
                    pageState.value = pageState.value.copy(busy = false, message = if (ok) "模型下载完成" else "下载失败（可改用导入）")
                }
                is PageAction.DeleteModel -> {
                    BuiltinTtsRuntime.modelManager().delete(action.profileId)
                    pageState.value = pageState.value.copy(message = "已删除模型")
                }
                is PageAction.ActivateProfile -> {
                    BuiltinTtsRuntime.setActiveProfile(action.profileId)
                    pageState.value = pageState.value.copy(message = "已切换档位")
                }
                PageAction.ActivateEngine -> pageState.value = pageState.value.copy(message = activateAsSystemEngine())
                PageAction.SelfCheck -> runSelfCheck()
                PageAction.DiscoverImport -> runDiscoverImport()
                PageAction.ClearCache -> {
                    BuiltinTtsRuntime.clearCache()
                    pageState.value = pageState.value.copy(message = "合成缓存已清理")
                }
                PageAction.AutoTag -> {
                    pageState.value = pageState.value.copy(busy = true, message = "打标中…")
                    val result = VoiceAutoTagger.tagAll(BuiltinTtsRuntime.voiceStore())
                    pageState.value = pageState.value.copy(
                        busy = false,
                        message = "打标完成：男 ${result.male} / 女 ${result.female} / 未定 ${result.unknown}"
                    )
                }
                is PageAction.DeleteVoice -> {
                    BuiltinTtsRuntime.deleteVoice(action.voiceId)
                    pageState.value = pageState.value.copy(message = "音色已删除")
                }
                is PageAction.PreviewVoice -> previewVoice(action.voiceName)
                PageAction.AssignCasting -> assignCasting()
                PageAction.IdentifyRoles -> identifyRoles()
                PageAction.Reload -> Unit
            }
    }

    private suspend fun assignCasting() {
        val book = ReadBook.book
        if (book == null) {
            pageState.value = pageState.value.copy(message = "请先打开一本书")
            return
        }
        pageState.value = pageState.value.copy(busy = true, message = "配声中…")
        val result = CharacterVoiceAssignService.assignAll(this, book, overwrite = false)
        pageState.value = pageState.value.copy(
            busy = false,
            message = if (result.assignments.isEmpty()) {
                "没有可分配的角色（可先在角色管理里识别角色）"
            } else {
                "已配声 ${result.assignments.size} 个角色（撞音 ${result.conflicts.size}）"
            }
        )
    }

    private suspend fun identifyRoles() {
        val book = ReadBook.book
        val chapter = ReadBook.curTextChapter
        if (book == null || chapter == null) {
            pageState.value = pageState.value.copy(message = "请先在阅读页打开当前章节")
            return
        }
        pageState.value = pageState.value.copy(busy = true, message = "识别中…")
        val bookKey = book.characterBookKey()
        val paragraphs = chapter.paragraphs.map { it.text }
        val characters = CharacterVoiceAssignService.roleCharacters(bookKey)
        val segments = OfflineRoleResolver.ensure(
            bookKey = bookKey,
            chapterIndex = chapter.chapter.index,
            chapterTitle = chapter.chapter.title,
            paragraphs = paragraphs,
            characters = characters,
            aliasStore = BookAliasStore(BuiltinTtsRuntime.paths().rulesRoot),
            forceRecompute = true
        )
        val recognized = segments.count { it.recognized }
        pageState.value = pageState.value.copy(
            busy = false,
            message = "本章识别 ${segments.size} 段，其中角色命中 $recognized 段"
        )
    }

    private fun previewVoice(voiceName: String) {
        val target = BuiltinTtsRuntime.voices().firstOrNull { it.voiceName == voiceName || it.id == voiceName }
        if (target == null) {
            pageState.value = pageState.value.copy(message = "音色不存在")
            return
        }
        if (previewTts == null) {
            previewTts = TextToSpeech(this, { status ->
                if (status == TextToSpeech.SUCCESS) speakPreview(target)
            }, packageName)
        } else {
            speakPreview(target)
        }
    }

    private fun speakPreview(target: BuiltinVoice) {
        val tts = previewTts ?: return
        runCatching {
            tts.voices?.firstOrNull { it.name == target.voiceName }?.let { tts.setVoice(it) }
            tts.speak("这是${target.displayName}的声音。", TextToSpeech.QUEUE_FLUSH, null, "builtin_preview")
        }
    }

    private fun activateAsSystemEngine(): String {
        return runCatching {
            // 与既有引擎选择共用同一偏好键（ReadAloud 路由读取它）
            io.legado.app.help.config.AppConfig.ttsEngine = packageName
            io.legado.app.constant.AppLog.put("已切换到内置离线引擎：$packageName")
            getString(R.string.builtin_tts_activated)
        }.getOrElse { "切换失败：${it.message}" }
    }

    private fun guessProfile(name: String): String = BuiltinTtsUiSupport.guessProfile(name)

    private fun displayName(uri: Uri): String {
        return runCatching {
            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
            }
        }.getOrNull().orEmpty().ifBlank { "model.bin" }
    }

    private fun targetCleanup() {
        runCatching { File(cacheDir, "voice-import.zip").delete() }
    }

    companion object {
        const val EXTRA_AUTO_SETUP = "auto_setup"
    }

    private fun reload() {
        BuiltinTtsRuntime.init(this)
        val manager = BuiltinTtsRuntime.modelManager()
        val profiles = manager.profiles().map { profile ->
            ProfileRow(
                id = profile.id,
                title = profile.title,
                state = manager.state(profile),
                active = BuiltinTtsRuntime.activeProfile()?.id == profile.id,
                sampleRate = profile.sampleRate
            )
        }
        val voices = runCatching { BuiltinTtsRuntime.voices() }.getOrDefault(emptyList())
        val stats = BuiltinTtsRuntime.stats()
        val bookKey = ReadBook.book?.characterBookKey()
        pageState.value = pageState.value.copy(
            profiles = profiles,
            voices = voices,
            stats = StatsRow(
                voiceCount = stats.voiceCount,
                cacheBytes = stats.cacheBytes,
                lastRtf = stats.lastRtf,
                lastError = stats.lastError.ifBlank { getString(R.string.builtin_tts_no_error) }
            ),
            importDir = BuiltinTtsRuntime.paths().importDir.absolutePath,
            hasBook = bookKey != null,
            roleSummary = roleSummary(bookKey)
        )
    }

    private fun roleSummary(bookKey: String?): String {
        if (bookKey == null) return getString(R.string.builtin_tts_roles_need_book)
        val chapter = ReadBook.curTextChapter
        val segments = chapter?.let { OfflineRoleResolver.cached(bookKey, it.chapter.index) }
        if (segments.isNullOrEmpty()) {
            return "尚未识别（开读或点“识别角色”即可）"
        }
        val recognized = segments.count { it.recognized }
        return "本章 ${segments.size} 段，命中角色 $recognized 段"
    }
}

data class ProfileRow(
    val id: String,
    val title: String,
    val state: BuiltinEngineState,
    val active: Boolean,
    val sampleRate: Int
)

data class StatsRow(
    val voiceCount: Int = 0,
    val cacheBytes: Long = 0,
    val lastRtf: Float = 0f,
    val lastError: String = ""
)

data class BuiltinTtsPageState(
    val profiles: List<ProfileRow> = emptyList(),
    val voices: List<BuiltinVoice> = emptyList(),
    val stats: StatsRow = StatsRow(),
    val importDir: String = "",
    val hasBook: Boolean = false,
    val roleSummary: String = "",
    val message: String = "",
    val busy: Boolean = false,
    val progress: Int = 0
)

sealed interface PageAction {
    data class DownloadModel(val profileId: String) : PageAction
    data class DeleteModel(val profileId: String) : PageAction
    data class ActivateProfile(val profileId: String) : PageAction
    data object ActivateEngine : PageAction
    data object SelfCheck : PageAction
    data object DiscoverImport : PageAction
    data object ClearCache : PageAction
    data object AutoTag : PageAction
    data class DeleteVoice(val voiceId: String) : PageAction
    data class PreviewVoice(val voiceName: String) : PageAction
    data object AssignCasting : PageAction
    data object IdentifyRoles : PageAction
    data object Reload : PageAction
}

private enum class PageTab(val labelRes: Int) {
    ENGINE(R.string.builtin_tts_tab_engine),
    VOICES(R.string.builtin_tts_tab_voices),
    ROLES(R.string.builtin_tts_tab_roles),
    WIZARD(R.string.builtin_tts_tab_wizard)
}

@Composable
private fun BuiltinTtsScreen(
    state: BuiltinTtsPageState,
    onPickModel: (String) -> Unit,
    onPickVoiceZip: () -> Unit,
    onAction: (PageAction) -> Unit
) {
    var tab by remember { mutableStateOf(PageTab.ENGINE) }
    LaunchedEffect(state.message) { /* 触发重组展示提示 */ }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Text(
            text = androidx.compose.ui.platform.LocalContext.current.getString(R.string.builtin_tts_title),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PageTab.entries.forEach { item ->
                FilterChip(
                    selected = tab == item,
                    onClick = { tab = item },
                    label = { Text(androidx.compose.ui.platform.LocalContext.current.getString(item.labelRes)) }
                )
            }
        }
        if (state.busy) {
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        if (state.message.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(state.message, style = MaterialTheme.typography.bodyMedium)
        }
        Spacer(Modifier.height(12.dp))
        when (tab) {
            PageTab.ENGINE -> EnginePanel(state, onPickModel, onAction)
            PageTab.VOICES -> VoicesPanel(state, onPickVoiceZip, onAction)
            PageTab.ROLES -> RolesPanel(state, onAction)
            PageTab.WIZARD -> WizardPanel(state, onAction)
        }
    }
}

@Composable
private fun EnginePanel(
    state: BuiltinTtsPageState,
    onPickModel: (String) -> Unit,
    onAction: (PageAction) -> Unit
) {
    Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
        state.profiles.forEach { profile ->
            Card(modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp)) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        text = (if (profile.id == "fast") "极速单声档" else "高质量克隆档") +
                                (if (profile.active) " · 当前" else ""),
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = when (profile.state) {
                            BuiltinEngineState.READY -> "已就绪（${profile.sampleRate}Hz）"
                            BuiltinEngineState.CORRUPT -> "已损坏（请重新下载/导入）"
                            BuiltinEngineState.DOWNLOADING -> "下载中"
                            BuiltinEngineState.LOW_MEM -> "内存不足"
                            BuiltinEngineState.NONE -> "未下载"
                        },
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (profile.state != BuiltinEngineState.READY) {
                            Button(onClick = { onAction(PageAction.DownloadModel(profile.id)) }) {
                                Text(androidx.compose.ui.platform.LocalContext.current.getString(R.string.builtin_tts_model_download))
                            }
                            OutlinedButton(onClick = { onPickModel(profile.id) }) {
                                Text(androidx.compose.ui.platform.LocalContext.current.getString(R.string.builtin_tts_model_import))
                            }
                        } else {
                            OutlinedButton(onClick = { onAction(PageAction.ActivateProfile(profile.id)) }) {
                                Text("设为当前档")
                            }
                            TextButton(onClick = { onAction(PageAction.DeleteModel(profile.id)) }) {
                                Text(androidx.compose.ui.platform.LocalContext.current.getString(R.string.builtin_tts_model_delete))
                            }
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { onAction(PageAction.ActivateEngine) }) {
                Text(androidx.compose.ui.platform.LocalContext.current.getString(R.string.builtin_tts_activate_engine))
            }
            OutlinedButton(onClick = { onAction(PageAction.SelfCheck) }) {
                Text(androidx.compose.ui.platform.LocalContext.current.getString(R.string.builtin_tts_self_check))
            }
            TextButton(onClick = { onAction(PageAction.DiscoverImport) }) {
                Text(androidx.compose.ui.platform.LocalContext.current.getString(R.string.builtin_tts_model_discover))
            }
        }
        Spacer(Modifier.height(12.dp))
        Text("音色 ${state.stats.voiceCount} · 缓存 ${formatBytes(state.stats.cacheBytes)} · RTF ${"%.2f".format(state.stats.lastRtf)}")
        Text("最近错误：${state.stats.lastError}")
        TextButton(onClick = { onAction(PageAction.ClearCache) }) {
            Text(androidx.compose.ui.platform.LocalContext.current.getString(R.string.builtin_tts_clear_cache))
        }
        if (state.importDir.isNotBlank()) {
            Text(
                text = androidx.compose.ui.platform.LocalContext.current
                    .getString(R.string.builtin_tts_import_dir_hint, state.importDir),
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

@Composable
private fun VoicesPanel(
    state: BuiltinTtsPageState,
    onPickVoiceZip: () -> Unit,
    onAction: (PageAction) -> Unit
) {
    Column(Modifier.fillMaxSize()) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onPickVoiceZip) {
                Text(androidx.compose.ui.platform.LocalContext.current.getString(R.string.builtin_tts_voice_import_zip))
            }
            OutlinedButton(onClick = { onAction(PageAction.AutoTag) }) {
                Text(androidx.compose.ui.platform.LocalContext.current.getString(R.string.builtin_tts_voice_auto_tag))
            }
        }
        if (state.voices.isEmpty()) {
            Text(androidx.compose.ui.platform.LocalContext.current.getString(R.string.builtin_tts_voice_empty))
            return@Column
        }
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(state.voices, key = { it.id }) { voice ->
                Card(modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 3.dp)) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(voice.displayName, fontWeight = FontWeight.SemiBold)
                            val badge = when (voice.origin) {
                                BuiltinVoice.ORIGIN_PRESET -> "内置"
                                BuiltinVoice.ORIGIN_SELF -> "自录"
                                else -> "导入"
                            }
                            val meta = buildList {
                                add(badge)
                                if (voice.gender.isNotBlank()) add(if (voice.gender == "male") "男" else "女")
                                if (voice.age.isNotBlank()) add(voice.age)
                                if (voice.refText.length > 0) add("稿${voice.refText.length}字")
                            }.joinToString(" · ")
                            Text(meta, style = MaterialTheme.typography.bodySmall)
                        }
                        TextButton(onClick = { onAction(PageAction.PreviewVoice(voice.voiceName)) }) {
                            Text(androidx.compose.ui.platform.LocalContext.current.getString(R.string.builtin_tts_voice_preview))
                        }
                        if (!voice.readOnly) {
                            TextButton(onClick = { onAction(PageAction.DeleteVoice(voice.id)) }) {
                                Text("删除")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RolesPanel(state: BuiltinTtsPageState, onAction: (PageAction) -> Unit) {
    Column(Modifier.verticalScroll(rememberScrollState())) {
        Text(state.roleSummary)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { onAction(PageAction.IdentifyRoles) }, enabled = state.hasBook) {
                Text("识别当前章角色")
            }
            OutlinedButton(onClick = { onAction(PageAction.AssignCasting) }, enabled = state.hasBook) {
                Text(androidx.compose.ui.platform.LocalContext.current.getString(R.string.builtin_tts_roles_assign_all))
            }
        }
        Spacer(Modifier.height(12.dp))
        Text(
            "说明：离线识别只做“人名 + 引号邻接 + 轮转/对话锁”，置信不足一律标未识别，不会乱猜。",
            style = MaterialTheme.typography.bodySmall
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "纠错：在阅读页改一次角色后，同 cue 模式会自动学习到本书（可在本书 fixed_roles 中查看）。",
            style = MaterialTheme.typography.bodySmall
        )
    }
}

@Composable
private fun WizardPanel(state: BuiltinTtsPageState, onAction: (PageAction) -> Unit) {
    Column(Modifier.verticalScroll(rememberScrollState())) {
        Text(androidx.compose.ui.platform.LocalContext.current.getString(R.string.builtin_tts_wizard_step1))
        TextButton(onClick = { onAction(PageAction.IdentifyRoles) }, enabled = state.hasBook) {
            Text(androidx.compose.ui.platform.LocalContext.current.getString(R.string.builtin_tts_wizard_run))
        }
        Spacer(Modifier.height(6.dp))
        Text(androidx.compose.ui.platform.LocalContext.current.getString(R.string.builtin_tts_wizard_step2))
        TextButton(onClick = { onAction(PageAction.AssignCasting) }, enabled = state.hasBook) {
            Text(androidx.compose.ui.platform.LocalContext.current.getString(R.string.builtin_tts_wizard_run))
        }
        Spacer(Modifier.height(6.dp))
        Text(androidx.compose.ui.platform.LocalContext.current.getString(R.string.builtin_tts_wizard_step3))
        Text("回到阅读页点「听书」即可；未识别段会用模板对白音色（并在分镜里标红）。")
        Spacer(Modifier.height(10.dp))
        Text(state.roleSummary, style = MaterialTheme.typography.bodySmall)
    }
}

private fun formatBytes(bytes: Long): String = BuiltinTtsUiSupport.formatBytes(bytes)

/** 管理页纯逻辑（可单测）：导入档案推断 + 体积格式化 + 页签数量 */
internal object BuiltinTtsUiSupport {

    fun guessProfile(name: String): String = when {
        name.equals("fast", true) || name.contains("piper", true) || name.contains("vits", true) -> "fast"
        else -> "clone"
    }

    fun formatBytes(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${bytes / 1024} KB"
        bytes < 1024L * 1024 * 1024 -> "${bytes / (1024 * 1024)} MB"
        else -> String.format(Locale.US, "%.1f GB", bytes.toDouble() / (1024 * 1024 * 1024))
    }
}
