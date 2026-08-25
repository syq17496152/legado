package io.legado.app.ui.widget.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import io.legado.app.R

/**
 * 设置搜索栏（我的页顶部，Rimchars buildVisibleSections 模式的入口；发现/订阅/书源管理等多页共用）
 *
 * 尺寸说明（2026-08-16 收敛）：原实现用 M3 TextField（minHeight=56dp）+ 外层 8dp 上下 padding，
 * 总高约 72dp 在顶部栏场景偏大。改为 BasicTextField 自定义实现，总高约 44dp（40dp 输入区 + 4dp×2
 * 上下留白），与全局字号/圆角 token（AppShapes.Button）统一；接口签名不变，18 处调用点无需改动。
 * 字号走 MaterialTheme.typography.bodyMedium（14sp，与全局刻度 text_14sp 一致），统一由主题管理。
 */
@Composable
fun SettingsSearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    onSearch: (() -> Unit)? = null,
    focusRequester: FocusRequester? = null
) {
    BasicTextField(
        value = query,
        onValueChange = onQueryChange,
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyMedium.copy(
            color = MaterialTheme.colorScheme.onSurface
        ),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        keyboardOptions = if (onSearch != null) KeyboardOptions(imeAction = ImeAction.Search)
        else KeyboardOptions.Default,
        keyboardActions = if (onSearch != null) KeyboardActions(onSearch = { onSearch() })
        else KeyboardActions.Default,
        decorationBox = { innerTextField ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Search,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(8.dp))
                Box(modifier = Modifier.weight(1f)) {
                    if (query.isEmpty()) {
                        Text(
                            text = placeholder ?: stringResource(R.string.settings_search),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    innerTextField()
                }
            }
        },
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .height(40.dp)
            .background(
                MaterialTheme.colorScheme.surfaceVariant,
                AppShapes.Search
            )
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
    )
}
