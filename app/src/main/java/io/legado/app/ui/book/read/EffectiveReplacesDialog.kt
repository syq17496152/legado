package io.legado.app.ui.book.read

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.fragment.app.activityViewModels
import io.legado.app.R
import io.legado.app.data.appDb
import io.legado.app.data.entities.ReplaceRule
import io.legado.app.help.config.AppConfig
import io.legado.app.model.ReadBook
import io.legado.app.ui.replace.edit.ReplaceEditActivity
import io.legado.app.ui.widget.compose.AppDialogFrame
import io.legado.app.ui.widget.compose.AppDialogSize
import io.legado.app.ui.widget.compose.ComposeDialogFragment
import io.legado.app.ui.widget.compose.showComposeSingleChoiceDialog

/**
 * 起效的替换规则（D1 P0 迁移：BaseDialogFragment 旧 View 弹框 → ComposeDialogFragment，随主题全量纳管）
 */
class EffectiveReplacesDialog : ComposeDialogFragment() {

    override val dialogSize: AppDialogSize = AppDialogSize.Management
    override val dialogWindowAnimations: Int = R.style.AnimDialogFade

    private val viewModel by activityViewModels<ReadBookViewModel>()
    private val chineseConvert by lazy { ReplaceRule(0, "繁简转换") }
    private var isEdit by mutableStateOf(false)

    private val editActivity =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (it.resultCode == AppCompatActivity.RESULT_OK) {
                isEdit = true
            }
        }

    private fun initialItems(): List<ReplaceRule> {
        return buildList {
            addAll(ReadBook.curTextChapter?.effectiveReplaceRules ?: emptyList())
            if (AppConfig.chineseConverterType > 0) add(chineseConvert)
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
                var items by remember { mutableStateOf(initialItems()) }
                EffectiveReplacesPanel(
                    items = items,
                    onRowClick = { item ->
                        if (item == chineseConvert) {
                            showChineseConvertAlert()
                        } else {
                            editActivity.launch(ReplaceEditActivity.startIntent(requireContext(), item.id))
                        }
                    },
                    onClose = { item ->
                        isEdit = true
                        items = items.filterNot { it === item }
                        if (item == chineseConvert) {
                            AppConfig.chineseConverterType = 0
                        } else {
                            item.isEnabled = false
                            appDb.replaceRuleDao.insert(item)
                        }
                    },
                    onDismiss = { dismissAllowingStateLoss() }
                )
            }
        }
    }

    private fun showChineseConvertAlert() {
        showComposeSingleChoiceDialog(
            title = getString(R.string.chinese_converter),
            labels = resources.getStringArray(R.array.chinese_mode).toList(),
            selectedIndex = AppConfig.chineseConverterType,
            onPositive = { i ->
                if (AppConfig.chineseConverterType != i) {
                    AppConfig.chineseConverterType = i
                    isEdit = true
                }
            }
        )
    }

    override fun onDismiss(dialog: android.content.DialogInterface) {
        super.onDismiss(dialog)
        if (isEdit) {
            viewModel.replaceRuleChanged()
        }
    }

}

@Composable
private fun EffectiveReplacesPanel(
    items: List<ReplaceRule>,
    onRowClick: (ReplaceRule) -> Unit,
    onClose: (ReplaceRule) -> Unit,
    onDismiss: () -> Unit
) {
    AppDialogFrame(
        title = stringResource(R.string.effective_replaces),
        content = {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 440.dp)
            ) {
                items(items) { item ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(onClick = { onRowClick(item) })
                            .padding(start = 4.dp, top = 4.dp, bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = item.name,
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = androidx.compose.material3.MaterialTheme.typography.bodyLarge
                        )
                        IconButton(onClick = { onClose(item) }) {
                            Icon(
                                imageVector = Icons.Filled.Close,
                                contentDescription = stringResource(R.string.delete)
                            )
                        }
                    }
                }
            }
        },
        actions = { },
        scrollContent = false
    )
}