package io.legado.app.ui.widget.compose

import android.content.DialogInterface
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.legado.app.R
import io.legado.app.ui.theme.LegadoTheme

class ComposeConfirmDialog : ComposeDialogFragment() {

    override val dialogSize: AppDialogSize = AppDialogSize.Confirm

    private var onPositive: (() -> Unit)? = null
    private var onNegative: (() -> Unit)? = null
    private var onNeutral: (() -> Unit)? = null
    private var onDismissAction: (() -> Unit)? = null
    private var handledAction = false
    // 可选勾选项（2026-09-21 M5 book/info 弹窗族收口）：用于「删除书籍 → 同时删除本地文件」这类二选一；
    // 不传 label 时完全不渲染 ⇒ 既有调用点零改动。勾选值在「确认」时一次性回调（语义与旧 alert{} 一致）。
    private var checkboxCheckedState = mutableStateOf(false)
    private var onCheckboxConfirmed: ((Boolean) -> Unit)? = null

    override fun onDismiss(dialog: DialogInterface) {
        super.onDismiss(dialog)
        if (!handledAction) onDismissAction?.invoke()
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val args = arguments ?: Bundle()
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                LegadoTheme {
                    val style = rememberAppDialogStyle()
                    val positiveText = args.getString(ARG_POSITIVE_TEXT)
                        .orEmpty()
                        .ifBlank { stringResource(R.string.ok) }
                    val negativeText = args.getString(ARG_NEGATIVE_TEXT)
                        .orEmpty()
                        .ifBlank { stringResource(R.string.cancel) }
                    val neutralText = args.getString(ARG_NEUTRAL_TEXT)?.takeIf { it.isNotBlank() }
                    val dangerPositive = args.getBoolean(ARG_DANGER_POSITIVE)
                    val positiveRequiresCallback = args.getBoolean(ARG_POSITIVE_REQUIRES_CALLBACK, true)
                    val negativeRequiresCallback = args.getBoolean(ARG_NEGATIVE_REQUIRES_CALLBACK, false)
                    val messageInContent = args.getBoolean(ARG_MESSAGE_IN_CONTENT)
                    val showNegative = args.getBoolean(ARG_SHOW_NEGATIVE, true)
                    val checkboxLabel = args.getString(ARG_CHECKBOX_LABEL)?.takeIf { it.isNotBlank() }
                    checkboxCheckedState.value = args.getBoolean(ARG_CHECKBOX_CHECKED)
                    DismissWhenCallbackMissing(
                        missing = positiveRequiresCallback && onPositive == null ||
                            negativeRequiresCallback && onNegative == null,
                        dismiss = ::dismissAllowingStateLoss
                    )
                    AppDialogFrame(
                        title = args.getString(ARG_TITLE).orEmpty(),
                        message = args.getString(ARG_MESSAGE),
                        messageInContent = messageInContent,
                        content = {
                            if (checkboxLabel != null) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            checkboxCheckedState.value = !checkboxCheckedState.value
                                        }
                                ) {
                                    Checkbox(
                                        checked = checkboxCheckedState.value,
                                        onCheckedChange = { checkboxCheckedState.value = it }
                                    )
                                    Text(
                                        text = checkboxLabel,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = style.primaryText
                                    )
                                }
                            }
                        },
                        actions = {
                            val palette = style.toMiuixPalette()
                            var hasPriorAction = false
                            val neutralCallback = onNeutral
                            if (neutralText != null && neutralCallback != null) {
                                LegadoMiuixActionButton(
                                    text = neutralText,
                                    palette = palette,
                                    onClick = {
                                        handledAction = true
                                        dismissAllowingStateLoss()
                                        neutralCallback.invoke()
                                    },
                                    cornerRadius = style.actionRadius
                                )
                                hasPriorAction = true
                            }
                            val negativeCallback = onNegative
                            if (showNegative && (!negativeRequiresCallback || negativeCallback != null)) {
                                if (hasPriorAction) {
                                    Spacer(modifier = Modifier.width(8.dp))
                                }
                                LegadoMiuixActionButton(
                                    text = negativeText,
                                    palette = palette,
                                    onClick = {
                                        if (negativeCallback != null) handledAction = true
                                        dismissAllowingStateLoss()
                                        negativeCallback?.invoke()
                                    },
                                    cornerRadius = style.actionRadius
                                )
                                hasPriorAction = true
                            }
                            val positiveCallback = onPositive
                            if (!positiveRequiresCallback || positiveCallback != null) {
                                if (hasPriorAction) {
                                    Spacer(modifier = Modifier.width(8.dp))
                                }
                                LegadoMiuixActionButton(
                                    text = positiveText,
                                    palette = palette,
                                    onClick = {
                                        handledAction = true
                                        dismissAllowingStateLoss()
                                        onCheckboxConfirmed?.invoke(checkboxCheckedState.value)
                                        positiveCallback?.invoke()
                                    },
                                    primary = !dangerPositive,
                                    danger = dangerPositive,
                                    cornerRadius = style.actionRadius
                                )
                            }
                        }
                    )
                }
            }
        }
    }

    companion object {
        /**
         * Lambda callbacks are intentionally transient. If Android recreates the dialog,
         * action buttons with lost callbacks are hidden instead of running stale work.
         */
        fun create(
            title: String,
            message: String? = null,
            positiveText: String,
            negativeText: String,
            neutralText: String? = null,
            dangerPositive: Boolean = false,
            positiveRequiresCallback: Boolean = true,
            negativeRequiresCallback: Boolean = false,
            messageInContent: Boolean = false,
            showNegative: Boolean = true,
            /** 可选勾选项文案（如「同时删除本地文件」）；null = 不渲染（既有调用点零改动） */
            checkboxLabel: String? = null,
            checkboxChecked: Boolean = false,
            onPositive: () -> Unit,
            onNegative: (() -> Unit)? = null,
            onNeutral: (() -> Unit)? = null,
            onDismissAction: (() -> Unit)? = null,
            /** 勾选项在「确认」时一次性回调（与旧「弹框 DSL + customView」语义一致：非确认关闭不写偏好） */
            onCheckboxConfirmed: ((Boolean) -> Unit)? = null
        ): ComposeConfirmDialog {
            return ComposeConfirmDialog().apply {
                arguments = Bundle().apply {
                    putString(ARG_TITLE, title)
                    putString(ARG_MESSAGE, message)
                    putString(ARG_POSITIVE_TEXT, positiveText)
                    putString(ARG_NEGATIVE_TEXT, negativeText)
                    putString(ARG_NEUTRAL_TEXT, neutralText)
                    putBoolean(ARG_DANGER_POSITIVE, dangerPositive)
                    putBoolean(ARG_POSITIVE_REQUIRES_CALLBACK, positiveRequiresCallback)
                    putBoolean(ARG_NEGATIVE_REQUIRES_CALLBACK, negativeRequiresCallback)
                    putBoolean(ARG_MESSAGE_IN_CONTENT, messageInContent)
                    putBoolean(ARG_SHOW_NEGATIVE, showNegative)
                    putString(ARG_CHECKBOX_LABEL, checkboxLabel)
                    putBoolean(ARG_CHECKBOX_CHECKED, checkboxChecked)
                }
                this.onPositive = onPositive
                this.onNegative = onNegative
                this.onNeutral = onNeutral
                this.onDismissAction = onDismissAction
                this.onCheckboxConfirmed = onCheckboxConfirmed
            }
        }

        private const val ARG_TITLE = "title"
        private const val ARG_MESSAGE = "message"
        private const val ARG_POSITIVE_TEXT = "positiveText"
        private const val ARG_NEGATIVE_TEXT = "negativeText"
        private const val ARG_NEUTRAL_TEXT = "neutralText"
        private const val ARG_DANGER_POSITIVE = "dangerPositive"
        private const val ARG_POSITIVE_REQUIRES_CALLBACK = "positiveRequiresCallback"
        private const val ARG_NEGATIVE_REQUIRES_CALLBACK = "negativeRequiresCallback"
        private const val ARG_MESSAGE_IN_CONTENT = "messageInContent"
        private const val ARG_SHOW_NEGATIVE = "showNegative"
        private const val ARG_CHECKBOX_LABEL = "checkboxLabel"
        private const val ARG_CHECKBOX_CHECKED = "checkboxChecked"
    }
}

