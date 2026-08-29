package io.legado.app.ui.book.read.config

import android.app.Activity.RESULT_OK
import android.app.Dialog
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.viewModels
import io.legado.app.R
import io.legado.app.data.entities.HttpTTS
import io.legado.app.ui.about.AppLogDialog
import io.legado.app.ui.code.CodeEditActivity
import io.legado.app.ui.login.SourceLoginActivity
import io.legado.app.ui.theme.LegadoTheme
import io.legado.app.ui.widget.compose.AppDialogFrame
import io.legado.app.ui.widget.compose.AppDialogSize
import io.legado.app.ui.widget.compose.AppDialogStyle
import io.legado.app.ui.widget.compose.ComposeDialogFragment
import io.legado.app.ui.widget.compose.LegadoMiuixActionButton
import io.legado.app.ui.widget.compose.rememberAppDialogStyle
import io.legado.app.ui.widget.compose.showComposeConfirmDialog
import io.legado.app.ui.widget.compose.toMiuixPalette
import io.legado.app.utils.GSON
import io.legado.app.utils.isJsonArray
import io.legado.app.utils.isJsonObject
import io.legado.app.utils.sendToClip
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.showHelp
import io.legado.app.utils.startActivity
import io.legado.app.utils.toastOnUi

class HttpTtsEditDialog() : ComposeDialogFragment() {

    override val dialogSize: AppDialogSize = AppDialogSize.Management

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

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        return super.onCreateDialog(savedInstanceState).apply {
            setCanceledOnTouchOutside(false)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                LaunchedEffect(Unit) {
                    viewModel.initData(arguments) { draft = it.toDraft() }
                }
                LegadoTheme {
                    val style = rememberAppDialogStyle()
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null
                            ) { dismiss() },
                        contentAlignment = Alignment.Center
                    ) {
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
                            onClose = { dismiss() },
                            style = style
                        )
                    }
                }
            }
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
    onClose: () -> Unit,
    style: AppDialogStyle
) {
    val palette = style.toMiuixPalette()
    AppDialogFrame(
        modifier = Modifier.clickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = null
        ) {},
        title = "HTTP 朗读规则",
        scrollContent = true,
        content = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    HeaderActionChip("登录", style, onLogin)
                    HeaderActionChip("复制", style, onCopy)
                    HeaderActionChip("粘贴", style, onPaste)
                    HeaderActionChip("登录头", style, onShowLoginHeader)
                    HeaderActionChip("清登录头", style, onClearLoginHeader)
                    HeaderActionChip("日志", style, onLog)
                    HeaderActionChip("帮助", style, onHelp)
                }
                EditField(HttpTtsField.Name, draft.name, { onDraftChange(draft.copy(name = it)) }, onFocus, style, singleLine = true)
                EditField(
                    HttpTtsField.Speakers, draft.speakersJson,
                    { onDraftChange(draft.copy(speakersJson = it)) }, onFocus, style,
                    minLines = 4,
                    supportingText = "可为空；为空时该 HTTP TTS 会作为普通发言人使用。"
                )
                EditField(
                    HttpTtsField.Emotions, draft.emotionsJson,
                    { onDraftChange(draft.copy(emotionsJson = it)) }, onFocus, style,
                    minLines = 3,
                    supportingText = "可为空；填写后角色和快捷选择可选默认情绪。"
                )
                SelectionContainer {
                    Text(
                        text = "发言人可填 [{\"speakerName\":\"晓晓\",\"toneID\":\"xxx\"}]，也可用 [{\"groupName\":\"女声\",\"items\":[...]}] 分组；情绪字段使用 emotionName / emotionTag。",
                        color = style.secondaryText,
                        fontSize = MaterialTheme.typography.bodySmall.fontSize,
                        lineHeight = 18.sp
                    )
                }
                EditField(HttpTtsField.Url, draft.url, { onDraftChange(draft.copy(url = it)) }, onFocus, style, minLines = 4)
                EditField(HttpTtsField.ContentType, draft.contentType, { onDraftChange(draft.copy(contentType = it)) }, onFocus, style, singleLine = true)
                EditField(HttpTtsField.ConcurrentRate, draft.concurrentRate, { onDraftChange(draft.copy(concurrentRate = it)) }, onFocus, style, singleLine = true)
                EditField(HttpTtsField.SynthesisThreadCount, draft.synthesisThreadCount, { onDraftChange(draft.copy(synthesisThreadCount = it)) }, onFocus, style, singleLine = true)
                EditField(HttpTtsField.LoginUrl, draft.loginUrl, { onDraftChange(draft.copy(loginUrl = it)) }, onFocus, style, minLines = 2)
                EditField(HttpTtsField.LoginUi, draft.loginUi, { onDraftChange(draft.copy(loginUi = it)) }, onFocus, style, minLines = 3)
                EditField(HttpTtsField.LoginCheckJs, draft.loginCheckJs, { onDraftChange(draft.copy(loginCheckJs = it)) }, onFocus, style, minLines = 3)
                EditField(HttpTtsField.Header, draft.header, { onDraftChange(draft.copy(header = it)) }, onFocus, style, minLines = 3)
                EditField(HttpTtsField.JsLib, draft.jsLib, { onDraftChange(draft.copy(jsLib = it)) }, onFocus, style, minLines = 4)
            }
        },
        actions = {
            LegadoMiuixActionButton(
                text = "全屏",
                palette = palette,
                onClick = onFullEdit,
                cornerRadius = style.actionRadius
            )
            Spacer(modifier = Modifier.width(8.dp))
            LegadoMiuixActionButton(
                text = "保存",
                palette = palette,
                onClick = onSave,
                primary = true,
                cornerRadius = style.actionRadius
            )
            Spacer(modifier = Modifier.width(8.dp))
            LegadoMiuixActionButton(
                text = "关闭",
                palette = palette,
                onClick = onClose,
                cornerRadius = style.actionRadius
            )
        }
    )
}

