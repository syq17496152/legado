package io.legado.app.ui.config

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.legado.app.R
import io.legado.app.help.config.AppConfig
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.ui.book.thought.ObsidianApi
import io.legado.app.ui.book.thought.ThoughtObsidianExporter
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.ui.theme.LegadoTheme
import io.legado.app.ui.widget.components.AppDropdownMenu
import io.legado.app.ui.widget.components.MenuAction
import io.legado.app.ui.widget.compose.AppDialogFrame
import io.legado.app.ui.widget.compose.AppDialogSize
import io.legado.app.ui.widget.compose.AppDialogStyle
import io.legado.app.ui.widget.compose.ComposeDialogFragment
import io.legado.app.ui.widget.compose.LegadoMiuixActionButton
import io.legado.app.ui.widget.compose.rememberAppDialogStyle
import io.legado.app.ui.widget.compose.toMiuixPalette
import io.legado.app.utils.toastOnUi

/**
 * B16 批注导出 Obsidian 的配置弹窗（NEW-81 接线）。
 *
 * 缺陷背景：`ThoughtObsidianExporter` / `ObsidianApi` / `ThoughtMarkdownGenerator` 整条导出链路
 * 早已实现（updateLog 亦已宣传「划线批注可导出到 Obsidian」），但 **5 个 `PreferKey.obsidian*` 配置项
 * 全仓零 UI、导出器零调用点** ⇒ 用户可感的功能完全不可达（改了键也无任何效果）。
 *
 * 本弹窗补齐配置面（方式开关 / API 地址与密钥 / vault 子路径 / 本地 vault 目录 / 自动导出开关）
 * 与两个手动动作（测试连接、立即导出全部），并由 `OtherConfigFragment` 提供设置页入口 +
 * `ReadBook.addHighlight` 落库后触发自动导出 ⇒ 功能端到端可达。
 *
 * 口径：取色一律走 [AppDialogStyle] 语义 token（不引入硬编码色）；内容根在 [LegadoTheme] 内（G-37）。
 */
class ObsidianExportConfig : ComposeDialogFragment() {

    override val dialogSize: AppDialogSize = AppDialogSize.Management

    /** 本地 vault 目录（SAF）：弹窗内即时反映，点「保存」时统一落库（与其它设置弹窗同口径）。 */
    private val localDirUri = mutableStateOf(AppConfig.obsidianLocalDirUri)

