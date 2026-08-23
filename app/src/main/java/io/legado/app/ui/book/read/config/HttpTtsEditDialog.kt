package io.legado.app.ui.book.read.config

import android.app.Activity.RESULT_OK
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.viewModels
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.data.entities.HttpTTS
import io.legado.app.help.config.AppConfig
import io.legado.app.lib.theme.accentColor
import io.legado.app.lib.theme.composeActionRadius
import io.legado.app.lib.theme.composePanelRadius
import io.legado.app.lib.theme.primaryTextColor
import io.legado.app.lib.theme.secondaryTextColor
import io.legado.app.lib.theme.uiTypeface
import io.legado.app.ui.about.AppLogDialog
import io.legado.app.ui.code.CodeEditActivity
import io.legado.app.ui.login.SourceLoginActivity
import io.legado.app.ui.widget.compose.showComposeConfirmDialog
import io.legado.app.utils.GSON
import io.legado.app.utils.ColorUtils
import io.legado.app.utils.dpToPx
import io.legado.app.utils.isJsonArray
import io.legado.app.utils.isJsonObject
import io.legado.app.utils.sendToClip
import io.legado.app.utils.setLayout
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.showHelp
import io.legado.app.utils.startActivity
import io.legado.app.utils.toastOnUi

class HttpTtsEditDialog() : BaseDialogFragment(0) {

    constructor(id: Long) : this() {
        arguments = Bundle().apply {
            putLong("id", id)
        }
    }

    private val viewModel by viewModels<HttpTtsEditViewModel>()
    private var draft by mutableStateOf(HttpTtsEditDraft())
    private var focusedField by mutableStateOf(HttpTtsField.Url)

    private val textEditLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            result.data?.getStringExtra("text")?.let { text ->
                draft = draft.withField(focusedField, text)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                HttpTtsEditScreen(
                    draft = draft,
                    onDraftChange = { draft = it },
                    onFocus = { focusedField = it },
                    onSave = ::save,
                    onLogin = ::saveAndLogin,
                    onFullEdit = ::openFullEdit,
                    onCopy = { context?.sendToClip(GSON.toJson(draft.toHttpTts(viewModel.id))) },
                    onPaste = { viewModel.importFromClip { draft = it.toDraft() } },
                    onShowLoginHeader = ::showLoginHeader,
                    onClearLoginHeader = {
                        val tts = draft.toHttpTts(viewModel.id)
                        tts.removeLoginHeader()
                        draft = tts.toDraft()
                    },
                    onLog = { showDialogFragment<AppLogDialog>() },
                    onHelp = { showHelp("httpTTSHelp") },
                    onClose = { dismiss() }
                )
            }
        }
    }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        viewModel.initData(arguments) {
            draft = it.toDraft()
        }
    }

    private fun save() {
        val tts = draft.toHttpTts(viewModel.id)
        if (!validate(tts)) return
        viewModel.save(tts) {
            dismissAllowingStateLoss()
            toastOnUi("保存成功")
        }
    }

    private fun saveAndLogin() {
        val tts = draft.toHttpTts(viewModel.id)
        if (!validate(tts)) return
        if (tts.loginUrl.isNullOrBlank()) {
            toastOnUi("登录url不能为空")
            return
        }
        viewModel.save(tts) {
            startActivity<SourceLoginActivity> {
                putExtra("type", "httpTts")
                putExtra("key", tts.id.toString())
            }
        }
    }

    private fun validate(httpTTS: HttpTTS): Boolean {
        if (httpTTS.name.isBlank()) {
            toastOnUi("名称不能为空")
            return false
        }
        fun validJsonCatalog(value: String): Boolean {
            val text = value.trim()
            return text.isBlank() || text.isJsonArray() || text.isJsonObject()
        }
        if (!validJsonCatalog(httpTTS.speakersJson)) {
            toastOnUi("发言人列表 JSON 必须是数组或对象")
            return false
        }
        if (!validJsonCatalog(httpTTS.emotionsJson)) {
            toastOnUi("情绪列表 JSON 必须是数组或对象")
            return false
        }
        return true
    }

    private fun openFullEdit() {
        val currentText = draft.field(focusedField)
        val intent = Intent(requireActivity(), CodeEditActivity::class.java).apply {
            putExtra("text", currentText)
            putExtra("title", focusedField.label)
            putExtra("cursorPosition", currentText.length)
        }
        textEditLauncher.launch(intent)
    }

    private fun showLoginHeader() {
        showComposeConfirmDialog(
            title = getString(R.string.login_header),
            message = draft.toHttpTts(viewModel.id).getLoginHeader(),
            showNegative = false,
            messageInContent = true,
            onPositive = {}
        )
    }

    private fun isSame(): Boolean {
        val old = viewModel.httpTTS ?: return draft.name.isBlank() && draft.url.isBlank()
        return draft.toHttpTts(viewModel.id).equal(old)
    }

    override fun dismiss() {
        if (!isSame()) {
            showComposeConfirmDialog(
                title = getString(R.string.exit),
                message = getString(R.string.exit_no_save),
                positiveText = getString(R.string.yes),
                negativeText = getString(R.string.no),
                onPositive = {},
                onNegative = { dismissWithoutConfirm() }
            )
        } else {
            super.dismiss()
        }
    }

    private fun dismissWithoutConfirm() {
        super.dismiss()
    }
}

