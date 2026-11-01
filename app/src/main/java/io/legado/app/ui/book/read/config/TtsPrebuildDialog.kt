package io.legado.app.ui.book.read.config

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.constant.AppLog
import io.legado.app.data.appDb
import io.legado.app.help.config.AppConfig
import io.legado.app.help.readaloud.prebuild.TtsPrebuildManager
import io.legado.app.help.readaloud.speech.SpeechRoute
import io.legado.app.help.tts.builtin.BuiltinTtsRuntime
import io.legado.app.model.ReadAloud
import io.legado.app.model.ReadBook
import io.legado.app.service.TtsPrebuildService
import io.legado.app.ui.theme.LegadoTheme
import io.legado.app.ui.widget.compose.AppDialogFrame
import io.legado.app.ui.widget.compose.AppDialogSize
import io.legado.app.ui.widget.compose.AppDialogTextField
import io.legado.app.ui.widget.compose.ComposeDialogFragment
import io.legado.app.ui.widget.compose.LegadoMiuixActionButton
import io.legado.app.ui.widget.compose.rememberAppDialogStyle
import io.legado.app.ui.widget.compose.toMiuixPalette
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 预合成门禁阻断原因（纯函数返回值，便于 JVM 单测；资源文案在调用侧映射） */
internal enum class PrebuildGateBlock { ENGINE, STREAM, NETWORK }

/**
 * 预合成门禁判定：**复用** `TtsPrebuildManager.prebuildGate` 作为唯一引擎/网络口径
 * （内置离线就绪即可用、只有 HTTP 才要网络），再叠加流式开关（只作用于 HTTP 通道）。
 * 多角色模板不再是阻断项（T3.3 已支持按段+角色音色预合成）。
 */
internal fun prebuildGateBlock(
    builtinReady: Boolean,
    httpTtsAvailable: Boolean,
    streamReadAloud: Boolean,
    networkAvailable: Boolean
): PrebuildGateBlock? {
    if (httpTtsAvailable && streamReadAloud) return PrebuildGateBlock.STREAM
    return when (TtsPrebuildManager.prebuildGate(builtinReady, httpTtsAvailable, networkAvailable)) {
        TtsPrebuildManager.PrebuildGate.OK -> null
        TtsPrebuildManager.PrebuildGate.NEED_ENGINE -> PrebuildGateBlock.ENGINE
        TtsPrebuildManager.PrebuildGate.NO_NETWORK -> PrebuildGateBlock.NETWORK
    }
}

/**
 * 批量预合成发起对话框（P2-7，§3.7.2 启动链）：
 * 门控预检（引擎可用/非流式，任一不满足禁用并分别明示原因；内置离线引擎与多角色模板均可用）
 * +起止章节输入+网络预检+流量提示 → enqueue（参数快照）→ 前台服务
 */
class TtsPrebuildDialog : ComposeDialogFragment() {

    override val dialogSize: AppDialogSize = AppDialogSize.Form

    companion object {
        fun newInstance(): TtsPrebuildDialog = TtsPrebuildDialog()
    }

