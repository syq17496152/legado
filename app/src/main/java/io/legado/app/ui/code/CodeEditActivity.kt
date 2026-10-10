package io.legado.app.ui.code

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.View
import androidx.activity.viewModels
import androidx.annotation.RequiresApi
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.Article
import androidx.compose.material.icons.outlined.FormatAlignLeft
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.WrapText
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.core.widget.addTextChangedListener
import androidx.lifecycle.lifecycleScope
import androidx.viewbinding.ViewBinding
import com.google.android.material.textfield.TextInputEditText
import io.github.rosemoe.sora.event.ContentChangeEvent
import io.github.rosemoe.sora.event.PublishSearchResultEvent
import io.github.rosemoe.sora.event.SelectionChangeEvent
import io.github.rosemoe.sora.langs.textmate.registry.ThemeRegistry
import io.github.rosemoe.sora.util.regex.RegexBackrefGrammar
import io.github.rosemoe.sora.widget.CodeEditor
import io.github.rosemoe.sora.widget.EditorSearcher
import io.github.rosemoe.sora.widget.EditorSearcher.SearchOptions
import io.legado.app.R
import io.legado.app.base.VMBaseActivity
import io.legado.app.base.attachComposeContent
import io.legado.app.base.composeShell
import io.legado.app.constant.AppLog
import io.legado.app.constant.PreferKey
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.ThemeConfig
import io.legado.app.lib.dialogs.SelectItem
import io.legado.app.ui.about.AppLogDialog
import io.legado.app.ui.code.config.ChangeThemeDialog
import io.legado.app.ui.code.config.SettingsDialog
import io.legado.app.ui.widget.compose.AppSemanticColors
import io.legado.app.ui.widget.compose.AppUiTokens
import io.legado.app.ui.widget.compose.showComposeConfirmDialog
import io.legado.app.ui.theme.LegadoTheme
import io.legado.app.ui.widget.components.AppDropdownMenu
import io.legado.app.ui.widget.components.GlassTopAppBar
import io.legado.app.ui.widget.components.MenuAction
import io.legado.app.ui.widget.keyboard.KeyboardToolPop
import io.legado.app.utils.imeHeight
import io.legado.app.utils.putPrefBoolean
import io.legado.app.utils.setOnApplyWindowInsetsListenerCompat
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.showHelp