private enum class HttpTtsField(val label: String) {
    Name("名称"),
    Speakers("发言人列表 JSON"),
    Emotions("情绪列表 JSON"),
    Url("url"),
    ContentType("Content-Type"),
    ConcurrentRate("并发率"),
    SynthesisThreadCount("生成线程数"),
    LoginUrl("登录 URL"),
    LoginUi("登录 UI"),
    LoginCheckJs("登录检测 JS"),
    Header("Header"),
    JsLib("jsLib")
}

private data class HttpTtsEditDraft(
    val name: String = "",
    val speakersJson: String = "",
    val emotionsJson: String = "",
    val url: String = "",
    val contentType: String = "",
    val concurrentRate: String = "0",
    val synthesisThreadCount: String = "1",
    val loginUrl: String = "",
    val loginUi: String = "",
    val loginCheckJs: String = "",
    val header: String = "",
    val jsLib: String = ""
) {
    fun field(field: HttpTtsField): String = when (field) {
        HttpTtsField.Name -> name
        HttpTtsField.Speakers -> speakersJson
        HttpTtsField.Emotions -> emotionsJson
        HttpTtsField.Url -> url
        HttpTtsField.ContentType -> contentType
        HttpTtsField.ConcurrentRate -> concurrentRate
        HttpTtsField.SynthesisThreadCount -> synthesisThreadCount
        HttpTtsField.LoginUrl -> loginUrl
        HttpTtsField.LoginUi -> loginUi
        HttpTtsField.LoginCheckJs -> loginCheckJs
        HttpTtsField.Header -> header
        HttpTtsField.JsLib -> jsLib
    }

    fun withField(field: HttpTtsField, value: String): HttpTtsEditDraft = when (field) {
        HttpTtsField.Name -> copy(name = value)
        HttpTtsField.Speakers -> copy(speakersJson = value)
        HttpTtsField.Emotions -> copy(emotionsJson = value)
        HttpTtsField.Url -> copy(url = value)
        HttpTtsField.ContentType -> copy(contentType = value)
        HttpTtsField.ConcurrentRate -> copy(concurrentRate = value)
        HttpTtsField.SynthesisThreadCount -> copy(synthesisThreadCount = value)
        HttpTtsField.LoginUrl -> copy(loginUrl = value)
        HttpTtsField.LoginUi -> copy(loginUi = value)
        HttpTtsField.LoginCheckJs -> copy(loginCheckJs = value)
        HttpTtsField.Header -> copy(header = value)
        HttpTtsField.JsLib -> copy(jsLib = value)
    }

    fun toHttpTts(id: Long?): HttpTTS {
        return HttpTTS(
            id = id ?: System.currentTimeMillis(),
            name = name,
            url = url,
            contentType = contentType,
            concurrentRate = concurrentRate,
            synthesisThreadCount = synthesisThreadCount.toIntOrNull()?.coerceIn(1, 8) ?: 1,
            loginUrl = loginUrl,
            loginUi = loginUi,
            loginCheckJs = loginCheckJs,
            header = header,
            jsLib = jsLib,
            speakersJson = speakersJson,
            emotionsJson = emotionsJson
        )
    }
}

