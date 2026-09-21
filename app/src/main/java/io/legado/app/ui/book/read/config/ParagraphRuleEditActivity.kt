package io.legado.app.ui.book.read.config

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.EditText
import androidx.appcompat.widget.AppCompatImageButton
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.base.BaseActivity
import io.legado.app.data.appDb
import io.legado.app.data.entities.ParagraphRule
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.Book
import io.legado.app.databinding.ActivityParagraphRuleEditBinding
import io.legado.app.help.book.BookHelp
import io.legado.app.help.book.ParagraphRuleProcessor
import io.legado.app.model.ReadBook
import io.legado.app.model.webBook.WebBook
import io.legado.app.utils.stackTraceStr
import io.legado.app.ui.code.CodeEditActivity
import io.legado.app.ui.widget.components.EmptyFieldTemplateRow
import io.legado.app.ui.widget.components.FieldTemplate
import io.legado.app.ui.widget.components.MenuAction
import io.legado.app.ui.widget.components.installGlassTopBar
import io.legado.app.ui.widget.compose.LegadoComposeTheme
import io.legado.app.ui.widget.compose.showComposeChoiceListDialog
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.core.widget.doAfterTextChanged
import io.legado.app.ui.widget.code.addJsPattern
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import io.legado.app.utils.getClipText
import io.legado.app.utils.sendToClip
import io.legado.app.utils.showHelp
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** F62：正文图片标签计数（调试概要展示 imgTags 去留） */
private val IMAGE_TAG_REGEX = Regex("<img\\b", RegexOption.IGNORE_CASE)

// F63（优化 2）：4 个空脚本模板骨架——注入即带注释的可运行结构（模板注释即文档）。
// 均为 `process(ctx)` 形式（引擎按 buildRuleScript 调用 process(ctx)，返回值即处理后正文）；
// 用原始字符串常量，JS 侧转义（\u3000 等）原样保留。

/** 模板 1：短行合并 */
private val TEMPLATE_SHORT_MERGE = """
// 短行合并：把不足 N 字的相邻段落并到上一段（常见于"一句话拆多行"的站点）
// TODO: 可调参数 —— MIN_LEN 合并阈值
function process(ctx) {
  var MIN_LEN = 20;
  var out = [];
  ctx.paragraphs.forEach(function (p) {
    var t = (p.text || '').trim();
    if (!t) return;
    if (out.length > 0 && t.length < MIN_LEN) {
      out[out.length - 1] = out[out.length - 1] + t;
    } else {
      out.push(t);
    }
  });
  return out.join('\n');
}
""".trimIndent()

/** 模板 2：去广告行 */
private val TEMPLATE_REMOVE_ADS = """
// 去广告行：删除命中关键词、纯链接水印的段落
// TODO: 可调参数 —— KEYWORDS 关键词表
function process(ctx) {
  var KEYWORDS = ['请记住本站', '最新章节', '手机版阅读', '本章未完', '内容严重缺失'];
  var LINK = /(https?:\/\/|www\.)/;
  var out = [];
  ctx.paragraphs.forEach(function (p) {
    var t = (p.text || '').trim();
    if (!t) return;
    var hit = false;
    for (var i = 0; i < KEYWORDS.length; i++) {
      if (t.indexOf(KEYWORDS[i]) >= 0) { hit = true; break; }
    }
    if (!hit && LINK.test(t) && t.length < 40) hit = true;
    if (!hit) out.push(t);
  });
  return out.join('\n');
}
""".trimIndent()

/** 模板 3：缩进清理 */
private val TEMPLATE_INDENT = """
// 缩进清理：去掉段首空白与零宽字符，压掉空段
// TODO: 可调参数 —— indent=true 时统一加两个全角空格
function process(ctx) {
  var indent = false;
  var out = [];
  ctx.paragraphs.forEach(function (p) {
    var t = (p.text || '').replace(/^[\s\u3000\u200b]+/, '');
    if (!t) return;
    out.push(indent ? '\u3000\u3000' + t : t);
  });
  return out.join('\n');
}
""".trimIndent()

/** 模板 4：空模板骨架 */
private val TEMPLATE_BLANK = """
// 空模板骨架：最小可运行结构（process 的返回值即处理后的正文）
// ctx.paragraphs: [{ index, text, start, end, separator }]
// ctx.book / ctx.chapter: 当前书籍与章节信息；vars 可跨次保存（ctx.vars）
function process(ctx) {
  var out = [];
  ctx.paragraphs.forEach(function (p) {
    var t = (p.text || '').trim();
    if (!t) return;
    out.push(t); // TODO: 在此改写 t（替换 / 合并 / 丢弃）
  });
  return out.join('\n');
}
""".trimIndent()

class ParagraphRuleEditActivity : BaseActivity<ActivityParagraphRuleEditBinding>() {

