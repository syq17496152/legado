package io.legado.app.ui.widget.dialog

import android.app.Application
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.fragment.app.viewModels
import io.legado.app.R
import io.legado.app.base.BaseViewModel
import io.legado.app.ui.widget.compose.AppDialogFrame
import io.legado.app.ui.widget.compose.AppDialogSize
import io.legado.app.ui.widget.compose.AppDialogStyle
import io.legado.app.ui.widget.compose.ComposeDialogFragment
import io.legado.app.ui.widget.compose.LegadoMiuixActionButton
import io.legado.app.ui.widget.compose.rememberAppDialogStyle
import io.legado.app.ui.widget.compose.toMiuixPalette

class VariableDialog() : ComposeDialogFragment() {

    override val dialogSize: AppDialogSize = AppDialogSize.Form

    private val viewModel by viewModels<ViewModel>()

    constructor(title: String, key: String, variable: String?, comment: String) : this() {
        arguments = Bundle().apply {
            putString("title", title)
            putString("key", key)
            putString("variable", variable)
            putString("comment", comment)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val args = arguments
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                var comment by rememberSaveable { mutableStateOf<String?>(null) }
                var variable by rememberSaveable { mutableStateOf<String?>(null) }
                LaunchedEffect(Unit) {
                    if (args == null) {
                        dismissAllowingStateLoss()
                    } else {
                        viewModel.init(args) {
                            comment = viewModel.comment
                            variable = viewModel.variable
                        }
                    }
                }
                val style = rememberAppDialogStyle()
                AppDialogFrame(
                    title = args?.getString("title").orEmpty(),
                    content = {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            VariableInputField(
                                value = variable.orEmpty(),
                                onValueChange = { variable = it },
                                style = style
                            )
                            Text(
                                text = stringResource(R.string.variable_comment),
                                color = style.accent,
                                fontSize = MaterialTheme.typography.bodySmall.fontSize,
                                fontWeight = FontWeight.Medium
                            )
                            SelectionContainer {
                                Text(
                                    text = comment.orEmpty(),
                                    color = style.secondaryText,
                                    fontSize = MaterialTheme.typography.bodyMedium.fontSize
                                )
                            }
                        }
                    },
                    actions = {
                        val palette = style.toMiuixPalette()
                        LegadoMiuixActionButton(
                            text = stringResource(R.string.action_save),
                            palette = palette,
                            onClick = {
                                callback?.setVariable(
                                    viewModel.key ?: "",
                                    variable.orEmpty()
                                )
                                dismissAllowingStateLoss()
                            },
                            primary = true,
                            cornerRadius = style.actionRadius
                        )
                    }
                )
            }
        }
    }

    val callback get() = (parentFragment as? Callback) ?: (activity as? Callback)

    class ViewModel(application: Application) : BaseViewModel(application) {

        var key: String? = null
        var comment: String? = null
        var variable: String? = null

        fun init(arguments: Bundle, onFinally: () -> Unit) {
            if (key != null) return
            execute {
                key = arguments.getString("key")
                comment = arguments.getString("comment")
                variable = arguments.getString("variable")
            }.onFinally {
                onFinally.invoke()
            }
        }

    }

    interface Callback {

        fun setVariable(key: String, variable: String?)

    }

}

@Composable
private fun VariableInputField(
    value: String,
    onValueChange: (String) -> Unit,
    style: AppDialogStyle
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "variable",
            color = style.secondaryText,
            fontSize = MaterialTheme.typography.bodySmall.fontSize,
            fontWeight = FontWeight.Medium
        )
        Spacer(modifier = Modifier.height(6.dp))
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth(),
            maxLines = 4,
            shape = RoundedCornerShape(style.actionRadius),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = style.primaryText,
                unfocusedTextColor = style.primaryText,
                focusedContainerColor = style.fieldSurface,
                unfocusedContainerColor = style.fieldSurface,
                cursorColor = style.accent,
                focusedBorderColor = style.accent.copy(alpha = 0.55f),
                unfocusedBorderColor = style.stroke
            ),
            textStyle = MaterialTheme.typography.bodyMedium.copy(
                color = style.primaryText,
                fontFamily = style.bodyFontFamily
            )
        )
    }
}
