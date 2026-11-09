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
import io.legado.app.help.IntentHelp
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
import io.legado.app.utils.startActivity
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
                        onDownload = { startDownload() },
                        onPause = ::pauseDownload,
                        onImportModel = ::importModelFile,
                        onImportVoicePack = { pickVoicePack.launch(arrayOf("application/zip", "*/*")) },
                        onSelectVoice = ::selectVoice,
                        onPreviewVoice = ::previewVoice,
                        onShuffleVoices = ::shuffleVoices,
                        onOpenAllVoices = ::openAllVoices,
                        onSelectCard = ::selectCard,
                        onHealthAction = ::handleHealthAction,
                        onConfirmMobileDownload = ::confirmMobileDownload,
                        onDismissMobileConfirm = ::dismissMobileConfirm,
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
            resumePercent = resumePercent(defaultId),
            readyProfileIds = readyProfileIds()
        )
        refreshHealth()
        TtsUxTrace.onboardingStep(1, "enter", defaultId)
    }

    /**
     * 完成前体检（R21）：通知 / 电池优化 / 系统语音引擎。
     * 在 onResume 也刷一次 —— 用户去系统设置回来要立刻看到状态变化，不用重进页面。
     */
    override fun onResume() {
        super.onResume()
        if (ui.capabilities.isNotEmpty()) refreshHealth()
    }

    private fun refreshHealth() {
        val notificationsOk = runCatching {
            androidx.core.app.NotificationManagerCompat.from(this).areNotificationsEnabled()
        }.getOrDefault(true)
        val batteryOk = runCatching {
            val pm = getSystemService(android.os.PowerManager::class.java)
            pm?.isIgnoringBatteryOptimizations(packageName) == true
        }.getOrDefault(true)
        val engineOk = runCatching {
            BuiltinTtsRuntime.isActiveEngine(this, AppConfig.ttsEngine.orEmpty())
        }.getOrDefault(false)
        ui = ui.copy(
            health = OnboardingHealthCheck.items(notificationsOk, batteryOk, engineOk)
        )
    }

    /** 体检项点击：只跳系统设置，不在应用内伪造"已开" */
    private fun handleHealthAction(id: String) {
        TtsUxTrace.action("health_$id", "system_settings")
        runCatching {
            when (id) {
                OnboardingHealthCheck.ID_NOTIFICATION -> startActivity(
                    Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, packageName)
                )
                OnboardingHealthCheck.ID_BATTERY -> startActivity(
                    Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                        .setData(Uri.parse("package:$packageName"))
                )
                OnboardingHealthCheck.ID_ENGINE -> IntentHelp.openTTSSetting()
            }
        }.onFailure { toastOnUi("打不开系统设置，可稍后手动开启") }
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

    /** 已下载就绪的档位集合（第 1 步卡片显示「已下载」，避免用户以为还要再下一遍） */
    private fun readyProfileIds(): Set<String> = runCatching {
        val manager = BuiltinTtsRuntime.modelManager()
        manager.profiles()
            .filter { manager.state(it) == BuiltinEngineState.READY }
            .map { it.id }
            .toSet()
    }.getOrDefault(emptySet())

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

    private fun startDownload(force: Boolean = false) {
        val profile = profileOf(ui.profileId) ?: return
        // 已经下载就绪的档位直接启用，不走下载、也不弹流量确认
        // （真机铁证 2026-11-09：选了已就绪的「轻量多音色」仍弹「正在使用移动网络」，用户会以为要重下 30MB）
        val ready = runCatching {
            BuiltinTtsRuntime.modelManager().state(profile) == BuiltinEngineState.READY
        }.getOrDefault(false)
        if (!OfflineTtsOnboardingState.step1NeedsDownload(ready)) {
            activateAndEnterStep2(profile.id)
            return
        }
        // S18 移动网络确认：蜂窝下不直接吃流量，先弹一次确认（用户确认后本次不再拦）
        if (!force && OnboardingHealthCheck.needsMobileConfirm(isCellular())) {
            ui = ui.copy(showMobileConfirm = true)
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
                    activateAndEnterStep2(profile.id)
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

    /** 移动网络确认：确认后本次直接下载 */
    private fun confirmMobileDownload() {
        ui = ui.copy(showMobileConfirm = false)
        startDownload(force = true)
    }

    private fun dismissMobileConfirm() {
        ui = ui.copy(showMobileConfirm = false, message = "已取消下载：可连 Wi-Fi 后再下")
    }

    private fun isCellular(): Boolean = runCatching {
        val cm = getSystemService(android.net.ConnectivityManager::class.java)
            ?: return@runCatching false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return@runCatching false
        caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR) &&
            !caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI)
    }.getOrDefault(false)

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
                activateAndEnterStep2(profileId)
            } else {
                ui = ui.copy(error = "导入失败：文件不完整或格式不支持（支持 zip / tar.bz2）")
                TtsUxTrace.onboardingStep(1, "fail", profileId)
            }
        }
    }

    // ---------------------------------------------------------------
    // 第 2 步：音色
    // ---------------------------------------------------------------

    /**
     * 启用某档并进入第 2 步：下载完成 / 本地导入 / 本来就已就绪 三条路径共用，
     * 保证「激活档 + 补播内置音色（多音色档否则显示本档可用 0）+ 进第 2 步」三件事不遗漏。
     */
    private fun activateAndEnterStep2(profileId: String) {
        runCatching { BuiltinTtsRuntime.setActiveProfile(profileId) }
        runCatching { BuiltinTtsRuntime.ensureSpeakersReady(profileId) }
        enterStep2()
    }

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
                val recommended = recommendVoices(voices, 0)
                ui = ui.copy(
                    step = 2,
                    voices = recommended,
                    // 默认选中第一个推荐音色：卡片没有选中态时用户不知道"下一步"会用谁的声音
                    selectedVoiceId = OfflineTtsOnboardingState.defaultSelectedVoiceId(
                        current = ui.selectedVoiceId,
                        availableIds = recommended.map { it.id }
                    ),
                    message = ""
                )
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
        returnToCastScene()
        finish()
    }

    /**
     * 返回「听书设置」：从引导页（引擎页向导 tab / 其它入口）进来的，
     * 配完不能把用户丢回"向导"页（那里只有两个按钮），要落到听书设置首屏；
     * 从听书设置进来的直接 finish 回栈里那一层，避免重复实例。
     */
    private fun returnToCastScene() {
        val from = intent.getStringExtra(EXTRA_FROM).orEmpty()
        if (OfflineTtsOnboardingState.shouldOpenCastSceneAfterOnboarding(from)) {
            runCatching {
                startActivity<CastSceneActivity> {
                    putExtra(CastSceneActivity.EXTRA_FROM, CastSceneActivity.FROM_GUIDE)
                }
            }
        }
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
        returnToCastScene()
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
    val lowEnd: Boolean = false,
    /** 完成前体检三项（design §1.17 第 4 维）；空列表 = 尚未计算 */
    val health: List<OnboardingHealthCheck.Item> = emptyList(),
    /** 移动网络下载确认弹窗（S18） */
    val showMobileConfirm: Boolean = false,
    /** 已下载就绪的档位（第 1 步显示「已下载」，不再误导用户重复下载） */
    val readyProfileIds: Set<String> = emptySet()
)
