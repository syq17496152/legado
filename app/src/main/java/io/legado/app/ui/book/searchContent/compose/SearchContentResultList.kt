package io.legado.app.ui.book.searchContent.compose

import android.content.Context
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.PaintDrawable
import android.view.Gravity
import android.widget.TextView
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import io.legado.app.help.config.AppConfig
import io.legado.app.ui.book.searchContent.SearchResult

/**
 * 全文搜索结果列表（CF 6.2：原 `FastScrollRecyclerView` + `SearchContentAdapter` + `item_search_list.xml`
 * 换装为 Compose `LazyColumn`）。
 *
 * 视觉/行为等价口径（逐项对齐原实现）：
 * - 条目：原 `item_search_list.xml` 是「单 `TextView` + `selectableItemBackground` + 12dp 内边距」，
 *   文本为 `SearchResult.getHtmlCompat(...)` 产出的 **`Spanned`**（章节名/命中词走 accent 着色、正文走主文字色；
 *   墨水屏模式转下划线）⇒ Compose 侧用 `AndroidView { TextView }` 承载同一 `Spanned`，
 *   **保证富文本渲染逐 span 等价**（不重写 HTML 解析，避免行内着色/字体差异）；按下态由 Compose
 *   `clickable` 的水波提供（与原 `selectableItemBackground` 同为波纹语义）；
 * - 当前阅读章加粗：原 `convert` 里 `tvSearchResult.paint.isFakeBoldText = isDur` ⇒ 行内同口径设置；
 * - 分割线：原 `VerticalDivider(context)`（`DividerItemDecoration(VERTICAL)`，取主题
 *   `android.R.attr.listDivider`）⇒ Compose `HorizontalDivider`，颜色**运行时解析同一主题属性**（[rememberListDividerColor]）；
 * - 点击：原 `registerListener` 仅在 `item.query` 非空时回调 `openSearchResult(item, layoutPosition)`
 *   ⇒ 行内 `clickable` 同口径（下标即列表位置）。
 *
 * 备注（顺带清理的无效行为，非视觉变更）：原 `initCacheFileNames` / `SAVE_CONTENT` 观察处调用
 * `notifyItemRangeChanged(0, count, true)` / `notifyItemChanged(index, true)` —— 带 `payloads` 会把
 * `convert` 的 `if (payloads.isEmpty())` 分支整段跳过 ⇒ **二者实际不刷新任何内容**（且后者把「章节下标」
 * 当「列表位置」用，属潜在错位）。换装后不再保留这对空转调用。
 */
@Composable
fun SearchContentResultList(
    items: List<SearchResult>,
    listState: LazyListState,
    textColorHex: String,
    accentColorHex: String,
    durChapterIndex: Int,
    onItemClick: (index: Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val dividerColor = rememberListDividerColor()
    LazyColumn(state = listState, modifier = modifier.fillMaxWidth()) {
        itemsIndexed(items) { index, item ->
            AndroidView(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = item.query.isNotBlank()) { onItemClick(index) },
                factory = { ctx ->
                    TextView(ctx).apply {
                        gravity = Gravity.START
                        // 原 XML：条目根即单 TextView，内边距 12dp
                        val pad = dpToPx(ctx, 12)
                        setPadding(pad, pad, pad, pad)
                    }
                },
                update = { tv ->
                    tv.text = item.getHtmlCompat(textColorHex, accentColorHex)
                    tv.paint.isFakeBoldText = item.chapterIndex == durChapterIndex
                }
            )
            if (index != items.lastIndex) {
                HorizontalDivider(thickness = 1.dp, color = dividerColor)
            }
        }
    }
}

/** 运行时解析主题 `android.R.attr.listDivider`（与 `DividerItemDecoration` 同源，保证分割线取色一致）。 */
@Composable
private fun rememberListDividerColor(): Color {
    val context = LocalContext.current
    return remember {
        val attrs = intArrayOf(android.R.attr.listDivider)
        val ta = context.obtainStyledAttributes(attrs)
        val drawable = ta.getDrawable(0)
        ta.recycle()
        val argb = when (drawable) {
            is ColorDrawable -> drawable.color
            is PaintDrawable -> drawable.paint.color
            else -> if (AppConfig.isNightTheme) 0x1FFFFFFF else 0x1F000000
        }
        Color(argb)
    }
}

private fun dpToPx(context: Context, dp: Int): Int =
    (dp * context.resources.displayMetrics.density).toInt()