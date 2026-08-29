package io.legado.app.ui.config

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.R
import io.legado.app.ui.main.ai.AiImageProviderConfig
import io.legado.app.ui.widget.components.GlassTopAppBar
import io.legado.app.ui.widget.compose.AppManagementCard
import io.legado.app.ui.widget.compose.AppListSpacing
import io.legado.app.ui.widget.compose.AppManagementMenuAction
import io.legado.app.ui.widget.compose.AppManagementMoreActionButton
import io.legado.app.ui.widget.compose.AppManagementPalette
import io.legado.app.ui.widget.compose.LegadoMiuixActionButton
import io.legado.app.ui.widget.compose.rememberAppManagementPalette
import androidx.compose.material3.MaterialTheme
import io.legado.app.ui.theme.bodyTertiary
import io.legado.app.ui.theme.bodySecondary

@Composable
internal fun AiImageProviderManageScreen(
    providers: List<AiImageProviderConfig>,
    currentProviderId: String,
    onBack: () -> Unit,
    onImportRules: () -> Unit,
    onExportRules: () -> Unit,
    onAdd: () -> Unit,
    onOpenProvider: (AiImageProviderConfig) -> Unit,
    providerActions: (AiImageProviderConfig) -> List<AppManagementMenuAction>
) {
    val palette = rememberAppManagementPalette()
    CompositionLocalProvider(
        LocalTextStyle provides LocalTextStyle.current.copy(fontFamily = palette.settings.bodyFontFamily)
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = palette.settings.page,
            contentColor = palette.settings.primaryText
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    // H15：顶栏换 GlassTopAppBar（自带状态栏 inset），父容器移除重复 statusBarsPadding
                    .navigationBarsPadding()
            ) {
                AiImageProviderTopBar(
                    onBack = onBack,
                    onImportRules = onImportRules,
                    onExportRules = onExportRules
                )
                Text(
                    text = stringResource(R.string.ai_image_provider_manage_summary),
                    color = palette.settings.secondaryText,
                    fontSize = MaterialTheme.typography.bodyTertiary.fontSize,
                    lineHeight = 18.sp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, top = 6.dp, end = 16.dp)
                )
                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentPadding = PaddingValues(vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(AppListSpacing.Normal)
                ) {
                    if (providers.isEmpty()) {
                        item {
                            AiImageProviderEmptyCard()
                        }
                    } else {
                        items(providers, key = { it.id }) { provider ->
                            AiImageProviderCard(
                                provider = provider,
                                current = provider.id == currentProviderId,
                                onClick = { onOpenProvider(provider) },
                                moreActions = providerActions(provider)
                            )
                        }
                    }
                }
                LegadoMiuixActionButton(
                    text = stringResource(R.string.add),
                    palette = palette.miuix,
                    onClick = onAdd,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    primary = true,
                    cornerRadius = palette.miuix.actionRadius,
                    minHeight = 46.dp
                )
            }
        }
    }
}

@Composable
private fun AiImageProviderTopBar(
    onBack: () -> Unit,
    onImportRules: () -> Unit,
    onExportRules: () -> Unit
) {
    // H15（2026-08-28）：自绘 54dp 透明顶栏 → GlassTopAppBar，导入/导出动作迁 actions 槽
    GlassTopAppBar(
        title = stringResource(R.string.ai_image_provider_manage),
        navIcon = Icons.AutoMirrored.Filled.ArrowBack,
        onNavClick = onBack,
        actions = {
            IconButton(onClick = onImportRules) {
                Icon(
                    painter = painterResource(R.drawable.ic_import),
                    contentDescription = stringResource(R.string.import_str)
                )
            }
            IconButton(onClick = onExportRules) {
                Icon(
                    painter = painterResource(R.drawable.ic_export),
                    contentDescription = stringResource(R.string.export_str)
                )
            }
        }
    )
}

@Composable
private fun AiImageProviderCard(
    provider: AiImageProviderConfig,
    current: Boolean,
    onClick: () -> Unit,
    moreActions: List<AppManagementMenuAction>
) {
    val palette = rememberAppManagementPalette()
    AppManagementCard(
        palette = palette,
        modifier = Modifier
            .fillMaxWidth(),
        onClick = onClick,
        insidePadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = provider.displayName(),
                        color = palette.settings.primaryText,
                        fontSize = MaterialTheme.typography.bodyLarge.fontSize,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    if (current) {
                        AiImageProviderCurrentBadge(palette)
                    }
                }
                Spacer(modifier = Modifier.height(5.dp))
                Text(
                    text = if (provider.type == AiImageProviderConfig.TYPE_OPENAI) {
                        provider.baseUrl.ifBlank { "OpenAI" }
                    } else {
                        stringResource(R.string.ai_image_provider_js)
                    },
                    color = palette.settings.secondaryText,
                    fontSize = MaterialTheme.typography.bodyTertiary.fontSize,
                    maxLines = 1,
                    overflow = TextOverflow.MiddleEllipsis
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = provider.model.ifBlank {
                        if (provider.type == AiImageProviderConfig.TYPE_OPENAI) "gpt-image-1" else "JS"
                    },
                    color = palette.settings.secondaryText,
                    fontSize = MaterialTheme.typography.bodyTertiary.fontSize,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = buildString {
                        if (current) append(stringResource(R.string.ai_current_provider)).append(" · ")
                        append(stringResource(if (provider.enabled) R.string.enabled else R.string.disabled))
                    },
                    color = if (current) palette.settings.accent else palette.settings.secondaryText,
                    fontSize = MaterialTheme.typography.bodySmall.fontSize,
                    fontWeight = if (current) FontWeight.Medium else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(modifier = Modifier.width(10.dp))
            AppManagementMoreActionButton(
                actionsProvider = { moreActions },
                palette = palette,
                contentDescription = stringResource(R.string.more)
            )
        }
    }
}

@Composable
private fun AiImageProviderCurrentBadge(palette: AppManagementPalette) {
    Surface(
        shape = RoundedCornerShape(palette.miuix.actionRadius ?: 12.dp),
        color = palette.settings.accent.copy(alpha = 0.14f),
        contentColor = palette.settings.accent,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp
    ) {
        Text(
            text = stringResource(R.string.ai_current_provider),
            color = palette.settings.accent,
            fontSize = MaterialTheme.typography.labelSmall.fontSize,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
        )
    }
}

@Composable
private fun AiImageProviderEmptyCard() {
    val palette = rememberAppManagementPalette()
    AppManagementCard(
        palette = palette,
        modifier = Modifier.fillMaxWidth(),
        insidePadding = PaddingValues(horizontal = 14.dp, vertical = 18.dp)
    ) {
        Text(
            text = stringResource(R.string.ai_image_provider_manage),
            color = palette.settings.primaryText,
            fontSize = MaterialTheme.typography.bodySecondary.fontSize,
            fontWeight = FontWeight.Medium
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = stringResource(R.string.ai_image_provider_manage_summary),
            color = palette.settings.secondaryText,
            fontSize = MaterialTheme.typography.bodyTertiary.fontSize,
            lineHeight = 18.sp
        )
    }
}