    // 目录选择复用 [HandleFileContract]（DIR_SYS ⇒ ACTION_OPEN_DOCUMENT_TREE；内部已 takePersistableUriPermission，
    // 并拦截「应用私有目录」选择），与「默认书籍目录」设置同一条通道，避免另造一套 SAF 契约。
    private val dirSelect = registerForActivityResult(HandleFileContract()) { result ->
        result.uri?.let { localDirUri.value = it.toString() }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                // G-37/K1：弹窗内容必须在应用主题作用域内
                LegadoTheme {
                    val style = rememberAppDialogStyle()
                    CompositionLocalProvider(
                        LocalTextStyle provides LocalTextStyle.current.copy(fontFamily = style.bodyFontFamily)
                    ) {
                        ObsidianExportContent(style = style)
                    }
                }
            }
        }
    }

    @Composable
    private fun ObsidianExportContent(style: AppDialogStyle) {
        val scope = rememberCoroutineScope()
        val palette = style.toMiuixPalette()
        // 0=REST API，1=本地 vault 文件（与 AppConfig.obsidianExportMethod 取值一致）
        var method by rememberSaveable { mutableStateOf(AppConfig.obsidianExportMethod) }
        var apiUrl by rememberSaveable { mutableStateOf(AppConfig.obsidianApiUrl) }
        var apiKey by rememberSaveable { mutableStateOf(AppConfig.obsidianApiKey) }
        var subPath by rememberSaveable { mutableStateOf(AppConfig.obsidianVaultSubPath) }
        var autoExport by rememberSaveable { mutableStateOf(AppConfig.obsidianAutoExport) }
        var menuExpanded by remember { mutableStateOf(false) }

        /** 一次性把弹窗内全部字段落入单源 AppConfig（漏传任一字段即静默不生效）。 */
        fun persist() {
            AppConfig.obsidianExportMethod = method
            AppConfig.obsidianApiUrl = apiUrl
            AppConfig.obsidianApiKey = apiKey
            AppConfig.obsidianVaultSubPath = subPath
            AppConfig.obsidianLocalDirUri = localDirUri.value
            AppConfig.obsidianAutoExport = autoExport
        }

        /** 保存前校验：两种方式各自的最小必需项（缺项时明确回执，不静默保存成半成品）。 */
        fun validate(): Boolean {
            if (method == 0 && apiUrl.isBlank()) {
                toastOnUi(getString(R.string.obsidian_api_url_required))
                return false
            }
            if (method == 1 && localDirUri.value.isNullOrEmpty()) {
                toastOnUi(getString(R.string.obsidian_local_dir_required))
                return false
            }
            return true
        }

        fun testConnection() {
            Coroutine.async(scope) {
                ObsidianApi.checkConnection(apiUrl, apiKey).getOrElse { false }
            }.onSuccess { ok ->
                toastOnUi(
                    getString(
                        if (ok) R.string.obsidian_connection_ok else R.string.obsidian_connection_failed
                    )
                )
            }.onError {
                toastOnUi(getString(R.string.obsidian_connection_failed))
            }
        }

        fun exportAll() {
            if (!validate()) return
            persist()
            Coroutine.async(scope) {
                ThoughtObsidianExporter.exportAll().getOrThrow()
            }.onSuccess { (success, total) ->
                toastOnUi(getString(R.string.obsidian_export_all_result, success, total))
            }.onError {
                toastOnUi(getString(R.string.obsidian_export_all_failed, it.localizedMessage ?: ""))
            }
        }

        val titleTrailing: (@Composable () -> Unit)? = if (method == 0) {
            {
                Box {
                    IconButton(
                        onClick = { menuExpanded = true },
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.MoreVert,
                            contentDescription = stringResource(R.string.more),
                            tint = style.primaryText
                        )
                    }
                    AppDropdownMenu(
                        expanded = menuExpanded,
                        onDismiss = { menuExpanded = false },
                        actions = listOf(
                            MenuAction(
                                title = stringResource(R.string.obsidian_test_connection),
                                onClick = { testConnection() }
                            )
                        )
                    )
                }
            }
        } else {
            null
        }

        AppDialogFrame(
            title = stringResource(R.string.obsidian_export_config),
            titleTrailing = titleTrailing,
            content = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = stringResource(R.string.obsidian_export_method),
                        color = style.accent,
                        fontSize = MaterialTheme.typography.bodyMedium.fontSize
                    )
                    CheckConfigDomainModeRow(
                        text = stringResource(R.string.obsidian_export_method_api),
                        selected = method == 0,
                        style = style,
                        onClick = { method = 0 }
                    )
                    CheckConfigDomainModeRow(
                        text = stringResource(R.string.obsidian_export_method_local),
                        selected = method == 1,
                        style = style,
                        onClick = { method = 1 }
                    )

                    if (method == 0) {
                        ObsidianTextField(
                            value = apiUrl,
                            onValueChange = { apiUrl = it },
                            label = stringResource(R.string.obsidian_api_url),
                            style = style
                        )
                        ObsidianTextField(
                            value = apiKey,
                            onValueChange = { apiKey = it },
                            label = stringResource(R.string.obsidian_api_key),
                            style = style
                        )
                    } else {
                        Text(
                            text = stringResource(R.string.obsidian_local_dir),
                            color = style.accent,
                            fontSize = MaterialTheme.typography.bodyMedium.fontSize
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = localDirUri.value
                                    ?: stringResource(R.string.obsidian_local_dir_unset),
                                modifier = Modifier.weight(1f),
                                color = if (localDirUri.value.isNullOrEmpty()) {
                                    style.secondaryText
                                } else {
                                    style.primaryText
                                },
                                fontSize = MaterialTheme.typography.bodySmall.fontSize,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            LegadoMiuixActionButton(
                                text = stringResource(R.string.obsidian_select_dir),
                                palette = palette,
                                onClick = {
                                    dirSelect.launch {
                                        title = getString(R.string.obsidian_select_dir)
                                        mode = HandleFileContract.DIR_SYS
                                    }
                                },
                                cornerRadius = style.actionRadius
                            )
                        }
                    }

                    ObsidianTextField(
                        value = subPath,
                        onValueChange = { subPath = it },
                        label = stringResource(R.string.obsidian_vault_sub_path),
                        style = style
                    )
                    CheckConfigChoiceRow(
                        text = stringResource(R.string.obsidian_auto_export),
                        selected = autoExport,
                        style = style,
                        onClick = { autoExport = !autoExport }
                    )
                }
            },
            actions = {
                LegadoMiuixActionButton(
                    text = stringResource(R.string.obsidian_export_all),
                    palette = palette,
                    onClick = { exportAll() },
                    cornerRadius = style.actionRadius
                )
                Spacer(modifier = Modifier.width(8.dp))
                LegadoMiuixActionButton(
                    text = stringResource(R.string.cancel),
                    palette = palette,
                    onClick = { dismissAllowingStateLoss() },
                    cornerRadius = style.actionRadius
                )
                Spacer(modifier = Modifier.width(8.dp))
                LegadoMiuixActionButton(
                    text = stringResource(R.string.ok),
                    palette = palette,
                    primary = true,
                    onClick = {
                        if (validate()) {
                            persist()
                            dismissAllowingStateLoss()
                        }
                    },
                    cornerRadius = style.actionRadius
                )
            }
        )
    }

    @Composable
    private fun ObsidianTextField(
        value: String,
        onValueChange: (String) -> Unit,
        label: String,
        style: AppDialogStyle
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text(label) },
            shape = RoundedCornerShape(style.actionRadius),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = style.primaryText,
                unfocusedTextColor = style.primaryText,
                focusedContainerColor = style.fieldSurface,
                unfocusedContainerColor = style.fieldSurface,
                cursorColor = style.accent,
                focusedBorderColor = Color.Transparent,
                unfocusedBorderColor = Color.Transparent,
                focusedLabelColor = style.accent,
                unfocusedLabelColor = style.secondaryText
            ),
            textStyle = MaterialTheme.typography.bodyMedium.copy(
                color = style.primaryText,
                fontFamily = style.bodyFontFamily
            )
        )
    }
}
