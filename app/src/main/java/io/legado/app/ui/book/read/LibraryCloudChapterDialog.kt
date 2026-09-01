package io.legado.app.ui.book.read

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.legado.app.help.book.library.LibraryCloudChapterVersion
import io.legado.app.ui.theme.LegadoTheme
import io.legado.app.ui.widget.compose.AppDialogFrame
import io.legado.app.ui.widget.compose.AppDialogSize
import io.legado.app.ui.widget.compose.AppDialogStyle
import io.legado.app.ui.widget.compose.ComposeDialogFragment
import io.legado.app.ui.widget.compose.LegadoMiuixActionButton
import io.legado.app.ui.widget.compose.LegadoMiuixCard
import io.legado.app.ui.widget.compose.rememberAppDialogStyle
import io.legado.app.ui.widget.compose.toMiuixPalette

data class LibraryCloudChapterRow(
    val item: LibraryCloudChapterVersion,
    val title: String,
    val time: String?
)

data class LibraryCloudChapterGroup(
    val label: String,
    val rows: List<LibraryCloudChapterRow>
)

/**
 * 书库云端章节选择（deep-fix F 迁移：原 AlertDialog+LinearLayout 动态章节行 → ComposeDialogFragment + AppDialogFrame + LazyColumn）
 */
class LibraryCloudChapterDialog(
    private val currentChapterTitle: String = "",
    private val groups: List<LibraryCloudChapterGroup> = emptyList(),
    private val onRead: (LibraryCloudChapterVersion) -> Unit = {},
    private val onDelete: (LibraryCloudChapterVersion) -> Unit = {}
) : ComposeDialogFragment() {

    override val dialogSize: AppDialogSize = AppDialogSize.Confirm

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                LegadoTheme {
                    LibraryCloudChapterContent(
                        currentChapterTitle = currentChapterTitle,
                        groups = groups,
                        onRead = { row ->
                            dismissAllowingStateLoss()
                            onRead(row.item)
                        },
                        onDelete = { onDelete(it.item) },
                        onCancel = { dismissAllowingStateLoss() }
                    )
                }
            }
        }
    }
}

@Composable
private fun LibraryCloudChapterContent(
    currentChapterTitle: String,
    groups: List<LibraryCloudChapterGroup>,
    onRead: (LibraryCloudChapterRow) -> Unit,
    onDelete: (LibraryCloudChapterRow) -> Unit,
    onCancel: () -> Unit
) {
    val style = rememberAppDialogStyle()
    AppDialogFrame(
        title = "选择书库章节",
        scrollContent = false,
        content = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "当前章节：$currentChapterTitle",
                    color = style.secondaryText,
                    fontSize = MaterialTheme.typography.bodySmall.fontSize,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(10.dp))
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    groups.forEach { group ->
                        item {
                            Text(
                                text = group.label,
                                color = style.secondaryText,
                                fontSize = MaterialTheme.typography.bodyMedium.fontSize,
                                fontWeight = FontWeight.Medium
                            )
                        }
                        items(group.rows) { row ->
                            LibraryCloudChapterRowItem(
                                row = row,
                                style = style,
                                onRead = onRead,
                                onDelete = onDelete
                            )
                        }
                    }
                }
            }
        },
        actions = {
            LegadoMiuixActionButton(
                text = "取消",
                palette = style.toMiuixPalette(),
                onClick = onCancel,
                cornerRadius = style.actionRadius
            )
        }
    )
}

@Composable
private fun LibraryCloudChapterRowItem(
    row: LibraryCloudChapterRow,
    style: AppDialogStyle,
    onRead: (LibraryCloudChapterRow) -> Unit,
    onDelete: (LibraryCloudChapterRow) -> Unit
) {
    LegadoMiuixCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onRead(row) },
        color = style.fieldSurface,
        contentColor = style.primaryText,
        cornerRadius = style.actionRadius,
        insidePadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp)
    ) {
        Column {
            Text(
                text = row.title,
                color = style.primaryText,
                fontSize = MaterialTheme.typography.bodyMedium.fontSize,
                fontWeight = FontWeight.Medium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            row.time?.let { time ->
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = time,
                    color = style.secondaryText,
                    fontSize = MaterialTheme.typography.bodySmall.fontSize
                )
            }
            Spacer(modifier = Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "读取",
                    color = style.accent,
                    fontSize = MaterialTheme.typography.bodyMedium.fontSize,
                    modifier = Modifier
                        .clickable { onRead(row) }
                        .padding(vertical = 4.dp)
                )
                Spacer(modifier = Modifier.width(14.dp))
                Text(
                    text = "删除",
                    color = style.danger,
                    fontSize = MaterialTheme.typography.bodyMedium.fontSize,
                    modifier = Modifier
                        .clickable { onDelete(row) }
                        .padding(vertical = 4.dp)
                )
            }
        }
    }
}