    private var bookUrl: String? = null
    private var bookName: String? = null
    private var chapterCount by mutableStateOf(0)
    private var startInput by mutableStateOf("")
    private var endInput by mutableStateOf("")
    private var gateError by mutableStateOf<String?>(null)
    private var submitting by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        bookUrl = ReadBook.book?.bookUrl
        bookName = ReadBook.book?.name
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                LegadoTheme {
                    PrebuildScreen()
                }
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        lifecycleScope.launch(Dispatchers.IO) {
            bookUrl?.let { url ->
                val count = runCatching {
                    appDb.bookChapterDao.getChapterCount(url)
                }.getOrDefault(0)
                val current = ReadBook.durChapterIndex + 1
                lifecycleScope.launch {
                    chapterCount = count
                    startInput = current.toString()
                    endInput = (current + 9).coerceAtMost(count).toString()
                }
            }
        }
    }

    /** 门控三条件（§3.7.1-6）：返回 null=可用，非 null=禁用原因（S9-3 逐一明示） */
    private fun gateCheck(): String? {
        val route = ReadAloud.currentRoute
        val appContext = requireContext().applicationContext
        // T3.2/T3.3 已让预合成支持「内置离线引擎」与「多角色模板」（BuiltinPrebuildUnits.multiRoleUnits），
        // 门禁必须与发起侧（onSubmit 的 prebuildGate）同口径，否则离线用户永远点不动（2026-11-01 真机 IF-14-G）。
        val builtinReady = BuiltinTtsRuntime.isReady() &&
            BuiltinTtsRuntime.isActiveEngine(appContext, route.engineValue)
        val httpTts = if (route.engineType == SpeechRoute.ENGINE_HTTP) {
            route.engineValue.toLongOrNull()?.let { appDb.httpTTSDao.get(it) }
        } else {
            null
        }
        return when (
            prebuildGateBlock(
                builtinReady = builtinReady,
                httpTtsAvailable = httpTts != null,
                streamReadAloud = AppConfig.streamReadAloudAudio,
                networkAvailable = isNetworkAvailable()
            )
        ) {
            PrebuildGateBlock.ENGINE -> getString(R.string.tts_casting_prebuild_gate_system)
            PrebuildGateBlock.STREAM -> getString(R.string.tts_casting_prebuild_gate_stream)
            PrebuildGateBlock.NETWORK -> getString(R.string.tts_casting_prebuild_no_net)
            null -> null
        }
    }

    private fun isNetworkAvailable(): Boolean {
        val cm = requireContext().getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    @Composable
    private fun PrebuildScreen() {
        val style = rememberAppDialogStyle()
        // 离线内置引擎不耗流量，提示文案需随之切换（原文案对离线用户是误导）；
        // 且 Runtime 只在引擎服务/管理页 init ⇒ 这里必须先 init+ensureSeeded，否则 isReady() 恒 NONE
        // （真机 3.26.110110 复现：模型就绪却仍提示「不支持批量预合成」）。
        val context = LocalContext.current
        var offlineHint by remember { mutableStateOf(false) }
        androidx.compose.runtime.LaunchedEffect(Unit) {
            val ready = withContext(Dispatchers.IO) {
                val app = context.applicationContext
                BuiltinTtsRuntime.init(app)
                BuiltinTtsRuntime.ensureSeeded()
                BuiltinTtsRuntime.isReady() &&
                    BuiltinTtsRuntime.isActiveEngine(app, ReadAloud.currentRoute.engineValue)
            }
            offlineHint = ready
            gateError = gateCheck()
        }
        // 统一弹层基线（同语义既有实现＝AppDialogFrame）：此前裸 Column 无卡片背景，
        // 真机 2026-11-01 复现文字直接叠在正文上的"透明弹窗"（IF-14-H）。
        AppDialogFrame(
            title = stringResource(R.string.tts_casting_prebuild_menu),
            message = stringResource(
                if (offlineHint) R.string.tts_casting_prebuild_flow_hint_offline
                else R.string.tts_casting_prebuild_flow_hint
            ),
            content = {
                Column(Modifier.fillMaxWidth()) {
                    Row(
                        Modifier.fillMaxWidth().padding(top = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        AppDialogTextField(
                            value = startInput,
                            onValueChange = { startInput = it.filter { ch -> ch.isDigit() } },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                            readOnly = gateError != null,
                            label = stringResource(R.string.tts_casting_prebuild_start_chapter)
                        )
                        Text(
                            text = "—",
                            color = style.secondaryText,
                            modifier = Modifier.padding(horizontal = 8.dp)
                        )
                        AppDialogTextField(
                            value = endInput,
                            onValueChange = { endInput = it.filter { ch -> ch.isDigit() } },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                            readOnly = gateError != null,
                            label = stringResource(R.string.tts_casting_prebuild_end_chapter)
                        )
                    }
                    if (chapterCount > 0) {
                        Text(
                            text = stringResource(R.string.tts_casting_prebuild_chapter_count, chapterCount),
                            style = MaterialTheme.typography.bodySmall,
                            color = style.secondaryText,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                    gateError?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.bodyMedium,
                            color = style.accent,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }
                }
            },
            actions = {
                val palette = style.toMiuixPalette()
                LegadoMiuixActionButton(
                    text = stringResource(R.string.cancel),
                    palette = palette,
                    onClick = { dismissAllowingStateLoss() },
                    cornerRadius = style.actionRadius
                )
                Spacer(Modifier.width(8.dp))
                LegadoMiuixActionButton(
                    text = stringResource(R.string.tts_casting_prebuild_start),
                    palette = palette,
                    primary = true,
                    enabled = gateError == null && !submitting,
                    loading = submitting,
                    onClick = { onStartPrebuild() },
                    cornerRadius = style.actionRadius
                )
            }
        )
    }

    /** 发起：网络预检→章界校验→enqueue（去重/上限判定在 Manager）→前台服务 */
    private fun onStartPrebuild() {
        if (submitting) return
        val book = ReadBook.book ?: run {
            context?.toastOnUi("无当前书籍")
            return
        }
        val start = startInput.toIntOrNull()?.minus(1)
        val end = endInput.toIntOrNull()?.minus(1)
        if (start == null || end == null) {
            // 起止输入为空：明示而非静默 return（P2 修复）
            context?.toastOnUi(R.string.tts_casting_prebuild_chapter_invalid)
            return
        }
        val s = start
        val e = end
        // P2 修复：结束章对 chapterCount 上界校验（Manager 侧 clamp 到 200 章，此处校验书籍边界）
        if (s < 0 || e < s || s >= chapterCount || e >= chapterCount) {
            context?.toastOnUi(R.string.tts_casting_prebuild_chapter_invalid)
            return
        }
        // P2 修复：200 章上限 Dialog 预提示（设计 §3.7.2：超出提示分批发起）
        if (e - s + 1 > TtsPrebuildManager.MAX_BATCH_CHAPTERS) {
            context?.toastOnUi(R.string.tts_casting_prebuild_too_many)
            return
        }
        submitting = true
        val appContext = requireContext().applicationContext
        val route = ReadAloud.currentRoute
        // T3.2/T3.3：内置离线引擎可离线预合成（不要求 HTTP 通道/网络）；仅 httpTts 路径才校验网络
        val builtinReady = BuiltinTtsRuntime.isReady() &&
            BuiltinTtsRuntime.isActiveEngine(appContext, route.engineValue)
        val httpTts = route.engineValue.toLongOrNull()?.let { appDb.httpTTSDao.get(it) }
        when (TtsPrebuildManager.prebuildGate(builtinReady, httpTts != null, isNetworkAvailable())) {
            TtsPrebuildManager.PrebuildGate.NO_NETWORK -> {
                context?.toastOnUi(R.string.tts_casting_prebuild_no_net)
                submitting = false
                return
            }
            TtsPrebuildManager.PrebuildGate.NEED_ENGINE -> {
                context?.toastOnUi(R.string.tts_casting_prebuild_gate_system)
                submitting = false
                return
            }
            TtsPrebuildManager.PrebuildGate.OK -> Unit
        }
        lifecycleScope.launch(Dispatchers.IO) {
            val reject = TtsPrebuildManager.enqueue(
                appContext,
                book, s, e, httpTts
            )
            lifecycleScope.launch {
                if (reject != null) {
                    context?.toastOnUi(reject)
                    submitting = false
                } else {
                    AppLog.put("TTS 预合成已发起：${book.bookUrl} [$s-$e]")
                    TtsPrebuildService.start(appContext)
                    dismissAllowingStateLoss()
                }
            }
        }
    }
}
