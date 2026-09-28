package io.legado.app.ui.config

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.R
import io.legado.app.ui.theme.bodyTertiary
import io.legado.app.ui.widget.components.GlassTopAppBar
import io.legado.app.ui.widget.compose.AppManagementCard
import io.legado.app.ui.widget.compose.AppManagementIconAction
import io.legado.app.ui.widget.compose.AppManagementPalette
import io.legado.app.ui.widget.compose.AppSettingSectionTitle
import io.legado.app.ui.widget.compose.LegadoMiuixActionButton
import io.legado.app.ui.widget.compose.LegadoMiuixPalette
import io.legado.app.ui.widget.compose.LegadoMiuixSwitch
import io.legado.app.ui.widget.compose.rememberAppManagementPalette
import io.legado.app.web.TokenManager
import io.legado.app.web.WebPortPolicy

/**
 * 页面渲染状态（一期 §6）：由 [WebServiceSettingsActivity] 组装，屏幕侧**只读**。
 *
 * 全部字段带默认值 ⇒ 预览/单测可只填关心的分支。
 */
@Immutable
internal data class WebServiceSettingsState(
    val serviceRunning: Boolean = false,
    val hostAddress: String = "",
    val port: Int = WebPortPolicy.DEFAULT,
    val wakeLock: Boolean = false,
    val strict: Boolean = false,
    val showFirstLaunchGuide: Boolean = false,
    val tokenStatuses: List<TokenManager.TokenStatus> = emptyList()
)

/**
 * 「Web 服务与 AI 接入」设置页内容（web-mcp-productization 一期 · §6.1）。
 *
 * 页面只做渲染与回调，服务启停 / 端口重启联动 / 令牌生成撤销全部由宿主 Activity 编排。
 * 取色一律走 [rememberAppManagementPalette]（面 token 直色），无硬编码色号（K1 已登记）。
 */
@Composable
internal fun WebServiceSettingsScreen(
    state: WebServiceSettingsState,
    onBack: () -> Unit,
    onServiceToggle: (Boolean) -> Unit,
    onEditPort: () -> Unit,
    onWakeLockToggle: (Boolean) -> Unit,
    onStrictToggle: (Boolean) -> Unit,
    onCopyAddress: () -> Unit,
    onOpenBrowser: () -> Unit,
    onGenerateToken: (TokenManager.Level) -> Unit,
    onRevokeToken: (TokenManager.Level) -> Unit,
    onDismissFirstLaunchGuide: () -> Unit,
    onGuideCopyAddress: () -> Unit,
    onGuideOpenBrowser: () -> Unit,
    onGuideGenerateReadonlyToken: () -> Unit
) {
    val palette = rememberAppManagementPalette()
    // 真机缺陷修复（2026-09-29 两次截图实测）：
    // ① `miuix.surfaceVariant` = `colors.row` ⇒ 与卡面**同色**，非主按钮完全不可见；
    // ② 换 `rowPressed` 仍不可见 —— `UiCorner.surfaceColor(..., pressed=true)` 只把 alpha +0.08，
    //    在不透明主题（`layoutAlpha()==1f`）下与未按下值**完全相同** ⇒ 无层次差。
    // 故次级按钮底改用 `settings.page`（页面根背景面）：它与卡面（`row`）**必然不同值**，
    // 仍是主题面 token（非硬编码色），日夜/主题色/主题包三态都随主题走。
    val actionPalette = remember(palette) {
        palette.miuix.copy(surfaceVariant = palette.settings.page)
    }
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
                    // GlassTopAppBar 自带状态栏 inset，此处只补导航栏
                    .navigationBarsPadding()
            ) {
                GlassTopAppBar(
                    title = stringResource(R.string.web_service_settings_title),
                    navIcon = Icons.AutoMirrored.Filled.ArrowBack,
                    onNavClick = onBack
                )
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(bottom = 24.dp)
                ) {
                    if (state.showFirstLaunchGuide) {
                        AppSettingSectionTitle(
                            title = stringResource(R.string.web_first_launch_title),
                            palette = palette.settings
                        )
                        AppManagementCard(palette = palette) {
                            FirstLaunchGuide(
                                palette = palette,
                                buttons = actionPalette,
                                onOpenBrowser = onGuideOpenBrowser,
                                onCopyAddress = onGuideCopyAddress,
                                onGenerateToken = onGuideGenerateReadonlyToken,
                                onDismiss = onDismissFirstLaunchGuide
                            )
                        }
                    }
                    ServiceSection(
                        state = state,
                        palette = palette,
                        onServiceToggle = onServiceToggle,
                        onEditPort = onEditPort,
                        onWakeLockToggle = onWakeLockToggle
                    )
                    AddressSection(
                        state = state,
                        palette = palette,
                        buttons = actionPalette,
                        onCopyAddress = onCopyAddress,
                        onOpenBrowser = onOpenBrowser
                    )
                    TokenSection(
                        state = state,
                        palette = palette,
                        buttons = actionPalette,
                        onGenerateToken = onGenerateToken,
                        onRevokeToken = onRevokeToken
                    )
                    SecuritySection(
                        state = state,
                        palette = palette,
                        onStrictToggle = onStrictToggle
                    )
                }
            }
        }
    }
}

