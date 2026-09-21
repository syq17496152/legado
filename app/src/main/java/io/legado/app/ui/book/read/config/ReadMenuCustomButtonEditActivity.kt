package io.legado.app.ui.book.read.config

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.EditText
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.widget.AppCompatImageButton
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.base.BaseActivity
import io.legado.app.constant.AppLog
import io.legado.app.constant.EventBus
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.ReadMenuCustomButton
import io.legado.app.databinding.ActivityParagraphRuleEditBinding
import io.legado.app.help.book.BookHelp
import io.legado.app.help.book.ReadMenuCustomButtonExecutor
import io.legado.app.model.ReadBook
import io.legado.app.ui.code.CodeEditActivity
import io.legado.app.ui.theme.LegadoTheme
import io.legado.app.ui.widget.code.addJsPattern
import io.legado.app.ui.widget.components.AppShapes
import io.legado.app.ui.widget.components.CollapseSectionHeader
import io.legado.app.ui.widget.components.MenuAction
import io.legado.app.ui.widget.components.installGlassTopBar
import io.legado.app.ui.widget.compose.AppUiTokens
import io.legado.app.ui.widget.compose.showComposeConfirmDialog
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import io.legado.app.utils.getClipText
import io.legado.app.utils.postEvent
import io.legado.app.utils.sendToClip
import io.legado.app.utils.showHelp
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.mozilla.javascript.NativeArray
import org.mozilla.javascript.NativeObject
import org.mozilla.javascript.Scriptable

class ReadMenuCustomButtonEditActivity : BaseActivity<ActivityParagraphRuleEditBinding>() {

    override val binding by viewBinding(ActivityParagraphRuleEditBinding::inflate)
    private var button = ReadMenuCustomButton()
    private var focusedEditText: EditText? = null
    // W7.2：原 5 个 AppCompatImageButton handle 为死写链（仅赋值无消费），随迁移删除

    /** F345（优化 3）：登录与高级字段的展开态（数据驱动初值，用户可切换） */
    private var advancedExpanded by mutableStateOf(false)

    /** F345：折叠态下是否有登录/高级配置（避免字段被折叠后「配置消失」的错觉） */
    private var advancedConfigured by mutableStateOf(false)

    /** F343（优化 1）：页内测试运行状态 */
    private var runState by mutableStateOf<ScriptRunState>(ScriptRunState.Idle)
    private var runJob: Job? = null

