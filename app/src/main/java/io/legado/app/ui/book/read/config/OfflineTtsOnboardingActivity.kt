package io.legado.app.ui.book.read.config

import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.lifecycleScope
import androidx.viewbinding.ViewBinding
import io.legado.app.base.BaseActivity
import io.legado.app.help.config.AppConfig
import io.legado.app.help.exoplayer.DeviceInfoHelper
import io.legado.app.help.readaloud.TtsUxTrace
import io.legado.app.help.readaloud.casting.TtsCastingStore
import io.legado.app.help.readaloud.role.BookAliasStore
import io.legado.app.help.tts.builtin.BuiltinEngineState
import io.legado.app.help.tts.builtin.BuiltinModelProfile
import io.legado.app.help.tts.builtin.BuiltinTtsRuntime
import io.legado.app.help.tts.builtin.BuiltinVoice
import io.legado.app.help.tts.builtin.ClonettsVoiceImporter
import io.legado.app.model.ReadAloud
import io.legado.app.model.ReadBook
import io.legado.app.ui.theme.LegadoTheme
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import okhttp3.Call
import java.io.File

/**
 * 离线听书首次配置引导（offline-tts-ux-consolidation 4）：三步买个明白。
 *
 * 设计约束（AD-03/AD-04/AD-12）：
 * - **不阻塞**：系统语音始终可听，每步都能「跳过」；跳过累计 3 次后不再自动弹出；
 * - **按档位分支**：单声档跳过第 2 步、克隆档第 2 步改为导入声音包（能力来自 [OnboardingCapability]）；
 * - **状态判定与 UI 分离**：进入条件、跳过计数、能力矩阵全部是纯函数，本类只做跳转与落盘。
 */
class OfflineTtsOnboardingActivity : BaseActivity<ViewBinding>() {

    companion object {
        const val EXTRA_FROM = "from"
        const val FROM_READ_DIALOG = "read_dialog"
        const val FROM_NOTIFICATION = "notification"
        const val FROM_GUIDE = "guide"
    }

    private var ui by mutableStateOf(OnboardingUi())

    /** 下载协程；「暂停」= cancel 当前 Call + 结束协程（`.part` 保留，重进可续传） */
    private var downloadJob: Job? = null
    private var activeCall: Call? = null
    private var userPaused = false