/** 服务区：总开关 + 端口 + 唤醒锁。 */
@Composable
private fun ServiceSection(
    state: WebServiceSettingsState,
    palette: AppManagementPalette,
    onServiceToggle: (Boolean) -> Unit,
    onEditPort: () -> Unit,
    onWakeLockToggle: (Boolean) -> Unit
) {
    val stoppedText = stringResource(R.string.web_service_stopped)
    val pendingText = stringResource(R.string.web_address_pending)
    val statusText = WebServiceSettingsLogic.serviceSummaryText(
        serviceRunning = state.serviceRunning,
        hostAddress = state.hostAddress,
        stoppedText = stoppedText,
        pendingText = pendingText
    )
    AppSettingSectionTitle(
        title = stringResource(R.string.web_section_service),
        palette = palette.settings
    )
    AppManagementCard(palette = palette) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.web_service),
                    color = palette.settings.primaryText,
                    fontSize = MaterialTheme.typography.bodyLarge.fontSize,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = statusText,
                    color = palette.settings.secondaryText,
                    fontSize = MaterialTheme.typography.bodyTertiary.fontSize,
                    lineHeight = 18.sp,
                    modifier = Modifier.padding(top = 3.dp)
                )
            }
            LegadoMiuixSwitch(
                checked = state.serviceRunning,
                onCheckedChange = onServiceToggle,
                palette = palette.miuix
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onEditPort
                )
                .padding(vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.web_port_title),
                    color = palette.settings.primaryText,
                    fontSize = MaterialTheme.typography.bodyLarge.fontSize,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = stringResource(R.string.web_port_summary, state.port.toString()) +
                        " · " + stringResource(R.string.web_port_range_hint),
                    color = palette.settings.secondaryText,
                    fontSize = MaterialTheme.typography.bodyTertiary.fontSize,
                    lineHeight = 18.sp,
                    modifier = Modifier.padding(top = 3.dp)
                )
            }
            AppManagementIconAction(
                iconRes = R.drawable.ic_edit,
                contentDescription = stringResource(R.string.edit),
                tint = palette.settings.primaryText,
                onClick = onEditPort
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.web_service_wake_lock),
                    color = palette.settings.primaryText,
                    fontSize = MaterialTheme.typography.bodyLarge.fontSize,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = stringResource(R.string.web_service_wake_lock_summary),
                    color = palette.settings.secondaryText,
                    fontSize = MaterialTheme.typography.bodyTertiary.fontSize,
                    lineHeight = 18.sp,
                    modifier = Modifier.padding(top = 3.dp)
                )
            }
            LegadoMiuixSwitch(
                checked = state.wakeLock,
                onCheckedChange = onWakeLockToggle,
                palette = palette.miuix
            )
        }
    }
}

/** 地址活字牌（S1）：点按整行复制；未运行时不给点（无地址可复制）。 */
@Composable
private fun AddressSection(
    state: WebServiceSettingsState,
    palette: AppManagementPalette,
    buttons: LegadoMiuixPalette,
    onCopyAddress: () -> Unit,
    onOpenBrowser: () -> Unit
) {
    val copyable = state.serviceRunning && state.hostAddress.isNotBlank()
    val addressText = WebServiceSettingsLogic.serviceSummaryText(
        serviceRunning = state.serviceRunning,
        hostAddress = state.hostAddress,
        stoppedText = stringResource(R.string.web_address_unavailable),
        pendingText = stringResource(R.string.web_address_pending)
    )
    AppSettingSectionTitle(
        title = stringResource(R.string.web_section_address),
        palette = palette.settings
    )
    AppManagementCard(
        palette = palette,
        onClick = onCopyAddress.takeIf { copyable }
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
            Text(
                text = addressText,
                color = if (copyable) palette.settings.accent else palette.settings.secondaryText,
                fontSize = 19.sp,
                fontWeight = FontWeight.Medium
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                // 未开启时**不得**再重复一遍"未开启"（真机截图实测为重复文案）⇒ 给可操作提示
                text = stringResource(
                    if (copyable) R.string.web_address_tap_to_copy else R.string.web_address_hint_when_off
                ),
                color = palette.settings.secondaryText,
                fontSize = MaterialTheme.typography.bodyTertiary.fontSize
            )
        }
        if (copyable) {
            LegadoMiuixActionButton(
                text = stringResource(R.string.open_in_browser),
                palette = buttons,
                onClick = onOpenBrowser,
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                primary = true,
                cornerRadius = buttons.actionRadius
            )
        }
    }
}