    private val textEditLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val view = focusedEditText ?: return@registerForActivityResult
            result.data?.getStringExtra("text")?.let { view.setText(it) }
            result.data?.getIntExtra("cursorPosition", -1)
                ?.takeIf { it in 0..view.text.length }
                ?.let { view.setSelection(it) }
        }
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        initTopBar()
        initView()
        val id = intent.getLongExtra("id", 0L)
        lifecycleScope.launch {
            button = withContext(Dispatchers.IO) {
                appDb.readMenuCustomButtonDao.get(id)
            } ?: ReadMenuCustomButton()
            bindButton()
        }
    }

    // W7.2（Delta 3→1）：顶栏归一 installGlassTopBar（原 MainTopBarView Mode.SUB 消亡）
    private fun initTopBar() {
        installGlassTopBar(
            binding,
            titleProvider = { getString(R.string.read_menu_custom_button_edit) },
            actionsProvider = {
                listOf(
                    MenuAction(
                        iconRes = R.drawable.ic_code,
                        title = getString(R.string.edit_content),
                        alwaysShow = true
                    ) { onFullEditClicked() },
                    MenuAction(
                        iconRes = R.drawable.ic_save,
                        title = getString(R.string.action_save),
                        alwaysShow = true
                    ) { save() },
                    MenuAction(
                        iconRes = R.drawable.ic_export,
                        title = getString(R.string.copy_rule),
                        alwaysShow = true
                    ) { sendToClip(GSON.toJson(getButton())) },
                    MenuAction(
                        iconRes = R.drawable.ic_import,
                        title = getString(R.string.paste_rule),
                        alwaysShow = true
                    ) { pasteButton() },
                    MenuAction(
                        iconRes = R.drawable.ic_help,
                        title = getString(R.string.help),
                        alwaysShow = true
                    ) { showHelp("readMenuCustomButtonHelp") }
                )
            },
            onBack = { finish() }
        )
    }

    private fun initView() = binding.run {
        tilScript.hint = getString(R.string.read_menu_button_script)
        listOf(etLoginUrl, etLoginUi, etScript, etJsLib).forEach { codeView ->
            codeView.addJsPattern()
            codeView.setOnFocusChangeListener { v, hasFocus ->
                if (hasFocus && v is EditText) focusedEditText = v
            }
        }
        etName.setOnFocusChangeListener { v, hasFocus ->
            if (hasFocus && v is EditText) focusedEditText = v
        }
        etTimeout.setOnFocusChangeListener { v, hasFocus ->
            if (hasFocus && v is EditText) focusedEditText = v
        }
        applyFormOrder()
        initLoginAdvanced()
        initScriptTest()
    }

    /**
     * F345（优化 3）：本页表单分组重排 —— 基础组（名称 / 按键 JS）连成一片置顶，
     * 登录与高级组（loginUrl / loginUi / Cookie / 超时 / 公共库）紧随其后并由组头收拢。
     *
     * 共用布局 `activity_paragraph_rule_edit.xml` 的字段声明顺序为段落规则编辑页服务，
     * 本页只在运行时重排 `ll_content` 子视图顺序，**不改共用布局** ⇒ 段落规则编辑页零影响。
     * 两个新槽（组头 / 测试运行）在本页置为可见，共用该布局的段落规则编辑页恒 `gone`（零占位）。
     */
    private fun applyFormOrder() = binding.run {
        cvLoginAdvanced.visibility = View.VISIBLE
        cvScriptTest.visibility = View.VISIBLE
        // 全量列全部子视图后再按目标顺序重排，避免未列出者插在中间造成错位
        listOf(
            tilName, cvScriptTemplates, tilScript,
            cvLoginAdvanced,
            tilLoginUrl, tilLoginUi, cbIsEnableCookie, tilTimeout, tilJsLib,
            cvScriptTest,
        ).forEachIndexed { index, view ->
            llContent.removeView(view)
            llContent.addView(view, index)
        }
    }

    /**
     * F345（优化 3）：登录与高级字段的渐进披露组头。
     *
     * 只切换 5 个 View 的可见性，字段定义 / 绑定 / 保存逻辑零改动（纯视图层折叠）；
     * 折叠态 `getButton()` 仍读取全部控件 ⇒ 已配置内容不会因折叠丢失。
     */
    private fun initLoginAdvanced() {
        binding.cvLoginAdvanced.setViewCompositionStrategy(
            ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed
        )
        binding.cvLoginAdvanced.setContent {
            LegadoTheme {
                CollapseSectionHeader(
                    title = getString(R.string.read_menu_advanced_group),
                    hint = if (!advancedExpanded && advancedConfigured) {
                        getString(R.string.read_menu_advanced_group_hint)
                    } else {
                        null
                    },
                    expanded = advancedExpanded,
                    onToggle = {
                        advancedExpanded = !advancedExpanded
                        applyAdvancedVisibility()
                    },
                )
            }
        }
        applyAdvancedVisibility()
    }

    private fun applyAdvancedVisibility() {
        val visibility = if (advancedExpanded) View.VISIBLE else View.GONE
        binding.run {
            tilLoginUrl.visibility = visibility
            tilLoginUi.visibility = visibility
            cbIsEnableCookie.visibility = visibility
            tilTimeout.visibility = visibility
            tilJsLib.visibility = visibility
        }
    }

    /** F345：表单里是否已有登录/高级配置（读控件；折叠态不可编辑 ⇒ 仅绑定/导入后需要重算） */
    private fun hasAdvancedConfig(): Boolean = binding.run {
        etLoginUrl.text?.isNotBlank() == true ||
            etLoginUi.text?.isNotBlank() == true ||
            etJsLib.text?.isNotBlank() == true ||
            cbIsEnableCookie.isChecked
    }

    /** F343（优化 1）：页内测试运行槽（运行按钮 + 结果面板就地展开） */
    private fun initScriptTest() {
        binding.cvScriptTest.setViewCompositionStrategy(
            ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed
        )
        binding.cvScriptTest.setContent {
            LegadoTheme {
                ScriptRunSection(
                    state = runState,
                    onRun = { runScriptTest() },
                    onCopy = { text ->
                        sendToClip(text)
                        toastOnUi(R.string.paragraph_rule_debug_copied)
                    },
                    onClear = { runState = ScriptRunState.Idle },
                )
            }
        }
    }

    private fun bindButton() = binding.run {
        etName.setText(button.name)
        etLoginUrl.setText(button.loginUrl)
        etLoginUi.setText(button.loginUi)
        cbIsEnableCookie.isChecked = button.enabledCookieJar
        etTimeout.setText(button.validTimeout().toString())
        etScript.setText(button.script)
        etJsLib.setText(button.jsLib)
        // F345：已有登录配置的按键进入编辑时自动展开（数据驱动，不引入新模式）
        advancedConfigured = hasAdvancedConfig()
        advancedExpanded = advancedConfigured
        applyAdvancedVisibility()
    }

    private fun onFullEditClicked() {
        val view = (window.decorView.findFocus() as? EditText) ?: focusedEditText
        if (view == null) {
            toastOnUi(R.string.please_focus_cursor_on_textbox)
            return
        }
        focusedEditText = view
        textEditLauncher.launch(Intent(this, CodeEditActivity::class.java).apply {
            putExtra("text", view.text.toString())
            putExtra("title", hintFor(view))
            putExtra("cursorPosition", view.selectionStart)
        })
    }

    private fun hintFor(view: View): String? = when (view.id) {
        R.id.et_name -> getString(R.string.name)
        R.id.et_login_url -> getString(R.string.login_url)
        R.id.et_login_ui -> getString(R.string.login_ui)
        R.id.et_timeout -> getString(R.string.timeout_millisecond)
        R.id.et_script -> getString(R.string.read_menu_button_script)
        R.id.et_js_lib -> "jsLib"
        else -> null
    }

    /**
     * F344（优化 2）：粘贴导入前给出**字段级差异预览**，确认后才覆盖表单。
     *
     * 原实现解析成功即静默覆盖全部字段（仅保留 id/order/updateTime），已填内容可能被冲掉；
     * 剪贴板无效的既有 toast 反馈保持不变。
     */
    private fun pasteButton() {
        val raw = getClipText()
        val imported = GSON.fromJsonObject<ReadMenuCustomButton>(raw).getOrNull()
        if (imported == null) {
            toastOnUi(R.string.wrong_format)
            return
        }
        val current = getButton()
        val diffs = mutableListOf<String>()
        fun diff(label: String, old: String?, new: String?) {
            val oldText = old.orEmpty()
            val newText = new.orEmpty()
            if (oldText != newText) diffs += "$label：${briefValue(oldText)} → ${briefValue(newText)}"
        }
        diff(getString(R.string.name), current.name, imported.name)
        diff(getString(R.string.read_menu_button_script), current.script, imported.script)
        diff(getString(R.string.login_url), current.loginUrl, imported.loginUrl)
        diff(getString(R.string.login_ui), current.loginUi, imported.loginUi)
        diff(
            getString(R.string.auto_save_cookie),
            current.enabledCookieJar.toString(),
            imported.enabledCookieJar.toString()
        )
        diff(
            getString(R.string.timeout_millisecond),
            current.validTimeout().toString(),
            imported.validTimeout().toString()
        )
        diff("jsLib", current.jsLib, imported.jsLib)
        if (diffs.isEmpty()) {
            toastOnUi(getString(R.string.read_menu_button_paste_same))
            return
        }
        showComposeConfirmDialog(
            title = getString(R.string.read_menu_button_paste_preview, diffs.size),
            message = diffs.joinToString("\n"),
            positiveText = getString(R.string.read_menu_button_paste_confirm),
            negativeText = getString(R.string.cancel),
            messageInContent = true,
            onPositive = {
                button = imported.copy(
                    id = button.id,
                    order = button.order,
                    updateTime = System.currentTimeMillis()
                )
                bindButton()
            }
        )
    }

    private fun getButton(): ReadMenuCustomButton = binding.run {
        button.copy(
            name = etName.text?.toString().orEmpty().trim(),
            loginUrl = etLoginUrl.text?.toString().orEmpty(),
            loginUi = etLoginUi.text?.toString().orEmpty(),
            enabledCookieJar = cbIsEnableCookie.isChecked,
            timeoutMillisecond = etTimeout.text?.toString()?.toLongOrNull() ?: 3000L,
            script = etScript.text?.toString().orEmpty(),
            jsLib = etJsLib.text?.toString().orEmpty(),
            updateTime = System.currentTimeMillis()
        )
    }

    private fun save() {
        val edited = getButton()
        if (edited.name.isBlank() || edited.script.isBlank()) {
            toastOnUi(R.string.read_menu_custom_button_save_invalid)
            return
        }
        lifecycleScope.launch {
            val id = withContext(Dispatchers.IO) {
                if (edited.id == 0L) {
                    val order = (appDb.readMenuCustomButtonDao.maxOrder() ?: 0) + 1
                    appDb.readMenuCustomButtonDao.insert(edited.copy(order = order))
                } else {
                    appDb.readMenuCustomButtonDao.update(edited)
                    edited.id
                }
            }
            postEvent(EventBus.READ_MENU_BUTTON_CHANGED, true)
            setResult(Activity.RESULT_OK, Intent().putExtra("id", id))
            finish()
        }
    }

    /**
     * F343（优化 1）：页内测试运行。
     *
     * **复用阅读页同一套执行实现** [ReadMenuCustomButtonExecutor.execute]——同一 `java` 扩展、
     * 同一变量绑定（book/chapter/content/title/baseUrl/bookSource/button）、同一超时口径、
     * 同一正文取材（[BookHelp.getContent]，未命中即空正文，与阅读页短按路径一致），
     * 保证「页内跑通 = 阅读页跑通」；只把返回值/异常就地呈现，**不落库、不影响阅读页**。
     *
     * 章节优先取阅读页当前章；尚未装上当前章（正文未加载完成）时回落到书内目录里书签位置那一章。
     */
    private fun runScriptTest() {
        val tested = getButton()
        if (tested.script.isBlank()) {
            toastOnUi(R.string.read_menu_custom_button_save_invalid)
            return
        }
        val book = ReadBook.book ?: run {
            toastOnUi(R.string.paragraph_rule_no_book_hint)
            return
        }
        runJob?.cancel()
        runState = ScriptRunState.Running
        runJob = lifecycleScope.launch {
            val chapter = resolveTestChapter(book)
            if (chapter == null) {
                toastOnUi(R.string.read_menu_no_current_chapter)
                runState = ScriptRunState.Idle
                return@launch
            }
            val outcome = kotlin.runCatching {
                withContext(Dispatchers.IO) {
                    val content = BookHelp.getContent(book, chapter).orEmpty()
                    ReadMenuCustomButtonExecutor.execute(
                        this@ReadMenuCustomButtonEditActivity,
                        tested,
                        book,
                        chapter,
                        content,
                        ReadBook.bookSource,
                    )
                }
            }
            runState = outcome.fold(
                onSuccess = { value ->
                    val (type, text) = describeJsReturn(value)
                    ScriptRunState.Done(book.name, chapter.title, type, text)
                },
                onFailure = { error ->
                    // 超时（TimeoutCancellationException）是执行结果，需展示；其余协程取消照常上抛
                    if (error is CancellationException && error !is TimeoutCancellationException) {
                        throw error
                    }
                    AppLog.put(
                        "阅读菜单自定义按键测试运行失败: ${tested.displayName()}\n${error.localizedMessage}",
                        error,
                        true
                    )
                    ScriptRunState.Failed(
                        book.name,
                        chapter.title,
                        error.localizedMessage ?: error.toString()
                    )
                },
            )
            scrollToResult()
        }
    }

    /** F343：测试运行的章节取材——阅读页当前章优先，未就绪时回落书内目录中书签位置那一章 */
    private suspend fun resolveTestChapter(book: Book): BookChapter? {
        ReadBook.curTextChapter?.chapter?.let { return it }
        return withContext(Dispatchers.IO) {
            appDb.bookChapterDao.getChapterList(book.bookUrl)
                .getOrNull(ReadBook.durChapterIndex)
        }
    }

    /** F343：结果面板挂在表单末尾，运行后滚到底让结果可见（不改变任何字段状态） */
    private fun scrollToResult() {
        binding.nestedScroll.post {
            if (isFinishing || isDestroyed) return@post
            binding.nestedScroll.fullScroll(View.FOCUS_DOWN)
        }
    }

    /** F343：JS 返回值 → 「类型 + 文本」（Rhino 对象先摊平成 Map/List 再 JSON 化） */
    private fun describeJsReturn(value: Any?): Pair<String, String> {
        val plain = jsToPlain(value)
        val text = when (plain) {
            null -> "null"
            is String -> plain
            is Map<*, *>, is List<*>, is Array<*> ->
                runCatching { GSON.toJson(plain) }.getOrElse { plain.toString() }
            else -> plain.toString()
        }
        val type = when (value) {
            null -> "null"
            is NativeObject -> "Object"
            is NativeArray -> "Array"
            else -> value.javaClass.simpleName
        }
        return type to text
    }

    private fun jsToPlain(value: Any?, depth: Int = 0): Any? = when (value) {
        null -> null
        is NativeObject -> if (depth >= JS_VALUE_MAX_DEPTH) {
            value.toString()
        } else {
            value.ids.associate { key -> key.toString() to jsToPlain(jsMember(value, key), depth + 1) }
        }
        is NativeArray -> if (depth >= JS_VALUE_MAX_DEPTH) {
            value.toString()
        } else {
            value.ids.map { key -> jsToPlain(jsMember(value, key), depth + 1) }
        }
        else -> value
    }

    private fun jsMember(holder: Scriptable, key: Any?): Any? {
        val value = if (key is Int) holder.get(key, holder) else holder.get(key.toString(), holder)
        return value.takeIf { it != Scriptable.NOT_FOUND }
    }

}

