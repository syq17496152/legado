package io.legado.app.ui.book.toc.rule

import android.app.Application
import android.app.Dialog
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.fragment.app.viewModels
import io.legado.app.R
import io.legado.app.base.BaseViewModel
import io.legado.app.constant.AppLog
import io.legado.app.data.appDb
import io.legado.app.data.entities.TxtTocRule
import io.legado.app.exception.NoStackTraceException
import io.legado.app.ui.widget.code.CodeView
import io.legado.app.ui.widget.code.addJsPattern
import io.legado.app.ui.widget.code.addJsonPattern
import io.legado.app.ui.widget.compose.AppDialogFrame
import io.legado.app.ui.widget.compose.AppDialogSize
import io.legado.app.ui.widget.compose.AppDialogStyle
import io.legado.app.ui.widget.compose.ComposeDialogFragment
import io.legado.app.ui.widget.compose.LegadoMiuixActionButton
import io.legado.app.ui.widget.compose.LegadoMiuixCard
import io.legado.app.ui.widget.compose.rememberAppDialogStyle
import io.legado.app.ui.widget.compose.showComposeConfirmDialog
import io.legado.app.ui.widget.compose.toMiuixPalette
import io.legado.app.utils.*
import kotlinx.coroutines.Dispatchers
import java.util.regex.Pattern
import java.util.regex.PatternSyntaxException

class TxtTocRuleEditDialog() : ComposeDialogFragment() {

    override val dialogSize: AppDialogSize = AppDialogSize.Management

    constructor(id: Long?) : this() {
        id ?: return
        arguments = Bundle().apply {
            putLong("id", id)
        }
    }

    private val viewModel by viewModels<ViewModel>()
    private val callback get() = (parentFragment as? Callback) ?: activity as? Callback

    private var name by mutableStateOf("")
    private var rule by mutableStateOf("")
    private var replacement by mutableStateOf("")
    private var example by mutableStateOf("")

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
                    viewModel.initData(arguments?.getLong("id")) { upRuleView(it) }
                }
                val style = rememberAppDialogStyle()
                val palette = style.toMiuixPalette()
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) { dismiss() },
                    contentAlignment = Alignment.Center
                ) {
                    AppDialogFrame(
                        modifier = Modifier.clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) {},
                        title = stringResource(R.string.txt_toc_rule),
                        scrollContent = true,
                        content = {
                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                LabeledTextField(
                                    label = stringResource(R.string.name),
                                    value = name,
                                    onValueChange = { name = it },
                                    style = style
                                )
                                LabeledTextField(
                                    label = stringResource(R.string.regex),
                                    value = rule,
                                    onValueChange = { rule = it },
                                    style = style
                                )
                                LabeledCodeField(
                                    label = stringResource(R.string.replace_to_js),
                                    value = replacement,
                                    onValueChange = { replacement = it },
                                    style = style
                                )
                                LabeledTextField(
                                    label = stringResource(R.string.example),
                                    value = example,
                                    onValueChange = { example = it },
                                    style = style
                                )
                            }
                        },
                        actions = {
                            LegadoMiuixActionButton(
                                text = stringResource(R.string.paste_rule),
                                palette = palette,
                                onClick = { viewModel.pasteRule { upRuleView(it) } },
                                cornerRadius = style.actionRadius
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            LegadoMiuixActionButton(
                                text = stringResource(R.string.copy_rule),
                                palette = palette,
                                onClick = { context?.sendToClip(GSON.toJson(getRuleFromView())) },
                                cornerRadius = style.actionRadius
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            LegadoMiuixActionButton(
                                text = stringResource(R.string.action_save),
                                palette = palette,
                                onClick = {
                                    val tocRule = getRuleFromView()
                                    if (checkValid(tocRule)) {
                                        callback?.saveTxtTocRule(tocRule)
                                        dismissAllowingStateLoss()
                                    }
                                },
                                primary = true,
                                cornerRadius = style.actionRadius
                            )
                        }
                    )
                }
            }
        }
    }

    private fun checkValid(tocRule: TxtTocRule): Boolean {
        if (tocRule.name.isEmpty()) {
            toastOnUi("名称不能为空")
            return false
        }

        try {
            Pattern.compile(tocRule.rule, Pattern.MULTILINE)
        } catch (ex: PatternSyntaxException) {
            AppLog.put("正则语法错误或不支持(txt)：${ex.localizedMessage}", ex, true)
            return false
        }

        return true
    }

    private fun upRuleView(tocRule: TxtTocRule?) {
        name = tocRule?.name.orEmpty()
        rule = tocRule?.rule.orEmpty()
        replacement = tocRule?.replacement.orEmpty()
        example = tocRule?.example.orEmpty()
    }

    private fun getRuleFromView(): TxtTocRule {
        val tocRule = viewModel.tocRule ?: TxtTocRule().apply {
            viewModel.tocRule = this
        }
        tocRule.name = name
        tocRule.rule = rule
        tocRule.replacement = replacement
        tocRule.example = example
        return tocRule
    }

    private fun isSame(): Boolean {
        val tocRule = viewModel.tocRule ?: return name.isEmpty()
        return tocRule.name == name &&
                tocRule.rule == rule &&
                tocRule.replacement == replacement &&
                tocRule.example == example
    }

    override fun dismiss() {
        if (!isSame()) {
            showComposeConfirmDialog(
                title = getString(R.string.exit),
                message = getString(R.string.exit_no_save),
                positiveText = getString(R.string.yes),
                negativeText = getString(R.string.no),
                onPositive = {},
                onNegative = { super.dismiss() }
            )
        } else {
            super.dismiss()
        }
    }

    class ViewModel(application: Application) : BaseViewModel(application) {

        var tocRule: TxtTocRule? = null

        fun initData(id: Long?, finally: (tocRule: TxtTocRule?) -> Unit) {
            if (tocRule != null) return
            execute {
                if (id == null) return@execute
                tocRule = appDb.txtTocRuleDao.get(id)
            }.onFinally {
                finally.invoke(tocRule)
            }
        }

        fun pasteRule(success: (TxtTocRule) -> Unit) {
            execute(context = Dispatchers.Main) {
                val text = context.getClipText()
                if (text.isNullOrBlank()) {
                    throw NoStackTraceException("剪贴板为空")
                }
                GSON.fromJsonObject<TxtTocRule>(text).getOrNull()
                    ?: throw NoStackTraceException("格式不对")
            }.onSuccess {
                success.invoke(it)
            }.onError {
                context.toastOnUi(it.localizedMessage ?: "Error")
                it.printOnDebug()
            }
        }

    }

    interface Callback {

        fun saveTxtTocRule(txtTocRule: TxtTocRule)

    }

}

