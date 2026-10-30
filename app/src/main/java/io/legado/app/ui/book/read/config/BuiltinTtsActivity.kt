package io.legado.app.ui.book.read.config

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.speech.tts.TextToSpeech
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import io.legado.app.help.readaloud.casting.BuiltinCastingSetup
import io.legado.app.help.readaloud.casting.CharacterVoiceAssignService
import io.legado.app.help.readaloud.role.BookAliasStore
import io.legado.app.help.readaloud.role.OfflineCharacterDiscovery
import io.legado.app.help.readaloud.role.OfflineRoleCacheStore
import io.legado.app.help.readaloud.role.OfflineRoleResolver
import io.legado.app.help.tts.builtin.BuiltinEngineState
import io.legado.app.help.tts.builtin.BuiltinModelProfile
import io.legado.app.help.tts.builtin.BuiltinTtsRuntime
import io.legado.app.help.tts.builtin.BuiltinVoice
import io.legado.app.help.tts.builtin.BuiltinVoiceExporter
import io.legado.app.help.tts.builtin.BuiltinVoiceFilter
import io.legado.app.help.tts.builtin.BuiltinVoicePools
import io.legado.app.help.tts.builtin.ClonettsVoiceImporter
import io.legado.app.help.tts.builtin.ReferenceAudioProcessor
import io.legado.app.help.tts.builtin.ReferenceAudioRecorder
import io.legado.app.help.tts.builtin.VoiceAutoTagger
import io.legado.app.model.ReadBook
import io.legado.app.ui.book.character.BookCharacterEditActivity
import io.legado.app.ui.book.character.BookCharacterManageActivity
import io.legado.app.ui.theme.LegadoTheme
import io.legado.app.utils.LogUtils
import kotlinx.coroutines.Dispatchers
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

    /** 自录音色控制器（T2.4）：录音/加工/入库细节不在 Activity 内（也避免文件超行数门禁） */
    private val recordController: BuiltinVoiceRecordController by lazy {
        BuiltinVoiceRecordController(
            scope = lifecycleScope,
            cacheDir = cacheDir,
            sampleRateProvider = { BuiltinTtsRuntime.activeProfile()?.sampleRate ?: 24000 },
            voiceStore = { BuiltinTtsRuntime.voiceStore() },
            profileIdProvider = { BuiltinTtsRuntime.activeProfile()?.id.orEmpty() },
            onState = { recording, hint ->
                pageState.value = pageState.value.copy(recording = recording, recordHint = hint)
            },
            onSaved = { voice, durationMs ->
                pageState.value = pageState.value.copy(
                    busy = false,
                    message = "已保存音色「${voice.displayName}」（${durationMs / 1000}s，已自动去静音）"
                )
                reload()
            },
            onError = { message ->
                pageState.value = pageState.value.copy(busy = false, message = message)
            }
        )
    }

    private val requestRecordPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                startRecord()
            } else {
                pageState.value = pageState.value.copy(message = "未授予录音权限，无法自录音色")
            }
        }

    /** 角色卡编辑（T8.11）：跳既有编辑页，返回后刷新角色列表与分镜 */
    private val editCharacter =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            reload()
            pageState.value = pageState.value.copy(message = "角色卡已更新（可重新配声）")
        }

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
                // T8.16：导入后按 tag 聚合成声音池（幂等，失败不阻断导入结果）
                    .also { runCatching { BuiltinVoicePools.sync(BuiltinTtsRuntime.voiceStore()) } }
            }.getOrNull()
            pageState.value = pageState.value.copy(
                busy = false,
                message = report?.message ?: "音色包导入失败"
            )
            targetCleanup()
            reload()
        }
    }

    private val exportVoices = registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri == null) return@registerForActivityResult
        lifecycleScope.launch(Dispatchers.IO) {
            pageState.value = pageState.value.copy(busy = true, message = "导出中…")
            val target = File(cacheDir, "voice-export.zip")
            val report = BuiltinVoiceExporter(BuiltinTtsRuntime.voiceStore()).export(target)
            val ok = runCatching {
                contentResolver.openOutputStream(uri)?.use { out ->
                    target.inputStream().use { input -> input.copyTo(out) }
                }
                true
            }.getOrDefault(false)
            runCatching { target.delete() }
            pageState.value = pageState.value.copy(
                busy = false,
                message = if (ok && report.exported > 0) report.message else "导出失败（无音色或写入被拒绝）"
            )
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
        runCatching { recordController.cancel() }
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
                    // T6.3：页内可见进度（已打标的音色自动跳过 ⇒ 可断点续）
                    val result = VoiceAutoTagger.tagAll(BuiltinTtsRuntime.voiceStore()) { done, total ->
                        pageState.value = pageState.value.copy(message = "打标中 $done/$total")
                    }
                    // T8.16：打标后按 tag 自动聚合声音池（幂等）
                    val pooled = runCatching { BuiltinVoicePools.sync(BuiltinTtsRuntime.voiceStore()) }
                        .getOrDefault(0)
                    pageState.value = pageState.value.copy(
                        busy = false,
                        message = BuiltinTtsUiSupport.autoTagMessage(
                            processed = result.processed,
                            male = result.male,
                            female = result.female,
                            unknown = result.unknown,
                            pooled = pooled
                        )
                    )
                }
                is PageAction.DeleteVoice -> {
                    BuiltinTtsRuntime.deleteVoice(action.voiceId)
                    pageState.value = pageState.value.copy(message = "音色已删除")
                }
                is PageAction.PreviewVoice -> previewVoice(action.voiceName)
                PageAction.PreviewAllVoices -> previewAllVoices()
                is PageAction.UpdateRecordText ->
                    pageState.value = pageState.value.copy(recordText = action.text)
                PageAction.StartRecord -> startRecord()
                PageAction.StopRecord -> stopRecord()
                PageAction.ExportVoices -> exportVoices.launch("legado-voices.zip")
                PageAction.AssignCasting -> assignCasting()
                PageAction.IdentifyRoles -> identifyRoles()
                PageAction.EnableMultiRole -> enableMultiRole()
                PageAction.QuickEnable -> quickEnable()
                is PageAction.AssignSegment -> assignSegment(action)
                is PageAction.AssignVoice -> assignVoice(action)
                is PageAction.EditCharacter -> openCharacterEditor(action.characterId)
                PageAction.Reload -> Unit
            }
    }

    /**
     * 自录音色（T2.4）：录音前先让用户照读示范句（参考文本必须与音频一致）；
     * 录音/加工/入库细节在 [BuiltinVoiceRecordController]，这里只处理权限与页面状态。
     */
    private fun startRecord() {
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
        if (!granted) {
            requestRecordPermission.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        val sampleText = pageState.value.recordText.ifBlank { ReferenceAudioRecorder.DEFAULT_SAMPLE_TEXT }
        recordController.start(sampleText)
    }

    private fun stopRecord() {
        recordController.stop()
    }
    /**
     * 分镜改绑（T5.5）：写离线缓存（`OfflineRoleCacheStore`，零网络、不触发 AI 重跑），
     * 同时把"该 cue → 角色"学习进本书（`BookAliasStore`），后续章同模式自动命中。
     */
    private suspend fun assignSegment(action: PageAction.AssignSegment) {
        val book = ReadBook.book
        val chapter = ReadBook.curTextChapter
        if (book == null || chapter == null) {
            pageState.value = pageState.value.copy(message = "请先在阅读页打开当前章节")
            return
        }
        val bookKey = book.characterBookKey()
        val paragraphs = chapter.paragraphs.map { it.text }
        val ok = OfflineRoleCacheStore.updateSegmentAssignment(
            bookKey = bookKey,
            chapterIndex = chapter.chapter.index,
            paragraphs = paragraphs,
            paragraphIndex = action.paragraphIndex,
            start = action.start,
            end = action.end,
            roleType = if (action.narrator) "narrator" else "character",
            characterId = if (action.narrator) 0L else action.characterId,
            characterName = if (action.narrator) "" else action.characterName
        )
        if (!action.narrator && action.characterName.isNotBlank()) {
            val paragraph = paragraphs.getOrNull(action.paragraphIndex).orEmpty()
            val cue = paragraph.take(action.start).takeLast(CUE_WINDOW) +
                    paragraph.substring(action.end.coerceAtMost(paragraph.length))
            cue.trim().takeIf { it.isNotBlank() }?.let { text ->
                BookAliasStore(BuiltinTtsRuntime.paths().rulesRoot).learnCue(bookKey, text, action.characterName)
            }
        }
        // 内存缓存与数据库会分叉：清掉本章内存后**必须重新读库回填**，否则分镜页会瞬间变空（IF-10）
        OfflineRoleResolver.clearMemory(bookKey)
        if (ok) {
            runCatching {
                OfflineRoleResolver.ensure(
                    bookKey = bookKey,
                    chapterIndex = chapter.chapter.index,
                    chapterTitle = chapter.chapter.title,
                    paragraphs = paragraphs,
                    characters = CharacterVoiceAssignService.roleCharacters(bookKey),
                    aliasStore = BookAliasStore(BuiltinTtsRuntime.paths().rulesRoot)
                )
            }.onFailure {
                LogUtils.e("BuiltinTtsUi", "改绑后回填分镜失败：${it.stackTraceToString()}")
            }
        }
        pageState.value = pageState.value.copy(
            busy = false,
            message = BuiltinTtsUiSupport.segmentAssignMessage(
                ok = ok,
                narrator = action.narrator,
                characterName = action.characterName
            )
        )
        reload()
    }

    /** 角色配音单角色换音色（T5.4）：手动绑定写 `source=manual`，撞音由页面即时标记 */
    private suspend fun assignVoice(action: PageAction.AssignVoice) {
        val book = ReadBook.book
        if (book == null) {
            pageState.value = pageState.value.copy(message = "请先打开一本书")
            return
        }
        val voice = BuiltinTtsRuntime.voices().firstOrNull {
            it.voiceName == action.voiceName || it.id == action.voiceName
        }
        if (voice == null) {
            pageState.value = pageState.value.copy(message = "音色不存在（可能已被删除）")
            return
        }
        val ok = CharacterVoiceAssignService.assignVoice(this, book, action.characterId, voice)
        pageState.value = pageState.value.copy(
            message = if (ok) {
                "已把「${voice.displayName}」配给该角色（重开朗读生效）"
            } else {
                "换音色失败（角色卡可能已被删除）"
            }
        )
        reload()
    }

    /** T8.11：跳既有角色卡编辑页（改性别/别名后返回，由 result 回调刷新并提示重新配声） */
    private fun openCharacterEditor(characterId: Long) {
        val book = ReadBook.book
        if (book == null) {
            pageState.value = pageState.value.copy(message = "请先打开一本书")
            return
        }
        val intent = Intent(this, BookCharacterEditActivity::class.java)
            .putExtra(BookCharacterManageActivity.EXTRA_BOOK_URL, book.bookUrl)
            .putExtra(BookCharacterManageActivity.EXTRA_CHARACTER_BOOK_KEY, book.characterBookKey())
            .putExtra(BookCharacterManageActivity.EXTRA_CHARACTER_ID, characterId)
        runCatching { editCharacter.launch(intent) }
            .onFailure { LogUtils.e("BuiltinTtsUi", "打开角色卡编辑失败：${it.message}") }
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

    /**
     * 一键开书（T5.3）：角色发现（离线）→ 一键配声 → 激活内置多角色模板。
     * 三步缺一不可：没有角色=配声空转；没有模板=阅读服务永远走 legacy 单声（铁证见 issues-found IF-05）。
     */
    private suspend fun enableMultiRole() {
        val book = ReadBook.book
        if (book == null) {
            pageState.value = pageState.value.copy(message = "请先打开一本书")
            return
        }
        val chapter = ReadBook.curTextChapter
        if (chapter == null) {
            pageState.value = pageState.value.copy(message = "请先在阅读页打开当前章节")
            return
        }
        pageState.value = pageState.value.copy(busy = true, message = "准备多角色…")
        val bookKey = book.characterBookKey()
        val paragraphs = chapter.paragraphs.map { it.text }
        var discovered = 0
        var characters = CharacterVoiceAssignService.roleCharacters(bookKey)
        if (characters.isEmpty()) {
            discovered = OfflineCharacterDiscovery.ensureCharacters(bookKey, paragraphs)
            characters = CharacterVoiceAssignService.roleCharacters(bookKey)
        }
        // 离线分镜（AD-13/T5.3）：一键开书必须同时产出「段→角色」，否则分镜页为空、多角色只能靠模板兜底
        val storyboardSegments = runCatching {
            OfflineRoleResolver.ensure(
                bookKey = bookKey,
                chapterIndex = chapter.chapter.index,
                chapterTitle = chapter.chapter.title,
                paragraphs = paragraphs,
                characters = characters,
                aliasStore = BookAliasStore(BuiltinTtsRuntime.paths().rulesRoot)
            ).size
        }.onFailure {
            LogUtils.e("BuiltinTtsUi", "离线分镜失败：${it.stackTraceToString()}")
        }.getOrDefault(0)
        val assigned = CharacterVoiceAssignService.assignAll(this, book)
        val activated = BuiltinCastingSetup.ensureAndActivate(assets)
        pageState.value = pageState.value.copy(
            busy = false,
            message = BuiltinTtsUiSupport.multiRoleSummary(
                characters = characters.size,
                discovered = discovered,
                assigned = assigned.assignments.size,
                activated = activated,
                storyboardSegments = storyboardSegments
            )
        )
        reload()
    }

    /**
     * 首次启用一键卡（T4.1）：模型就绪 → 切系统引擎 → 启用多角色模板 → 自检。
     * 小白用户只需点一次（模型下载/导入仍是必要前置，不计入点击数）。
     */
    private suspend fun quickEnable() {
        pageState.value = pageState.value.copy(busy = true, message = "正在启用…")
        BuiltinTtsRuntime.ensureSeeded()
        if (!BuiltinTtsRuntime.isReady()) {
            pageState.value = pageState.value.copy(
                busy = false,
                message = "还差语音包：请先下载或导入（引擎页），再点一键启用"
            )
            return
        }
        val profileId = BuiltinTtsRuntime.activeProfile()?.id.orEmpty()
        BuiltinTtsRuntime.setActiveProfile(profileId)
        val engineMessage = activateAsSystemEngine()
        val castingActivated = BuiltinCastingSetup.ensureAndActivate(assets)
        val checkMessage = runSelfCheck()
        pageState.value = pageState.value.copy(
            busy = false,
            message = BuiltinTtsUiSupport.quickEnableSummary(engineMessage, castingActivated, checkMessage)
        )
        reload()
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

    /**
     * 批量试听（T8.8）：按列表顺序连续播报，优先抽非内置音色（内置 6 个用户已熟悉）。
     * 用 QUEUE_ADD 串行，不打断正在进行的朗读（试听走独立 TextToSpeech 实例）。
     */
    private fun previewAllVoices() {
        val all = BuiltinTtsRuntime.voices()
        if (all.isEmpty()) {
            pageState.value = pageState.value.copy(message = "还没有音色可试听")
            return
        }
        val batch = BuiltinTtsUiSupport.previewBatch(all, PREVIEW_BATCH_SIZE)
        val run = {
            val tts = previewTts
            if (tts != null) {
                batch.forEach { voice ->
                    runCatching {
                        tts.voices?.firstOrNull { it.name == voice.voiceName }?.let { tts.setVoice(it) }
                        tts.speak(
                            "这是${voice.displayName}的声音。",
                            TextToSpeech.QUEUE_ADD,
                            null,
                            "builtin_preview_all"
                        )
                    }
                }
            }
        }
        if (previewTts == null) {
            previewTts = TextToSpeech(this, { status ->
                if (status == TextToSpeech.SUCCESS) run()
            }, packageName)
        } else {
            run()
        }
        pageState.value = pageState.value.copy(message = "连续试听 ${batch.size} 个音色")
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
        /** 改绑学习时截取的 cue 窗口（引号两侧各 24 字，与离线归因的近场口径一致） */
        private const val CUE_WINDOW = 24
        /** 批量试听一次最多几个（T8.8，避免一次播太久） */
        private const val PREVIEW_BATCH_SIZE = 5
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
        val storage = runCatching { BuiltinTtsRuntime.storageBreakdown() }
            .getOrDefault(BuiltinTtsRuntime.StorageBreakdown())
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
            storageText = BuiltinTtsUiSupport.storageSummary(
                modelBytes = storage.modelBytes,
                voiceBytes = storage.voiceBytes,
                cacheBytes = storage.cacheBytes
            ),
            importDir = BuiltinTtsRuntime.paths().importDir.absolutePath,
            hasBook = bookKey != null,
            roleSummary = roleSummary(bookKey),
            storyboard = buildStoryboard(bookKey),
            pickCharacters = emptyList(),
            storyboardHint = storyboardHint(bookKey)
        )
        // 角色卡是挂起查询（DAO），异步补进状态（分镜改绑弹窗 + 角色配音页用）
        if (bookKey != null) {
            lifecycleScope.launch {
                val characters = runCatching {
                    CharacterVoiceAssignService.roleCharacters(bookKey)
                }.getOrDefault(emptyList())
                val rows = runCatching { CharacterVoiceAssignService.rows(bookKey) }
                    .getOrDefault(emptyList())
                pageState.value = pageState.value.copy(
                    pickCharacters = characters.map { character ->
                        RolePickRow(
                            id = character.id,
                            name = character.name,
                            gender = character.gender,
                            roleLevel = character.roleLevel
                        )
                    },
                    roleRows = rows,
                    conflictVoices = CharacterVoiceAssignService.conflictingVoices(rows)
                )
            }
        }
    }

    /** 分镜行（T5.5）：只展示当前章已识别的段；未识别段标"未识别"，不静默归旁白 */
    private fun buildStoryboard(bookKey: String?): List<StoryboardRow> {
        if (bookKey == null) return emptyList()
        val chapter = ReadBook.curTextChapter ?: return emptyList()
        val segments = OfflineRoleResolver.cached(bookKey, chapter.chapter.index) ?: return emptyList()
        val paragraphs = chapter.paragraphs.map { it.text }
        return segments.map { segment ->
            val paragraph = paragraphs.getOrNull(segment.paragraphIndex).orEmpty()
            StoryboardRow(
                paragraphIndex = segment.paragraphIndex,
                start = segment.start,
                end = segment.end,
                text = BuiltinTtsUiSupport.storyboardSnippet(paragraph, segment.start, segment.end),
                roleLabel = BuiltinTtsUiSupport.storyboardLabel(
                    roleType = segment.roleType,
                    recognized = segment.recognized,
                    characterName = segment.characterName
                ),
                confidence = segment.confidence,
                narrator = segment.roleType == "narrator",
                recognized = segment.recognized
            )
        }
    }

    private fun storyboardHint(bookKey: String?): String = when {
        bookKey == null -> "请先打开一本书"
        ReadBook.curTextChapter == null -> "请先在阅读页打开当前章节"
        buildStoryboard(bookKey).isEmpty() -> "本章还没有分镜：先点「识别角色」（离线，零网络）"
        else -> "点任意一段可改绑角色；改动只写本地缓存，不会触发联网"
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
