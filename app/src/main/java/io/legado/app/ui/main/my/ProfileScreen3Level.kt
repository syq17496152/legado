package io.legado.app.ui.main.my

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.Book
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.LibraryBooks
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material.icons.outlined.AutoFixHigh
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.legado.app.R
import io.legado.app.constant.PreferKey
import io.legado.app.data.appDb
import io.legado.app.help.config.ThemeConfig
import io.legado.app.service.AutoTaskService
import io.legado.app.service.WebService
import io.legado.app.ui.about.AboutActivity
import io.legado.app.ui.about.ReadRecordActivity
import io.legado.app.ui.autoTask.AutoTaskActivity
import io.legado.app.ui.book.bookmark.AllBookmarkActivity
import io.legado.app.ui.book.source.manage.BookSourceActivity
import io.legado.app.ui.book.toc.rule.TxtTocRuleActivity
import io.legado.app.ui.config.ConfigActivity
import io.legado.app.ui.config.ConfigTag
import io.legado.app.ui.dict.rule.DictRuleActivity
import io.legado.app.ui.file.FileManageActivity
import io.legado.app.ui.replace.ReplaceRuleActivity
import io.legado.app.ui.widget.components.MetricGrid
import io.legado.app.ui.widget.components.MetricItem
import io.legado.app.ui.widget.components.SettingsCard
import io.legado.app.ui.widget.components.SettingsClickRow
import io.legado.app.ui.widget.components.SettingsSection
import io.legado.app.ui.widget.components.SettingsToggleRow
import io.legado.app.utils.formatDuring
import io.legado.app.utils.startActivity
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 我的页 Compose 化（MyCenter 三级布局，AD-16）。
 * ① 统计卡 MetricGrid（真实 Room 数据）→ ② 高频功能卡 ③ 低频列表，入口零删除。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen3Level() {
    val context = LocalContext.current
    var webServiceRun by remember { mutableStateOf(WebService.isRun) }
    var autoTaskRun by remember { mutableStateOf(AutoTaskService.isRun) }

    val webServiceDesc = context.getString(R.string.web_service_desc)
    val stats by produceState<List<MetricItem>?>(initialValue = null) {
        value = withContext(Dispatchers.IO) {
            val readCount = appDb.readRecordDao.allShow.size
            val readTime = formatDuring(appDb.readRecordDao.allTime)
            val bookmarkCount = appDb.bookmarkDao.all.size
            val bookSourceCount = appDb.bookSourceDao.allCount()
            listOf(
                MetricItem(context.getString(R.string.read_count), readCount.toString(), Icons.Filled.LibraryBooks),
                MetricItem(context.getString(R.string.total_read_time), readTime, Icons.Filled.History),
                MetricItem(context.getString(R.string.bookmark), bookmarkCount.toString(), Icons.Filled.Bookmark),
                MetricItem(context.getString(R.string.book_source), bookSourceCount.toString(), Icons.Filled.Storage)
            )
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        if (stats == null) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
            ) {
                // ① 统计卡（真实 Room 数据）
                stats?.let { MetricGrid(metrics = it, columns = 2) }

                // ② 高频功能
                SettingsSection(title = context.getString(R.string.frequent)) {
                    SettingsCard {
                        SettingsClickRow(
                            icon = Icons.Filled.CloudUpload,
                            title = context.getString(R.string.backup_restore),
                            subtitle = context.getString(R.string.web_dav_set_import_old),
                            onClick = {
                                context.startActivity<ConfigActivity> {
                                    putExtra("configTag", ConfigTag.BACKUP_CONFIG)
                                }
                            }
                        )
                        SettingsClickRow(
                            icon = Icons.Filled.Palette,
                            title = context.getString(R.string.theme_setting),
                            subtitle = context.getString(R.string.theme_setting_s),
                            onClick = {
                                context.startActivity<ConfigActivity> {
                                    putExtra("configTag", ConfigTag.THEME_CONFIG)
                                }
                            }
                        )
                        SettingsClickRow(
                            icon = Icons.Filled.Settings,
                            title = context.getString(R.string.other_setting),
                            subtitle = context.getString(R.string.other_setting_s),
                            onClick = {
                                context.startActivity<ConfigActivity> {
                                    putExtra("configTag", ConfigTag.OTHER_CONFIG)
                                }
                            }
                        )
                        SettingsClickRow(
                            icon = Icons.Filled.Book,
                            title = context.getString(R.string.book_source_manage),
                            subtitle = context.getString(R.string.book_source_manage_desc),
                            onClick = { context.startActivity<BookSourceActivity>() }
                        )
                        SettingsClickRow(
                            icon = Icons.Outlined.AutoFixHigh,
                            title = context.getString(R.string.replace_purify),
                            subtitle = context.getString(R.string.replace_purify_desc),
                            onClick = { context.startActivity<ReplaceRuleActivity>() }
                        )
                        SettingsClickRow(
                            icon = Icons.Filled.Translate,
                            title = context.getString(R.string.dict_rule),
                            subtitle = context.getString(R.string.config_dict_rule),
                            onClick = { context.startActivity<DictRuleActivity>() }
                        )
                        SettingsClickRow(
                            icon = Icons.Outlined.Sync,
                            title = context.getString(R.string.txt_toc_rule),
                            subtitle = context.getString(R.string.config_txt_toc_rule),
                            onClick = { context.startActivity<TxtTocRuleActivity>() }
                        )
                        SettingsClickRow(
                            icon = Icons.Filled.Schedule,
                            title = context.getString(R.string.auto_task_manage),
                            subtitle = context.getString(R.string.auto_task_manage_desc),
                            onClick = { context.startActivity<AutoTaskActivity>() }
                        )
                    }
                }

                // 服务开关
                SettingsSection(title = context.getString(R.string.service)) {
                    SettingsCard {
                        SettingsToggleRow(
                            icon = Icons.Filled.Send,
                            title = context.getString(R.string.web_service),
                            checked = webServiceRun,
                            subtitle = if (webServiceRun) WebService.hostAddress else webServiceDesc,
                            onCheckedChange = { checked ->
                                webServiceRun = checked
                                if (checked) WebService.start(context) else WebService.stop(context)
                            }
                        )
                        SettingsToggleRow(
                            icon = Icons.Filled.SmartToy,
                            title = context.getString(R.string.auto_task_service),
                            checked = autoTaskRun,
                            subtitle = context.getString(R.string.auto_task_service_desc),
                            onCheckedChange = { checked ->
                                autoTaskRun = checked
                                if (checked) AutoTaskService.start(context) else AutoTaskService.stop(context)
                            }
                        )
                    }
                }

                // ③ 低频列表
                SettingsSection(title = context.getString(R.string.other)) {
                    SettingsCard {
                        SettingsClickRow(
                            icon = Icons.Filled.Bookmark,
                            title = context.getString(R.string.bookmark),
                            subtitle = context.getString(R.string.all_bookmark),
                            onClick = { context.startActivity<AllBookmarkActivity>() }
                        )
                        SettingsClickRow(
                            icon = Icons.Filled.History,
                            title = context.getString(R.string.read_record),
                            subtitle = context.getString(R.string.read_record_summary),
                            onClick = { context.startActivity<ReadRecordActivity>() }
                        )
                        SettingsClickRow(
                            icon = Icons.Filled.FolderOpen,
                            title = context.getString(R.string.file_manage),
                            subtitle = context.getString(R.string.file_manage_summary),
                            onClick = { context.startActivity<FileManageActivity>() }
                        )
                        SettingsClickRow(
                            icon = Icons.Filled.Storage,
                            title = context.getString(R.string.precise_manage),
                            subtitle = context.getString(R.string.precise_manage_summary),
                            onClick = {
                                context.startActivity<ConfigActivity> {
                                    putExtra("configTag", ConfigTag.PRECISE_MANAGE)
                                }
                            }
                        )
                        SettingsClickRow(
                            icon = Icons.Filled.Info,
                            title = context.getString(R.string.about),
                            onClick = { context.startActivity<AboutActivity>() }
                        )
                        SettingsClickRow(
                            icon = Icons.AutoMirrored.Filled.ExitToApp,
                            title = context.getString(R.string.exit),
                            trailingIcon = Icons.Filled.KeyboardArrowRight,
                            onClick = { (context as? android.app.Activity)?.finish() }
                        )
                    }
                }
            }
        }
    }
}