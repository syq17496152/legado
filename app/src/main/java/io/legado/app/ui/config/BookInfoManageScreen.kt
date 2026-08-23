package io.legado.app.ui.config

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.R
import io.legado.app.help.config.BookInfoComponentItem
import io.legado.app.help.config.BookInfoPageStyle
import io.legado.app.ui.widget.compose.AppManagementCard
import io.legado.app.ui.widget.compose.AppListSpacing
import io.legado.app.ui.widget.compose.AppManagementPalette
import io.legado.app.ui.widget.compose.LegadoMiuixActionButton
import io.legado.app.ui.widget.compose.LegadoMiuixSwitch
import io.legado.app.ui.widget.compose.rememberAppManagementPalette

@Composable
internal fun BookInfoManageScreen(
    style: BookInfoPageStyle,
    components: List<BookInfoComponentItem>,
    onStyleChanged: (BookInfoPageStyle) -> Unit,
    onComponentToggle: (Int, Boolean) -> Unit,
    onReset: () -> Unit,
    onMoveItem: (Int, Int) -> Unit
) {
    val palette = rememberAppManagementPalette()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(palette.settings.page)
    ) {
        // Tab bar
        StyleTabBar(
            style = style,
            palette = palette,
            onStyleChanged = onStyleChanged
        )

        Spacer(modifier = Modifier.height(10.dp))

        // Summary text
        val summaryText = when (style) {
            BookInfoPageStyle.CLASSIC -> stringResource(R.string.book_info_components_hint)
            BookInfoPageStyle.IMMERSIVE_COMPOSE -> stringResource(R.string.book_info_style_immersive_hint)
        }
        Text(
            text = summaryText,
            color = palette.settings.secondaryText,
            fontSize = 13.sp,
            modifier = Modifier.padding(horizontal = 18.dp),
            maxLines = 3,
            overflow = TextOverflow.Ellipsis
        )

        Spacer(modifier = Modifier.height(8.dp))

        when (style) {
            BookInfoPageStyle.CLASSIC -> {
                // Component list
                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentPadding = PaddingValues(vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(AppListSpacing.Normal)
                ) {
                    itemsIndexed(
                        items = components,
                        key = { _, item -> item.type.name }
                    ) { index, item ->
                        ComponentItemRow(
                            item = item,
                            palette = palette,
                            onCheckedChange = { checked ->
                                onComponentToggle(index, checked)
                            }
                        )
                    }
                }

                // Reset button
                LegadoMiuixActionButton(
                    text = stringResource(R.string.reset),
                    palette = palette.miuix,
                    onClick = onReset,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 16.dp)
                )
            }

            BookInfoPageStyle.IMMERSIVE_COMPOSE -> {
                // Immersive info panel
                AppManagementCard(
                    palette = palette,
                    modifier = Modifier
                        .fillMaxWidth(),
                    insidePadding = PaddingValues(16.dp)
                ) {
                    Text(
                        text = stringResource(R.string.book_info_style_immersive_title),
                        color = palette.settings.primaryText,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = stringResource(R.string.book_info_style_immersive_desc),
                        color = palette.settings.secondaryText,
                        fontSize = 13.5.sp,
                        lineHeight = 20.sp,
                        maxLines = 6,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Spacer(modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun StyleTabBar(
    style: BookInfoPageStyle,
    palette: AppManagementPalette,
    onStyleChanged: (BookInfoPageStyle) -> Unit
) {
    val isClassic = style == BookInfoPageStyle.CLASSIC
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        StyleTabButton(
            text = stringResource(R.string.book_info_style_classic),
            selected = isClassic,
            palette = palette,
            onClick = { onStyleChanged(BookInfoPageStyle.CLASSIC) },
            modifier = Modifier.weight(1f)
        )
        StyleTabButton(
            text = stringResource(R.string.book_info_style_immersive),
            selected = !isClassic,
            palette = palette,
            onClick = { onStyleChanged(BookInfoPageStyle.IMMERSIVE_COMPOSE) },
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun StyleTabButton(
    text: String,
    selected: Boolean,
    palette: AppManagementPalette,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val backgroundColor = if (selected) {
        palette.settings.accent.copy(alpha = 0.14f)
    } else {
        palette.miuix.surfaceVariant
    }
    val textColor = if (selected) palette.settings.accent else palette.settings.secondaryText

    Box(
        modifier = modifier
            .height(42.dp)
            .background(backgroundColor, RoundedCornerShape(palette.miuix.actionRadius ?: 12.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = textColor,
            fontSize = 14.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun ComponentItemRow(
    item: BookInfoComponentItem,
    palette: AppManagementPalette,
    onCheckedChange: (Boolean) -> Unit
) {
    AppManagementCard(
        palette = palette,
        modifier = Modifier.fillMaxWidth(),
        insidePadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            LegadoMiuixSwitch(
                checked = item.enabled,
                onCheckedChange = onCheckedChange,
                palette = palette.miuix
            )

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(item.type.titleRes),
                    color = palette.settings.primaryText,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = stringResource(item.type.hintRes),
                    color = palette.settings.secondaryText,
                    fontSize = 12.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            Icon(
                painter = painterResource(R.drawable.ic_arrange),
                contentDescription = stringResource(R.string.read_record_drag_sort),
                tint = palette.settings.secondaryText.copy(alpha = 0.6f),
                modifier = Modifier.size(24.dp)
            )
        }
    }
}
