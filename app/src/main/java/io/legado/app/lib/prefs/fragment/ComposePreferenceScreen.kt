package io.legado.app.lib.prefs.fragment

import android.graphics.Rect
import android.graphics.drawable.Drawable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceGroup
import androidx.preference.SwitchPreferenceCompat
import io.legado.app.lib.prefs.ColorPreference
import io.legado.app.lib.prefs.IconListPreference
import io.legado.app.lib.prefs.SeekBarPreference
import io.legado.app.lib.prefs.Preference as LegadoPreference
import io.legado.app.lib.prefs.SwitchPreference as LegadoSwitchPreference
import io.legado.app.lib.theme.UiCorner
import io.legado.app.lib.theme.composeActionRadius
import io.legado.app.ui.widget.compose.AppSettingPalette
import io.legado.app.ui.widget.compose.AppSettingSectionTitle
import io.legado.app.ui.widget.compose.AppThemedStepperSlider
import io.legado.app.ui.widget.compose.LegadoMiuixPalette
import io.legado.app.ui.widget.compose.LegadoMiuixSwitch
import io.legado.app.ui.widget.compose.appSettingPanelBackground
import io.legado.app.ui.widget.compose.appSettingRowDecoration
import io.legado.app.ui.widget.compose.rememberAppDialogStyle
import io.legado.app.ui.widget.compose.rememberAppSettingPalette
import io.legado.app.ui.widget.compose.toMiuixPalette

private val PanelHorizontalPadding = 12.dp

private data class ComposePreferenceSection(
    val title: CharSequence?,
    val rows: List<Preference>
)

private fun ComposePreferenceSection.stableKey(index: Int): String {
    return "$index:${title?.toString().orEmpty()}:${rows.firstOrNull()?.key.orEmpty()}"
}

@Composable
internal fun ComposePreferenceScreen(
    root: PreferenceGroup?,
    refreshTick: Int,
    onPreferenceClick: (Preference) -> Unit,
    onSwitchChange: (SwitchPreferenceCompat, Boolean) -> Unit,
    onSeekChange: (SeekBarPreference, Int) -> Unit,
    scrollTargetKey: String?,
    onScrollTargetConsumed: () -> Unit
) {
    val colors = rememberAppSettingPalette()
    val switchPalette = rememberAppDialogStyle().toMiuixPalette()
    val panelRadiusPx = colors.panelRadiusPx
    val sections = remember(root, refreshTick) {
        root?.toComposeSections().orEmpty()
    }
    val listState = rememberLazyListState()
    LaunchedEffect(scrollTargetKey, sections) {
        val targetKey = scrollTargetKey?.takeIf { it.isNotBlank() } ?: return@LaunchedEffect
        val sectionIndex = sections.indexOfFirst { section ->
            section.rows.any { it.key == targetKey }
        }
        if (sectionIndex >= 0) {
            listState.animateScrollToItem(sectionIndex)
        } else {
            onScrollTargetConsumed()
        }
    }
    CompositionLocalProvider(
        LocalTextStyle provides LocalTextStyle.current.copy(fontFamily = colors.bodyFontFamily)
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .background(colors.page)
                .navigationBarsPadding(),
            contentPadding = PaddingValues(
                top = 8.dp,
                bottom = 24.dp
            ),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            itemsIndexed(
                sections,
                key = { index, section -> section.stableKey(index) }
            ) { _, section ->
                PreferenceSectionPanel(
                    section = section,
                    colors = colors,
                    switchPalette = switchPalette,
                    panelRadiusPx = panelRadiusPx,
                    onPreferenceClick = onPreferenceClick,
                    onSwitchChange = onSwitchChange,
                    onSeekChange = onSeekChange,
                    scrollTargetKey = scrollTargetKey,
                    onScrollTargetConsumed = onScrollTargetConsumed
                )
            }
        }
    }
}