    override val binding: ViewBinding by lazy {
        val view = ComposeView(this).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                LegadoTheme {
                    OfflineTtsOnboardingScreen(
                        ui = ui,
                        onSelectProfile = ::selectProfile,
                        onDownload = ::startDownload,
                        onPause = ::pauseDownload,
                        onImportModel = ::importModelFile,
                        onImportVoicePack = { pickVoicePack.launch(arrayOf("application/zip", "*/*")) },
                        onSelectVoice = ::selectVoice,
                        onPreviewVoice = ::previewVoice,
                        onShuffleVoices = ::shuffleVoices,
                        onOpenAllVoices = ::openAllVoices,
                        onSelectCard = ::selectCard,
                        onNext = ::next,
                        onBack = ::previous,
                        onSkip = { skip(silent = false) }
                    )
                }
            }
        }
        object : ViewBinding {
            override fun getRoot(): View = view
        }
    }

    private val pickModelFile =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) importModelFromUri(uri)
        }

    private val pickVoicePack =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) importVoicePackFromUri(uri)
        }

    override fun onActivityCreated(savedInstanceState: android.os.Bundle?) {
        BuiltinTtsRuntime.init(this)
        runCatching { BuiltinTtsRuntime.ensureSeeded() }
        val from = intent.getStringExtra(EXTRA_FROM).orEmpty()
        TtsUxTrace.enterSettings(from.ifBlank { "guide" })
        val lowEnd = CastSceneState.isLowEndDevice(DeviceInfoHelper.totalMemoryMb())
        val capabilities = capabilities()
        val defaultId = OnboardingCapability.defaultProfileId(lowEnd)
            .takeIf { id -> capabilities.any { it.profileId == id } }
            ?: capabilities.firstOrNull()?.profileId.orEmpty()
        ui = ui.copy(
            lowEnd = lowEnd,
            capabilities = capabilities,
            profileId = defaultId,
            cardId = defaultCard(capabilities.firstOrNull { it.profileId == defaultId }),
            resumePercent = resumePercent(defaultId)
        )
        TtsUxTrace.onboardingStep(1, "enter", defaultId)
    }

    // ---------------------------------------------------------------
    // 档位与下载
    // ---------------------------------------------------------------

    private fun capabilities(): List<OnboardingCapability> = runCatching {
        OnboardingCapability.orderForDisplay(
            BuiltinTtsRuntime.profiles().map { profile ->
                OnboardingCapability.of(
                    profileId = profile.id,
                    engine = profile.engine,
                    speakerCount = profile.speakerCount,
                    sampleRate = profile.sampleRate,
                    sizeBytes = profile.sizeBytes,
                    recommended = profile.id == OnboardingCapability.PROFILE_HQ
                )
            }
        )
    }.getOrDefault(emptyList())

    private fun profileOf(id: String): BuiltinModelProfile? =
        runCatching { BuiltinTtsRuntime.modelManager().profile(id) }.getOrNull()

    private fun capabilityOf(id: String): OnboardingCapability? =
        ui.capabilities.firstOrNull { it.profileId == id }

    private fun selectProfile(profileId: String) {
        if (ui.downloading) return
        ui = ui.copy(
            profileId = profileId,
            error = "",
            message = "",
            paused = false,
            progress = 0,
            resumePercent = resumePercent(profileId),
            cardId = defaultCard(capabilityOf(profileId))
        )
    }

    /** 起始断点百分比（`.part` 长度 / 档案体积）：进页面就能告诉用户"上次下到哪" */
    private fun resumePercent(profileId: String): Int {
        if (profileId.isBlank()) return 0
        val profile = profileOf(profileId) ?: return 0
        val part = File(BuiltinTtsRuntime.paths().cacheDir, "download/${profileId}.part")
        if (!part.isFile || profile.sizeBytes <= 0) return 0
        return (part.length() * 100 / profile.sizeBytes).toInt().coerceIn(0, 99)
    }

    private fun startDownload() {
        val profile = profileOf(ui.profileId) ?: return
        val engineReady = runCatching {
            BuiltinTtsRuntime.state() == BuiltinEngineState.READY
        }.getOrDefault(false)
        if (engineReady && runCatching { BuiltinTtsRuntime.activeProfile()?.id }.getOrNull() == profile.id) {
            enterStep2()
            return
        }
        downloadJob?.cancel()
        userPaused = false
        ui = ui.copy(downloading = true, paused = false, progress = 0, error = "", message = "")
        TtsUxTrace.onboardingStep(1, "download", profile.id)
        downloadJob = lifecycleScope.launch {
            val ok = runCatching {
                BuiltinTtsRuntime.modelManager().download(
                    profile = profile,
                    onProgress = { p -> ui = ui.copy(progress = p.percent) },
                    onCallCreated = { activeCall = it }
                )
            }.getOrDefault(false)
            activeCall = null
            ui = ui.copy(downloading = false)
            when {
                ok -> {
                    BuiltinTtsRuntime.setActiveProfile(profile.id)
                    runCatching { BuiltinTtsRuntime.ensureSeeded() }
                    enterStep2()
                }
                userPaused -> {
                    ui = ui.copy(
                        paused = true,
                        resumePercent = resumePercent(profile.id),
                        message = "已暂停，可继续下载"
                    )
                    TtsUxTrace.onboardingStep(1, "pause", profile.id)
                }
                else -> {
                    ui = ui.copy(
                        error = "下载失败（可能是网络或存储空间不足）",
                        resumePercent = resumePercent(profile.id)
                    )
                    TtsUxTrace.onboardingStep(1, "fail", profile.id)
                }
            }
        }
    }

    private fun pauseDownload() {
        if (!ui.downloading) return
        userPaused = true
        runCatching { activeCall?.cancel() }
    }

    private fun importModelFile() {
        pickModelFile.launch(arrayOf("application/zip", "application/x-bzip2", "application/octet-stream", "*/*"))
    }

    private fun importModelFromUri(uri: Uri) {
        val profileId = ui.profileId
        val name = displayName(uri)
        ui = ui.copy(busy = true, error = "", message = "正在导入语音包…")
        lifecycleScope.launch {
            val ok = runCatching {
                contentResolver.openInputStream(uri)?.use { input ->
                    BuiltinTtsRuntime.modelManager().importArchive(profileId, input, name)
                } ?: false
            }.getOrDefault(false)
            ui = ui.copy(busy = false)
            if (ok) {
                BuiltinTtsRuntime.setActiveProfile(profileId)
                runCatching { BuiltinTtsRuntime.ensureSeeded() }
                enterStep2()
            } else {
                ui = ui.copy(error = "导入失败：文件不完整或格式不支持（支持 zip / tar.bz2）")
                TtsUxTrace.onboardingStep(1, "fail", profileId)
            }
        }
    }

    // ---------------------------------------------------------------
    // 第 2 步：音色
    // ---------------------------------------------------------------

    private fun enterStep2() {
        val capability = capabilityOf(ui.profileId)
        val mode = capability?.let { OnboardingCapability.step2Mode(it) }
        val voices = loadVoices()
        when (mode) {
            OnboardingStep2Mode.SKIPPED_SINGLE -> {
                // 单声档：整步跳过，直接把"不需要选音色"说清楚（4.6）
                ui = ui.copy(step = 3, voices = emptyList(), message = "本档为单声，不需要选音色")
                TtsUxTrace.onboardingStep(2, "skip", ui.profileId)
            }
            OnboardingStep2Mode.IMPORT_VOICE_PACK -> {
                ui = ui.copy(step = 2, voices = voices.take(3), message = "")
                TtsUxTrace.onboardingStep(2, "enter", ui.profileId)
            }
            else -> {
                ui = ui.copy(step = 2, voices = recommendVoices(voices, 0), message = "")
                TtsUxTrace.onboardingStep(2, "enter", ui.profileId)
            }
        }
        ui = ui.copy(cardId = defaultCard(capability))
    }

    private fun loadVoices(): List<BuiltinVoice> = runCatching {
        BuiltinTtsRuntime.voices()
            .filter { it.profileId.isBlank() || it.profileId == ui.profileId }
    }.getOrDefault(emptyList())

    /** 推荐 3 个（女/男/其它各一）；不足则取前面的，保证"永不给空列表"（防呆 1） */
    private fun recommendVoices(voices: List<BuiltinVoice>, page: Int): List<BuiltinVoice> {
        if (voices.isEmpty()) return emptyList()
        val usable = voices.filter { it.voiceName.isNotBlank() }
        if (usable.isEmpty()) return emptyList()
        val picked = LinkedHashMap<String, BuiltinVoice>()
        listOf("女", "男").forEach { gender ->
            usable.firstOrNull { it.gender.contains(gender) }?.let { picked[it.id] = it }
        }
        usable.firstOrNull { picked[it.id] == null }?.let { picked[it.id] = it }
        if (picked.size < 3) {
            usable.forEach { if (picked.size < 3) picked[it.id] = it }
        }
        val list = picked.values.toList()
        if (page <= 0) return list
        // 换一批：在可用池里按页滑动，保证每次都是 3 个不同的（池子小于 3 时原样返回）
        if (usable.size <= 3) return list
        val start = (page * 3) % usable.size
        val rotated = usable.drop(start) + usable.take(start)
        return rotated.take(3)
    }

    private var voicePage = 0

    private fun shuffleVoices() {
        voicePage += 1
        ui = ui.copy(voices = recommendVoices(loadVoices(), voicePage))
    }

    private fun selectVoice(voiceId: String) {
        ui = ui.copy(selectedVoiceId = voiceId)
        val voice = ui.voices.firstOrNull { it.id == voiceId } ?: return
        TtsUxTrace.action("select_voice", ui.profileId)
        // 立即写全局旁白：让"选了就生效"成立，不要求用户再回设置里绑定
        runCatching {
            val store = BookAliasStore(BuiltinTtsRuntime.paths().rulesRoot)
            store.writeGlobal(store.readGlobal().copy(narrator = voice.voiceName))
        }
    }

    private fun previewVoice(voiceId: String) {
        val voice = ui.voices.firstOrNull { it.id == voiceId } ?: loadVoices().firstOrNull { it.id == voiceId }
        if (voice == null) {
            toastOnUi("没有可试听的音色")
            return
        }
        val ok = BuiltinVoiceCompare.preview(this, voice)
        if (!ok) toastOnUi("该音色暂不可试听")
    }

    private fun openAllVoices() {
        startActivity(
            Intent(this, BuiltinTtsActivity::class.java)
                // key 与 PageTab.key 单源（深链），避免另写一套字符串
                .putExtra(BuiltinTtsActivity.EXTRA_TAB, PageTab.VOICES.key)
        )
    }

    private fun importVoicePackFromUri(uri: Uri) {
        ui = ui.copy(busy = true, message = "正在导入声音包…")
        lifecycleScope.launch {
            val temp = File(cacheDir, "voice_pack_${System.currentTimeMillis()}.zip")
            val copied = runCatching {
                contentResolver.openInputStream(uri)?.use { input ->
                    temp.outputStream().use { out -> input.copyTo(out) }
                    true
                } ?: false
            }.getOrDefault(false)
            val report = if (copied) {
                runCatching {
                    ClonettsVoiceImporter(BuiltinTtsRuntime.voiceStore()).importZip(
                        zipFile = temp,
                        profileId = ui.profileId.ifBlank { "clone" },
                        targetSampleRate = profileOf(ui.profileId)?.sampleRate ?: 24000
                    )
                }.getOrNull()
            } else {
                null
            }
            runCatching { temp.delete() }
            val voices = loadVoices()
            ui = ui.copy(
                busy = false,
                voices = voices.take(3),
                message = report?.let { if (it.imported > 0) "已导入 ${it.imported} 个音色" else it.message } ?: "导入失败"
            )
            TtsUxTrace.onboardingStep(2, if ((report?.imported ?: 0) > 0) "ok" else "fail", ui.profileId)
        }
    }

    // ---------------------------------------------------------------
    // 第 3 步与完成
    // ---------------------------------------------------------------

    private fun selectCard(cardId: String) {
        val capability = capabilityOf(ui.profileId) ?: return
        if (!OnboardingCapability.cardAvailable(capability, cardId)) {
            toastOnUi(OnboardingCapability.disabledReason(capability, cardId).orEmpty())
            return
        }
        ui = ui.copy(cardId = cardId)
    }

    private fun defaultCard(capability: OnboardingCapability?): String =
        if (capability == null || OnboardingCapability.cardAvailable(capability, CastSceneState.CARD_MULTI)) {
            CastSceneState.CARD_MULTI
        } else {
            CastSceneState.CARD_MONO
        }

    /** 下一步：每步边界都复检（模型 READY + 音色数 > 0），不满足就留在原地说明原因（4.11） */
    private fun next() {
        when (ui.step) {
            1 -> {
                val modelReady = runCatching {
                    profileOf(ui.profileId)?.let {
                        BuiltinTtsRuntime.modelManager().state(it) == BuiltinEngineState.READY
                    } == true
                }.getOrDefault(false)
                if (!modelReady) {
                    ui = ui.copy(error = "还没有可用的语音包：先下载或导入，再继续")
                    return
                }
                BuiltinTtsRuntime.setActiveProfile(ui.profileId)
                runCatching { BuiltinTtsRuntime.ensureSeeded() }
                enterStep2()
            }
            2 -> {
                val capability = capabilityOf(ui.profileId)
                val voices = loadVoices()
                if (capability?.needsVoicePack == true && voices.isEmpty()) {
                    ui = ui.copy(error = "这个档位需要先导入声音包")
                    return
                }
                if (capability != null && OnboardingCapability.step2Mode(capability) == OnboardingStep2Mode.INLINE_VOICES &&
                    ui.selectedVoiceId.isBlank()
                ) {
                    // 默认选中第一个推荐音色：不让"没点选"成为卡点（防呆 4：默认值即最优）
                    voices.firstOrNull()?.let { selectVoice(it.id) }
                }
                ui = ui.copy(step = 3, error = "", message = "")
                TtsUxTrace.onboardingStep(2, "ok", ui.profileId)
            }
            else -> complete(startReading = true)
        }
    }

    private fun previous() {
        if (ui.step <= 1) return
        if (ui.step == 3) {
            // 单声档第 2 步是跳过的，返回时直接回第 1 步
            val capability = capabilityOf(ui.profileId)
            val single = capability != null &&
                    OnboardingCapability.step2Mode(capability) == OnboardingStep2Mode.SKIPPED_SINGLE
            ui = ui.copy(step = if (single) 1 else 2, message = "")
            return
        }
        ui = ui.copy(step = 1, message = "")
    }

    private fun skip(silent: Boolean) {
        val result = OfflineTtsOnboardingState.skipResult(AppConfig.offlineTtsOnboardSkipCount)
        AppConfig.offlineTtsOnboardSkipCount = result.newCount
        TtsUxTrace.onboardingStep(ui.step, "skip", ui.profileId)
        if (!silent) toastOnUi("已跳过：本次先用系统语音朗读")
        finish()
    }

    /** 完成：写 onboarded、清零跳过计数、激活演播方式，然后回阅读页起播（4.9） */
    private fun complete(startReading: Boolean) {
        val mark = OfflineTtsOnboardingState.onCompleted()
        AppConfig.offlineTtsOnboarded = mark.onboarded
        AppConfig.offlineTtsOnboardSkipCount = mark.skipCount
        runCatching { BuiltinTtsRuntime.setActiveProfile(ui.profileId) }
        val template = CastSceneState.templateOf(ui.cardId)
        if (template != null) {
            lifecycleScope.launch {
                runCatching { importBuiltinTemplates() }
                runCatching { TtsCastingStore.setActiveTemplateId(template) }
            }
        }
        TtsUxTrace.onboardingStep(3, "ok", ui.profileId)
        if (startReading && ReadBook.book != null) {
            ReadAloud.play(this)
        }
        finish()
    }

    private suspend fun importBuiltinTemplates() {
        runCatching {
            assets.open("defaultData/tts/castingTemplates.json").bufferedReader().use { it.readText() }
        }.getOrNull()?.let { json ->
            runCatching { TtsCastingStore.importBuiltinTemplates(json) }
        }
    }

    private fun displayName(uri: Uri): String = runCatching {
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
        }
    }.getOrNull() ?: uri.lastPathSegment.orEmpty().substringAfterLast('/')
}

/** 引导页 UI 状态（拆出便于 Screen 纯渲染；不含业务判定） */
data class OnboardingUi(
    val step: Int = 1,
    val profileId: String = "",
    val capabilities: List<OnboardingCapability> = emptyList(),
    val voices: List<BuiltinVoice> = emptyList(),
    val selectedVoiceId: String = "",
    val cardId: String = CastSceneState.CARD_MULTI,
    val downloading: Boolean = false,
    val paused: Boolean = false,
    val progress: Int = 0,
    val resumePercent: Int = 0,
    val error: String = "",
    val busy: Boolean = false,
    val message: String = "",
    val lowEnd: Boolean = false
)