class CodeEditActivity :
    VMBaseActivity<ViewBinding, CodeEditViewModel>(),
    KeyboardToolPop.CallBack, ChangeThemeDialog.CallBack, SettingsDialog.CallBack {
    companion object {
        private var isInitialized = false
        private var findText = ""
        private var replaceText = ""
        private var isRegex = true
    }

    // CE 5.2（compose 包）：原 activity_code_edit.xml 已退役 ⇒ composeShell 合成壳 +
    // attachComposeContent 单源承载；两处 View 内核（Sora `CodeEditor` / 搜索替换面板）
    // 无 Compose 等价物 ⇒ 一律 `AndroidView` 原样托管（面板节点在代码里逐项复刻 XML）。
    override val binding: ViewBinding by lazy { composeShell(this) }
    override val viewModel by viewModels<CodeEditViewModel>()
    private val softKeyboardTool by lazy {
        KeyboardToolPop(this, lifecycleScope, binding.root, this)
    }

    /** 原 `@+id/editText`（Sora 代码编辑器内核，无 Compose 等价物）。 */
    private val editor: CodeEditor by lazy {
        CodeEditor(this).apply {
            // 原 XML `app:textSize="@dimen/text_18sp"` 的初值（随后由 upEdit(AppConfig.editFontScale) 覆盖）。
            // Sora 覆写了 `setTextSize(Float)`（单位 sp、无 2 参重载）⇒ 由 dimen 的像素值反算 sp 数值
            setTextSize(
                resources.getDimension(R.dimen.text_18sp) / resources.displayMetrics.scaledDensity
            )
        }
    }
    private val editorSearcher: EditorSearcher by lazy { editor.searcher }
    private var searchOptions: SearchOptions? = null
    private var menuExpanded by mutableStateOf(false)
    private var titleState by mutableStateOf("")
    private var saveVisible by mutableStateOf(false)
    // F196：未保存（脏态）可视化；F197：只读模式显性化（原文案/行为不变，仅补状态层）
    private var dirtyState by mutableStateOf(false)
    private var readonlyState by mutableStateOf(false)
    /** initData 回调内 setText 会触发内容变更事件，用该标记吞掉初始化自身产生的事件 */
    private var editorInitDone = false
    /** 载荷请求时刻（4.8c「首屏 <1s」的计时起点；见 `editor ready` 日志）。 */
    private var payloadRequestedAt = 0L
    private var autoWrapChecked by mutableStateOf(AppConfig.editAutoWrap)

    private val isDark
        get() = AppConfig.editTemeAuto && ThemeConfig.isDarkTheme()
    private var themeIndex = -1

    // ==================== CE 5.2：搜索/替换面板（视图构造见 CodeEditSearchPanelViews） ====================
    // 4.8c 拆分：原 XML `search_group` 的程序化复刻（约 190 行）已下沉到 [CodeEditSearchPanelViews]，
    // 本类只保留**行为**（搜索/替换流程 + 显隐切换）。控件语义与取色口径逐行不变。

    /** 面板视图（懒构造：与旧实现同为"首次访问才建"）。 */
    private val searchViews by lazy { CodeEditSearchPanelViews(this) }

    private val searchPanel get() = searchViews.searchPanel
    private val switchRegex get() = searchViews.switchRegex
    private val tvSearchResult get() = searchViews.tvSearchResult
    private val etFind get() = searchViews.etFind
    private val btnCloseFind get() = searchViews.btnCloseFind
    private val etReplace get() = searchViews.etReplace
    private val btnCloseReplace get() = searchViews.btnCloseReplace
    private val btnPrevious get() = searchViews.btnPrevious
    private val btnNext get() = searchViews.btnNext
    private val btnReplace get() = searchViews.btnReplace
    private val btnReplaceAll get() = searchViews.btnReplaceAll
    private val replaceGroup get() = searchViews.replaceGroup

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        softKeyboardTool.attachToWindow(window)
        editor.colorScheme = TextMateColorScheme2.create(ThemeRegistry.getInstance()) //先设置颜色,避免一开始的白屏
        payloadRequestedAt = System.currentTimeMillis()
        viewModel.initData(intent) {
            editor.apply {
                viewModel.title?.let {
                    titleState = it
                }
                nonPrintablePaintingFlags = AppConfig.editNonPrintable
                setEditorLanguage(viewModel.language)
                upEdit(AppConfig.editFontScale, null, AppConfig.editAutoWrap)
                setText(viewModel.initialText)
                editable = viewModel.writable
                saveVisible = viewModel.writable
                readonlyState = !viewModel.writable
                requestFocus()
                // 4.8c「首屏 <1s」的**可测口径**：从发起 initData 到"文本已进编辑器、可编辑"的耗时。
                // 大载荷（模板 CSS ≈320KB）在此路径上必须仍在一秒内，否则编辑体验是"点了半天不动"。
                AppLog.putDebugWithTag(
                    AppLog.TAG_CODE_EDIT,
                    "editor ready: chars=${viewModel.initialText.length}, " +
                        "writable=${viewModel.writable}, costMs=${System.currentTimeMillis() - payloadRequestedAt}"
                )
                postDelayed({
                    val pos = cursor.indexer.getCharPosition(viewModel.cursorPosition)
                    setSelection(pos.line, pos.column, true)
                    // 光标跳转完成后才允许脏态跟踪，避免初始化/定位过程被误判为「已修改」
                    editorInitDone = true
                }, 360) // 进行延时,确保加载渲染完成,从而确保光标能显示跳转到长文本最后
            }
        }
        initView()
        initComposeContent()
        initDirtyTracking()
    }

    /** F196：脏态跟踪——编辑器内容变更即置「未保存」（保存语义 = 携带结果退出，故无就地保存回执） */
    private fun initDirtyTracking() {
        editor.subscribeEvent(ContentChangeEvent::class.java) { _, _ ->
            if (editorInitDone && !dirtyState) {
                dirtyState = true
            }
        }
    }

    private fun initView() {
        // CE 5.2：insets 锚点随换装改到合成壳 root（原 XML 根 View 已退役）。
        // ComposeView 作为 root 的子节点照常派发 insets ⇒ 监听体与原实现一字不变。
        binding.root.setOnApplyWindowInsetsListenerCompat { _, windowInsets ->
            softKeyboardTool.initialPadding = windowInsets.imeHeight
            windowInsets
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        editorSearcher.stopSearch()
        editor.release()
    }

    /**
     * 使用super.finish(),防止循环回调
     * */
    private fun save(check: Boolean) {
        if (!viewModel.writable) return super.finish()
        val text = editor.text.toString()
        val cursorPos = editor.cursor?.left ?: 0
        when {
            text == viewModel.initialText -> {
                if (cursorPos > 0) {
                    val result = Intent().apply {
                        putExtra("cursorPosition", cursorPos)
                    }
                    setResult(RESULT_OK, result)
                }
                super.finish()
            }
            check -> {
                showComposeConfirmDialog(
                    title = getString(R.string.exit),
                    message = getString(R.string.exit_no_save),
                    positiveText = getString(R.string.yes),
                    negativeText = getString(R.string.no),
                    onPositive = { /* 停留当前页，不退出 */ },
                    onNegative = {
                        if (cursorPos > 0) {
                            val result = Intent().apply {
                                putExtra("cursorPosition", cursorPos)
                            }
                            setResult(RESULT_OK, result)
                        }
                        super.finish()
                    }
                )
            }
            else -> {
                setResult(RESULT_OK, buildResult(text, cursorPos))
                super.finish()
            }
        }
    }

    /**
     * 构造回传结果（4.8c **出方向**载荷通道）。
     *
     * 真机铁证（2026-10-10）：320KB 模板 CSS 经 result Intent 回传 ⇒
     * `TransactionTooLargeException (data parcel size 662368 bytes)` ⇒ **进程被杀、用户编辑全部丢失**
     * （日志里只有 `Process … has died: fore TOP`，没有 FATAL EXCEPTION ⇒ 极易被误判为"偶发闪退"）。
     * 故大载荷与入方向同口径：写临时文件、只回传路径；**仅当调用方显式开启**文件通道
     * （其它复用本页的调用方只读 `text` extra，不能改它们的数据形态）。
     */
    private fun buildResult(text: String, cursorPos: Int): Intent {
        val result = Intent().apply { putExtra("cursorPosition", cursorPos) }
        val optIn = intent.getBooleanExtra(CodeEditPayloadPolicy.ExtraFileChannelOptIn, false)
        if (!optIn || !CodeEditPayloadPolicy.useFileChannel(text.length)) {
            return result.apply { putExtra("text", text) }
        }
        val payloadFile = CodeEditPayloadStore(this).write(text)
        if (payloadFile == null) {
            // 写不进去就回落内联：宁可能溢出 Binder，也不能"保存了却什么都没带回去"
            AppLog.putDebugWithTag(
                AppLog.TAG_CODE_EDIT,
                "result payload file write failed ⇒ fallback inline: chars=${text.length}"
            )
            return result.apply { putExtra("text", text) }
        }
        AppLog.putDebugWithTag(
            AppLog.TAG_CODE_EDIT,
            "result payload via file: chars=${text.length}"
        )
        return result.apply { putExtra(CodeEditPayloadPolicy.ExtraTextFile, payloadFile.absolutePath) }
    }

    override fun upEdit(fontSize: Int?, autoComplete: Boolean?, autoWarp: Boolean?, editNonPrintable: Int?) {
        if (fontSize != null) {
            editor.setTextSize(fontSize.toFloat())
        }
        if (autoComplete != null) {
            viewModel.language?.isAutoCompleteEnabled = autoComplete
            editor.setEditorLanguage(viewModel.language)
        }
        if (autoWarp != null) {
            editor.isWordwrap = autoWarp
        }
        if (editNonPrintable != null) {
            editor.nonPrintablePaintingFlags = editNonPrintable
        }
    }

    override fun initTheme() {
        super.initTheme()
        if (!isInitialized) {
            viewModel.initSora()
            isInitialized = true
        }
        val index = if (isDark) {
            AppConfig.editThemeDark
        } else {
            AppConfig.editTheme
        }
        upTheme(index)
        themeIndex = index
    }

    override fun upTheme(index: Int) {
        if (themeIndex != index) {
            viewModel.loadTextMateThemes(index)
            editor.setEditorLanguage(viewModel.language) //每次更改颜色后需要再执行一次语言设置,防止切换主题后高亮颜色不正确
            themeIndex = index
        }
    }

    /**
     * CE 5.2：Compose 承载页面骨架（顶栏 + 编辑器 + 搜索/替换面板）。
     *
     * 与原 XML（`activity_code_edit.xml`）的**逐一对应关系**（三不影响口径）：
     *  · `compose_top_bar` → `LegadoTheme { GlassTopAppBar(…) }`；4.8c 起 `LegadoTheme` **上提为宿主入口顶层**（G-37：只包顶栏会让内容子树在主题包/夜间下回落 M3 默认色板），顶栏内容逐行不变
     *  · `search_group`（`wrap_content` + `gone`）→ 视图构造见 [CodeEditSearchPanelViews]（4.8c 按职责拆分），
     *    宿主经 `AndroidView` 托管；**显隐仍由宿主按 View 语义切换**（`visibility`）⇒ 视图实例常驻、
     *    监听器与搜索订阅语义与原实现一致。
     *    原 `layout_gravity=bottom` 在竖向 LinearLayout 中对 `wrap_content` 子节点无几何作用
     *    （`editText` 的 weight 已把面板压到底部）⇒ 等价。
     */
    private fun initComposeContent() {
        binding.root.attachComposeContent {
            // G-37：宿主入口**顶层**必须是主题作用域（原形态只把顶栏包在 LegadoTheme 里 ⇒
            // 内容子树在主题包/夜间下会回落 M3 默认亮色基线）。上提为承载内容的根作用域，语义与取色不变。
            LegadoTheme {
                Column(modifier = Modifier.fillMaxSize()) {
                    // ---- 顶栏（原 compose_top_bar，内容逐行不变）----
                    GlassTopAppBar(
                        title = titleState.ifBlank { getString(R.string.edit_code) },
                        navIcon = Icons.AutoMirrored.Filled.ArrowBack,
                        onNavClick = { finish() },
                        // F196/F197 状态行：**必须挂 secondRow**——实测固定栏高下 `subtitle` 槽被裁掉不可见
                        // （真机截图铁证：脏态时保存键已 accent 高亮，但 subtitle 未渲染），secondRow 为栏内第二行常显区。
                        secondRow = when {
                            readonlyState -> {
                                {
                                    Text(
                                        text = stringResource(R.string.code_edit_readonly_bar),
                                        color = AppSemanticColors.Warning,
                                        fontSize = MaterialTheme.typography.bodySmall.fontSize,
                                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
                                    )
                                }
                            }
                            dirtyState -> {
                                {
                                    Text(
                                        text = stringResource(R.string.code_edit_unsaved),
                                        color = AppUiTokens.settingPalette().accent,
                                        fontSize = MaterialTheme.typography.bodySmall.fontSize,
                                        fontWeight = FontWeight.Medium,
                                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
                                    )
                                }
                            }
                            else -> null
                        },
                        actions = {
                            // 常驻快捷按钮：搜索 / 保存
                            IconButton(onClick = { search() }) {
                                Icon(Icons.Outlined.Search, contentDescription = null)
                            }
                            if (saveVisible) {
                                IconButton(onClick = { save(false) }) {
                                    // F196：脏态下保存键 accent 高亮（干净态保持既有前景色）
                                    Icon(
                                        Icons.Outlined.Save,
                                        // 保存键必须有可读文案：既是无障碍（TalkBack）要求，
                                        // 也是 4.8c 真机 L2 能定位并点击该按钮的唯一凭据（原为 null ⇒ 无法按描述定位）
                                        contentDescription = "保存",
                                        tint = if (dirtyState) {
                                            AppUiTokens.settingPalette().accent
                                        } else {
                                            LocalContentColor.current
                                        }
                                    )
                                }
                            } else {
                                // F197：只读态原保存位给「只读」徽章（告知能力缺失而非静默消失）
                                Text(
                                    text = stringResource(R.string.code_edit_readonly_badge),
                                    color = AppSemanticColors.Warning,
                                    fontSize = MaterialTheme.typography.labelMedium.fontSize,
                                    fontWeight = FontWeight.Medium,
                                    modifier = Modifier.padding(horizontal = 8.dp)
                                )
                            }
                            // 溢出菜单
                            Box {
                                IconButton(onClick = { menuExpanded = true }) {
                                    Icon(Icons.Filled.MoreVert, contentDescription = null)
                                }
                                AppDropdownMenu(
                                    expanded = menuExpanded,
                                    onDismiss = { menuExpanded = false },
                                    actions = buildMenuActions()
                                )
                            }
                        }
                    )
                // ---- 编辑器内核（原 editText：match_parent × 0dp + weight 1）----
                AndroidView(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    factory = { editor }
                )
                // ---- 搜索/替换面板（原 search_group：match_parent × wrap_content）----
                AndroidView(
                    modifier = Modifier.fillMaxWidth(),
                    factory = { searchPanel }
                )
                }
            }
        }
    }

    private fun buildMenuActions(): List<MenuAction> = buildList {
        // 格式化
        add(
            MenuAction(
                icon = Icons.Outlined.FormatAlignLeft,
                title = getString(R.string.format_code),
                onClick = { viewModel.formatCode(editor) }
            )
        )
        // 更换主题
        add(
            MenuAction(
                icon = Icons.Outlined.Palette,
                title = getString(R.string.change_theme),
                onClick = { showDialogFragment(ChangeThemeDialog()) }
            )
        )
        // 配置设置
        add(
            MenuAction(
                icon = Icons.Outlined.Settings,
                title = getString(R.string.config_settings),
                onClick = { showDialogFragment(SettingsDialog(this@CodeEditActivity, this@CodeEditActivity)) }
            )
        )
        // 自动换行（勾选态）
        add(
            MenuAction(
                icon = Icons.Outlined.WrapText,
                title = getString(R.string.auto_wrap),
                checked = autoWrapChecked,
                onClick = {
                    autoWrapChecked = !AppConfig.editAutoWrap
                    upEdit(autoWarp = !AppConfig.editAutoWrap)
                    putPrefBoolean(PreferKey.editAutoWrap, !AppConfig.editAutoWrap)
                }
            )
        )
        // 日志
        add(
            MenuAction(
                icon = Icons.Outlined.Article,
                title = getString(R.string.log),
                onClick = { showDialogFragment<AppLogDialog>() }
            )
        )
    }

    private fun setSearchOptions() {
        searchOptions =  SearchOptions(
            if (isRegex) SearchOptions.TYPE_REGULAR_EXPRESSION else SearchOptions.TYPE_NORMAL,
            !isRegex,
            RegexBackrefGrammar.DEFAULT
        )
    }

    override fun finish() {
        save(true)
    }

    private fun search() {
        if (searchPanel.isVisible) return
        switchRegex.run {
            isChecked = isRegex
            setSearchOptions()
            setOnCheckedChangeListener { _, isChecked ->
                isRegex = isChecked
                setSearchOptions()
                searchTxt(etFind.text.toString())
            }
        }
        val receiptSearch =
            editor.subscribeEvent(PublishSearchResultEvent::class.java) { event, _ ->
                if (event.editor == editor) {
                    updateSearchResults()
                }
            }
        val receiptChange = editor.subscribeEvent(SelectionChangeEvent::class.java) { event, _ ->
            if (event.cause == SelectionChangeEvent.CAUSE_SEARCH) {
                updateSearchResults()
            }
        }
        searchPanel.visibility = View.VISIBLE
        btnCloseFind.setOnClickListener {
            searchPanel.visibility = View.GONE
            editorSearcher.stopSearch()
            receiptSearch.unsubscribe()
            receiptChange.unsubscribe()
            editor.requestFocus()
            editor.invalidate()
        }
        searchTxt(findText)
        etFind.run {
            requestFocus()
            setText(findText)
            addTextChangedListener { text ->
                if (!text.isNullOrEmpty()) {
                    findText = text.toString()
                    searchTxt(findText)
                } else {
                    editorSearcher.stopSearch()
                    editor.invalidate()
                }
            }

        }
        etReplace.run {
            setText(replaceText)
            addTextChangedListener { text ->
                if (!text.isNullOrEmpty()) {
                    replaceText = text.toString()
                }
            }
        }
        btnPrevious.setOnClickListener {
            if (editorSearcher.hasQuery()) {
                editorSearcher.gotoPrevious()
            }
        }
        btnNext.setOnClickListener {
            if (editorSearcher.hasQuery()) {
                editorSearcher.gotoNext()
            }
        }
        btnReplace.setOnClickListener {
            if (replaceGroup.isGone) {
                replaceGroup.visibility = View.VISIBLE
                btnReplaceAll.isEnabled = true
                etReplace.requestFocus()
            } else {
                if (editorSearcher.hasQuery()) {
                    editorSearcher.replaceCurrentMatch(etReplace.text.toString())
                }
            }
        }
        btnCloseReplace.setOnClickListener {
            replaceGroup.visibility = View.GONE
            btnReplaceAll.isEnabled = false
            etFind.requestFocus()
        }
        btnReplaceAll.setOnClickListener {
            if (editorSearcher.hasQuery()) {
                editorSearcher.replaceAll(etReplace.text.toString())
            }
        }
    }

    private fun searchTxt(txt: String) {
        if (txt.isNotEmpty()) {
            try {
                searchOptions?.let {
                    editorSearcher.search(txt, it)
                }
            } catch (_: java.util.regex.PatternSyntaxException) {
                // 忽略正则表达式语法错误
                editorSearcher.stopSearch()
                editor.invalidate()
            }
        }
    }

    @SuppressLint("SetTextI18n")
    private fun updateSearchResults() {
        if (editorSearcher.hasQuery()) {
            val totalResults = editorSearcher.matchedPositionCount
            val currentPosition = editorSearcher.currentMatchedPositionIndex + 1
            tvSearchResult.text =
                "${if (currentPosition > 0) "$currentPosition/" else ""}$totalResults"
        }
    }

    override fun helpActions(): List<SelectItem<String>> {
        return arrayListOf(
            SelectItem("书源教程", "ruleHelp"),
            SelectItem("订阅源教程", "rssRuleHelp"),
            SelectItem("js教程", "jsHelp"),
            SelectItem("正则教程", "regexHelp")
        )
    }

    override fun onHelpActionSelect(action: String) {
        when (action) {
            "ruleHelp" -> showHelp("ruleHelp")
            "rssRuleHelp" -> showHelp("rssRuleHelp")
            "jsHelp" -> showHelp("jsHelp")
            "regexHelp" -> showHelp("regexHelp")
        }
    }

    override fun sendText(text: String) {
        val view = window.decorView.findFocus()
        if (view is TextInputEditText) {
            var start = view.selectionStart
            var end = view.selectionEnd
            if (start > end) {
                val temp = start
                start = end
                end = temp
            }
            if (text.isNotEmpty()) {
                val edit = view.editableText//获取EditText的文字
                if (start < 0 || start >= edit.length) {
                    edit.append(text)
                } else {
                    edit.replace(start, end, text)//光标所在位置插入文字
                }
            }
        }
        else {
            editor.insertText(text, text.length)
        }
    }

    @RequiresApi(Build.VERSION_CODES.M)
    override fun onUndoClicked() {
        editor.undo()
    }

    @RequiresApi(Build.VERSION_CODES.M)
    override fun onRedoClicked() {
        editor.redo()
    }
}