package io.legado.app.ui.widget.dialog

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import io.legado.app.R
import io.legado.app.help.IntentData
import io.legado.app.ui.widget.code.CodeView
import io.legado.app.ui.widget.code.addJsPattern
import io.legado.app.ui.widget.code.addJsonPattern
import io.legado.app.ui.widget.code.addLegadoPattern
import io.legado.app.ui.widget.compose.AppDialogFrame
import io.legado.app.ui.widget.compose.ComposeDialogFragment
import io.legado.app.ui.widget.compose.LegadoMiuixActionButton
import io.legado.app.ui.widget.compose.rememberAppDialogStyle
import io.legado.app.ui.widget.compose.toMiuixPalette
import io.legado.app.utils.disableEdit
import io.legado.app.utils.dpToPx

class CodeDialog() : ComposeDialogFragment() {

    override val dialogHeight: Int = ViewGroup.LayoutParams.MATCH_PARENT

    private var codeViewRef: CodeView? = null

    constructor(code: String, disableEdit: Boolean = true, requestId: String? = null) : this() {
        arguments = Bundle().apply {
            putBoolean("disableEdit", disableEdit)
            putString("code", IntentData.put(code))
            putString("requestId", requestId)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val args = arguments ?: Bundle()
        val disableEdit = args.getBoolean("disableEdit")
        val code = args.getString("code")?.let { IntentData.get<String>(it) }
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                val style = rememberAppDialogStyle()
                AppDialogFrame(
                    title = if (disableEdit) "code view" else "code edit",
                    scrollContent = false,
                    content = {
                        AndroidView(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 320.dp),
                            factory = { context ->
                                CodeView(context).apply {
                                    val pad = 12.dpToPx()
                                    setPadding(pad, pad, pad, pad)
                                    if (disableEdit) {
                                        disableEdit()
                                    }
                                    addLegadoPattern()
                                    addJsonPattern()
                                    addJsPattern()
                                    code?.let { setText(it) }
                                    codeViewRef = this
                                }
                            }
                        )
                    },
                    actions = {
                        if (!disableEdit) {
                            val palette = style.toMiuixPalette()
                            LegadoMiuixActionButton(
                                text = stringResource(R.string.action_save),
                                palette = palette,
                                onClick = {
                                    codeViewRef?.text?.toString()?.let { codeText ->
                                        val requestId = args.getString("requestId")
                                        (parentFragment as? Callback)?.onCodeSave(codeText, requestId)
                                            ?: (activity as? Callback)?.onCodeSave(codeText, requestId)
                                    }
                                    dismissAllowingStateLoss()
                                },
                                primary = true,
                                cornerRadius = style.actionRadius
                            )
                        }
                    }
                )
            }
        }
    }

    interface Callback {

        fun onCodeSave(code: String, requestId: String?)

    }

}
