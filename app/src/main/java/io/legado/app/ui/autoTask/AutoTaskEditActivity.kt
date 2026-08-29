package io.legado.app.ui.autoTask

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.viewModels
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Help
import androidx.compose.material.icons.filled.Login
import androidx.compose.material.icons.filled.Save
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.legado.app.R
import io.legado.app.base.VMBaseActivity
import io.legado.app.databinding.ActivityAutoTaskEditBinding
import io.legado.app.model.AutoTask
import io.legado.app.model.AutoTaskRule
import io.legado.app.ui.login.SourceLoginActivity
import io.legado.app.ui.widget.compose.showComposeConfirmDialog
import io.legado.app.ui.theme.LegadoTheme
import io.legado.app.ui.widget.components.MenuAction
import io.legado.app.utils.CronSchedule
import io.legado.app.utils.GSON
import io.legado.app.utils.sendToClip
import io.legado.app.utils.showHelp
import io.legado.app.utils.startActivity
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding

/**
 * 自动任务编辑页（S3 表单编辑页，简化版）
 * Compose 化：S3 表单族壳层 AutoTaskEditScreen，构建/保存校验/未保存拦截/登录/复制粘贴逻辑保留 Activity
 */
class AutoTaskEditActivity :
    VMBaseActivity<ActivityAutoTaskEditBinding, AutoTaskEditViewModel>() {

    companion object {
        // Cron 频率预设值
        private const val CRON_EVERY_DAY = "0 0 * * *"
        private const val CRON_EVERY_HOUR = "0 * * * *"

        fun startIntent(context: Context, id: String? = null): Intent {
            return Intent(context, AutoTaskEditActivity::class.java).apply {
                if (!id.isNullOrBlank()) {
                    putExtra("id", id)
                }
            }
        }
    }

    override val binding by viewBinding(ActivityAutoTaskEditBinding::inflate)
    override val viewModel by viewModels<AutoTaskEditViewModel>()

    private var task: AutoTaskRule? = null
    private var originTask: AutoTaskRule? = null

    // Compose 桥接状态
    private var editState by mutableStateOf(AutoTaskEditState())

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        initComposeHost()
        viewModel.initData(intent) {
            task = it
            upView(it)
        }
    }

    private fun initComposeHost() {
        binding.composeHost.setContent {
            LegadoTheme {
                AutoTaskEditScreen(
                    state = editState,
                    onStateChange = { editState = it },
                    menuActions = buildMenuActions(),
                    onBack = { finish() }
                )
            }
        }
    }

    private fun buildMenuActions(): List<MenuAction> {
        return listOf(
            MenuAction(Icons.Default.Save, getString(R.string.action_save)) { onSave() },
            MenuAction(Icons.Default.BugReport, getString(R.string.debug)) {
                // TODO: AutoTaskDebugActivity 尚未创建，创建后启用
                toastOnUi(R.string.auto_task_debug_pending)
            },
            MenuAction(Icons.Default.Login, getString(R.string.login)) { openLogin() },
            MenuAction(Icons.Default.ContentCopy, getString(R.string.copy_source)) {
                sendToClip(GSON.toJson(buildTaskDraft()))
            },
            MenuAction(Icons.Default.ContentPaste, getString(R.string.paste_source)) {
                viewModel.pasteSource { upView(it) }
            },
            MenuAction(Icons.Default.Help, getString(R.string.help)) { showHelpDialog() }
        )
    }

    private fun onSave() {
        val rule = buildTask() ?: return
        viewModel.save(rule) {
            originTask = rule.copy()
            setResult(RESULT_OK)
            finish()
        }
    }

    private fun upView(rule: AutoTaskRule) {
        originTask = rule.copy()
        task = rule
        // 根据 cron 判断频率并设置选中项
        val cron = rule.cron?.ifBlank { AutoTask.DEFAULT_CRON }.orEmpty()
        val freqIndex = when (cron) {
            CRON_EVERY_DAY -> 0
            CRON_EVERY_HOUR -> 1
            else -> 2
        }
        editState = AutoTaskEditState(
            enable = rule.enable,
            enabledCookieJar = rule.enabledCookieJar,
            name = rule.name,
            cronFrequency = freqIndex,
            cron = cron,
            comment = rule.comment.orEmpty(),
            script = rule.script,
            header = rule.header.orEmpty(),
            jsLib = rule.jsLib.orEmpty(),
            concurrentRate = rule.concurrentRate.orEmpty(),
            loginUrl = rule.loginUrl.orEmpty(),
            loginUi = rule.loginUi.orEmpty(),
            loginCheckJs = rule.loginCheckJs.orEmpty()
        )
    }

    private fun buildTask(): AutoTaskRule? {
        val name = editState.name.trim()
        if (name.isBlank()) {
            toastOnUi(getString(R.string.auto_task_name_required))
            return null
        }
        // 根据频率生成 cron
        val cron = when (editState.cronFrequency) {
            0 -> CRON_EVERY_DAY
            1 -> CRON_EVERY_HOUR
            else -> {
                val customCron = editState.cron.trim()
                    .ifBlank { AutoTask.DEFAULT_CRON }
                if (CronSchedule.parse(customCron) == null) {
                    toastOnUi(getString(R.string.auto_task_cron_invalid))
                    return null
                }
                customCron
            }
        }
        val script = editState.script
        if (script.isBlank()) {
            toastOnUi(getString(R.string.auto_task_script_empty))
            return null
        }
        val rule = task ?: AutoTaskRule()
        rule.name = name
        rule.cron = cron
        rule.comment = editState.comment.trim().ifBlank { null }
        rule.script = script
        rule.header = editState.header.trim().ifBlank { null }
        rule.jsLib = editState.jsLib.trim().ifBlank { null }
        rule.concurrentRate = editState.concurrentRate.trim().ifBlank { null }
        rule.loginUrl = editState.loginUrl.trim().ifBlank { null }
        rule.loginUi = editState.loginUi.trim().ifBlank { null }
        rule.loginCheckJs = editState.loginCheckJs.trim().ifBlank { null }
        rule.enable = editState.enable
        rule.enabledCookieJar = editState.enabledCookieJar
        task = rule
        return rule
    }

    private fun buildTaskDraft(): AutoTaskRule {
        val base = originTask ?: task ?: AutoTaskRule()
        return base.copy(
            name = editState.name,
            // 根据频率生成 cron
            cron = when (editState.cronFrequency) {
                0 -> CRON_EVERY_DAY
                1 -> CRON_EVERY_HOUR
                else -> editState.cron.trim().ifBlank { AutoTask.DEFAULT_CRON }
            },
            comment = editState.comment.trim().ifBlank { null },
            script = editState.script,
            header = editState.header.trim().ifBlank { null },
            jsLib = editState.jsLib.trim().ifBlank { null },
            concurrentRate = editState.concurrentRate.trim().ifBlank { null },
            loginUrl = editState.loginUrl.trim().ifBlank { null },
            loginUi = editState.loginUi.trim().ifBlank { null },
            loginCheckJs = editState.loginCheckJs.trim().ifBlank { null },
            enable = editState.enable,
            enabledCookieJar = editState.enabledCookieJar
        )
    }

    override fun finish() {
        val base = originTask ?: task ?: AutoTaskRule()
        val current = buildTaskDraft()
        if (current != base) {
            showComposeConfirmDialog(
                title = getString(R.string.exit),
                message = getString(R.string.exit_no_save),
                positiveText = getString(R.string.yes),
                negativeText = getString(R.string.no),
                onPositive = { /* 保留当前页不退出 */ },
                onNegative = { super.finish() }
            )
        } else {
            super.finish()
        }
    }

    private fun openLogin() {
        val rule = buildTask() ?: return
        val loginUrl = rule.loginUrl.orEmpty()
        if (loginUrl.isBlank()) {
            toastOnUi(getString(R.string.source_no_login))
            return
        }
        // TODO: SourceLoginViewModel 当前的 initData 仅支持 bookSource/rssSource/httpTts 三种 type,
        //  需扩展支持 "autoTask" 类型（通过 AutoTask.buildSource(rule) 构造 BaseSource）
        viewModel.save(rule) {
            startActivity<SourceLoginActivity> {
                putExtra("type", "autoTask")
                putExtra("key", rule.id)
            }
        }
    }

    private fun showHelpDialog() {
        // TODO: 需创建 assets/web/help/md/autoTaskHelp.md 帮助文档
        kotlin.runCatching {
            showHelp("autoTaskHelp")
        }.onFailure {
            toastOnUi(getString(R.string.auto_task_help_missing))
        }
    }
}