class ComposeFetchedModelDialog : ComposeDialogFragment() {

    override val dialogSize: AppDialogSize = AppDialogSize.Management

    private var onAddSingle: ((String) -> Unit)? = null
    private var onAddSelected: ((List<String>) -> Unit)? = null
    private var onAddAll: ((List<String>) -> Unit)? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val args = arguments ?: Bundle()
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                LegadoTheme {
                    DismissWhenCallbackMissing(
                        missing = onAddSingle == null,
                        dismiss = ::dismissAllowingStateLoss
                    )
                    val modelIds = remember {
                        args.getStringArrayList(ARG_MODEL_IDS)?.toList().orEmpty()
                    }
                    val existingIds = remember {
                        args.getStringArrayList(ARG_EXISTING_IDS)?.toSet().orEmpty()
                    }
                    io.legado.app.ui.config.FetchedModelSelectorContent(
                        modelIds = modelIds,
                        existingIds = existingIds,
                        onAddSingle = { modelId ->
                            dismissAllowingStateLoss()
                            onAddSingle?.invoke(modelId)
                        },
                        onAddSelected = { selectedIds ->
                            dismissAllowingStateLoss()
                            onAddSelected?.invoke(selectedIds)
                        },
                        onAddAll = { allIds ->
                            dismissAllowingStateLoss()
                            onAddAll?.invoke(allIds)
                        },
                        onDismiss = { dismissAllowingStateLoss() }
                    )
                }
            }
        }
    }

    companion object {
        fun create(
            modelIds: List<String>,
            existingIds: Set<String>,
            onAddSingle: (String) -> Unit,
            onAddSelected: (List<String>) -> Unit,
            onAddAll: (List<String>) -> Unit
        ): ComposeFetchedModelDialog {
            return ComposeFetchedModelDialog().apply {
                arguments = Bundle().apply {
                    putStringArrayList(ARG_MODEL_IDS, ArrayList(modelIds))
                    putStringArrayList(ARG_EXISTING_IDS, ArrayList(existingIds))
                }
                this.onAddSingle = onAddSingle
                this.onAddSelected = onAddSelected
                this.onAddAll = onAddAll
            }
        }

        private const val ARG_MODEL_IDS = "modelIds"
        private const val ARG_EXISTING_IDS = "existingIds"
    }
}