/** F343：JS 值摊平深度上限（防对象环引用） */
private const val JS_VALUE_MAX_DEPTH = 4

/** F344：差异摘要里的取值——只取首行 + 截断，空值显示「（空）」 */
private fun briefValue(value: String): String {
    val oneLine = value.lineSequence().firstOrNull().orEmpty()
    return when {
        oneLine.isEmpty() -> "（空）"
        oneLine.length > 24 -> oneLine.take(24) + "…"
        else -> oneLine
    }
}

/** F343：页内测试运行的三种状态（[Idle] = 仅运行按钮，零占位） */
private sealed interface ScriptRunState {
    data object Idle : ScriptRunState
    data object Running : ScriptRunState

    data class Done(
        val bookName: String,
        val chapterTitle: String,
        val returnType: String,
        val output: String,
    ) : ScriptRunState

    data class Failed(
        val bookName: String,
        val chapterTitle: String,
        val errorMessage: String,
    ) : ScriptRunState
}

/**
 * F343：页内测试运行区（运行按钮 + 结果面板）。
 *
 * 成功 = 状态徽标 + 书籍/章节 + 返回类型 + 等宽返回值块（限高可滚、可复制）；
 * 失败 = 状态徽标 + danger 红块（错误信息，可复制）；两者都可「清空结果」收回面板。
 * 取色走 [AppUiTokens.settingPalette]（禁止页内自建取色链）。
 */
