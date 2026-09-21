package io.legado.app.ui.config

import android.content.Intent
import android.os.Bundle
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.R
import io.legado.app.base.BaseActivity
import io.legado.app.constant.EventBus
import io.legado.app.databinding.ActivityAiProviderManageBinding
import io.legado.app.help.config.AppConfig
import io.legado.app.ui.main.ai.AiProviderConfig
import io.legado.app.ui.widget.components.EmptyStateAction
import io.legado.app.ui.widget.components.EmptyStatePlaceholder
import io.legado.app.ui.widget.components.SettingsSearchBar
import io.legado.app.ui.widget.compose.AppManagementCard
import io.legado.app.ui.widget.compose.AppListSpacing
import io.legado.app.ui.widget.compose.AppManagementMenuAction
import io.legado.app.ui.widget.compose.AppManagementMoreActionButton
import io.legado.app.ui.widget.compose.AppManagementPalette
import io.legado.app.ui.widget.compose.AppManagementScaffold
import io.legado.app.ui.widget.compose.LegadoMiuixActionButton
import io.legado.app.ui.widget.compose.rememberAppManagementPalette
import io.legado.app.ui.widget.compose.showComposeConfirmDialog
import io.legado.app.utils.postEvent
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding
import androidx.compose.material3.MaterialTheme
import io.legado.app.ui.theme.bodyTertiary
import io.legado.app.ui.theme.bodySecondary

class AiProviderManageActivity : BaseActivity<ActivityAiProviderManageBinding>() {

    override val binding by viewBinding(ActivityAiProviderManageBinding::inflate)
    private var providersState by mutableStateOf<List<AiProviderConfig>>(emptyList())
    private var modelCountsState by mutableStateOf<Map<String, Int>>(emptyMap())
    private var currentProviderIdState by mutableStateOf<String?>(null)

    // ui-theme-governance-polish P6：管理族宿主接入背景透明度（1.5 封闭清单成员）
    override fun manageBackgroundAlphaEnabled(): Boolean = true

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        binding.composeRoot.setViewCompositionStrategy(
            ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed
        )
        binding.composeRoot.setContent {
            AiProviderManageScreen(
                providers = providersState,
                modelCounts = modelCountsState,
                currentProviderId = currentProviderIdState,
                onBack = { finish() },
                onAdd = { openEdit(null) },
                onOpenProvider = { openEdit(it) },
                onOpenProviderModels = { openEdit(it, openModels = true) },
                providerActions = ::providerActions
            )
        }
    }

    override fun onResume() {
        super.onResume()
        reload()
    }

    private fun reload() {
        providersState = AppConfig.aiProviderList
        currentProviderIdState = AppConfig.aiCurrentProviderId
        modelCountsState = AppConfig.aiProviderList.associate { provider ->
            provider.id to AppConfig.aiModelConfigList.count { it.providerId == provider.id }
        }
    }

    private fun openEdit(provider: AiProviderConfig?, openModels: Boolean = false) {
        startActivity(Intent(this, AiProviderEditActivity::class.java).apply {
            provider?.id?.let { putExtra(AiProviderEditActivity.EXTRA_PROVIDER_ID, it) }
            if (openModels) putExtra(AiProviderEditActivity.EXTRA_TAB_MODEL, true)
        })
    }

    private fun providerActions(provider: AiProviderConfig): List<AppManagementMenuAction> {
        return listOf(
            AppManagementMenuAction(getString(R.string.edit)) {
                openEdit(provider)
            },
            AppManagementMenuAction(
                text = getString(R.string.delete),
                danger = true,
                onClick = { confirmRemoveProvider(provider) }
            )
        )
    }

    private fun providerName(provider: AiProviderConfig): String {
        return provider.name.ifBlank {
            provider.baseUrl.ifBlank { getString(R.string.ai_provider) }
        }
    }

    private fun confirmRemoveProvider(provider: AiProviderConfig) {
        val relatedModelCount = AppConfig.aiModelConfigList.count { it.providerId == provider.id }
        showComposeConfirmDialog(
            title = providerName(provider),
            message = getString(
                if (relatedModelCount > 0) R.string.ai_remove_provider_confirm_with_models
                else R.string.ai_remove_provider_confirm,
                relatedModelCount
            ),
            positiveText = getString(R.string.delete),
            negativeText = getString(R.string.cancel),
            dangerPositive = true,
            onPositive = {
                AppConfig.aiProviderList = AppConfig.aiProviderList.filterNot { it.id == provider.id }
                notifyAiConfigChanged()
                reload()
                toastOnUi(R.string.ai_provider_removed)
            }
        )
    }

    private fun notifyAiConfigChanged() {
        postEvent(EventBus.AI_CONFIG_CHANGED, true)
    }
}