/** 令牌区：三级列表（生成 / 重新生成 / 撤销）+ 明文只显示一次告示。 */
@Composable
private fun TokenSection(
    state: WebServiceSettingsState,
    palette: AppManagementPalette,
    buttons: LegadoMiuixPalette,
    onGenerateToken: (TokenManager.Level) -> Unit,
    onRevokeToken: (TokenManager.Level) -> Unit
) {
    val neverText = stringResource(R.string.web_token_never_generated)
    val generatedAtTemplate = stringResource(R.string.web_token_generated_at)
    AppSettingSectionTitle(
        title = stringResource(R.string.web_section_token),
        palette = palette.settings
    )
    AppManagementCard(palette = palette) {
        WebServiceSettingsLogic.tokenLevelsInDisplayOrder().forEachIndexed { index, level ->
            if (index > 0) {
                Spacer(modifier = Modifier.height(14.dp))
            }
            val status = state.tokenStatuses.firstOrNull { it.level == level }
            val generated = status?.generatedAt != null
            val statusText = WebServiceSettingsLogic.tokenStatusText(status?.generatedAt, neverText) { millis ->
                generatedAtTemplate.format(WebServiceSettingsLogic.formatGeneratedAt(millis))
            }
            Text(
                text = stringResource(WebServiceSettingsLogic.levelTitleRes(level)),
                color = palette.settings.primaryText,
                fontSize = MaterialTheme.typography.bodyLarge.fontSize,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = statusText,
                color = if (generated) palette.settings.accent else palette.settings.secondaryText,
                fontSize = MaterialTheme.typography.bodyTertiary.fontSize,
                modifier = Modifier.padding(top = 3.dp)
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                LegadoMiuixActionButton(
                    text = stringResource(
                        if (generated) R.string.web_token_regenerate else R.string.web_token_generate
                    ),
                    palette = buttons,
                    onClick = { onGenerateToken(level) },
                    modifier = Modifier.weight(1f),
                    cornerRadius = buttons.actionRadius
                )
                if (generated) {
                    LegadoMiuixActionButton(
                        text = stringResource(R.string.web_token_revoke),
                        palette = buttons,
                        onClick = { onRevokeToken(level) },
                        modifier = Modifier.weight(1f),
                        danger = true,
                        cornerRadius = buttons.actionRadius
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = stringResource(R.string.web_token_plain_only_once),
            color = palette.settings.secondaryText,
            fontSize = MaterialTheme.typography.bodyTertiary.fontSize,
            lineHeight = 18.sp
        )
    }
}

/** 安全区：严格模式开关。 */
@Composable
private fun SecuritySection(
    state: WebServiceSettingsState,
    palette: AppManagementPalette,
    onStrictToggle: (Boolean) -> Unit
) {
    AppSettingSectionTitle(
        title = stringResource(R.string.web_section_security),
        palette = palette.settings
    )
    AppManagementCard(palette = palette) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.web_auth_strict_title),
                    color = palette.settings.primaryText,
                    fontSize = MaterialTheme.typography.bodyLarge.fontSize,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = stringResource(R.string.web_auth_strict_summary),
                    color = palette.settings.secondaryText,
                    fontSize = MaterialTheme.typography.bodyTertiary.fontSize,
                    lineHeight = 18.sp,
                    modifier = Modifier.padding(top = 3.dp)
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            LegadoMiuixSwitch(
                checked = state.strict,
                onCheckedChange = onStrictToggle,
                palette = palette.miuix
            )
        }
    }
}

/** S2 首启引导卡：三个按钮 + 关闭；关闭即置位首启标志（保证"首次弹、二次不弹"）。 */
@Composable
private fun FirstLaunchGuide(
    palette: AppManagementPalette,
    buttons: LegadoMiuixPalette,
    onOpenBrowser: () -> Unit,
    onCopyAddress: () -> Unit,
    onGenerateToken: () -> Unit,
    onDismiss: () -> Unit
) {
    Text(
        text = stringResource(R.string.web_first_launch_summary),
        color = palette.settings.secondaryText,
        fontSize = MaterialTheme.typography.bodyTertiary.fontSize,
        lineHeight = 19.sp
    )
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        LegadoMiuixActionButton(
            text = stringResource(R.string.open_in_browser),
            palette = buttons,
            onClick = onOpenBrowser,
            modifier = Modifier.weight(1f),
            primary = true,
            cornerRadius = buttons.actionRadius
        )
        LegadoMiuixActionButton(
            text = stringResource(R.string.web_first_launch_copy),
            palette = buttons,
            onClick = onCopyAddress,
            modifier = Modifier.weight(1f),
            cornerRadius = buttons.actionRadius
        )
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        LegadoMiuixActionButton(
            text = stringResource(R.string.web_first_launch_token),
            palette = buttons,
            onClick = onGenerateToken,
            modifier = Modifier.weight(1f),
            cornerRadius = buttons.actionRadius
        )
        LegadoMiuixActionButton(
            text = stringResource(R.string.web_first_launch_dismiss),
            palette = buttons,
            onClick = onDismiss,
            modifier = Modifier.weight(1f),
            cornerRadius = buttons.actionRadius
        )
    }
}