@Composable
private fun HeaderActionChip(text: String, style: AppDialogStyle, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        color = style.fieldSurface,
        contentColor = style.primaryText,
        shape = RoundedCornerShape(style.actionRadius),
        border = BorderStroke(1.dp, style.stroke)
    ) {
        Text(
            text = text,
            fontSize = MaterialTheme.typography.bodySmall.fontSize,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
        )
    }
}

@Composable
private fun EditField(
    field: HttpTtsField,
    value: String,
    onValueChange: (String) -> Unit,
    onFocus: (HttpTtsField) -> Unit,
    style: AppDialogStyle,
    singleLine: Boolean = false,
    minLines: Int = 2,
    supportingText: String? = null
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(field.label) },
        singleLine = singleLine,
        minLines = if (singleLine) 1 else minLines,
        maxLines = if (singleLine) 1 else 10,
        modifier = Modifier
            .fillMaxWidth()
            .onFocusChanged {
                if (it.isFocused) onFocus(field)
            },
        shape = RoundedCornerShape(style.actionRadius),
        textStyle = MaterialTheme.typography.bodyMedium.copy(color = style.primaryText),
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = style.primaryText,
            unfocusedTextColor = style.primaryText,
            focusedContainerColor = style.fieldSurface,
            unfocusedContainerColor = style.fieldSurface,
            cursorColor = style.accent,
            focusedBorderColor = style.accent.copy(alpha = 0.55f),
            unfocusedBorderColor = style.stroke,
            focusedLabelColor = style.secondaryText,
            unfocusedLabelColor = style.secondaryText,
            focusedSupportingTextColor = style.secondaryText,
            unfocusedSupportingTextColor = style.secondaryText
        ),
        supportingText = supportingText?.let { helpText ->
            { Text(helpText, color = style.secondaryText) }
        }
    )
}
