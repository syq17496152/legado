package io.legado.app.ui.replace.edit

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.EditText
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.annotation.RequiresApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import io.legado.app.ui.widget.components.AppShapes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.base.VMBaseActivity
import io.legado.app.data.entities.ReplaceRule
import io.legado.app.databinding.ActivityReplaceEditBinding
import io.legado.app.lib.dialogs.SelectItem
import io.legado.app.ui.code.CodeEditActivity
import io.legado.app.ui.theme.LegadoTheme
import io.legado.app.ui.widget.components.AppDropdownMenu
import io.legado.app.ui.widget.components.GlassTopAppBar
import io.legado.app.ui.widget.components.MenuAction
import io.legado.app.ui.widget.keyboard.KeyboardToolPop
import io.legado.app.utils.GSON
import io.legado.app.utils.imeHeight
import io.legado.app.utils.sendToClip
import io.legado.app.utils.setOnApplyWindowInsetsListenerCompat
import io.legado.app.utils.showHelp
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding

/**
 * 编辑替换规则
 *
 * L-C4 替换规则编辑页（S3 骨架范式）：顶栏 GlassTopAppBar + 菜单下沉 AppDropdownMenu
 * （全屏编辑/保存/复制规则/粘贴规则），字段区 View 内核保留（EditText 行内编辑 + KeyboardToolPop
 * + 正则帮助，AD-20 内核 View 桥接），底部保存/取消栏 Compose 化（12dp 圆角 48dp 高）。
 */