@Composable
private fun AiProviderManageScreen(
    providers: List<AiProviderConfig>,
    modelCounts: Map<String, Int>,
    currentProviderId: String?,
    onBack: () -> Unit,
    onAdd: () -> Unit,
    onOpenProvider: (AiProviderConfig) -> Unit,
    onOpenProviderModels: (AiProviderConfig) -> Unit,
    providerActions: (AiProviderConfig) -> List<AppManagementMenuAction>
) {
    val palette = rememberAppManagementPalette()
    // F91：页内检索（名称 / Base URL 双向匹配，纯内存过滤，零新数据源）
    var query by remember { mutableStateOf("") }
    val keyword = query.trim()
    val visibleProviders = remember(providers, keyword) {
        if (keyword.isEmpty()) {
            providers
        } else {
            providers.filter {
                it.name.contains(keyword, ignoreCase = true) ||
                    it.baseUrl.contains(keyword, ignoreCase = true)
            }
        }
    }
    // F90：页首统计摘要——数据全部来自 reload() 已拉取的 modelCounts map，零新查询。
    // 口径：「可用」= 至少配置 1 个模型的提供商（未配模型的提供商无任何 AI 功能可选中）。
    val usableCount = providers.count { (modelCounts[it.id] ?: 0) > 0 }
    val modelTotal = modelCounts.values.sum()
    val currentName = providers.firstOrNull { it.id == currentProviderId }?.name
    val managementTitle = stringResource(R.string.ai_provider_manage_title)
    CompositionLocalProvider(
        LocalTextStyle provides LocalTextStyle.current.copy(fontFamily = palette.settings.bodyFontFamily)
    ) {
        // followup F5：统一管理族壳（AppManagementScaffold 平移，删页内自绘 GlassTopAppBar 顶栏与根 Surface）
        AppManagementScaffold(
            title = managementTitle,
            selectedCount = 0,
            totalCount = providers.size,
            palette = palette,
            onBack = onBack
        ) { _ ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .navigationBarsPadding()
            ) {
                AiProviderStatsRow(
                    usableCount = usableCount,
                    providerCount = providers.size,
                    modelTotal = modelTotal,
                    currentName = currentName,
                    palette = palette
                )
                SettingsSearchBar(
                    query = query,
                    onQueryChange = { query = it },
                    placeholder = stringResource(R.string.ai_provider_search_hint)
                )
                Text(
                    text = stringResource(R.string.ai_provider_manage_summary),
                    color = palette.settings.secondaryText,
                    fontSize = MaterialTheme.typography.bodyTertiary.fontSize,
                    lineHeight = 18.sp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, top = 2.dp, end = 16.dp)
                )
                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentPadding = PaddingValues(vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(AppListSpacing.Normal)
                ) {
                    when {
                        providers.isEmpty() -> item {
                            AiProviderEmptyCard()
                        }
                        // F91：空结果闭环——搜完一片白会制造新问题，给出覆盖范围说明 + 清除搜索出路
                        visibleProviders.isEmpty() -> item {
                            EmptyStatePlaceholder(
                                icon = Icons.Default.SearchOff,
                                title = stringResource(
                                    R.string.ai_provider_search_empty_title,
                                    keyword
                                ),
                                subtitle = stringResource(R.string.ai_provider_search_empty_hint),
                                primaryAction = EmptyStateAction(
                                    label = stringResource(
                                        R.string.ai_provider_search_empty_clear,
                                        providers.size
                                    ),
                                    onClick = { query = "" }
                                )
                            )
                        }
                        else -> items(visibleProviders, key = { it.id }) { provider ->
                            AiProviderCard(
                                provider = provider,
                                modelCount = modelCounts[provider.id] ?: 0,
                                current = provider.id == currentProviderId,
                                onClick = { onOpenProvider(provider) },
                                onOpenModels = { onOpenProviderModels(provider) },
                                moreActions = providerActions(provider)
                            )
                        }
                    }
                }
                LegadoMiuixActionButton(
                    text = stringResource(R.string.ai_add_provider),
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

/**
 * F90 页首统计摘要条（三格）：可用 / 模型总数 / 当前使用。
 * 与卡片内条目级状态形成「总-分」结构，避免逐卡扫读后心算。
 * 「当前使用」格按克制原则暂不做点击跳转（蓝图待审项）。
 */
@Composable
private fun AiProviderStatsRow(
    usableCount: Int,
    providerCount: Int,
    modelTotal: Int,
    currentName: String?,
    palette: AppManagementPalette
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, top = 6.dp, end = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        AiProviderStatCell(
            value = "$usableCount / $providerCount",
            label = stringResource(R.string.ai_provider_stat_usable),
            palette = palette,
            accent = true,
            modifier = Modifier.weight(1f)
        )
        AiProviderStatCell(
            value = modelTotal.toString(),
            label = stringResource(R.string.ai_provider_stat_models),
            palette = palette,
            modifier = Modifier.weight(1f)
        )
        AiProviderStatCell(
            value = currentName ?: stringResource(R.string.ai_provider_stat_current_none),
            label = stringResource(R.string.ai_provider_stat_current),
            palette = palette,
            compactValue = true,
            accent = currentName != null,
            modifier = Modifier.weight(1.4f)
        )
    }
}

@Composable
private fun AiProviderStatCell(
    value: String,
    label: String,
    palette: AppManagementPalette,
    modifier: Modifier = Modifier,
    compactValue: Boolean = false,
    accent: Boolean = false
) {
    Surface(
        shape = RoundedCornerShape(palette.miuix.actionRadius ?: 12.dp),
        color = Color(palette.settings.row),
        contentColor = if (accent) palette.settings.accent else palette.settings.primaryText,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
        modifier = modifier
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = value,
                color = if (accent) palette.settings.accent else palette.settings.primaryText,
                fontSize = if (compactValue) {
                    MaterialTheme.typography.bodyMedium.fontSize
                } else {
                    MaterialTheme.typography.bodyLarge.fontSize
                },
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = label,
                color = palette.settings.secondaryText,
                fontSize = MaterialTheme.typography.labelSmall.fontSize,
                maxLines = 1
            )
        }
    }
}

