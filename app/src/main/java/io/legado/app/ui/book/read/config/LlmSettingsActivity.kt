package io.legado.app.ui.book.read.config

import android.app.ActivityManager
import android.net.Uri
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
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
import io.legado.app.BuildConfig
import io.legado.app.base.BaseActivity
import io.legado.app.constant.AppLog
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.LocalLlmPrefs
import io.legado.app.help.llm.DebugLlmOverride
import io.legado.app.help.llm.LlmClient
import io.legado.app.help.llm.LlmModelCatalog
import io.legado.app.help.llm.LlmModelDownloader
import io.legado.app.help.llm.LlmModelManager
import io.legado.app.ui.theme.LegadoTheme
import io.legado.app.utils.startActivity
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 2.4 本地 AI 设置页（设计 `ui/.../llm/LlmSettingsScreen` 的落点）。
 *
 * 入口：朗读设置 → 引擎组「本地 AI」。
 * 页面回答四个问题：能不能用（三层判定 + 原因）、有哪些模型、自检多快、AI 任务走哪条通道。
 *
 * 资产来源分两类（对用户问题的直接回答）：
 * - **公共资源可二次下载**：随包目录里 `distribution=download` 且带 sha256 的档位
 *   （当前 = 推荐档 Qwen3-0.6B Q4_K_M，ModelScope 镜像实测 206 可续传）；
 * - **需自备导入**：其余候选（无公开源或未锁定发行文件）⇒ 只能「导入 .gguf」。
 */
class LlmSettingsActivity : BaseActivity<ViewBinding>() {

    private var ui by mutableStateOf(
        LlmSettingsUi(
            showEntry = true,
            switchChecked = false,
            switchEnabled = false,
            reason = "",
            models = emptyList(),
            policy = LocalLlmPrefs.POLICY_LOCAL,
            policyLabel = "",
            speedText = "",
            importHint = ""
        )
    )
    private var busy by mutableStateOf(false)
    private var message by mutableStateOf("")
    /** 可二次下载的推荐档（目录里 downloadable 的那一个；无则为 null ⇒ 只显示导入） */
    private var downloadProfile by mutableStateOf<LlmModelCatalog.Profile?>(null)
    private var downloadProgress by mutableStateOf("")

    private val manager by lazy { LlmModelManager.forApp(this) }

    /**
     * IPC 客户端（自检走 `:llm` 进程）。
     *
     * 注意：这里**不再**持有 `LlmRuntime`。原实现主进程直接 `LlmRuntime.forContext(this)`，
     * 点一次自检就会把 llama.cpp native 和整个 GGUF（推荐档 396MB）加载进主进程 ——
     * 与 AD-11 的隔离初衷相反（native OOM 会直接带走正在朗读的 App）。
     *
     * 用显式 `Lazy` 而非 `by lazy`：`onDestroy` 里要判断"是否真的建立过连接"再解绑，
     * 否则退出页面时反而会去新建一个 ServiceConnection。
     */
    private val llmClientDelegate = lazy { LlmClient.forContext(this) }
    private val llmClient by llmClientDelegate

