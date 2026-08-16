package io.legado.app.ui.autoTask

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.legado.app.R
import io.legado.app.ui.widget.components.AppDropdownMenu
import io.legado.app.ui.widget.components.GlassTopAppBar
import io.legado.app.ui.widget.components.MenuAction
import io.legado.app.ui.widget.components.SettingsCard

/**
 * L-C16 自动任务编辑页（S3 表单编辑页）：全 Compose 内容区。
 *
 * 字段状态由宿主（Activity）以 [AutoTaskEditState] 传入并上抛，顶栏 GlassTopAppBar + 菜单
 * （保存/调试/登录/复制/粘贴/帮助），SettingsCard 分组字段：
 * 开关组（启用/Cookie）· 基本信息（名称/执行频率/自定义 cron/备注）· 脚本（脚本/请求头/JS库/并发率）· 登录（地址/UI/校验JS）。
 * cron 频率：每天/每小时/自定义（自定义档显示 cron 输入框）。
 */
data class AutoTaskEditState(
    val enable: Boolean = true,
    val enabledCookieJar: Boolean = true,
    val name: String = "",
    val cronFrequency: Int = 2,   // 0=每天 1=每小时 2=自定义
    val cron: String = "",
    val comment: String = "",
    val script: String = "",
    val header: String = "",
    val jsLib: String = "",
    val concurrentRate: String = "",
    val loginUrl: String = "",
    val loginUi: String = "",
    val loginCheckJs: String = ""
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AutoTaskEditScreen(
    state: AutoTaskEditState,
    onStateChange: (AutoTaskEditState) -> Unit,
    menuActions: List<MenuAction>,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    var moreMenuVisible by remember { mutableStateOf(false) }
    var frequencyMenuVisible by remember { mutableStateOf(false) }

    val frequencyOptions = stringArrayResource(R.array.auto_task_cron_frequency_items)
    val scrollState = rememberScrollState()

    Column(modifier = modifier.fillMaxSize()) {
        GlassTopAppBar(
            title = stringResource(R.string.auto_task_edit),
            navIcon = Icons.AutoMirrored.Filled.ArrowBack,
            onNavClick = onBack,
            actions = {
                Box {
                    IconButton(onClick = { moreMenuVisible = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = null)
                    }
                    AppDropdownMenu(
                        expanded = moreMenuVisible,
                        onDismiss = { moreMenuVisible = false },
                        actions = menuActions
                    )
                }
            }
        )

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(scrollState)
                .navigationBarsPadding()
        ) {
            // 开关组：启用 / 启用 Cookie
            SettingsCard(modifier = Modifier.padding(top = 8.dp)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = stringResource(R.string.is_enable),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f)
                        )
                        Switch(
                            checked = state.enable,
                            onCheckedChange = {
                                onStateChange(state.copy(enable = it))
                            }
                        )
                    }
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = stringResource(R.string.auto_task_cookie_jar),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f)
                        )
                        Switch(
                            checked = state.enabledCookieJar,
                            onCheckedChange = {
                                onStateChange(state.copy(enabledCookieJar = it))
                            }
                        )
                    }
                }
            }

            // 基本信息
            SettingsCard {
                OutlinedTextField(
                    value = state.name,
                    onValueChange = { onStateChange(state.copy(name = it)) },
                    label = { Text(stringResource(R.string.name)) },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                )

                // 执行频率选择器（每天/每小时/自定义）
                Box(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { frequencyMenuVisible = true }
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = stringResource(R.string.auto_task_cron_frequency),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = frequencyOptions.getOrElse(state.cronFrequency) { "" },
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                        Icon(
                            imageVector = Icons.Default.ArrowDropDown,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    DropdownMenu(
                        expanded = frequencyMenuVisible,
                        onDismissRequest = { frequencyMenuVisible = false }
                    ) {
                        frequencyOptions.forEachIndexed { index, option ->
                            DropdownMenuItem(
                                text = { Text(option) },
                                onClick = {
                                    frequencyMenuVisible = false
                                    onStateChange(state.copy(cronFrequency = index))
                                }
                            )
                        }
                    }
                }

                // 自定义档才显示 cron 表达式
                if (state.cronFrequency == 2) {
                    OutlinedTextField(
                        value = state.cron,
                        onValueChange = { onStateChange(state.copy(cron = it)) },
                        label = { Text(stringResource(R.string.auto_task_cron)) },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp)
                    )
                }

                OutlinedTextField(
                    value = state.comment,
                    onValueChange = { onStateChange(state.copy(comment = it)) },
                    label = { Text(stringResource(R.string.auto_task_comment)) },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                )
            }

            // 脚本
            SettingsCard {
                OutlinedTextField(
                    value = state.script,
                    onValueChange = { onStateChange(state.copy(script = it)) },
                    label = { Text(stringResource(R.string.auto_task_script)) },
                    minLines = 4,
                    maxLines = 8,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                )
                OutlinedTextField(
                    value = state.header,
                    onValueChange = { onStateChange(state.copy(header = it)) },
                    label = { Text(stringResource(R.string.auto_task_header)) },
                    minLines = 3,
                    maxLines = 6,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                )
                OutlinedTextField(
                    value = state.jsLib,
                    onValueChange = { onStateChange(state.copy(jsLib = it)) },
                    label = { Text(stringResource(R.string.auto_task_jslib)) },
                    minLines = 3,
                    maxLines = 6,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                )
                OutlinedTextField(
                    value = state.concurrentRate,
                    onValueChange = { onStateChange(state.copy(concurrentRate = it)) },
                    label = { Text(stringResource(R.string.auto_task_concurrent_rate)) },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                )
            }

            // 登录
            SettingsCard {
                OutlinedTextField(
                    value = state.loginUrl,
                    onValueChange = { onStateChange(state.copy(loginUrl = it)) },
                    label = { Text(stringResource(R.string.login_url)) },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                )
                OutlinedTextField(
                    value = state.loginUi,
                    onValueChange = { onStateChange(state.copy(loginUi = it)) },
                    label = { Text(stringResource(R.string.login_ui)) },
                    minLines = 3,
                    maxLines = 6,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                )
                OutlinedTextField(
                    value = state.loginCheckJs,
                    onValueChange = { onStateChange(state.copy(loginCheckJs = it)) },
                    label = { Text(stringResource(R.string.login_check_js)) },
                    minLines = 3,
                    maxLines = 6,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                )
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}