@Composable
private fun AiProviderCard(
    provider: AiProviderConfig,
    modelCount: Int,
    current: Boolean,
    onClick: () -> Unit,
    onOpenModels: () -> Unit,
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
                        text = provider.name,
                        color = palette.settings.primaryText,
                        fontSize = MaterialTheme.typography.bodyLarge.fontSize,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    if (current) {
                        AiProviderCurrentBadge(palette)
                    }
                }
                Spacer(modifier = Modifier.height(5.dp))
                Text(
                    text = provider.baseUrl.ifBlank { "OpenAI compatible" },
                    color = palette.settings.secondaryText,
                    fontSize = MaterialTheme.typography.bodyTertiary.fontSize,
                    maxLines = 1,
                    overflow = TextOverflow.MiddleEllipsis
                )
                Spacer(modifier = Modifier.height(4.dp))
                // F4：模型数行直达「模型管理」Tab（整卡点击仍进配置 Tab），把最常用的管理动作从两次点击降到一次
                Text(
                    text = stringResource(R.string.ai_manage_models_summary, modelCount),
                    color = palette.settings.secondaryText,
                    fontSize = MaterialTheme.typography.bodyTertiary.fontSize,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .clickable(onClick = onOpenModels)
                        .padding(vertical = 4.dp)
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = stringResource(if (current) R.string.ai_current_provider else R.string.ai_provider),
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
private fun AiProviderCurrentBadge(palette: AppManagementPalette) {
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
private fun AiProviderEmptyCard() {
    val palette = rememberAppManagementPalette()
    AppManagementCard(
        palette = palette,
        modifier = Modifier.fillMaxWidth(),
        insidePadding = PaddingValues(horizontal = 14.dp, vertical = 18.dp)
    ) {
        Text(
            text = stringResource(R.string.ai_current_provider_summary_empty),
            color = palette.settings.primaryText,
            fontSize = MaterialTheme.typography.bodySecondary.fontSize,
            fontWeight = FontWeight.Medium
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = stringResource(R.string.ai_add_provider_summary),
            color = palette.settings.secondaryText,
            fontSize = MaterialTheme.typography.bodyTertiary.fontSize,
            lineHeight = 18.sp
        )
    }
}