@Composable
private fun PreferenceSectionPanel(
    section: ComposePreferenceSection,
    colors: AppSettingPalette,
    switchPalette: LegadoMiuixPalette,
    panelRadiusPx: Float,
    onPreferenceClick: (Preference) -> Unit,
    onSwitchChange: (SwitchPreferenceCompat, Boolean) -> Unit,
    onSeekChange: (SeekBarPreference, Int) -> Unit,
    scrollTargetKey: String?,
    onScrollTargetConsumed: () -> Unit
) {
    val context = LocalContext.current
    val panelImage = remember(context, panelRadiusPx, colors.themeSignature) {
        UiCorner.panelImageDrawable(context, panelRadiusPx)
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = PanelHorizontalPadding)
            .appSettingPanelBackground(
                normalColor = colors.row,
                panelImage = panelImage,
                borderColor = colors.border,
                radiusPx = panelRadiusPx
            )
    ) {
        AppSettingSectionTitle(title = section.title, palette = colors)
        section.rows.forEachIndexed { index, preference ->
            PreferenceRow(
                preference = preference,
                colors = colors,
                switchPalette = switchPalette,
                panelRadiusPx = panelRadiusPx,
                isLast = index == section.rows.lastIndex,
                showDivider = index != section.rows.lastIndex,
                onPreferenceClick = onPreferenceClick,
                onSwitchChange = onSwitchChange,
                onSeekChange = onSeekChange,
                scrollTargetKey = scrollTargetKey,
                onScrollTargetConsumed = onScrollTargetConsumed
            )
        }
    }
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun PreferenceRow(
    preference: Preference,
    colors: AppSettingPalette,
    switchPalette: LegadoMiuixPalette,
    panelRadiusPx: Float,
    isLast: Boolean,
    showDivider: Boolean,
    onPreferenceClick: (Preference) -> Unit,
    onSwitchChange: (SwitchPreferenceCompat, Boolean) -> Unit,
    onSeekChange: (SeekBarPreference, Int) -> Unit,
    scrollTargetKey: String?,
    onScrollTargetConsumed: () -> Unit
) {
    val bringIntoViewRequester = remember { BringIntoViewRequester() }
    LaunchedEffect(scrollTargetKey, preference.key) {
        if (!scrollTargetKey.isNullOrBlank() && preference.key == scrollTargetKey) {
            bringIntoViewRequester.bringIntoView()
            onScrollTargetConsumed()
        }
    }
    if (preference is SeekBarPreference) {
        SeekBarPreferenceRow(
            preference = preference,
            colors = colors,
            panelRadiusPx = panelRadiusPx,
            isLast = isLast,
            showDivider = showDivider,
            onSeekChange = onSeekChange,
            modifier = Modifier.bringIntoViewRequester(bringIntoViewRequester)
        )
        return
    }
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val enabled = preference.isEnabled
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .bringIntoViewRequester(bringIntoViewRequester)
            .defaultMinSize(minHeight = 60.dp)
            .appSettingRowDecoration(
                pressed = pressed,
                pressedColor = colors.rowPressed,
                dividerColor = colors.divider,
                showDivider = showDivider,
                radiusPx = panelRadiusPx,
                isLast = isLast
            )
            .combinedClickable(
                enabled = enabled && preference.isSelectable,
                interactionSource = interactionSource,
                indication = null,
                onClick = { onPreferenceClick(preference) },
                onLongClick = preference.composeLongClickHandler()
            )
            .padding(horizontal = 16.dp, vertical = 10.dp)
    ) {
        PreferenceText(
            preference = preference,
            colors = colors,
            enabled = enabled,
            modifier = Modifier.weight(1f)
        )
        PreferenceWidget(
            preference = preference,
            colors = colors,
            switchPalette = switchPalette,
            enabled = enabled,
            onSwitchChange = onSwitchChange
        )
    }
}

private fun Preference.composeLongClickHandler(): (() -> Unit)? {
    return when (this) {
        is LegadoPreference -> if (hasLongClickListener()) {
            { performLongClick() }
        } else {
            null
        }

        is LegadoSwitchPreference -> if (hasLongClickListener()) {
            { performLongClick() }
        } else {
            null
        }

        else -> null
    }
}

