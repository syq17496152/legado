package io.legado.app.ui.book.manage.compose

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.R
import io.legado.app.data.entities.Book
import io.legado.app.help.book.isLocal
import io.legado.app.lib.theme.backgroundColor
import io.legado.app.lib.theme.rememberThemeUiPalette
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

/**
 * 书籍整理（书架管理）列表 —— Compose 侧实现（CF 6.2：`item_arrange_book` 退役）。
 *
 * 原实现 = `FastScrollRecyclerView` + `BookAdapter`（inflate `item_arrange_book.xml`）
 * + `ItemTouchHelper`（拖拽换序）+ `DragSelectTouchHelper`（滑选，`activeSlideSelect` 常开）。
 * 宿主 `activity_arrange_book.xml` 已随 CE-b 退役（列表为程序化构造 + `AndroidView` 托管）
 * ⇒ 本例改为 Compose `LazyColumn`，**观感与交互逐项等价**：
 *
 * | 项 | 原 View 实现 | 本实现 |
 * |---|---|---|
 * | 行 | `item_arrange_book.xml`（`ThemeCheckBox` + 书名/作者 + 来源/分组名/「分组」键/「删除」键 + 拖拽手柄） | 同结构的 Compose 行 |
 * | 拖拽换序 | `ItemTouchHelper` + `ItemTouchCallback`（手柄按下即起拖） | `sh.calvin.reorderable`（手柄 `draggableHandle`） |
 * | 换序落库 | `BookAdapter.swap`（**两侧 `order` 相同 ⇒ 整体重排 1..n；否则只互换这两本的 `order`**）+ `onClearView` 落库 | **逐字保留同一规则**（见 onMove），拖拽结束回调宿主落库 |
 * | 滑选 | `DragSelectTouchHelper`（`ToggleAndReverse` + 常开） | 容器 `pointerInput` 长按拖动，**逐项切换一次** |
 * | 行间分隔线 | `VerticalDivider` ItemDecoration（0.5dp） | `HorizontalDivider`（主题 divider 面 token） |
 * | 点击行 | 切换勾选 | 同 |
 * | 点书名 | 按 `AppConfig.openBookInfoByClickTitle` 开关打开详情 | 同 |
 *
 * **状态单源**：选择态由**宿主**持有（`selectedUrls: Set<String>`）。原 `selectedBooks` 在 Adapter 内，
 * 而批量操作（更新/分组/删除/换源）与底栏计数都在宿主读取 ⇒ 提到宿主后不再有两份选择态。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ArrangeBookList(
    books: List<Book>,
    selectedUrls: Set<String>,
    dragMode: Boolean,
    openBookInfoByClickTitle: Boolean,
    groupNameOf: (Book) -> String,
    onToggleSelect: (Book) -> Unit,
    onOpenBook: (Book) -> Unit,
    onDeleteBook: (Book) -> Unit,
    onSelectGroup: (Book) -> Unit,
    onOrderCommitted: (List<Book>) -> Unit,
    listState: LazyListState = rememberLazyListState(),
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val pageColor = Color(context.backgroundColor)
    val dividerColor = Color(rememberThemeUiPalette().dividerColor)
    // 拖拽期间的本地顺序（数据源更新且未拖拽时同步 —— 与原实现「拖拽中不被刷新打断」一致）
    var localBooks by remember { mutableStateOf(books) }
    var dragging by remember { mutableStateOf(false) }
    LaunchedEffect(books, dragMode) {
        if (!dragging) localBooks = books
    }
    val reorderState = rememberReorderableLazyListState(listState) { from, to ->
        val list = localBooks.toMutableList()
        val src = list.getOrNull(from.index)
        val target = list.getOrNull(to.index)
        if (src != null && target != null) {
            // 与原 `BookAdapter.swap` 同规则（逐字保留，避免「整体重排 / 只互换两本」语义漂移）
            if (src.order == target.order) {
                list.forEachIndexed { index, book -> book.order = index + 1 }
            } else {
                val order = src.order
                src.order = target.order
                target.order = order
            }
            list.add(to.index, list.removeAt(from.index))
            localBooks = list
            dragging = true
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(pageColor)
            // 滑选：长按拖动逐项切换（原 DragSelectTouchHelper 常开；行内点击不受影响）
            .pointerInput(localBooks) {
                var lastIndex: Int? = null
                detectDragGesturesAfterLongPress(
                    onDragStart = { offset ->
                        val index = listState.indexOfPoint(offset.y)
                        lastIndex = index
                        index?.let { localBooks.getOrNull(it) }?.let(onToggleSelect)
                    },
                    onDrag = { change, _ ->
                        val index = listState.indexOfPoint(change.position.y)
                        if (index != null && index != lastIndex) {
                            lastIndex = index
                            localBooks.getOrNull(index)?.let(onToggleSelect)
                        }
                    },
                    onDragEnd = { lastIndex = null },
                    onDragCancel = { lastIndex = null }
                )
            }
    ) {
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
            itemsIndexed(localBooks, key = { _, book -> book.bookUrl }) { _, book ->
                if (dragMode) {
                    ReorderableItem(reorderState, key = book.bookUrl) {
                        ArrangeBookRow(
                            book = book,
                            checked = book.bookUrl in selectedUrls,
                            groupName = groupNameOf(book),
                            openBookInfoByClickTitle = openBookInfoByClickTitle,
                            onToggleSelect = { onToggleSelect(book) },
                            onOpenBook = { onOpenBook(book) },
                            onDeleteBook = { onDeleteBook(book) },
                            onSelectGroup = { onSelectGroup(book) },
                            dragHandle = {
                                Icon(
                                    imageVector = Icons.Default.DragHandle,
                                    contentDescription = stringResource(R.string.drag_handle),
                                    tint = colorResource(R.color.secondaryText),
                                    modifier = Modifier
                                        .padding(horizontal = 10.dp)
                                        .draggableHandle(
                                            onDragStopped = {
                                                // 只在**真正发生过换序**时落库（对齐原 `BookAdapter.onClearView`
                                                // 的 `isMoved` 守卫：未移动不写库，避免无谓 IO 与排序模式外的扰动）
                                                if (dragging) {
                                                    dragging = false
                                                    onOrderCommitted(localBooks)
                                                }
                                            }
                                        )
                                )
                            }
                        )
                    }
                } else {
                    ArrangeBookRow(
                        book = book,
                        checked = book.bookUrl in selectedUrls,
                        groupName = groupNameOf(book),
                        openBookInfoByClickTitle = openBookInfoByClickTitle,
                        onToggleSelect = { onToggleSelect(book) },
                        onOpenBook = { onOpenBook(book) },
                        onDeleteBook = { onDeleteBook(book) },
                        onSelectGroup = { onSelectGroup(book) },
                        dragHandle = null
                    )
                }
                HorizontalDivider(color = dividerColor, thickness = 0.5.dp)
            }
        }
    }
}

@Composable
private fun ArrangeBookRow(
    book: Book,
    checked: Boolean,
    groupName: String,
    openBookInfoByClickTitle: Boolean,
    onToggleSelect: () -> Unit,
    onOpenBook: () -> Unit,
    onDeleteBook: () -> Unit,
    onSelectGroup: () -> Unit,
    dragHandle: (@Composable () -> Unit)?
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggleSelect)
            .padding(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = { onToggleSelect() },
            modifier = Modifier.padding(horizontal = 10.dp)
        )
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = book.name,
                    color = colorResource(R.color.primaryText),
                    fontSize = 15.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .padding(3.dp)
                        .clickable(enabled = openBookInfoByClickTitle, onClick = onOpenBook)
                )
                Text(
                    text = book.author,
                    color = colorResource(R.color.tv_text_summary),
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 12.dp, end = 8.dp)
                )
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.End,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
            ) {
                Text(
                    text = if (book.isLocal) stringResource(R.string.local_book) else book.originName,
                    color = colorResource(R.color.tv_text_summary),
                    fontSize = 12.sp,
                    maxLines = 1,
                    modifier = Modifier.padding(3.dp)
                )
                Text(
                    text = groupName,
                    color = colorResource(R.color.secondaryText),
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 8.dp, top = 3.dp, bottom = 3.dp)
                )
                Text(
                    text = stringResource(R.string.group),
                    color = colorResource(R.color.secondaryText),
                    fontSize = 14.sp,
                    maxLines = 1,
                    modifier = Modifier
                        .clickable(onClick = onSelectGroup)
                        .padding(10.dp)
                )
                Text(
                    text = stringResource(R.string.delete),
                    color = colorResource(R.color.secondaryText),
                    fontSize = 14.sp,
                    maxLines = 1,
                    modifier = Modifier
                        .clickable(onClick = onDeleteBook)
                        .padding(10.dp)
                )
                dragHandle?.invoke()
            }
        }
    }
}

private fun LazyListState.indexOfPoint(y: Float): Int? {
    return layoutInfo.visibleItemsInfo.firstOrNull {
        y >= it.offset && y <= it.offset + it.size
    }?.index
}