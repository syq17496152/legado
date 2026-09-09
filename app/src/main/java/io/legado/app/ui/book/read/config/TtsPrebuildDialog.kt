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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.constant.AppLog
import io.legado.app.data.appDb
import io.legado.app.help.config.AppConfig
import io.legado.app.help.readaloud.casting.TtsCastingStore
import io.legado.app.help.readaloud.prebuild.TtsPrebuildManager
import io.legado.app.help.readaloud.speech.SpeechRoute
import io.legado.app.model.ReadAloud
import io.legado.app.model.ReadBook
import io.legado.app.service.TtsPrebuildService
import io.legado.app.ui.theme.LegadoTheme
import io.legado.app.ui.widget.compose.AppDialogSize
import io.legado.app.ui.widget.compose.ComposeDialogFragment
import io.legado.app.ui.widget.compose.rememberAppDialogStyle
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 批量预合成发起对话框（P2-7，§3.7.2 启动链）：
 * 门控三条件预检（type∈{1,2}/非流式/多角色未激活，任一不满足禁用并分别明示原因）
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
        if (route.engineType != SpeechRoute.ENGINE_HTTP) {
            return getString(R.string.tts_casting_prebuild_gate_system)
        }
        val httpTts = route.engineValue.toLongOrNull()?.let { appDb.httpTTSDao.get(it) }
        if (httpTts == null) {
            return getString(R.string.tts_casting_prebuild_gate_system)
        }
        if (AppConfig.streamReadAloudAudio) {
            return getString(R.string.tts_casting_prebuild_gate_stream)
        }
        if (TtsCastingStore.activeTemplateId() != null) {
            return getString(R.string.tts_casting_prebuild_gate_multi_role)
        }
        return null
    }

    private fun isNetworkAvailable(): Boolean {
        val cm = requireContext().getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    @Composable
    private fun PrebuildScreen() {
        val style = rememberAppDialogStyle()
        androidx.compose.runtime.LaunchedEffect(Unit) {
            gateError = gateCheck()
        }
        Column(
            Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Text(
                text = stringResource(R.string.tts_casting_prebuild_menu),
                style = MaterialTheme.typography.titleMedium,
                color = style.primaryText
            )
            Text(
                text = stringResource(R.string.tts_casting_prebuild_flow_hint),
                style = MaterialTheme.typography.bodySmall,
                color = style.secondaryText
            )
            Row(
                Modifier.fillMaxWidth().padding(top = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = startInput,
                    onValueChange = { startInput = it.filter { ch -> ch.isDigit() } },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    enabled = gateError == null,
                    label = { Text(stringResource(R.string.tts_casting_prebuild_start_chapter), style = MaterialTheme.typography.bodySmall) }
                )
                Text(
                    text = "—",
                    color = style.secondaryText,
                    modifier = Modifier.padding(horizontal = 8.dp)
                )
                OutlinedTextField(
                    value = endInput,
                    onValueChange = { endInput = it.filter { ch -> ch.isDigit() } },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    enabled = gateError == null,
                    label = { Text(stringResource(R.string.tts_casting_prebuild_end_chapter), style = MaterialTheme.typography.bodySmall) }
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
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.End
            ) {
                TextButton(onClick = { dismissAllowingStateLoss() }) {
                    Text(stringResource(R.string.cancel), color = style.secondaryText)
                }
                TextButton(
                    enabled = gateError == null && !submitting,
                    onClick = { onStartPrebuild() }
                ) {
                    Text(stringResource(R.string.tts_casting_prebuild_start), color = style.accent)
                }
            }
        }
    }

    /** 发起：网络预检→章界校验→enqueue（去重/上限判定在 Manager）→前台服务 */
    private fun onStartPrebuild() {
        val book = ReadBook.book ?: run {
            context?.toastOnUi("无当前书籍")
            return
        }
        if (!isNetworkAvailable()) {
            context?.toastOnUi(R.string.tts_casting_prebuild_no_net)
            submitting = false
            return
        }
        val start = startInput.toIntOrNull()?.minus(1)
        val end = endInput.toIntOrNull()?.minus(1)
        val s = start ?: return
        val e = end ?: return
        if (s < 0 || e < s || s >= chapterCount) {
            context?.toastOnUi(R.string.tts_casting_prebuild_chapter_invalid)
            submitting = false
            return
        }
        val route = ReadAloud.currentRoute
        val httpTts = route.engineValue.toLongOrNull()?.let { appDb.httpTTSDao.get(it) }
        if (httpTts == null) {
            context?.toastOnUi(R.string.tts_casting_prebuild_gate_system)
            submitting = false
            return
        }
        lifecycleScope.launch(Dispatchers.IO) {
            val reject = TtsPrebuildManager.enqueue(
                requireContext().applicationContext,
                book, s, e, httpTts
            )
            lifecycleScope.launch {
                if (reject != null) {
                    context?.toastOnUi(reject)
                    submitting = false
                } else {
                    AppLog.put("TTS 预合成已发起：${book.bookUrl} [$s-$e]")
                    TtsPrebuildService.start(requireContext())
                    dismissAllowingStateLoss()
                }
            }
        }
    }
}
