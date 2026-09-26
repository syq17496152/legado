package io.legado.app.ui.widget

import android.content.Context
import android.view.ViewGroup
import android.widget.PopupWindow
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.lib.dialogs.SelectItem
import io.legado.app.lib.theme.rememberThemeUiPalette
import io.legado.app.ui.widget.components.AppShapes
import io.legado.app.ui.widget.compose.LegadoComposeTheme
import io.legado.app.ui.widget.compose.rememberAppSettingPalette

/**
 * 轻量文字动作弹窗（CF 6.2：原「`FlexboxLayoutManager` + `RecyclerView` + `item_text.xml`」换装为 Compose）。
 *
 * 视觉/行为等价口径（逐项对齐原实现）：
 * - 容器：`FlexboxLayoutManager(flexDirection=row, flexWrap=wrap)` ⇒ Compose `FlowRow`（行内自动换行）；
 *   底色 `@color/background_card` ⇒ 主题面 token `ThemeUiPalette.cardColor`；圆角 `@dimen/corner_small`(8dp)
 *   ⇒ `AppShapes.Chip`（同 8dp × `UiCorner.scale()`）；内边距 5dp（`popup_action.xml` 一致）。
 * - 条目：单行文本（14sp、单行省略、居中、按下态由 `clickable` 的水波提供），文案取 `SelectItem.title`，
 *   点击回调 `onActionClick(SelectItem.value)`（与原 Adapter 的 `itemView.setOnClickListener` 一致）。
 * - 弹窗本体仍为 `WRAP_CONTENT × WRAP_CONTENT`、`isFocusable = true`、`isOutsideTouchable = false`，
 *   宿主 `ReadBookActivity` 的 `showAtLocation(...)` / `dismiss()` 调用点零改动。
 */
class PopupAction(private val context: Context) :
    PopupWindow(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT) {

    private val composeView = ComposeView(context)
    /** 条目状态（属性名不得与 `setItems` 同名，否则 JVM 签名与 setter 冲突） */
    private var actionItems by mutableStateOf<List<SelectItem<String>>>(emptyList())

    var onActionClick: ((action: String) -> Unit)? = null

    init {
        contentView = composeView

        isTouchable = true
        isOutsideTouchable = false
        isFocusable = true

        composeView.setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
        composeView.setContent {
            LegadoComposeTheme {
                PopupActionContent(
                    items = actionItems,
                    onActionClick = { onActionClick?.invoke(it) }
                )
            }
        }
    }

    fun setItems(items: List<SelectItem<String>>) {
        this.actionItems = items
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PopupActionContent(
    items: List<SelectItem<String>>,
    onActionClick: (String) -> Unit
) {
    val themeUiPalette = rememberThemeUiPalette()
    val palette = rememberAppSettingPalette()
    FlowRow(
        modifier = Modifier
            .clip(AppShapes.Chip)
            .background(Color(themeUiPalette.cardColor))
            .padding(5.dp),
        horizontalArrangement = Arrangement.Center,
        verticalArrangement = Arrangement.Center
    ) {
        items.forEach { item ->
            Text(
                text = item.title,
                color = palette.primaryText,
                fontSize = 14.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .clickable { onActionClick(item.value) }
                    .padding(5.dp)
            )
        }
    }
}