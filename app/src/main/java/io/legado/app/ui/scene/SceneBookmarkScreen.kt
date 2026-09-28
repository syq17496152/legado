package io.legado.app.ui.scene

import android.widget.ImageView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmarks
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import io.legado.app.R
import io.legado.app.data.entities.SceneBookmark
import io.legado.app.help.book.SceneBookmarkHelper
import io.legado.app.help.glide.ImageLoader
import io.legado.app.ui.widget.components.EmptyStateAction
import io.legado.app.ui.widget.components.EmptyStatePlaceholder
import io.legado.app.ui.widget.components.GroupHeader
import io.legado.app.ui.widget.components.MenuAction
import io.legado.app.ui.widget.compose.AppManagementLazyColumn
import io.legado.app.ui.widget.compose.AppManagementListRow
import io.legado.app.ui.widget.compose.AppManagementPalette
import io.legado.app.ui.widget.compose.AppManagementScaffold
import io.legado.app.ui.widget.compose.AppManagementMenuAction
import io.legado.app.ui.widget.compose.rememberAppManagementPalette
import io.legado.app.ui.widget.image.FilletImageView
import io.legado.app.utils.dpToPx
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * W8 9.4（REQ-32）：**名场面书签库页**（唯一新增页面）。
 *
 * 规格来源：design §10.2(1) —— `AppManagementScaffold` + `AppManagementLazyColumn`
 * + `AppManagementListRow`（`minHeight = 56.dp`）+ `EmptyStatePlaceholder`；取色全部来自
 * `rememberAppManagementPalette()`（K1 三步：面 token 归属 → 同语义既有实现 → 排除 M3 派生色）。
 *
 * **与 design §10.2(1) 的有意差异（1 处，已登记）**：按书分组头用 **`GroupHeader`** 而非
 * `CollapseSectionHeader` —— 前者才是「按书分组列表」的既有同语义实现（`AllBookmarkScreen` 范式），
 * 且**带组级溢出菜单槽位**（承载「清空本书名场面」）；后者是表单字段分组头（无计数/无菜单槽）。
 * 两者均为已登记组件，**不新建组件**。
 *
 * @param groups             按书聚合分组（由 [SceneBookmarkHelper.groupByBook] 产出）
 * @param bookFilterName     非空 = 「本书名场面」视图（从阅读菜单进入），仅影响标题
 * @param onOpen             点击条目 → 跳回原位置（路由由宿主按 `contentKind` 决定）
 * @param onRegenerate       重新生成 AI 描述
 * @param onDelete           删除单项
 * @param onClearBook        按书清空
 */