    private val pickModel = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(::importModel)
    }

    override val binding: ViewBinding by lazy {
        val view = ComposeView(this).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                LegadoTheme {
                    LlmSettingsScreen(
                        ui = ui,
                        busy = busy,
                        message = message,
                        onToggle = ::toggleEnabled,
                        onImport = { pickModel.launch(arrayOf("*/*")) },
                        onDownload = ::downloadRecommended,
                        downloadTitle = LlmSettingsState.downloadEntryTitle(
                            profileId = downloadProfile?.id,
                            profileTitle = downloadProfile?.title,
                            installedIds = ui.models.map { it.id }
                        ),
                        downloadProgress = downloadProgress,
                        onBench = ::runBenchmark,
                        onPickModel = ::activateModel,
                        onDelete = ::deleteModel,
                        onPolicy = ::setPolicy,
                        onOpenAssets = { startActivity<VoiceAssetCenterActivity>() }
                    )
                }
            }
        }
        object : ViewBinding {
            override fun getRoot(): View = view
        }
    }

    override fun onDestroy() {
        // 解绑推理服务：不解绑会一直持有 ServiceConnection，把 `:llm` 进程（含 native 常驻内存）
        // 无必要地养活着。只在真正建立过连接时才解绑。
        if (llmClientDelegate.isInitialized()) {
            runCatching { llmClient.close() }
        }
        super.onDestroy()
    }

    override fun onActivityCreated(savedInstanceState: android.os.Bundle?) {
        // 仅 DEBUG：允许通过 Intent extra 注入本地 AI 开关。低内存设备被 RAM 门限拦在 UI 外，
        // 而端侧链路验证（tasks 7.2/7.6）必须能打开它；改 prefs 文件会被平台回写（E15）。
        DebugLlmOverride.request(
            debugBuild = BuildConfig.BUILD_DEBUG,
            wantEnable = intent?.getBooleanExtra(DebugLlmOverride.EXTRA_ENABLE, false) == true,
            rawModelId = intent?.getStringExtra(DebugLlmOverride.EXTRA_MODEL_ID),
            wantBench = intent?.getBooleanExtra(DebugLlmOverride.EXTRA_BENCH, false) == true
        )?.let { request ->
            AppConfig.localLlmProfileId = request.modelId
            AppConfig.localLlmEnabled = true
            // 顺带做一次 IPC 连通性自检：把「主进程 ↔ :llm」是否真的建立写进日志，
            // 这样 7.2/7.6 的 L3 脚本不必依赖"某本书有正文"就能判定推理链路是否可用
            lifecycleScope.launch {
                val client = llmClient
                val connected = client.ensureConnected()
                val phase = if (connected) client.status().phase else ""
                AppLog.put(LlmSettingsState.ipcSelfCheckMessage(connected, phase))
                if (connected && request.bench) {
                    // 调试链路验证：真正在 `:llm` 里加载模型并生成 64 token（7.2 自检环）
                    val model = runCatching { manager.find(request.modelId) }.getOrNull()
                    if (model == null) {
                        AppLog.put("DebugLlmOverride: 自检失败：找不到模型 ${request.modelId}")
                    } else {
                        val bench = client.bench(model.modelFile.absolutePath)
                        AppLog.put(
                            "DebugLlmOverride: " + LlmSettingsState.benchMessage(
                                ok = bench.ok,
                                loadMs = bench.loadMs,
                                tokenCount = bench.tokenCount,
                                tokPerSec = bench.tokPerSec,
                                failureMessage = bench.message
                            )
                        )
                    }
                }
                client.close()
                finish()
            }
            return
        }
        reload()
        lifecycleScope.launch {
            val profiles = withContext(Dispatchers.IO) { LlmModelCatalog.load(this@LlmSettingsActivity) }
            downloadProfile = LlmModelCatalog.recommendedDownloadable(profiles)
        }
    }

    private fun reload() {
        lifecycleScope.launch {
            val facts = withContext(Dispatchers.IO) { loadFacts() }
            ui = LlmSettingsState.build(facts)
        }
    }

    private suspend fun loadFacts(): LlmSettingsState.Facts {
        val models = withContext(Dispatchers.IO) { manager.scan() }
        return LlmSettingsState.Facts(
            engineAvailable = engineAvailable(),
            modelInstalled = models.isNotEmpty(),
            totalRamMb = totalRamMb(),
            enabled = AppConfig.localLlmEnabled,
            policy = AppConfig.aiTaskPolicy,
            speedTokPerSec = AppConfig.localLlmSpeedTokPerSec,
            models = models.map { Triple(it.id, it.displayName, it.bytes) },
            activeModelId = AppConfig.localLlmProfileId
        )
    }

    private fun totalRamMb(): Long = runCatching {
        val info = ActivityManager.MemoryInfo()
        getSystemService(ActivityManager::class.java).getMemoryInfo(info)
        info.totalMem / (1024L * 1024L)
    }.getOrDefault(8000L)

    /**
     * 模型就绪后自动配置本地 AI（用户裁定：下载/导入完成即帮用户配好，不用再去翻开关）。
     * 判定与文案统一走纯函数 [LlmSettingsState.autoConfigureAfterModelReady]。
     */
    private fun configureAfterModelReady(): String {
        val result = LlmSettingsState.autoConfigureAfterModelReady(
            engineAvailable = engineAvailable(),
            totalRamMb = totalRamMb(),
            policy = AppConfig.aiTaskPolicy
        )
        if (result.enableLocalLlm) {
            AppConfig.localLlmEnabled = true
        }
        return result.message
    }

    /** 引擎可用 = 模块类存在 + 当前 ABI 在模块支持列表内（v7a 包没有 llama so） */
    private fun engineAvailable(): Boolean =
        runCatching { Class.forName("com.arm.aichat.AiChat") }.isSuccess &&
                android.os.Build.SUPPORTED_ABIS.any { it == "arm64-v8a" || it == "x86_64" }

    private fun toggleEnabled(checked: Boolean) {
        if (checked && AppConfig.localLlmProfileId.isBlank()) {
            AppConfig.localLlmProfileId = manager.scan().firstOrNull()?.id.orEmpty()
        }
        AppConfig.localLlmEnabled = checked
        message = if (checked) "本地 AI 已开启（不出端）" else "本地 AI 已关闭，AI 任务回落到已配置的云端或停用"
        reload()
    }

    private fun activateModel(id: String) {
        AppConfig.localLlmProfileId = id
        message = "已切换到模型：$id"
        reload()
    }

    private fun deleteModel(id: String) {
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) { manager.remove(id) }
            if (AppConfig.localLlmProfileId == id) {
                AppConfig.localLlmProfileId = ""
                AppConfig.localLlmEnabled = false
            }
            message = if (ok) "已删除模型：$id" else "删除失败：$id"
            reload()
        }
    }

    private fun setPolicy(policy: String) {
        AppConfig.aiTaskPolicy = policy
        message = "任务策略：${LlmSettingsState.policyLabel(policy)}"
        reload()
    }

    private fun importModel(uri: Uri) {
        lifecycleScope.launch {
            busy = true
            message = "正在导入模型…"
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val name = queryDisplayName(uri) ?: "imported.gguf"
                    val cache = File(cacheDir, "llm-import.gguf")
                    contentResolver.openInputStream(uri)?.use { input ->
                        cache.outputStream().use { output -> input.copyTo(output) }
                    } ?: return@runCatching null
                    manager.importModel(cache, id = name.removeSuffix(".gguf"), displayName = name)
                        .also { cache.delete() }
                }.getOrNull()
            }
            busy = false
            when (result) {
                is LlmModelManager.ImportResult.Success -> {
                    AppConfig.localLlmProfileId = result.model.id
                    message = "导入成功：${result.model.displayName}（${LlmSettingsState.sizeText(result.model.bytes)}）" +
                            configureAfterModelReady()
                }
                is LlmModelManager.ImportResult.Failure -> message = "导入失败：${result.message}"
                null -> message = "导入失败：无法读取所选文件"
            }
            reload()
        }
    }

    /**
     * 下载推荐档（公共资源二次下载）：断点续传 + sha256 校验 + 原子落盘，
     * 成功后直接把该档设为激活模型（用户点一次就能用）。
     */
    private fun downloadRecommended() {
        val profile = downloadProfile ?: return
        lifecycleScope.launch {
            busy = true
            downloadProgress = "准备下载…"
            message = "正在下载：${profile.title}"
            val result = withContext(Dispatchers.IO) {
                LlmModelDownloader.download(this@LlmSettingsActivity, profile) { progress ->
                    downloadProgress = if (progress.total > 0) {
                        "已下载 ${LlmSettingsState.sizeText(progress.bytes)} / " +
                                "${LlmSettingsState.sizeText(progress.total)}（${progress.percent}%）"
                    } else {
                        "已下载 ${LlmSettingsState.sizeText(progress.bytes)}"
                    }
                }
            }
            busy = false
            downloadProgress = ""
            when (result) {
                is LlmModelDownloader.Result.Success -> {
                    AppConfig.localLlmProfileId = profile.id
                    message = "下载完成：${profile.title}（${LlmSettingsState.sizeText(result.bytes)}）" +
                            configureAfterModelReady()
                }
                is LlmModelDownloader.Result.Failure -> message = result.message
            }
            reload()
        }
    }

    private fun queryDisplayName(uri: Uri): String? = runCatching {
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && cursor.moveToFirst()) cursor.getString(idx) else null
        }
    }.getOrNull()

    /** 自检：加载 + 生成固定 token，结论写回 AppConfig（设置页与播放页共用） */
    private fun runBenchmark() {
        val id = AppConfig.localLlmProfileId.ifBlank {
            manager.scan().firstOrNull()?.id.orEmpty()
        }
        val model = runCatching { manager.find(id) }.getOrNull()
        if (model == null) {
            toastOnUi("先导入一个模型再自检")
            return
        }
        lifecycleScope.launch {
            busy = true
            message = "正在自检（加载 + 生成 64 token，在推理进程内执行）…"
            val bench = withContext(Dispatchers.IO) { llmClient.bench(model.modelFile.absolutePath) }
            AppConfig.localLlmSpeedTokPerSec = bench.tokPerSec
            AppConfig.localLlmUnsupportedReason = if (bench.ok) "" else bench.message
            busy = false
            message = LlmSettingsState.benchMessage(
                ok = bench.ok,
                loadMs = bench.loadMs,
                tokenCount = bench.tokenCount,
                tokPerSec = bench.tokPerSec,
                failureMessage = bench.message
            )
            reload()
        }
    }
}

