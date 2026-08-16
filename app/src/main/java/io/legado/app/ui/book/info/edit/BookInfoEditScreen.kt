package io.legado.app.ui.book.info.edit

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import io.legado.app.R
import io.legado.app.data.entities.Book
import io.legado.app.help.book.isAudio
import io.legado.app.help.book.isImage
import io.legado.app.help.book.isVideo
import io.legado.app.ui.widget.components.AppDropdownMenu
import io.legado.app.ui.widget.components.GlassTopAppBar
import io.legado.app.ui.widget.components.MenuAction
import io.legado.app.ui.widget.components.SettingsCard
import io.legado.app.ui.widget.image.CoverImageView

/**
 * 书籍信息编辑页 Compose 受控组件（L-B7 枝叶页，S3 表单/编辑器范式）。
 * 状态由宿主（Activity）传入，事件全部上抛；顶栏 GlassTopAppBar + 保存菜单，
 * SettingsCard 分组字段（封面/书名作者、类型/封面地址/封面操作、简介）+ 保存/取消。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookInfoEditScreen(
    book: Book?,
    onBack: () -> Unit,
    onSave: (name: String, author: String, typeIndex: Int, coverUrl: String, intro: String) -> Unit,
    onSelectCover: () -> Unit,
    onChangeCover: () -> Unit,
    onRefreshCover: (coverUrl: String) -> Unit,
    modifier: Modifier = Modifier
) {
    var name by remember(book) { mutableStateOf(book?.name ?: "") }
    var author by remember(book) { mutableStateOf(book?.author ?: "") }
    var typeIndex by remember(book) {
        mutableIntStateOf(
            when {
                book?.isVideo == true -> 4
                book?.isImage == true -> 2
                book?.isAudio == true -> 1
                else -> 0
            }
        )
    }
    var coverUrl by remember(book) { mutableStateOf(book?.getDisplayCover() ?: "") }
    var intro by remember(book) { mutableStateOf(book?.getDisplayIntro() ?: "") }
    var moreMenuVisible by remember { mutableStateOf(false) }
    var typeMenuVisible by remember { mutableStateOf(false) }

    val typeOptions = stringArrayResource(R.array.book_type)
    val scrollState = rememberScrollState()

    Column(modifier = modifier.fillMaxSize()) {
        GlassTopAppBar(
            title = stringResource(R.string.book_info_edit),
            navIcon = Icons.AutoMirrored.Filled.ArrowBack,
            onNavClick = onBack,
            actions = {
                IconButton(onClick = { moreMenuVisible = true }) {
                    Icon(
                        Icons.Default.MoreVert,
                        contentDescription = stringResource(R.string.more_menu)
                    )
                }
                AppDropdownMenu(
                    expanded = moreMenuVisible,
                    onDismiss = { moreMenuVisible = false },
                    actions = listOf(
                        MenuAction(
                            icon = Icons.Default.Save,
                            title = stringResource(R.string.action_save),
                            onClick = {
                                moreMenuVisible = false
                                onSave(name, author, typeIndex, coverUrl, intro)
                            }
                        )
                    )
                )
            }
        )

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(scrollState)
                .navigationBarsPadding()
        ) {
            // 封面 + 书名/作者卡片
            SettingsCard(modifier = Modifier.padding(top = 8.dp)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    AndroidView(
                        factory = { ctx ->
                            CoverImageView(ctx).apply {
                                scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
                            }
                        },
                        update = { iv ->
                            book?.let { iv.load(it, false) }
                        },
                        modifier = Modifier.size(width = 90.dp, height = 130.dp)
                    )

                    Spacer(modifier = Modifier.width(16.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        OutlinedTextField(
                            value = name,
                            onValueChange = { name = it },
                            label = { Text(stringResource(R.string.book_name)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(
                            value = author,
                            onValueChange = { author = it },
                            label = { Text(stringResource(R.string.author)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }

            // 类型 + 封面地址 + 封面操作
            SettingsCard {
                // 类型选择器（Box 容器包裹 DropdownMenu 锚点）
                Box(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { typeMenuVisible = true }
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = stringResource(R.string.book_type),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = typeOptions.getOrElse(typeIndex) { "" },
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                        Icon(
                            imageVector = Icons.Default.ArrowDropDown,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    DropdownMenu(
                        expanded = typeMenuVisible,
                        onDismissRequest = { typeMenuVisible = false }
                    ) {
                        typeOptions.forEachIndexed { index, option ->
                            DropdownMenuItem(
                                text = { Text(option) },
                                onClick = {
                                    typeMenuVisible = false
                                    typeIndex = index
                                }
                            )
                        }
                    }
                }

                OutlinedTextField(
                    value = coverUrl,
                    onValueChange = { coverUrl = it },
                    label = { Text(stringResource(R.string.cover_path)) },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                )

                // 封面操作按钮
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = onSelectCover,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(stringResource(R.string.select_local_image), maxLines = 1)
                    }
                    OutlinedButton(
                        onClick = onChangeCover,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(stringResource(R.string.change_cover_source), maxLines = 1)
                    }
                    Button(
                        onClick = { onRefreshCover(coverUrl) },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(stringResource(R.string.refresh_cover), maxLines = 1)
                    }
                }
            }

            // 简介
            SettingsCard {
                OutlinedTextField(
                    value = intro,
                    onValueChange = { intro = it },
                    label = { Text(stringResource(R.string.book_intro)) },
                    minLines = 3,
                    maxLines = 6,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                )
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}