private fun HttpTTS.toDraft(): HttpTtsEditDraft {
    return HttpTtsEditDraft(
        name = name,
        speakersJson = speakersJson,
        emotionsJson = emotionsJson,
        url = url,
        contentType = contentType.orEmpty(),
        concurrentRate = concurrentRate.orEmpty(),
        synthesisThreadCount = synthesisThreadCount.coerceIn(1, 8).toString(),
        loginUrl = loginUrl.orEmpty(),
        loginUi = loginUi.orEmpty(),
        loginCheckJs = loginCheckJs.orEmpty(),
        header = header.orEmpty(),
        jsLib = jsLib.orEmpty()
    )
}

@Composable
private fun HttpTtsEditScreen(
    draft: HttpTtsEditDraft,
    onDraftChange: (HttpTtsEditDraft) -> Unit,
    onFocus: (HttpTtsField) -> Unit,
    onSave: () -> Unit,
    onLogin: () -> Unit,
    onFullEdit: () -> Unit,
    onCopy: () -> Unit,
    onPaste: () -> Unit,
    onShowLoginHeader: () -> Unit,
    onClearLoginHeader: () -> Unit,
    onLog: () -> Unit,
    onHelp: () -> Unit,
    onClose: () -> Unit
) {
    val colors = rememberHttpTtsEditColors()
    val context = LocalContext.current
    CompositionLocalProvider(
        LocalTextStyle provides LocalTextStyle.current.copy(fontFamily = FontFamily(context.uiTypeface()))
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = colors.page,
            shape = RoundedCornerShape(context.composePanelRadius())
        ) {
            Column(modifier = Modifier.fillMaxSize().padding(16.dp).imePadding()) {
            Row {
                Text(
                    text = "HTTP 朗读规则",
                    color = colors.text,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = onFullEdit) { Text("全屏", color = colors.accent) }
                TextButton(onClick = onSave) { Text("保存", color = colors.accent) }
                TextButton(onClick = onClose) { Text("关闭", color = colors.subText) }
            }
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()).padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                HeaderAction("登录", colors, onLogin)
                HeaderAction("复制", colors, onCopy)
                HeaderAction("粘贴", colors, onPaste)
                HeaderAction("登录头", colors, onShowLoginHeader)
                HeaderAction("清登录头", colors, onClearLoginHeader)
                HeaderAction("日志", colors, onLog)
                HeaderAction("帮助", colors, onHelp)
            }
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = 12.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                EditField(HttpTtsField.Name, draft.name, { onDraftChange(draft.copy(name = it)) }, onFocus, singleLine = true)
                EditField(HttpTtsField.Speakers, draft.speakersJson, { onDraftChange(draft.copy(speakersJson = it)) }, onFocus, minLines = 4)
                EditField(HttpTtsField.Emotions, draft.emotionsJson, { onDraftChange(draft.copy(emotionsJson = it)) }, onFocus, minLines = 3)
                SelectionContainer {
                    Text(
                        text = "发言人可填 [{\"speakerName\":\"晓晓\",\"toneID\":\"xxx\"}]，也可用 [{\"groupName\":\"女声\",\"items\":[...]}] 分组；情绪字段使用 emotionName / emotionTag。",
                        color = colors.subText,
                        fontSize = 12.sp,
                        lineHeight = 18.sp
                    )
                }
                EditField(HttpTtsField.Url, draft.url, { onDraftChange(draft.copy(url = it)) }, onFocus, minLines = 4)
                EditField(HttpTtsField.ContentType, draft.contentType, { onDraftChange(draft.copy(contentType = it)) }, onFocus, singleLine = true)
                EditField(HttpTtsField.ConcurrentRate, draft.concurrentRate, { onDraftChange(draft.copy(concurrentRate = it)) }, onFocus, singleLine = true)
                EditField(HttpTtsField.SynthesisThreadCount, draft.synthesisThreadCount, { onDraftChange(draft.copy(synthesisThreadCount = it)) }, onFocus, singleLine = true)
                EditField(HttpTtsField.LoginUrl, draft.loginUrl, { onDraftChange(draft.copy(loginUrl = it)) }, onFocus, minLines = 2)
                EditField(HttpTtsField.LoginUi, draft.loginUi, { onDraftChange(draft.copy(loginUi = it)) }, onFocus, minLines = 3)
                EditField(HttpTtsField.LoginCheckJs, draft.loginCheckJs, { onDraftChange(draft.copy(loginCheckJs = it)) }, onFocus, minLines = 3)
                EditField(HttpTtsField.Header, draft.header, { onDraftChange(draft.copy(header = it)) }, onFocus, minLines = 3)
                EditField(HttpTtsField.JsLib, draft.jsLib, { onDraftChange(draft.copy(jsLib = it)) }, onFocus, minLines = 4)
                Spacer(modifier = Modifier.height(18.dp))
            }
            }
        }
    }
}