@Composable
private fun PreferenceText(
    preference: Preference,
    colors: AppSettingPalette,
    enabled: Boolean,
    modifier: Modifier = Modifier
) {
    val titleColor = if (enabled) colors.primaryText else colors.disabledText
    val summaryColor = if (enabled) colors.secondaryText else colors.disabledText
    Column(modifier = modifier) {
        preference.title?.takeIf { it.isNotBlank() }?.let {
            Text(
                text = it.toString(),
                color = titleColor,
                fontSize = 16.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        preference.summary?.takeIf { it.isNotBlank() }?.let {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = it.toString(),
                color = summaryColor,
                fontSize = 14.sp,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun PreferenceWidget(
    preference: Preference,
    colors: AppSettingPalette,
    switchPalette: LegadoMiuixPalette,
    enabled: Boolean,
    onSwitchChange: (SwitchPreferenceCompat, Boolean) -> Unit
) {
    val actionRadius = LocalContext.current.composeActionRadius()
    when (preference) {
        is SwitchPreferenceCompat -> {
            Spacer(modifier = Modifier.width(12.dp))
            LegadoMiuixSwitch(
                checked = preference.isChecked,
                enabled = enabled,
                onCheckedChange = { onSwitchChange(preference, it) },
                palette = switchPalette
            )
        }

        is IconListPreference -> {
            preference.selectedIconDrawable()?.let { drawable ->
                Spacer(modifier = Modifier.width(12.dp))
                DrawablePreview(
                    drawable = drawable,
                    enabled = enabled,
                    modifier = Modifier.size(42.dp)
                )
            }
        }

        is androidx.preference.ListPreference -> {
            val entry = preference.entry?.toString().orEmpty()
            if (entry.isNotBlank()) {
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = entry,
                    color = if (enabled) colors.accent else colors.disabledText,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .clip(RoundedCornerShape(actionRadius))
                        .background(colors.accent.copy(alpha = if (enabled) 0.10f else 0.05f))
                        .padding(horizontal = 10.dp, vertical = 5.dp)
                )
            }
        }

        is ColorPreference -> {
            Spacer(modifier = Modifier.width(12.dp))
            val color = preference.sharedPreferences
                ?.getInt(preference.key, colors.accent.toArgb())
                ?: colors.accent.toArgb()
            Box(
                modifier = Modifier
                    .size(24.dp)
                    .clip(CircleShape)
                    .background(Color(color))
            )
        }
    }
}

@Composable
private fun DrawablePreview(
    drawable: Drawable,
    enabled: Boolean,
    modifier: Modifier = Modifier
) {
    val alpha = if (enabled) 1f else 0.42f
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .drawWithCache {
                onDrawBehind {
                    drawIntoCanvas { canvas ->
                        drawable.alpha = (alpha * 255).toInt().coerceIn(0, 255)
                        drawable.bounds = Rect(0, 0, size.width.toInt(), size.height.toInt())
                        drawable.draw(canvas.nativeCanvas)
                    }
                }
            }
    )
}

@Composable
private fun SeekBarPreferenceRow(
    preference: SeekBarPreference,
    colors: AppSettingPalette,
    panelRadiusPx: Float,
    isLast: Boolean,
    showDivider: Boolean,
    onSeekChange: (SeekBarPreference, Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val enabled = preference.isEnabled
    var sliderValue by remember(preference.key, preference.value) {
        mutableIntStateOf(preference.value.coerceIn(preference.minValue, preference.maxValue))
    }
    val sliderPalette = remember(colors) {
        LegadoMiuixPalette(
            accent = colors.accent,
            surface = Color(colors.row),
            surfaceVariant = colors.secondaryText.copy(alpha = 0.12f),
            primaryText = colors.primaryText,
            secondaryText = colors.secondaryText,
            danger = colors.danger,
            onAccent = colors.onAccent
        )
    }
    fun commitValue(value: Int) {
        val nextValue = value.coerceIn(preference.minValue, preference.maxValue)
        if (sliderValue != nextValue) {
            sliderValue = nextValue
            onSeekChange(preference, nextValue)
        }
    }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 72.dp)
            .appSettingRowDecoration(
                pressed = false,
                pressedColor = colors.rowPressed,
                dividerColor = colors.divider,
                showDivider = showDivider,
                radiusPx = panelRadiusPx,
                isLast = isLast
            )
            .padding(horizontal = 16.dp, vertical = 10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            PreferenceText(
                preference = preference,
                colors = colors,
                enabled = enabled,
                modifier = Modifier.weight(1f)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = sliderValue.toString(),
                color = if (enabled) colors.accent else colors.disabledText,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        AppThemedStepperSlider(
            value = sliderValue,
            range = preference.minValue..preference.maxValue,
            enabled = enabled,
            onValueChange = { commitValue(it) },
            palette = sliderPalette,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

private fun PreferenceGroup.toComposeSections(): List<ComposePreferenceSection> {
    val sections = mutableListOf<ComposePreferenceSection>()
    val pendingRows = mutableListOf<Preference>()
    for (index in 0 until preferenceCount) {
        val preference = getPreference(index)
        if (!preference.isVisible) continue
        if (preference is PreferenceCategory) {
            if (pendingRows.isNotEmpty()) {
                sections += ComposePreferenceSection(null, pendingRows.toList())
                pendingRows.clear()
            }
            val rows = preference.visibleRows()
            if (rows.isNotEmpty()) {
                sections += ComposePreferenceSection(preference.title, rows)
            }
        } else if (preference is PreferenceGroup) {
            val rows = preference.visibleRows()
            if (rows.isNotEmpty()) {
                sections += ComposePreferenceSection(preference.title, rows)
            }
        } else {
            pendingRows += preference
        }
    }
    if (pendingRows.isNotEmpty()) {
        sections += ComposePreferenceSection(null, pendingRows.toList())
    }
    return sections
}

private fun PreferenceGroup.visibleRows(): List<Preference> {
    val rows = mutableListOf<Preference>()
    for (index in 0 until preferenceCount) {
        val preference = getPreference(index)
        if (!preference.isVisible) continue
        if (preference is PreferenceCategory || preference is PreferenceGroup) {
            rows += preference.visibleRows()
        } else {
            rows += preference
        }
    }
    return rows
}