@Composable
private fun LabeledTextField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    style: AppDialogStyle
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = label,
            color = style.secondaryText,
            fontSize = MaterialTheme.typography.bodySmall.fontSize,
            fontWeight = FontWeight.Medium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(modifier = Modifier.height(6.dp))
        LegadoMiuixCard(
            modifier = Modifier.fillMaxWidth(),
            color = style.fieldSurface,
            contentColor = style.primaryText,
            cornerRadius = style.actionRadius,
            insidePadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp)
        ) {
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.fillMaxWidth(),
                textStyle = TextStyle(
                    color = style.primaryText,
                    fontSize = MaterialTheme.typography.bodyMedium.fontSize,
                    fontFamily = style.bodyFontFamily
                ),
                cursorBrush = SolidColor(style.accent),
                singleLine = true
            )
        }
    }
}

@Composable
private fun LabeledCodeField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    style: AppDialogStyle
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = label,
            color = style.secondaryText,
            fontSize = MaterialTheme.typography.bodySmall.fontSize,
            fontWeight = FontWeight.Medium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(modifier = Modifier.height(6.dp))
        LegadoMiuixCard(
            modifier = Modifier.fillMaxWidth(),
            color = style.fieldSurface,
            contentColor = style.primaryText,
            cornerRadius = style.actionRadius,
            insidePadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp)
        ) {
            AndroidView(
                factory = { ctx ->
                    CodeView(ctx).apply {
                        addJsonPattern()
                        addJsPattern()
                        gravity = Gravity.TOP or Gravity.START
                        setBackgroundColor(android.graphics.Color.TRANSPARENT)
                        addTextChangedListener(object : TextWatcher {
                            override fun beforeTextChanged(
                                s: CharSequence?,
                                start: Int,
                                count: Int,
                                after: Int
                            ) = Unit

                            override fun onTextChanged(
                                s: CharSequence?,
                                start: Int,
                                before: Int,
                                count: Int
                            ) = Unit

                            override fun afterTextChanged(s: Editable?) {
                                onValueChange(s?.toString().orEmpty())
                            }
                        })
                    }
                },
                update = { codeView ->
                    codeView.setTextColor(style.primaryText.toArgb())
                    if (codeView.text?.toString() != value) {
                        codeView.setText(value)
                    }
                }
            )
        }
    }
}
