package io.legado.app.ui.rss.article

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.fragment.app.viewModels
import io.legado.app.R
import io.legado.app.data.entities.RssReadRecord
import io.legado.app.ui.rss.read.ReadRss
import io.legado.app.ui.widget.compose.AppDialogFrame
import io.legado.app.ui.widget.compose.AppDialogSize
import io.legado.app.ui.widget.compose.AppDialogStyle
import io.legado.app.ui.widget.compose.ComposeDialogFragment
import io.legado.app.ui.widget.compose.LegadoMiuixActionButton
import io.legado.app.ui.widget.compose.rememberAppDialogStyle
import io.legado.app.ui.widget.compose.showComposeConfirmDialog
import io.legado.app.ui.widget.compose.toMiuixPalette

/**
 * 阅读记录（View→Compose 迁移：RecyclerView → LazyColumn，AppDialogFrame 统一弹框样式）
 */
class ReadRecordDialog(private val origin: String? = null) : ComposeDialogFragment() {

    override val dialogSize: AppDialogSize = AppDialogSize.Management

    private val viewModel by viewModels<RssSortViewModel>()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                var records by remember { mutableStateOf(viewModel.getRecords(origin)) }
                val style = rememberAppDialogStyle()
                AppDialogFrame(
                    title = stringResource(R.string.read_record),
                    scrollContent = false,
                    content = {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 460.dp)
                        ) {
                            items(records) { record ->
                                ReadRecordRow(
                                    record = record,
                                    style = style,
                                    onClick = {
                                        ReadRss.readRss(activity as AppCompatActivity, record)
                                        dismiss()
                                    }
                                )
                            }
                        }
                    },
                    actions = {
                        val palette = style.toMiuixPalette()
                        LegadoMiuixActionButton(
                            text = stringResource(R.string.clear),
                            palette = palette,
                            onClick = {
                                val countRead = viewModel.countRecords(origin)
                                showComposeConfirmDialog(
                                    title = getString(R.string.draw),
                                    message = getString(R.string.sure_del) + "\n" + countRead + " " + getString(R.string.read_record),
                                    positiveText = getString(R.string.yes),
                                    negativeText = getString(R.string.no),
                                    dangerPositive = true,
                                    onPositive = {
                                        viewModel.deleteAllRecord(origin)
                                        records = emptyList()
                                    }
                                )
                            },
                            cornerRadius = style.actionRadius
                        )
                    }
                )
            }
        }
    }

    interface OnRecordClickListener {
        fun onRecordClick(record: RssReadRecord?)
    }

}

@Composable
private fun ReadRecordRow(
    record: RssReadRecord,
    style: AppDialogStyle,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp)
    ) {
        Text(
            text = record.title.orEmpty(),
            color = style.primaryText,
            fontSize = MaterialTheme.typography.bodyMedium.fontSize,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = record.record,
            color = style.secondaryText,
            fontSize = MaterialTheme.typography.bodySmall.fontSize,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