@Composable
private fun HeaderAction(text: String, colors: HttpTtsEditColors, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        color = colors.card,
        shape = RoundedCornerShape(LocalContext.current.composeActionRadius()),
        border = BorderStroke(1.dp, colors.stroke)
    ) {
        Text(text, color = colors.text, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
    }
}

@Composable
private fun EditField(
    field: HttpTtsField,
    value: String,
    onValueChange: (String) -> Unit,
    onFocus: (HttpTtsField) -> Unit,
    singleLine: Boolean = false,
    minLines: Int = 2
) {
    val colors = rememberHttpTtsEditColors()
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(field.label) },
        singleLine = singleLine,
        minLines = if (singleLine) 1 else minLines,
        modifier = Modifier
            .fillMaxWidth()
            .onFocusChanged {
                if (it.isFocused) onFocus(field)
            },
        shape = RoundedCornerShape(LocalContext.current.composeActionRadius()),
        supportingText = when (field) {
            HttpTtsField.Speakers -> ({ Text("可为空；为空时该 HTTP TTS 会作为普通发言人使用。", color = colors.subText) })
            HttpTtsField.Emotions -> ({ Text("可为空；填写后角色和快捷选择可选默认情绪。", color = colors.subText) })
            else -> null
        }
    )
}

private data class HttpTtsEditColors(
    val page: Color,
    val card: Color,
    val text: Color,
    val subText: Color,
    val stroke: Color,
    val accent: Color
)

@Composable
private fun rememberHttpTtsEditColors(): HttpTtsEditColors {
    val context = LocalContext.current
    val night = AppConfig.isNightTheme
    return HttpTtsEditColors(
        page = Color(if (night) 0xff15171b.toInt() else 0xffffffff.toInt()),
        card = Color(if (night) 0xff20242a.toInt() else 0xfff6f7fa.toInt()),
        text = Color(context.primaryTextColor),
        subText = Color(context.secondaryTextColor),
        stroke = Color(if (night) 0x26ffffff else 0x18000000),
        accent = Color(ColorUtils.blendColors(context.accentColor, if (night) 0xffffffff.toInt() else 0xff000000.toInt(), 0.04f))
    )
}
