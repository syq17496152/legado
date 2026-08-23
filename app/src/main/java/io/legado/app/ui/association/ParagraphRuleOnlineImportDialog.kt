package io.legado.app.ui.association

import android.content.DialogInterface
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.R
import io.legado.app.ui.widget.compose.AppDialogFrame
import io.legado.app.ui.widget.compose.ComposeDialogFragment
import io.legado.app.ui.widget.compose.AppDialogSize
import io.legado.app.ui.widget.compose.LegadoMiuixActionButton
import io.legado.app.ui.widget.compose.LegadoMiuixChoiceRow
import io.legado.app.ui.widget.compose.rememberAppDialogStyle
import io.legado.app.ui.widget.compose.toMiuixPalette

class ParagraphRuleOnlineImportDialog : ComposeDialogFragment() {

    interface Callback {
        fun onParagraphRuleImportConfirmed(strategy: ParagraphRuleConflictStrategy)
        fun onParagraphRuleImportCancelled()
    }

    override val dialogSize: AppDialogSize = AppDialogSize.Management

    private var confirmed = false

    override fun onDismiss(dialog: DialogInterface) {
        super.onDismiss(dialog)
        if (!confirmed) (activity as? Callback)?.onParagraphRuleImportCancelled()
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
                val style = rememberAppDialogStyle()
                val palette = style.toMiuixPalette()
                val conflictCount = args.getInt(ARG_CONFLICT_COUNT)
                var selectedIndex by rememberSaveable { mutableIntStateOf(0) }
                val strategies = listOf(
                    ParagraphRuleConflictStrategy.RENAME,
                    ParagraphRuleConflictStrategy.OVERWRITE,
                    ParagraphRuleConflictStrategy.SKIP
                )
                val labels = listOf(
                    stringResource(R.string.paragraph_import_conflict_rename),
                    stringResource(R.string.paragraph_import_conflict_overwrite),
                    stringResource(R.string.paragraph_import_conflict_skip)
                )
                val descriptions = listOf(
                    stringResource(R.string.paragraph_import_conflict_rename_description),
                    stringResource(R.string.paragraph_import_conflict_overwrite_description),
                    stringResource(R.string.paragraph_import_conflict_skip_description)
                )

                AppDialogFrame(
                    title = stringResource(R.string.paragraph_import_title),
                    message = args.getString(ARG_MESSAGE),
                    messageInContent = true,
                    content = {
                        if (conflictCount > 0) {
                            Text(
                                text = stringResource(
                                    R.string.paragraph_import_conflict_message,
                                    conflictCount
                                ),
                                color = style.primaryText,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium
                            )
                            Spacer(modifier = Modifier.height(10.dp))
                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                verticalArrangement = Arrangement.spacedBy(7.dp)
                            ) {
                                labels.forEachIndexed { index, label ->
                                    LegadoMiuixChoiceRow(
                                        text = label,
                                        description = descriptions[index],
                                        selected = selectedIndex == index,
                                        palette = palette,
                                        onClick = { selectedIndex = index }
                                    )
                                }
                            }
                        }
                    },
                    actions = {
                        LegadoMiuixActionButton(
                            text = stringResource(R.string.cancel),
                            palette = palette,
                            onClick = { dismissAllowingStateLoss() },
                            cornerRadius = style.actionRadius
                        )
                        if (activity is Callback) {
                            Spacer(modifier = Modifier.width(8.dp))
                            LegadoMiuixActionButton(
                                text = stringResource(R.string.import_),
                                palette = palette,
                                onClick = {
                                    val strategy = if (conflictCount > 0) {
                                        strategies[selectedIndex]
                                    } else {
                                        ParagraphRuleConflictStrategy.RENAME
                                    }
                                    confirmed = true
                                    (activity as? Callback)
                                        ?.onParagraphRuleImportConfirmed(strategy)
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

    companion object {
        fun create(message: String, conflictCount: Int) =
            ParagraphRuleOnlineImportDialog().apply {
                arguments = Bundle().apply {
                    putString(ARG_MESSAGE, message)
                    putInt(ARG_CONFLICT_COUNT, conflictCount.coerceAtLeast(0))
                }
            }

        private const val ARG_MESSAGE = "message"
        private const val ARG_CONFLICT_COUNT = "conflictCount"
    }
}
