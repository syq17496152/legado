package io.legado.app.ui.autoTask

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.AdapterView
import androidx.activity.viewModels
import io.legado.app.R
import io.legado.app.base.VMBaseActivity
import io.legado.app.databinding.ActivityAutoTaskEditBinding
import io.legado.app.lib.dialogs.alert
import io.legado.app.model.AutoTask
import io.legado.app.model.AutoTaskRule
import io.legado.app.ui.login.SourceLoginActivity
import io.legado.app.utils.CronSchedule
import io.legado.app.utils.GSON
import io.legado.app.utils.sendToClip
import io.legado.app.utils.showHelp
import io.legado.app.utils.startActivity
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding

/**
 * F-P1-1 自动任务编辑页（借鉴阅读T，简化版）
 *
 * 与阅读T 版本的差异：
 * - 不使用 KeyboardToolPop（软键盘工具栏）
 * - 不使用 WebCodeDialog（代码编辑弹框）
 * - 不使用 BookSourceEditAdapter（字段列表适配器），改用 ScrollView + TextInputLayout 表单
 * - 不使用字段导航（field_nav_scroll/field_nav_group）
 */
class AutoTaskEditActivity :
    VMBaseActivity<ActivityAutoTaskEditBinding, AutoTaskEditViewModel>() {

    companion object {
        // F-P9-1 Cron 频率预设值
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

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        viewModel.initData(intent) {
            task = it
            upView(it)
        }
        // F-P9-1 Cron 频率选择器：自定义档才显示 Cron 输入框
        binding.spCronFrequency.onItemSelectedListener =
            object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(
                    parent: AdapterView<*>?, view: View?, position: Int, id: Long
                ) {
                    binding.tilCron.visibility =
                        if (position == 2) View.VISIBLE else View.GONE
                }

                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }
    }

    override fun onCompatCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.auto_task_edit, menu)
        return super.onCompatCreateOptionsMenu(menu)
    }

    override fun onMenuOpened(featureId: Int, menu: Menu): Boolean {
        val loginUrl = binding.etLoginUrl.text?.toString()?.trim().orEmpty()
        menu.findItem(R.id.menu_login)?.let {
            it.isVisible = true
            it.isEnabled = loginUrl.isNotBlank()
        }
        return super.onMenuOpened(featureId, menu)
    }

    override fun onCompatOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.menu_save -> {
                val rule = buildTask() ?: return true
                viewModel.save(rule) {
                    originTask = rule.copy()
                    setResult(RESULT_OK)
                    finish()
                }
            }
            R.id.menu_debug_task -> {
                // TODO: AutoTaskDebugActivity 尚未创建，创建后启用下面注释
                toastOnUi(R.string.auto_task_debug_pending)
                // val rule = buildTask() ?: return true
                // viewModel.save(rule) {
                //     originTask = rule.copy()
                //     startActivity(AutoTaskDebugActivity.startIntent(this, rule.id))
                // }
            }
            R.id.menu_login -> openLogin()
            R.id.menu_copy_source -> sendToClip(GSON.toJson(buildTaskDraft()))
            R.id.menu_paste_source -> viewModel.pasteSource { upView(it) }
            R.id.menu_help -> showHelpDialog()
        }
        return super.onCompatOptionsItemSelected(item)
    }

    private fun upView(rule: AutoTaskRule) = binding.run {
        originTask = rule.copy()
        cbEnable.isChecked = rule.enable
        cbCookie.isChecked = rule.enabledCookieJar
        etName.setText(rule.name)
        // F-P9-1 根据 cron 判断频率并设置 Spinner 选中项
        val cron = rule.cron?.ifBlank { AutoTask.DEFAULT_CRON }.orEmpty()
        etCron.setText(cron)
        val freqIndex = when (cron) {
            CRON_EVERY_DAY -> 0
            CRON_EVERY_HOUR -> 1
            else -> 2
        }
        spCronFrequency.setSelection(freqIndex)
        etComment.setText(rule.comment.orEmpty())
        etScript.setText(rule.script)
        etHeader.setText(rule.header.orEmpty())
        etJslib.setText(rule.jsLib.orEmpty())
        etConcurrentRate.setText(rule.concurrentRate.orEmpty())
        etLoginUrl.setText(rule.loginUrl.orEmpty())
        etLoginUi.setText(rule.loginUi.orEmpty())
        etLoginCheckJs.setText(rule.loginCheckJs.orEmpty())
    }

    private fun buildTask(): AutoTaskRule? = binding.run {
        val name = etName.text?.toString()?.trim().orEmpty()
        if (name.isBlank()) {
            toastOnUi(getString(R.string.auto_task_name_required))
            return null
        }
        // F-P9-1 根据频率生成 cron
        val cron = when (spCronFrequency.selectedItemPosition) {
            0 -> CRON_EVERY_DAY
            1 -> CRON_EVERY_HOUR
            else -> {
                val customCron = etCron.text?.toString()?.trim().orEmpty()
                    .ifBlank { AutoTask.DEFAULT_CRON }
                if (CronSchedule.parse(customCron) == null) {
                    toastOnUi(getString(R.string.auto_task_cron_invalid))
                    return null
                }
                customCron
            }
        }
        val script = etScript.text?.toString().orEmpty()
        if (script.isBlank()) {
            toastOnUi(getString(R.string.auto_task_script_empty))
            return null
        }
        val rule = task ?: AutoTaskRule()
        rule.name = name
        rule.cron = cron
        rule.comment = etComment.text?.toString()?.trim()?.ifBlank { null }
        rule.script = script
        rule.header = etHeader.text?.toString()?.trim()?.ifBlank { null }
        rule.jsLib = etJslib.text?.toString()?.trim()?.ifBlank { null }
        rule.concurrentRate = etConcurrentRate.text?.toString()?.trim()?.ifBlank { null }
        rule.loginUrl = etLoginUrl.text?.toString()?.trim()?.ifBlank { null }
        rule.loginUi = etLoginUi.text?.toString()?.trim()?.ifBlank { null }
        rule.loginCheckJs = etLoginCheckJs.text?.toString()?.trim()?.ifBlank { null }
        rule.enable = cbEnable.isChecked
        rule.enabledCookieJar = cbCookie.isChecked
        task = rule
        return rule
    }

    private fun buildTaskDraft(): AutoTaskRule = binding.run {
        val base = originTask ?: task ?: AutoTaskRule()
        base.copy(
            name = etName.text?.toString().orEmpty(),
            // F-P9-1 根据频率生成 cron
            cron = when (spCronFrequency.selectedItemPosition) {
                0 -> CRON_EVERY_DAY
                1 -> CRON_EVERY_HOUR
                else -> etCron.text?.toString()?.trim()?.ifBlank { AutoTask.DEFAULT_CRON }
                    ?: AutoTask.DEFAULT_CRON
            },
            comment = etComment.text?.toString()?.trim()?.ifBlank { null },
            script = etScript.text?.toString().orEmpty(),
            header = etHeader.text?.toString()?.trim()?.ifBlank { null },
            jsLib = etJslib.text?.toString()?.trim()?.ifBlank { null },
            concurrentRate = etConcurrentRate.text?.toString()?.trim()?.ifBlank { null },
            loginUrl = etLoginUrl.text?.toString()?.trim()?.ifBlank { null },
            loginUi = etLoginUi.text?.toString()?.trim()?.ifBlank { null },
            loginCheckJs = etLoginCheckJs.text?.toString()?.trim()?.ifBlank { null },
            enable = cbEnable.isChecked,
            enabledCookieJar = cbCookie.isChecked
        )
    }

    override fun finish() {
        val base = originTask ?: task ?: AutoTaskRule()
        val current = buildTaskDraft()
        if (current != base) {
            alert(R.string.exit) {
                setMessage(R.string.exit_no_save)
                positiveButton(R.string.yes)
                negativeButton(R.string.no) {
                    super.finish()
                }
            }
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
