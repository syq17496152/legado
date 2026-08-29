package io.legado.app.ui.config

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.legado.app.R
import io.legado.app.help.DirectLinkUpload
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.lib.dialogs.selector
import io.legado.app.ui.widget.components.AppDropdownMenu
import io.legado.app.ui.widget.components.MenuAction
import io.legado.app.ui.widget.compose.AppDialogFrame
import io.legado.app.ui.widget.compose.AppDialogSize
import io.legado.app.ui.widget.compose.AppDialogStyle
import io.legado.app.ui.widget.compose.ComposeConfirmDialog
import io.legado.app.ui.widget.compose.ComposeDialogFragment
import io.legado.app.ui.widget.compose.LegadoMiuixActionButton
import io.legado.app.ui.widget.compose.LegadoMiuixChoiceRow
import io.legado.app.ui.widget.compose.rememberAppDialogStyle
import io.legado.app.ui.widget.compose.showComposeChoiceListDialog
import io.legado.app.ui.widget.compose.toMiuixPalette
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import io.legado.app.utils.getClipText
import io.legado.app.utils.sendToClip
import io.legado.app.utils.toastOnUi
import splitties.init.appCtx

class DirectLinkUploadConfig : ComposeDialogFragment() {

    override val dialogSize: AppDialogSize = AppDialogSize.Management

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                val style = rememberAppDialogStyle()
                CompositionLocalProvider(
                    LocalTextStyle provides LocalTextStyle.current.copy(fontFamily = style.bodyFontFamily)
                ) {
                    DirectLinkUploadConfigContent(style = style)
                }
            }
        }
    }

    @Composable
    private fun DirectLinkUploadConfigContent(style: AppDialogStyle) {
        val scope = rememberCoroutineScope()
        var uploadUrl by rememberSaveable { mutableStateOf("") }
        var downloadUrlRule by rememberSaveable { mutableStateOf("") }
        var summary by rememberSaveable { mutableStateOf("") }
        var compress by rememberSaveable { mutableStateOf(false) }
        var menuExpanded by remember { mutableStateOf(false) }

        fun upView(rule: DirectLinkUpload.Rule) {
            uploadUrl = rule.uploadUrl
            downloadUrlRule = rule.downloadUrlRule
            summary = rule.summary
            compress = rule.compress
        }

        fun getRule(): DirectLinkUpload.Rule? {
            if (uploadUrl.isBlank()) {
                toastOnUi("上传Url不能为空")
                return null
            }
            if (downloadUrlRule.isBlank()) {
                toastOnUi("下载Url规则不能为空")
                return null
            }
            if (summary.isBlank()) {
                toastOnUi("注释不能为空")
                return null
            }
            return DirectLinkUpload.Rule(uploadUrl, downloadUrlRule, summary, compress)
        }

        fun importDefault() {
            requireContext().selector(DirectLinkUpload.defaultRules) { _, rule, _ ->
                upView(rule)
            }
        }

        fun copyRule() {
            getRule()?.let { rule ->
                requireContext().sendToClip(GSON.toJson(rule))
            }
        }

        fun pasteRule() {
            kotlin.runCatching {
                requireContext().getClipText()!!.let {
                    val rule = GSON.fromJsonObject<DirectLinkUpload.Rule>(it).getOrThrow()
                    upView(rule)
                }
            }.onFailure {
                toastOnUi("剪贴板为空或格式不对")
            }
        }

        fun showTestResult(result: String) {
            ComposeConfirmDialog.create(
                title = "result",
                message = result,
                positiveText = getString(R.string.ok),
                negativeText = getString(R.string.copy_text),
                onPositive = {},
                onNegative = { appCtx.sendToClip(result) }
            ).show(childFragmentManager, "directLinkUploadTestResult")
        }

        fun test() {
            val rule = getRule() ?: return
            Coroutine.async(scope) {
                DirectLinkUpload.upLoad("test.json", "{}", "application/json", rule)
            }.onError {
                showTestResult(it.localizedMessage ?: "ERROR")
            }.onSuccess { result ->
                showTestResult(result)
            }
        }

        LaunchedEffect(Unit) {
            upView(DirectLinkUpload.getRule())
        }

        val menuActions = listOf(
            MenuAction(
                icon = Icons.Filled.Download,
                title = getString(R.string.import_default_rule),
                onClick = { importDefault() }
            ),
            MenuAction(
                icon = Icons.Filled.ContentCopy,
                title = getString(R.string.copy_rule),
                onClick = { copyRule() }
            ),
            MenuAction(
                icon = Icons.Filled.ContentPaste,
                title = getString(R.string.paste_rule),
                onClick = { pasteRule() }
            )
        )
        val palette = style.toMiuixPalette()

        AppDialogFrame(
            title = stringResource(R.string.direct_link_upload_config),
            content = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    DirectLinkField(
                        value = uploadUrl,
                        onValueChange = { uploadUrl = it },
                        label = stringResource(R.string.upload_url),
                        style = style
                    )
                    DirectLinkField(
                        value = downloadUrlRule,
                        onValueChange = { downloadUrlRule = it },
                        label = stringResource(R.string.download_url_rule),
                        style = style
                    )
                    DirectLinkField(
                        value = summary,
                        onValueChange = { summary = it },
                        label = stringResource(R.string.summary),
                        style = style
                    )
                    LegadoMiuixChoiceRow(
                        text = stringResource(R.string.is_compress),
                        selected = compress,
                        palette = palette,
                        onClick = { compress = !compress },
                        minHeight = 40.dp,
                        compact = true
                    )
                }
            },
            actions = {
                Box {
                    IconButton(
                        onClick = { menuExpanded = true },
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.MoreVert,
                            contentDescription = null,
                            tint = style.primaryText
                        )
                    }
                    AppDropdownMenu(
                        expanded = menuExpanded,
                        onDismiss = { menuExpanded = false },
                        actions = menuActions
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                LegadoMiuixActionButton(
                    text = stringResource(R.string.test),
                    palette = palette,
                    onClick = { test() },
                    cornerRadius = style.actionRadius
                )
                Spacer(modifier = Modifier.width(8.dp))
                LegadoMiuixActionButton(
                    text = stringResource(R.string.cancel),
                    palette = palette,
                    onClick = { dismissAllowingStateLoss() },
                    cornerRadius = style.actionRadius
                )
                Spacer(modifier = Modifier.width(8.dp))
                LegadoMiuixActionButton(
                    text = stringResource(R.string.ok),
                    palette = palette,
                    primary = true,
                    onClick = {
                        getRule()?.let { rule ->
                            DirectLinkUpload.putConfig(rule)
                            dismissAllowingStateLoss()
                        }
                    },
                    cornerRadius = style.actionRadius
                )
            }
        )
    }

    @Composable
    private fun DirectLinkField(
        value: String,
        onValueChange: (String) -> Unit,
        label: String,
        style: AppDialogStyle
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text(label) },
            shape = RoundedCornerShape(style.actionRadius),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = style.primaryText,
                unfocusedTextColor = style.primaryText,
                focusedContainerColor = style.fieldSurface,
                unfocusedContainerColor = style.fieldSurface,
                cursorColor = style.accent,
                focusedBorderColor = Color.Transparent,
                unfocusedBorderColor = Color.Transparent,
                focusedLabelColor = style.accent,
                unfocusedLabelColor = style.secondaryText
            ),
            textStyle = MaterialTheme.typography.bodyMedium.copy(
                color = style.primaryText,
                fontFamily = style.bodyFontFamily
            )
        )
    }
}