@Composable
private fun LlmSettingsScreen(
    ui: LlmSettingsUi,
    busy: Boolean,
    message: String,
    onToggle: (Boolean) -> Unit,
    onImport: () -> Unit,
    onDownload: () -> Unit,
    downloadTitle: String,
    downloadProgress: String,
    onBench: () -> Unit,
    onPickModel: (String) -> Unit,
    onDelete: (String) -> Unit,
    onPolicy: (String) -> Unit,
    onOpenAssets: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        ScreenHeader(
            title = "本地 AI",
            subtitle = "端侧小模型：不出端、不花钱；只做低置信段仲裁与规则草稿",
            trailing = {
                StatusPill(
                    text = if (ui.switchChecked) "已开启" else "未开启",
                    tone = if (ui.switchChecked) StatusTone.POSITIVE else StatusTone.WARNING
                )
            }
        )
        AppCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("启用本地 AI", style = MaterialTheme.typography.titleSmall)
                    Text(
                        text = ui.reason,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
                Switch(
                    checked = ui.switchChecked,
                    onCheckedChange = onToggle,
                    enabled = ui.switchEnabled && !busy
                )
            }
            if (!ui.showEntry) {
                Text(
                    text = "本机不满足本地 AI 条件：不显示下载/自检入口（纯规则链路零损失）",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
        }
        AppCard {
            SectionTitle("模型")
            if (ui.models.isEmpty()) {
                Text(
                    text = "还没有模型。${ui.importHint}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp)
                )
            }
            ui.models.forEach { row ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (row.active) "${row.title}（使用中）" else row.title,
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (row.active) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = row.sizeText,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (!row.active) {
                        AssistChip(onClick = { onPickModel(row.id) }, label = { Text("使用") })
                    }
                    AssistChip(onClick = { onDelete(row.id) }, label = { Text("删除") })
                }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(onClick = onImport, enabled = !busy) { Text("导入 .gguf") }
                if (downloadTitle.isNotBlank()) {
                    // 公共资源二次下载：只有目录里「有公开源 + 有 sha256」的档位才会出现
                    OutlinedButton(onClick = onDownload, enabled = !busy) {
                        Text("下载 $downloadTitle")
                    }
                }
                OutlinedButton(onClick = onBench, enabled = !busy && ui.models.isNotEmpty()) {
                    Text("自检")
                }
                AssistChip(onClick = onOpenAssets, label = { Text("声音资源") })
            }
            if (downloadProgress.isNotBlank()) {
                Text(
                    text = downloadProgress,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 6.dp)
                )
            }
            Text(
                text = "自检：${ui.speedText}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp)
            )
        }
        AppCard {
            SectionTitle("AI 任务策略")
            Text(
                text = ui.policyLabel,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp, bottom = 8.dp)
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(
                    LocalLlmPrefs.POLICY_LOCAL,
                    LocalLlmPrefs.POLICY_ONLINE,
                    LocalLlmPrefs.POLICY_OFF
                ).forEach { policy ->
                    AssistChip(
                        onClick = { onPolicy(policy) },
                        label = { Text(LlmSettingsState.policyLabel(policy)) }
                    )
                }
            }
        }
        if (busy) {
            Text("处理中…", style = MaterialTheme.typography.bodySmall)
        }
        if (message.isNotBlank()) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}