    override val binding by viewBinding(ActivityParagraphRuleEditBinding::inflate)
    private var rule = ParagraphRule()
    private var focusedEditText: EditText? = null
    private var bindToken = 0
    private var bindingLargeRuleFields = false

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
        initScriptTemplates()
        val id = intent.getLongExtra("id", 0L)
        lifecycleScope.launch {
            rule = withContext(Dispatchers.IO) { appDb.paragraphRuleDao.get(id) } ?: ParagraphRule()
            bindRule()
        }
    }

    // W7.2（Delta 3→1）：顶栏归一 installGlassTopBar（原 MainTopBarView Mode.SUB 消亡）；
    // 大字段编辑期禁用编辑类 action（原 updateActionButtonStates 改 Compose 状态驱动）
    private var topActionsEnabled by mutableStateOf(true)

    private fun initTopBar() {
        installGlassTopBar(
            binding,
            titleProvider = { getString(R.string.paragraph_rule_edit) },
            actionsProvider = {
                listOf(
                    MenuAction(
                        iconRes = R.drawable.ic_code,
                        title = getString(R.string.edit_content),
                        enabled = topActionsEnabled,
                        alwaysShow = true
                    ) { onFullEditClicked() },
                    MenuAction(
                        iconRes = R.drawable.ic_save,
                        title = getString(R.string.action_save),
                        enabled = topActionsEnabled,
                        alwaysShow = true
                    ) { save() },
                    MenuAction(
                        iconRes = R.drawable.ic_bug_report_outline,
                        title = getString(R.string.debug),
                        enabled = topActionsEnabled,
                        alwaysShow = true
                    ) { debugRule() },
                    MenuAction(
                        iconRes = R.drawable.ic_export,
                        title = getString(R.string.copy_rule),
                        enabled = topActionsEnabled,
                        alwaysShow = true
                    ) { sendToClip(GSON.toJson(getRule())) },
                    MenuAction(
                        iconRes = R.drawable.ic_import,
                        title = getString(R.string.paste_rule),
                        enabled = topActionsEnabled,
                        alwaysShow = true
                    ) { pasteRule() },
                    MenuAction(
                        iconRes = R.drawable.ic_help,
                        title = getString(R.string.help),
                        alwaysShow = true
                    ) { showHelp("paragraphRuleHelp") }
                )
            },
            onBack = { finish() }
        )
    }

    private fun updateActionButtonStates() {
        topActionsEnabled = !bindingLargeRuleFields
    }

    private fun initView() = binding.run {
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
    }

    // F63（优化 2）：空脚本模板 chip 行的显隐（仅脚本为空时出现，已编辑态零占位）。
    // 大字段延迟绑定期间不刷新（`bindingLargeRuleFields`）⇒ 打开既有规则时不会闪出模板行。
    private var scriptTemplateVisible by mutableStateOf(false)

    /** F63：空脚本模板（label 走 i18n，骨架为纯 JS 常量，注释即文档） */
    private fun scriptTemplates(): List<FieldTemplate> = listOf(
        FieldTemplate(getString(R.string.paragraph_rule_template_short_merge), TEMPLATE_SHORT_MERGE),
        FieldTemplate(getString(R.string.paragraph_rule_template_remove_ads), TEMPLATE_REMOVE_ADS),
        FieldTemplate(getString(R.string.paragraph_rule_template_indent), TEMPLATE_INDENT),
        FieldTemplate(getString(R.string.paragraph_rule_template_blank), TEMPLATE_BLANK),
    )

    private fun initScriptTemplates() {
        binding.etScript.doAfterTextChanged {
            if (!bindingLargeRuleFields) {
                scriptTemplateVisible = it.isNullOrBlank()
            }
        }
        binding.cvScriptTemplates.setViewCompositionStrategy(
            ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed
        )
        binding.cvScriptTemplates.setContent {
            LegadoComposeTheme {
                if (scriptTemplateVisible) {
                    EmptyFieldTemplateRow(
                        title = getString(R.string.paragraph_rule_template_title),
                        templates = scriptTemplates(),
                        onPick = { template ->
                            binding.etScript.setText(template.code)
                            binding.etScript.setSelection(template.code.length)
                        },
                    )
                }
            }
        }
    }

    private fun bindRule() = binding.run {
        val token = ++bindToken
        bindingLargeRuleFields = true
        setLargeEditorsEnabled(false)
        updateActionButtonStates()
        etName.setText(rule.name)
        etLoginUrl.setText(rule.loginUrl)
        etLoginUi.setText(rule.loginUi)
        cbIsEnableCookie.isChecked = rule.enabledCookieJar
        etTimeout.setText(rule.validTimeout().toString())
        etScript.setText("")
        etJsLib.setText("")
        val script = rule.script
        val jsLib = rule.jsLib
        root.post {
            if (!isActiveBind(token)) return@post
            etScript.setText(script)
            etScript.post {
                if (!isActiveBind(token)) return@post
                etJsLib.setText(jsLib)
                bindingLargeRuleFields = false
                setLargeEditorsEnabled(true)
                updateActionButtonStates()
                // 绑定结束再判定一次：新建规则（脚本为空）⇒ 显示模板 chip 行
                scriptTemplateVisible = etScript.text.isNullOrBlank()
            }
        }
    }

    private fun isActiveBind(token: Int): Boolean {
        return token == bindToken && !isFinishing && !isDestroyed
    }

    private fun setLargeEditorsEnabled(enabled: Boolean) = binding.run {
        etScript.isEnabled = enabled
        etJsLib.isEnabled = enabled
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
        R.id.et_script -> getString(R.string.paragraph_rule_script)
        R.id.et_js_lib -> "jsLib"
        else -> null
    }

    private fun debugRule() {
        val debugRule = getRule()
        lifecycleScope.launch {
            val pair = withContext(Dispatchers.IO) {
                val book = ReadBook.book ?: return@withContext null
                val chapters = appDb.bookChapterDao.getChapterList(book.bookUrl)
                book to chapters
            }
            val book = pair?.first
            val chapters = pair?.second.orEmpty()
            if (book == null) {
                toastOnUi(R.string.paragraph_rule_no_book_hint)
                return@launch
            }
            if (chapters.isEmpty()) {
                toastOnUi("No chapters")
                return@launch
            }
            val current = ReadBook.durChapterIndex
            val start = (current - 20).coerceAtLeast(0)
            val candidates = chapters.drop(start).take(80)
            val labels = candidates.map { chapter ->
                "${chapter.index + 1}. ${chapter.title}"
            }
            showComposeChoiceListDialog(getString(R.string.debug), labels) { index ->
                candidates.getOrNull(index)?.let { chapter ->
                    runDebugRule(debugRule, book, chapter)
                }
            }
        }
    }

    private fun runDebugRule(debugRule: ParagraphRule, book: Book, chapter: BookChapter) {
        lifecycleScope.launch {
            val outcome = kotlin.runCatching {
                withContext(Dispatchers.IO) {
                    val content = BookHelp.getContent(book, chapter) ?: run {
                        val source = ReadBook.bookSource ?: throw IllegalStateException("No book source and no local cache")
                        WebBook.getContentAwait(source, book, chapter, needSave = false)
                    }
                    val debug = ParagraphRuleProcessor.debug(debugRule, book, chapter, content)
                    val result = debug.content
                    // F62：概要指标改为前→后对比（before = 规则执行前的规范化正文）
                    val before = debug.inputContent
                    ParagraphRuleDebugDialog(
                        success = true,
                        bookName = book.name,
                        chapterTitle = chapter.title,
                        beforeParagraphs = before.paragraphCount(),
                        afterParagraphs = result.paragraphCount(),
                        beforeLength = before.length,
                        afterLength = result.length,
                        imageTags = IMAGE_TAG_REGEX.findAll(result).count(),
                        logs = debug.logs.joinToString("\n"),
                        content = result,
                    )
                }
            }.getOrElse {
                ParagraphRuleDebugDialog(
                    success = false,
                    bookName = book.name,
                    chapterTitle = chapter.title,
                    beforeParagraphs = 0,
                    afterParagraphs = 0,
                    beforeLength = 0,
                    afterLength = 0,
                    imageTags = 0,
                    logs = "",
                    // 失败态把原始错误详情放进"正文预览"（可复制，保留原有排查信息）
                    content = "Paragraph rule debug failed:\n${it.localizedMessage ?: it}\n\n${it.stackTraceStr}",
                    errorMessage = it.localizedMessage ?: it.toString(),
                )
            }
            outcome.show(supportFragmentManager, "paragraphRuleDebug")
        }
    }

    /** 段落数口径与调试弹窗展示一致：非空行计数 */
    private fun String.paragraphCount(): Int = split('\n').count { it.isNotBlank() }

    private fun pasteRule() {
        val raw = getClipText()
        val imported = GSON.fromJsonObject<ParagraphRule>(raw).getOrNull()
        if (imported == null) {
            toastOnUi(R.string.wrong_format)
            return
        }
        rule = imported.copy(id = rule.id, order = rule.order, updateTime = System.currentTimeMillis())
        bindRule()
    }

    private fun getRule(): ParagraphRule = binding.run {
        rule.copy(
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
        val edited = getRule()
        if (edited.name.isBlank() || edited.script.isBlank()) {
            toastOnUi(R.string.paragraph_rule_save_invalid)
            return
        }
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                if (edited.id == 0L) {
                    val order = (appDb.paragraphRuleDao.maxOrder() ?: 0) + 1
                    appDb.paragraphRuleDao.insert(edited.copy(order = order))
                } else {
                    appDb.paragraphRuleDao.update(edited)
                }
            }
            setResult(Activity.RESULT_OK)
            finish()
        }
    }
}
