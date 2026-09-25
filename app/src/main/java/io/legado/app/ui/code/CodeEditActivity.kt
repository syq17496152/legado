package io.legado.app.ui.code

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Space
import androidx.activity.viewModels
import androidx.annotation.RequiresApi
import androidx.appcompat.content.res.AppCompatResources
import androidx.appcompat.widget.AppCompatButton
import androidx.appcompat.widget.AppCompatImageView
import androidx.appcompat.widget.AppCompatTextView
import androidx.appcompat.widget.SwitchCompat
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
import com.google.android.material.textfield.TextInputLayout
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
import io.legado.app.constant.PreferKey
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.ThemeConfig
import io.legado.app.lib.dialogs.SelectItem
import io.legado.app.lib.theme.themeCardColorOrDefault
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
import io.legado.app.utils.dpToPx
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
    private var autoWrapChecked by mutableStateOf(AppConfig.editAutoWrap)

    private val isDark
        get() = AppConfig.editTemeAuto && ThemeConfig.isDarkTheme()
    private var themeIndex = -1

    // ==================== CE 5.2：原 XML 搜索/替换面板节点的程序化等价物 ====================
    // 原 `search_group` 子树在代码里逐项复刻；控件类与被 AppCompat 替换后的实际类型一致
    // （`TextView`/`ImageView`/`Button` → `AppCompat*`，`Switch` → `SwitchCompat`）。

    /** 面板内文本节点（原 `@dimen/text_14sp`；[colorRes] 非 0 时对齐原 `android:textColor`）。 */
    private fun panelText(colorRes: Int = 0): AppCompatTextView =
        AppCompatTextView(this).apply {
            textSize = 14f
            if (colorRes != 0) {
                setTextColor(
                    AppCompatResources.getColorStateList(this@CodeEditActivity, colorRes)
                )
            }
        }

    /** 原 `style="?android:attr/buttonBarButtonStyle"` 的等价值（以该属性为默认样式属性构造）。 */
    private fun panelButton(textRes: Int, colorRes: Int): AppCompatButton =
        AppCompatButton(this, null, android.R.attr.buttonBarButtonStyle).apply {
            setText(textRes)
            textSize = 14f
            setTextColor(AppCompatResources.getColorStateList(this@CodeEditActivity, colorRes))
        }

    /** 原 `TextInputLayout(boxBackgroundMode=none)` + 子 `TextInputEditText` 成对结构。 */
    private fun inputField(child: EditText): TextInputLayout =
        TextInputLayout(this, null).apply {
            boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_NONE
            addView(
                child, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            )
        }

    /** 原面板内的关闭图标（`8dp` 内边距 + `ic_baseline_close`）。 */
    private fun panelCloseIcon(): AppCompatImageView = AppCompatImageView(this).apply {
        contentDescription = getString(R.string.close)
        scaleType = ImageView.ScaleType.CENTER
        setImageResource(R.drawable.ic_baseline_close)
        val pad = 8.dpToPx()
        setPadding(pad, pad, pad, pad)
    }

    /** `wrap_content × wrap_content` 的 LinearLayout 子节点布局参数。 */
    private fun wrap() = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.WRAP_CONTENT,
        LinearLayout.LayoutParams.WRAP_CONTENT
    )

    private val tvSearchResultLabel by lazy { panelText().apply { setText(R.string.search_result) } }
    private val tvSearchResult by lazy { panelText().apply { text = "0" } }
    private val switchRegex by lazy {
        SwitchCompat(this).apply {
            isChecked = true
            setText(R.string.regex)
        }
    }
    private val tvFindLabel by lazy { panelText(R.color.primaryText).apply { setText(R.string.find) } }
    private val etFind by lazy { TextInputEditText(this) }
    private val btnCloseFind by lazy { panelCloseIcon() }
    private val tvReplaceLabel by lazy {
        panelText(R.color.primaryText).apply { setText(R.string.replace) }
    }
    private val etReplace by lazy { TextInputEditText(this) }
    private val btnCloseReplace by lazy { panelCloseIcon() }
    private val btnPrevious by lazy { panelButton(R.string.btn_previous, R.color.primaryText) }
    private val btnNext by lazy { panelButton(R.string.btn_next, R.color.primaryText) }
    private val btnReplace by lazy { panelButton(R.string.replace, R.color.primaryText) }
    private val btnReplaceAll by lazy {
        panelButton(R.string.replace_all, R.color.selector_btn_text_color).apply {
            isEnabled = false
        }
    }

    /** 原 `replace_group`（默认 `gone`，点击「替换」后才展开）。 */
    private val replaceGroup by lazy {
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            addView(tvReplaceLabel, wrap())
            addView(
                inputField(etReplace), LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                )
            )
            addView(btnCloseReplace, wrap())
        }
    }

    /**
     * 原 `search_group`（`12dp` 左右内边距 / 默认 `gone`）。
     *
     * 面板底原为 XML 静态 `@color/background_card`（R30 技术债，`theme_token_allowlist.json`
     * 已登记「待 code-side 改造」）⇒ 换装后改走运行时面 token [themeCardColorOrDefault]
     * （卡片面，`color.md` §六 面 token 归属表），与 Compose 侧 `themeUi.cardColor` 同语义。
     */
    private val searchPanel: LinearLayout by lazy {
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setBackgroundColor(themeCardColorOrDefault())
            val pad = 12.dpToPx()
            setPadding(pad, 0, pad, 0)
            // 行 1：命中计数 + 正则开关
            addView(
                LinearLayout(this@CodeEditActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                    addView(tvSearchResultLabel, wrap())
                    addView(tvSearchResult, wrap().apply { marginStart = 8.dpToPx() })
                    addView(
                        Space(this@CodeEditActivity),
                        LinearLayout.LayoutParams(0, 0, 1f)
                    )
                    addView(switchRegex, wrap())
                }
            )
            // 行 2：查找
            addView(
                LinearLayout(this@CodeEditActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    clipChildren = false
                    clipToPadding = false
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                    addView(tvFindLabel, wrap())
                    addView(
                        inputField(etFind), LinearLayout.LayoutParams(
                            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                        )
                    )
                    addView(btnCloseFind, wrap())
                }
            )
            // 行 3：替换（默认收起）
            addView(replaceGroup)
            // 行 4：操作按钮条（原 `style="?android:attr/buttonBarStyle"`）
            addView(
                LinearLayout(this@CodeEditActivity, null, android.R.attr.buttonBarStyle).apply {
                    orientation = LinearLayout.HORIZONTAL
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                    addView(
                        btnPrevious, LinearLayout.LayoutParams(
                            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                        )
                    )
                    addView(
                        btnNext, LinearLayout.LayoutParams(
                            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                        )
                    )
                    addView(
                        btnReplace, LinearLayout.LayoutParams(
                            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                        )
                    )
                    addView(
                        btnReplaceAll, LinearLayout.LayoutParams(
                            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                        )
                    )
                }
            )
        }
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        softKeyboardTool.attachToWindow(window)
        editor.colorScheme = TextMateColorScheme2.create(ThemeRegistry.getInstance()) //先设置颜色,避免一开始的白屏
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
                val result = Intent().apply {
                    putExtra("text", text)
                    putExtra("cursorPosition", cursorPos)
                }
                setResult(RESULT_OK, result)
                super.finish()
            }
        }
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
     *  · `compose_top_bar` → 顶部 `LegadoTheme { GlassTopAppBar(…) }`（内容逐行搬入，不再套壳 ComposeView）
     *  · `editText`（`0dp` + `layout_weight=1`）→ `AndroidView` 托管 [editor] + `Modifier.weight(1f)`
     *  · `search_group`（`wrap_content` + `gone`）→ `AndroidView` 托管 [searchPanel]；**显隐仍由宿主按
     *    View 语义切换**（`visibility`）⇒ 视图实例常驻、监听器与搜索订阅语义与原实现一致。
     *    原 `layout_gravity=bottom` 在竖向 LinearLayout 中对 `wrap_content` 子节点无几何作用
     *    （`editText` 的 weight 已把面板压到底部）⇒ 等价。
     */
    private fun initComposeContent() {
        binding.root.attachComposeContent {
            Column(modifier = Modifier.fillMaxSize()) {
                // ---- 顶栏（原 compose_top_bar，内容逐行不变）----
                LegadoTheme {
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
                                        contentDescription = null,
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
                }
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