@Composable
fun SceneBookmarkScreen(
    groups: List<SceneBookmarkHelper.BookSceneGroup>,
    bookFilterName: String?,
    onBack: () -> Unit,
    onOpen: (SceneBookmark) -> Unit,
    onRegenerate: (SceneBookmark) -> Unit,
    onDelete: (SceneBookmark) -> Unit,
    onClearBook: (SceneBookmarkHelper.BookSceneGroup) -> Unit,
    modifier: Modifier = Modifier
) {
    val palette = rememberAppManagementPalette()
    var collapsedGroups by rememberSaveable { mutableStateOf(emptyList<String>()) }
    val totalCount = groups.sumOf { it.items.size }
    // 注意：LazyListScope 的 content 与 ifBlank/onMenuActions 等 lambda 都**不是** @Composable 上下文
    // ⇒ 文案必须在组合函数体内先取好，不能在那些 lambda 里调 stringResource（编译期即报错）。
    val title = stringResource(
        if (bookFilterName.isNullOrBlank()) {
            R.string.scene_bookmark_library
        } else {
            R.string.scene_bookmark_book
        }
    )
    val untitledLabel = stringResource(R.string.scene_bookmark_untitled)
    val clearBookLabel = stringResource(R.string.scene_bookmark_clear_book)

    AppManagementScaffold(
        title = title,
        selectedCount = 0,
        totalCount = totalCount,
        modifier = modifier,
        palette = palette,
        onBack = onBack
    ) { palette ->
        if (groups.isEmpty()) {
            EmptyStatePlaceholder(
                icon = Icons.Default.Bookmarks,
                title = stringResource(R.string.scene_bookmark_empty_title),
                subtitle = stringResource(R.string.scene_bookmark_empty_subtitle),
                primaryAction = EmptyStateAction(
                    label = stringResource(R.string.scene_bookmark_go_read),
                    onClick = onBack
                ),
                modifier = Modifier.fillMaxSize()
            )
        } else {
            AppManagementLazyColumn(palette = palette) {
                groups.forEach { group ->
                    val groupKey = group.bookUrl.ifBlank { group.bookName }
                    val collapsed = groupKey in collapsedGroups
                    item(key = "header_$groupKey") {
                        GroupHeader(
                            name = group.bookName.ifBlank { untitledLabel },
                            enabledCount = group.items.size,
                            totalCount = group.items.size,
                            collapsed = collapsed,
                            onToggleCollapse = {
                                collapsedGroups = if (collapsed) {
                                    collapsedGroups - groupKey
                                } else {
                                    collapsedGroups + groupKey
                                }
                            },
                            onMenuActions = {
                                listOf(
                                    MenuAction(
                                        icon = Icons.Default.Delete,
                                        title = clearBookLabel,
                                        danger = true,
                                        onClick = { onClearBook(group) }
                                    )
                                )
                            },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    if (!collapsed) {
                        items(group.items, key = { it.id }) { item ->
                            SceneBookmarkRow(
                                bookmark = item,
                                palette = palette,
                                onClick = { onOpen(item) },
                                onLongClick = { onDelete(item) },
                                onRegenerate = { onRegenerate(item) },
                                onDelete = { onDelete(item) }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SceneBookmarkRow(
    bookmark: SceneBookmark,
    palette: AppManagementPalette,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onRegenerate: () -> Unit,
    onDelete: () -> Unit
) {
    AppManagementListRow(
        title = bookmark.desc.ifBlank {
            stringResource(R.string.scene_bookmark_untitled)
        },
        subtitle = sceneSubtitle(bookmark),
        palette = palette,
        minHeight = 56.dp,
        drawPanelImage = false,
        onClick = onClick,
        onLongClick = onLongClick,
        leadingContent = {
            SceneThumbnail(url = SceneBookmarkHelper.imageUrlOf(bookmark.anchor))
        },
        moreActions = listOf(
            AppManagementMenuAction(
                text = stringResource(R.string.scene_bookmark_regen),
                icon = Icons.Default.Refresh,
                onClick = onRegenerate
            ),
            AppManagementMenuAction(
                text = stringResource(R.string.scene_bookmark_delete),
                icon = Icons.Default.Delete,
                danger = true,
                onClick = onDelete
            )
        )
    )
}

/**
 * 副文本 = 章节名 · 原文片段/图片 · 打标时间。
 *
 * `AppManagementListRow` 无独立「行尾时间」槽位（design §10.2(1) 的行内口径）⇒ 时间并入副文本，
 * 与 `RecycleBinScreen` 既有写法（`"${typeText} · ${timeText}"`）同源。
 */
private fun sceneSubtitle(bookmark: SceneBookmark): String {
    val time = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(bookmark.time))
    val kindLabel = when (bookmark.contentKind) {
        SceneBookmarkHelper.KIND_MANGA -> bookmark.chapterName.ifBlank { bookmark.bookName }
        SceneBookmarkHelper.KIND_IMAGE -> bookmark.chapterName.ifBlank { bookmark.bookName }
        else -> bookmark.text.ifBlank { bookmark.chapterName }
    }
    return listOf(kindLabel, time).filter { it.isNotBlank() }.joinToString(" · ")
}

/** 缩略图：仅图片/漫画路径有可加载 URL（文字路径该位留白），圆角 12dp 沿用 `FilletImageView` */
@Composable
private fun SceneThumbnail(url: String?) {
    if (url.isNullOrBlank()) return
    Box(modifier = Modifier.size(44.dp)) {
        AndroidView(
            modifier = Modifier.size(44.dp),
            factory = { context ->
                FilletImageView(context).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    setCornerRadius(12.dpToPx())
                }
            },
            update = { imageView ->
                if (imageView.tag == url) return@AndroidView
                imageView.tag = url
                imageView.setImageDrawable(null)
                ImageLoader.load(imageView.context, url).into(imageView)
            }
        )
    }
}
