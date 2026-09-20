package io.legado.app.ui.replace.edit

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.EditText
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.annotation.RequiresApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
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
import io.legado.app.ui.widget.components.CollapseSectionHeader
import io.legado.app.ui.widget.components.GlassTopAppBar
import io.legado.app.ui.widget.components.MenuAction
import io.legado.app.ui.widget.compose.AppUiTokens
import io.legado.app.ui.widget.compose.showComposeConfirmDialog
import io.legado.app.ui.widget.keyboard.KeyboardToolPop
import io.legado.app.utils.GSON
import io.legado.app.utils.imeHeight
import io.legado.app.utils.replace
import io.legado.app.utils.sendToClip
import io.legado.app.utils.setOnApplyWindowInsetsListenerCompat
import io.legado.app.utils.showHelp
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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

    // ==================== F64：高级字段渐进披露 ====================
    /** 高级配置组展开态（默认收起；存量规则已有非空高级值时自动展开） */
    private var advancedExpanded by mutableStateOf(false)

    // ==================== F69：样本试运行 ====================
    /** 样本区展开态（默认收起） */
    private var sampleExpanded by mutableStateOf(false)
    /** 样本正文（用户粘贴，仅本页内存，不落盘） */
    private var sampleInput by mutableStateOf("")
    /** 试运行结果（null = 未运行） */
    private var sampleResult by mutableStateOf<SamplePreviewResult?>(null)
    /** 试运行进行中（禁用按钮防重复触发） */
    private var sampleRunning by mutableStateOf(false)

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        softKeyboardTool.attachToWindow(window)
        initComposeTopBar()
        initComposeBottomBar()
        initAdvancedHeader()
        initSampleSection()
        initView()
        viewModel.initData(intent) {
            upReplaceView(it)
        }
    }

    /**
     * F64：高级配置组头（点击展开/收起低频字段：替换范围 / 排除范围 / 超时）。
     *
     * 只切换这三个 View 的可见性，字段定义与保存逻辑零改动（纯视图层折叠）。
     */
    private fun initAdvancedHeader() {
        binding.composeAdvancedHeader.setContent {
            LegadoTheme {
                CollapseSectionHeader(
                    title = getString(R.string.replace_advanced_group),
                    hint = getString(R.string.replace_advanced_group_hint),
                    expanded = advancedExpanded,
                    onToggle = {
                        advancedExpanded = !advancedExpanded
                        applyAdvancedVisibility()
                    }
                )
            }
        }
        applyAdvancedVisibility()
    }

    private fun applyAdvancedVisibility() {
        val visibility = if (advancedExpanded) View.VISIBLE else View.GONE
        binding.tilScope.visibility = visibility
        binding.tilExcludeScope.visibility = visibility
        binding.tilTimeout.visibility = visibility
    }

    /** F69：样本试运行区（粘贴样本 → 就地看匹配与替换结果；不保存、不影响阅读页） */
    private fun initSampleSection() {
        binding.composeSampleSection.setContent {
            LegadoTheme {
                ReplaceSampleSection(
                    expanded = sampleExpanded,
                    onToggle = { sampleExpanded = !sampleExpanded },
                    input = sampleInput,
                    onInputChange = { sampleInput = it },
                    running = sampleRunning,
                    result = sampleResult,
                    onRun = { runSample() }
                )
            }
        }
    }

    /**
     * F69：执行样本试运行。
     *
     * 复用阅读页**同一套**替换实现 [io.legado.app.utils.replace]（带超时保护 + `@js:` 分支），
     * 保证预览口径与真实生效口径一致；正则非法 / 超时 / JS 异常都在结果区以文案呈现，不崩页。
     * 表单取值**直接读控件**（不走 `getReplaceRule()`），避免试运行产生任何写回副作用。
     */
    private fun runSample() {
        val input = sampleInput
        if (input.isBlank()) {
            sampleResult = SamplePreviewResult(0, "", getString(R.string.replace_sample_empty_input))
            return
        }
        val pattern = binding.etReplaceRule.text.toString()
        if (pattern.isEmpty()) {
            sampleResult = SamplePreviewResult(0, "", getString(R.string.replace_rule_invalid))
            return
        }
        val replacement = binding.etReplaceTo.text.toString()
        val isRegex = binding.cbUseRegex.isChecked
        val ruleName = binding.etName.text.toString().ifBlank { pattern }
        val timeout = binding.etTimeout.text.toString().trim().toLongOrNull()?.takeIf { it > 0 }
            ?: 3000L
        sampleRunning = true
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                kotlin.runCatching {
                    if (isRegex) {
                        val regex = pattern.toRegex()
                        val hits = regex.findAll(input).count()
                        SamplePreviewResult(
                            hits = hits,
                            output = input.replace(ruleName, regex, replacement, timeout),
                            error = null
                        )
                    } else {
                        SamplePreviewResult(
                            hits = countLiteral(input, pattern),
                            output = input.replace(pattern, replacement),
                            error = null
                        )
                    }
                }.getOrElse { e ->
                    SamplePreviewResult(0, "", e.localizedMessage ?: e.javaClass.simpleName)
                }
            }
            sampleResult = result
            sampleRunning = false
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
                        // topbar-icon-semantics-fix 3.2：代码/保存恢复一级图标
                        //（对齐原版 replace_edit.xml showAsAction=always；tint 继承 actionIconContentColor 禁自传）
                        IconButton(onClick = { onFullEditClicked() }) {
                            Icon(
                                imageVector = Icons.Filled.Code,
                                contentDescription = getString(R.string.edit_content)
                            )
                        }
                        IconButton(onClick = { saveReplaceRule() }) {
                            Icon(
                                imageVector = Icons.Filled.Save,
                                contentDescription = getString(R.string.action_save)
                            )
                        }
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
        // 代码/保存已恢复为一级图标（3.2），不再进入溢出菜单
        return listOf(
            MenuAction(
                Icons.Filled.ContentCopy,
                getString(R.string.copy_rule),
                onClick = { sendToClip(GSON.toJson(getReplaceRule())) }
            ),
            MenuAction(
                Icons.Filled.ContentPaste,
                getString(R.string.paste_rule),
                onClick = {
                    viewModel.pasteRule { pasted -> showPasteDiffDialog(pasted) }
                }
            )
        )
    }

    /**
     * F63：粘贴规则**覆盖前差异预览**。
     *
     * 原实现解析成功后直接 `upReplaceView` 全量覆盖表单——编辑到一半误点粘贴（或剪贴板是旧版本规则）
     * 会静默冲掉已填内容。改为先给「将覆盖 N 项字段 + 字段级 before → after 摘要」，确认后才回填。
     * 解析失败/剪贴板为空的既有反馈保持 ViewModel 原行为（不动）。
     */
    private fun showPasteDiffDialog(pasted: ReplaceRule) {
        val current = getReplaceRule()
        val diffs = mutableListOf<String>()
        fun diff(label: String, old: String?, new: String?) {
            val o = old.orEmpty()
            val n = new.orEmpty()
            if (o != n) diffs += "$label：${briefValue(o)} → ${briefValue(n)}"
        }
        diff(getString(R.string.replace_rule_summary), current.name, pasted.name)
        diff(getString(R.string.group), current.group, pasted.group)
        diff(getString(R.string.replace_rule), current.pattern, pasted.pattern)
        diff(getString(R.string.use_regex), current.isRegex.toString(), pasted.isRegex.toString())
        diff(getString(R.string.replace_to), current.replacement, pasted.replacement)
        diff(getString(R.string.scope_title), current.scopeTitle.toString(), pasted.scopeTitle.toString())
        diff(getString(R.string.scope_content), current.scopeContent.toString(), pasted.scopeContent.toString())
        diff(getString(R.string.replace_scope), current.scope, pasted.scope)
        diff(getString(R.string.replace_exclude_scope), current.excludeScope, pasted.excludeScope)
        diff(
            getString(R.string.timeout_millisecond),
            current.timeoutMillisecond.toString(),
            pasted.timeoutMillisecond.toString()
        )
        if (diffs.isEmpty()) {
            toastOnUi(getString(R.string.replace_paste_diff_none))
            return
        }
        showComposeConfirmDialog(
            title = getString(R.string.replace_paste_diff_title, diffs.size),
            message = diffs.take(MaxPasteDiffLines).joinToString("\n") +
                if (diffs.size > MaxPasteDiffLines) "\n…" else "",
            positiveText = getString(R.string.replace_paste_diff_confirm),
            negativeText = getString(R.string.cancel),
            messageInContent = true,
            onPositive = { upReplaceView(pasted) }
        )
    }

    private fun saveReplaceRule() {
        // 既有缺陷修复：超时字段原 `toLong()` 对非数字输入直接抛 NumberFormatException（保存即崩）
        if (!validateTimeout()) return
        viewModel.save(getReplaceRule()) {
            setResult(RESULT_OK)
            finish()
        }
    }

    /**
     * 超时字段校验：非法（非数字 / ≤0）时展开高级配置组、就地给出字段错误并阻断保存。
     *
     * 修复既有缺陷：原 `getReplaceRule()` 用 `toLong()`，输入非数字会抛 `NumberFormatException`。
     */
    private fun validateTimeout(): Boolean {
        val raw = binding.etTimeout.text.toString().trim()
        val value = if (raw.isEmpty()) 3000L else raw.toLongOrNull()
        if (value == null || value <= 0) {
            advancedExpanded = true
            applyAdvancedVisibility()
            binding.tilTimeout.error = getString(R.string.replace_timeout_invalid)
            return false
        }
        binding.tilTimeout.error = null
        return true
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
        // F64：已有非空高级值时自动展开，避免「值在收起区里看不见」造成误以为丢失
        if (!replaceRule.scope.isNullOrBlank()
            || !replaceRule.excludeScope.isNullOrBlank()
            || replaceRule.timeoutMillisecond != DefaultTimeoutMillisecond
        ) {
            advancedExpanded = true
        }
        binding.tilTimeout.error = null
        applyAdvancedVisibility()
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
        // 既有缺陷修复：原为 `toLong()`（非数字输入抛 NumberFormatException）；非法值由
        // validateTimeout() 在保存前拦截并就地报错，此处只做无异常兜底
        replaceRule.timeoutMillisecond = etTimeout.text.toString().trim().toLongOrNull()
            ?.takeIf { it > 0 } ?: DefaultTimeoutMillisecond
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

/** 超时字段默认值（与 [ReplaceRule.timeoutMillisecond] 默认口径一致，避免两处各写一个 3000） */
private const val DefaultTimeoutMillisecond = 3000L

/** F63：粘贴差异摘要最多列几行（超出折叠为「…」，避免长规则把弹窗撑爆） */
private const val MaxPasteDiffLines = 6

/** F69：样本试运行结果（[error] 非空表示未能完成试运行） */
private data class SamplePreviewResult(
    val hits: Int,
    val output: String,
    val error: String?
)

/** F63：差异摘要里的取值——只取首行 + 截断，空值显示「（空）」 */
private fun briefValue(value: String): String {
    val oneLine = value.lineSequence().firstOrNull().orEmpty()
    return when {
        oneLine.isEmpty() -> "（空）"
        oneLine.length > 20 -> oneLine.take(20) + "…"
        else -> oneLine
    }
}

/** F69：非正则模式下的字面量出现次数（与 `String.replace` 的非重叠语义一致） */
private fun countLiteral(text: String, pattern: String): Int {
    if (pattern.isEmpty()) return 0
    var count = 0
    var index = text.indexOf(pattern)
    while (index >= 0) {
        count++
        index = text.indexOf(pattern, index + pattern.length)
    }
    return count
}

/**
 * F69：样本试运行区（折叠区 + 样本输入 + 试运行按钮 + 结果）。
 *
 * 纯渲染件：状态（展开态/输入/结果/进行中）全部由宿主持有，本组件零业务状态。
 * 取色走 [AppUiTokens.settingPalette]（禁止页内自建取色链）。
 */
@Composable
private fun ReplaceSampleSection(
    expanded: Boolean,
    onToggle: () -> Unit,
    input: String,
    onInputChange: (String) -> Unit,
    running: Boolean,
    result: SamplePreviewResult?,
    onRun: () -> Unit
) {
    val palette = AppUiTokens.settingPalette()
    Column(modifier = Modifier.fillMaxWidth()) {
        CollapseSectionHeader(
            title = stringResource(R.string.replace_sample_group),
            hint = stringResource(R.string.replace_sample_group_hint),
            expanded = expanded,
            onToggle = onToggle
        )
        if (!expanded) return@Column
        Spacer(modifier = Modifier.height(4.dp))
        OutlinedTextField(
            value = input,
            onValueChange = onInputChange,
            label = { Text(stringResource(R.string.replace_sample_input_hint)) },
            minLines = 3,
            maxLines = 6,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(
                onClick = onRun,
                enabled = !running,
                shape = AppShapes.Button
            ) {
                Text(text = stringResource(R.string.replace_sample_run))
            }
            if (result != null) {
                Spacer(modifier = Modifier.width(12.dp))
                val summary = result.error
                    ?: if (result.hits > 0) {
                        stringResource(R.string.replace_sample_hit, result.hits)
                    } else {
                        stringResource(R.string.replace_sample_miss)
                    }
                Text(
                    text = summary,
                    color = if (result.error != null || result.hits == 0) {
                        palette.danger
                    } else {
                        palette.accent
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        if (result != null && result.error == null && result.output.isNotEmpty()) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.replace_sample_result_label),
                color = palette.secondaryText,
                style = MaterialTheme.typography.labelMedium
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = result.output,
                color = palette.primaryText,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 200.dp)
                    .verticalScroll(rememberScrollState())
            )
        }
    }
}