@Composable
private fun ScriptRunSection(
    state: ScriptRunState,
    onRun: () -> Unit,
    onCopy: (String) -> Unit,
    onClear: () -> Unit,
) {
    val palette = AppUiTokens.settingPalette()
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(
                onClick = onRun,
                enabled = state !is ScriptRunState.Running,
                shape = AppShapes.Button,
            ) {
                Text(
                    text = if (state is ScriptRunState.Running) {
                        stringResource(R.string.read_menu_button_run_running)
                    } else {
                        stringResource(R.string.read_menu_button_run)
                    }
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            when (state) {
                is ScriptRunState.Done -> Text(
                    text = "✓ " + stringResource(R.string.read_menu_button_run_ok),
                    color = colorResource(R.color.success),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                is ScriptRunState.Failed -> Text(
                    text = "✗ " + stringResource(R.string.read_menu_button_run_fail),
                    color = palette.danger,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                else -> Unit
            }
        }
        when (state) {
            is ScriptRunState.Done -> {
                RunKeyValue(stringResource(R.string.paragraph_rule_debug_book), state.bookName)
                RunKeyValue(stringResource(R.string.paragraph_rule_debug_chapter), state.chapterTitle)
                RunKeyValue(stringResource(R.string.read_menu_button_run_type), state.returnType)
                RunTextBlock(
                    label = stringResource(R.string.read_menu_button_run_result),
                    text = state.output,
                    danger = false,
                    onCopy = onCopy,
                    onClear = onClear,
                )
            }
            is ScriptRunState.Failed -> {
                RunKeyValue(stringResource(R.string.paragraph_rule_debug_book), state.bookName)
                RunKeyValue(stringResource(R.string.paragraph_rule_debug_chapter), state.chapterTitle)
                RunTextBlock(
                    label = stringResource(R.string.read_menu_button_run_error),
                    text = state.errorMessage,
                    danger = true,
                    onCopy = onCopy,
                    onClear = onClear,
                )
            }
            else -> Unit
        }
    }
}

@Composable
private fun RunKeyValue(label: String, value: String) {
    val palette = AppUiTokens.settingPalette()
    Row(modifier = Modifier.padding(top = 6.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = palette.secondaryText,
            modifier = Modifier.padding(end = 8.dp),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = palette.primaryText,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun RunTextBlock(
    label: String,
    text: String,
    danger: Boolean,
    onCopy: (String) -> Unit,
    onClear: () -> Unit,
) {
    val palette = AppUiTokens.settingPalette()
    val body = text.ifBlank { stringResource(R.string.read_menu_button_run_empty) }
    // 块底取状态语义色的低透明衬底（成功绿 / 失败红），正文保持可读的 primaryText
    val tint = if (danger) palette.danger else colorResource(R.color.success)
    Text(
        text = label,
        style = MaterialTheme.typography.labelMedium,
        color = palette.secondaryText,
        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
    )
    Surface(
        color = tint.copy(alpha = 0.10f),
        shape = AppShapes.Button,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = body,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = if (danger) palette.danger else palette.primaryText,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 220.dp)
                .verticalScroll(rememberScrollState())
                .padding(8.dp),
        )
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = stringResource(R.string.paragraph_rule_debug_copy),
            color = palette.accent,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
            modifier = Modifier
                .clickable { onCopy(body) }
                .padding(vertical = 8.dp, horizontal = 4.dp),
        )
        Text(
            text = stringResource(R.string.read_menu_button_run_clear),
            color = palette.secondaryText,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
            modifier = Modifier
                .clickable(onClick = onClear)
                .padding(vertical = 8.dp, horizontal = 12.dp),
        )
    }
}