class ReplaceEditActivity :
    VMBaseActivity<ActivityReplaceEditBinding, ReplaceEditViewModel>(),
    KeyboardToolPop.CallBack {

    companion object {

        fun startIntent(
            context: Context,
            id: Long = -1,
            pattern: String? = null,
            isRegex: Boolean = false,
            scope: String? = null
        ): Intent {
            val intent = Intent(context, ReplaceEditActivity::class.java)
            intent.putExtra("id", id)
            intent.putExtra("pattern", pattern)
            intent.putExtra("isRegex", isRegex)
            intent.putExtra("scope", scope)
            return intent
        }

    }

    override val binding by viewBinding(ActivityReplaceEditBinding::inflate)
    override val viewModel by viewModels<ReplaceEditViewModel>()

    private val softKeyboardTool by lazy {
        KeyboardToolPop(this, lifecycleScope, binding.root, this)
    }

    private var menuExpanded by mutableStateOf(false)

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        softKeyboardTool.attachToWindow(window)
        initComposeTopBar()
        initComposeBottomBar()
        initView()
        viewModel.initData(intent) {
            upReplaceView(it)
        }
    }

    private val textEditLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            val view = window.decorView.findFocus()
            if (view is EditText) {
                result.data?.getStringExtra("text")?.let {
                    view.setText(it)
                }
                result.data?.getIntExtra("cursorPosition", -1)?.takeIf { it in 0..<view.text.length }?.let {
                    view.setSelection(it)
                }
            } else {
                toastOnUi(R.string.focus_lost_on_textbox)
            }
        }
    }

    // L-C4 顶栏 Compose 化（S3 骨架范式：GlassTopAppBar + 菜单下沉 AppDropdownMenu）
    private fun initComposeTopBar() {
        binding.composeTopBar.setContent {
            LegadoTheme {
                GlassTopAppBar(
                    title = getString(R.string.replace_rule_edit),
                    navIcon = Icons.AutoMirrored.Filled.ArrowBack,
                    onNavClick = { finish() },
                    actions = {
                        Box {
                            IconButton(onClick = { menuExpanded = true }) {
                                Icon(
                                    imageVector = Icons.Filled.MoreVert,
                                    contentDescription = null
                                )
                            }
                            AppDropdownMenu(
                                expanded = menuExpanded,
                                onDismiss = { menuExpanded = false },
                                actions = buildMenuActions()
                            )
                        }
                    }
                )
            }
        }
    }

    // L-C4 底部保存/取消栏（S3 骨架范式：12dp 圆角 48dp 高）
    private fun initComposeBottomBar() {
        binding.composeBottomBar.setContent {
            LegadoTheme {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    OutlinedButton(
                        onClick = { finish() },
                        modifier = Modifier.weight(1f).height(48.dp),
                        shape = AppShapes.Button
                    ) {
                        Text(text = getString(R.string.cancel))
                    }
                    Button(
                        onClick = { saveReplaceRule() },
                        modifier = Modifier.weight(1f).height(48.dp),
                        shape = AppShapes.Button
                    ) {
                        Text(text = getString(R.string.action_save))
                    }
                }
            }
        }
    }

    private fun buildMenuActions(): List<MenuAction> {
        return listOf(
            MenuAction(
                Icons.Filled.Code,
                getString(R.string.edit_content),
                onClick = { onFullEditClicked() }
            ),
            MenuAction(
                Icons.Filled.Save,
                getString(R.string.action_save),
                onClick = { saveReplaceRule() }
            ),
            MenuAction(
                Icons.Filled.ContentCopy,
                getString(R.string.copy_rule),
                onClick = { sendToClip(GSON.toJson(getReplaceRule())) }
            ),
            MenuAction(
                Icons.Filled.ContentPaste,
                getString(R.string.paste_rule),
                onClick = {
                    viewModel.pasteRule {
                        upReplaceView(it)
                    }
                }
            )
        )
    }

    private fun saveReplaceRule() {
        viewModel.save(getReplaceRule()) {
            setResult(RESULT_OK)
            finish()
        }
    }

    private fun onFullEditClicked() {
        val view = window.decorView.findFocus()
        if (view is EditText) {
            val hint = findParentTextInputLayout(view)?.hint?.toString()
            val currentText = view.text.toString()
            val intent = Intent(this, CodeEditActivity::class.java).apply {
                putExtra("text", currentText)
                putExtra("title", hint)
                putExtra("cursorPosition", view.selectionStart)
            }
            textEditLauncher.launch(intent)
        } else {
            toastOnUi(R.string.please_focus_cursor_on_textbox)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        softKeyboardTool.dismiss()
    }

    private fun initView() {
        binding.ivHelp.setOnClickListener {
            showHelp("regexHelp")
        }
        binding.root.setOnApplyWindowInsetsListenerCompat { _, windowInsets ->
            softKeyboardTool.initialPadding = windowInsets.imeHeight
            windowInsets
        }
    }

    private fun upReplaceView(replaceRule: ReplaceRule) = binding.run {
        etName.setText(replaceRule.name)
        etGroup.setText(replaceRule.group)
        etReplaceRule.setText(replaceRule.pattern)
        cbUseRegex.isChecked = replaceRule.isRegex
        etReplaceTo.setText(replaceRule.replacement)
        cbScopeTitle.isChecked = replaceRule.scopeTitle
        cbScopeContent.isChecked = replaceRule.scopeContent
        etScope.setText(replaceRule.scope)
        etExcludeScope.setText(replaceRule.excludeScope)
        etTimeout.setText(replaceRule.timeoutMillisecond.toString())
    }

    private fun getReplaceRule(): ReplaceRule = binding.run {
        val replaceRule: ReplaceRule = viewModel.replaceRule ?: ReplaceRule()
        replaceRule.name = etName.text.toString()
        replaceRule.group = etGroup.text.toString()
        replaceRule.pattern = etReplaceRule.text.toString()
        replaceRule.isRegex = cbUseRegex.isChecked
        replaceRule.replacement = etReplaceTo.text.toString()
        replaceRule.scopeTitle = cbScopeTitle.isChecked
        replaceRule.scopeContent = cbScopeContent.isChecked
        replaceRule.scope = etScope.text.toString()
        replaceRule.excludeScope = etExcludeScope.text.toString()
        replaceRule.timeoutMillisecond = etTimeout.text.toString().ifEmpty { "3000" }.toLong()
        return replaceRule
    }

    override fun helpActions(): List<SelectItem<String>> {
        return arrayListOf(
            SelectItem("正则教程", "regexHelp")
        )
    }

    override fun onHelpActionSelect(action: String) {
        when (action) {
            "regexHelp" -> showHelp("regexHelp")
        }
    }

    override fun sendText(text: String) {
        if (text.isEmpty()) return
        val view = window?.decorView?.findFocus()
        if (view is EditText) {
            var start = view.selectionStart
            var end = view.selectionEnd
            if (start > end) {
                val temp = start
                start = end
                end = temp
            }
            //获取EditText的文字
            val edit = view.editableText
            if (start < 0 || start >= edit.length) {
                edit.append(text)
            } else {
                //光标所在位置插入文字
                edit.replace(start, end, text)
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.M)
    override fun onUndoClicked() {
        val editText = window.decorView.findFocus()
        if (editText is EditText) {
            editText.onTextContextMenuItem(android.R.id.undo)
        }
    }

    @RequiresApi(Build.VERSION_CODES.M)
    override fun onRedoClicked() {
        val editText = window.decorView.findFocus()
        if (editText is EditText) {
            editText.onTextContextMenuItem(android.R.id.redo)
        }
    }

}
