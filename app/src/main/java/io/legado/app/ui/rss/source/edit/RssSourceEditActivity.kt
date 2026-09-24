package io.legado.app.ui.rss.source.edit

import android.content.Intent
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.view.Menu
import android.view.View
import android.text.InputFilter
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.EditText
import androidx.appcompat.view.ContextThemeWrapper
import androidx.appcompat.widget.AppCompatEditText
import androidx.appcompat.widget.AppCompatSpinner
import androidx.appcompat.widget.AppCompatTextView
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.annotation.RequiresApi
import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Help
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Login
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.ToggleOn
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.viewbinding.ViewBinding
import com.google.android.material.tabs.TabLayout
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import io.legado.app.R
import io.legado.app.base.VMBaseActivity
import io.legado.app.data.entities.RssSource
import io.legado.app.base.attachComposeContent
import io.legado.app.base.composeShell
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.LocalConfig
import io.legado.app.lib.dialogs.SelectItem
import io.legado.app.ui.widget.compose.showComposeConfirmDialog
import io.legado.app.lib.theme.accentColor
import io.legado.app.lib.theme.backgroundColor
import io.legado.app.lib.theme.primaryColor
import io.legado.app.ui.about.AppLogDialog
import io.legado.app.ui.code.CodeEditActivity
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.ui.login.SourceLoginActivity
import io.legado.app.ui.qrcode.QrCodeResult
import io.legado.app.ui.rss.source.debug.RssSourceDebugActivity
import io.legado.app.ui.theme.LegadoTheme
import io.legado.app.ui.widget.components.AppDropdownMenu
import io.legado.app.ui.widget.components.GlassTopAppBar
import io.legado.app.ui.widget.components.MenuAction
import io.legado.app.ui.widget.dialog.UrlOptionDialog
import io.legado.app.ui.widget.dialog.VariableDialog
import io.legado.app.ui.widget.keyboard.KeyboardToolPop
import io.legado.app.ui.widget.text.EditEntity
import io.legado.app.utils.GSON
import io.legado.app.utils.imeHeight
import io.legado.app.utils.isContentScheme
import io.legado.app.utils.isTrue
import io.legado.app.utils.launch
import io.legado.app.utils.navigationBarHeight
import io.legado.app.utils.sendToClip
import io.legado.app.utils.setEdgeEffectColor
import io.legado.app.utils.setOnApplyWindowInsetsListenerCompat
import io.legado.app.utils.share
import io.legado.app.utils.shareWithQr
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.showHelp
import io.legado.app.utils.startActivity
import io.legado.app.utils.toastOnUi
import io.legado.app.lib.theme.view.ThemeCheckBox
import io.legado.app.utils.dpToPx
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import splitties.views.bottomPadding
import kotlin.text.isNotEmpty

class RssSourceEditActivity :
    VMBaseActivity<ViewBinding, RssSourceEditViewModel>(),
    KeyboardToolPop.CallBack,
    VariableDialog.Callback {

    // CE 5.2（compose 包）：原 activity_rss_source_edit.xml 已退役 ⇒ composeShell 合成壳 +
    // attachComposeContent 单源承载；**四个 View 内核一律 AndroidView 原样托管**
    // （多选框行 / 参数行 / TabLayout / RecyclerView）。
    override val binding: ViewBinding by lazy { composeShell(this) }
    override val viewModel by viewModels<RssSourceEditViewModel>()
    private var menuExpanded by mutableStateOf(false)

    // ---- CE 5.2：原 XML 节点的程序化等价物（随宿主即时创建：initView 早于组合挂载）----
    private val checkRowView: HorizontalScrollView by lazy { createCheckRow() }
    private val paramRowView: HorizontalScrollView by lazy { createParamRow() }
    private val tabLayoutView: TabLayout by lazy { createTabLayout() }
    private val recyclerView: RecyclerView by lazy { createRecyclerView() }
    // 多选框/参数行内节点（宿主读写它们的状态）
    private val cbIsEnable: ThemeCheckBox by lazy { checkBox(R.string.is_enable, true) }
    private val cbSingleUrl: ThemeCheckBox by lazy { checkBox(R.string.single_url, false) }
    private val cbIsEnableCookie: ThemeCheckBox by lazy { checkBox(R.string.auto_save_cookie, true) }
    private val cbIsEnablePreload: ThemeCheckBox by lazy { checkBox(R.string.enable_preload, false) }
    private val spType: AppCompatSpinner by lazy { spinner(R.array.rss_type) }
    private val lyType: AppCompatSpinner by lazy { spinner(R.array.layout_type) }
    private val editParseConcurrency: EditText by lazy { createParseConcurrencyEdit() }

    private val softKeyboardTool by lazy {
        KeyboardToolPop(this, lifecycleScope, binding.root, this)
    }
    private val adapter by lazy { RssSourceEditAdapter() }
    private val sourceEntities: ArrayList<EditEntity> = ArrayList()
    private val listEntities: ArrayList<EditEntity> = ArrayList()
    private val webViewEntities: ArrayList<EditEntity> = ArrayList()
    private val startEntities: ArrayList<EditEntity> = ArrayList()
    private val selectDoc = registerForActivityResult(HandleFileContract()) {
        it.uri?.let { uri ->
            if (uri.isContentScheme()) {
                sendText(uri.toString())
            } else {
                sendText(uri.path.toString())
            }
        }
    }
    private val qrCodeResult = registerForActivityResult(QrCodeResult()) {
        it?.let {
            viewModel.importSource(it) { source: RssSource ->
                upSourceView(source)
            }
        }
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        softKeyboardTool.attachToWindow(window)
        initView()
        initComposeContent()
        viewModel.initData(intent) {
            upSourceView(viewModel.rssSource)
        }
    }

    override fun onPostCreate(savedInstanceState: Bundle?) {
        super.onPostCreate(savedInstanceState)
        if (!LocalConfig.ruleHelpVersionIsLast) {
            showHelp("rssRuleHelp")
        }
    }

    override fun finish() {
        val source = getRssSource()
        if (!source.equal(viewModel.rssSource ?: RssSource())) {
            showComposeConfirmDialog(
                title = getString(R.string.exit),
                message = getString(R.string.exit_no_save),
                positiveText = getString(R.string.yes),
                negativeText = getString(R.string.no),
                onPositive = {
                    // F155：退出保存同样走必填校验 + 字段定位（校验不过则留在本页并高亮字段）
                    saveSource { super.finish() }
                },
                onNegative = {
                    super.finish()
                }
            )
        } else {
            super.finish()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        softKeyboardTool.dismiss()
    }

    override fun onCompatCreateOptionsMenu(menu: Menu): Boolean {
        return false
    }

    override fun onMenuOpened(featureId: Int, menu: Menu): Boolean {
        return false
    }

    private fun initComposeContent() {
        binding.root.attachComposeContent {
            Column(modifier = Modifier.fillMaxSize()) {
                // ---- 顶栏（原 compose_top_bar 内容，逐行不变）----
                LegadoTheme {
                    GlassTopAppBar(
                        title = getString(R.string.rss_source_edit),
                        navIcon = Icons.AutoMirrored.Filled.ArrowBack,
                        onNavClick = { finish() },
                        actions = {
                            // topbar-icon-semantics-fix 3.2：代码/保存/调试恢复一级图标
                            //（对齐原版 source_edit.xml showAsAction=always；tint 继承 actionIconContentColor 禁自传）
                            IconButton(onClick = { onFullEditClicked() }) {
                                Icon(
                                    imageVector = Icons.Filled.Code,
                                    contentDescription = getString(R.string.edit_content)
                                )
                            }
                            IconButton(onClick = {
                                saveSource {
                                    setResult(RESULT_OK)
                                    finish()
                                }
                            }) {
                                Icon(
                                    imageVector = Icons.Filled.Save,
                                    contentDescription = getString(R.string.action_save)
                                )
                            }
                            IconButton(onClick = {
                                saveSource { source ->
                                    startActivity<RssSourceDebugActivity> {
                                        putExtra("key", source.sourceUrl)
                                    }
                                }
                            }) {
                                Icon(
                                    imageVector = Icons.Filled.BugReport,
                                    contentDescription = getString(R.string.debug_source)
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
                // ---- 多选框行（原 HorizontalScrollView#1）----
                AndroidView(
                    modifier = Modifier.fillMaxWidth(),
                    factory = { checkRowView }
                )
                // ---- 参数行（原 HorizontalScrollView#2）----
                AndroidView(
                    modifier = Modifier.fillMaxWidth(),
                    factory = { paramRowView }
                )
                // ---- TabLayout（原 tab_layout：36dp 高 + elevation 3dp）----
                AndroidView(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(36.dp),
                    factory = { tabLayoutView }
                )
                // ---- RecyclerView（原 recycler_view：占剩余高度、clipToPadding=false）----
                AndroidView(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    factory = { recyclerView }
                )
            }
        }
    }

    // ==================== CE 5.2：原 XML 节点的程序化等价物 ====================

    /** 原 `HorizontalScrollView#1`：4 个多选框（accent tint 由 `ThemeCheckBox` 自身施加）。 */
    private fun createCheckRow(): HorizontalScrollView {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(8.dpToPx(), 0, 8.dpToPx(), 0)
        }
        row.addView(cbIsEnable)
        row.addView(cbSingleUrl)
        row.addView(cbIsEnableCookie)
        row.addView(cbIsEnablePreload)
        return HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(row)
        }
    }

    /** 原 `HorizontalScrollView#2`：书源类型 / 版面类型 / 解析并发 三个参数。 */
    private fun createParamRow(): HorizontalScrollView {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(8.dpToPx(), 0, 8.dpToPx(), 0)
        }
        row.addView(label(R.string.book_type))
        row.addView(spType)
        row.addView(label(R.string.layout_type))
        row.addView(lyType)
        row.addView(label(R.string.parse_concurrency, marginStart = 12))
        row.addView(editParseConcurrency)
        return HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(row)
        }
    }

    /** 原参数行标签（`TextView`：wrap × match_parent + `gravity=center` ⇒ 随行高垂直居中）。 */
    private fun label(
        @androidx.annotation.StringRes textRes: Int,
        marginStart: Int = 0
    ): AppCompatTextView = AppCompatTextView(this).apply {
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.MATCH_PARENT
        ).apply { this.marginStart = marginStart.dpToPx() }
        gravity = Gravity.CENTER
        setText(textRes)
    }

    /** 原 `ThemeCheckBox`（文案/勾选态由代码给，其余取主题默认 ⇒ 与 XML 同口径）。 */
    private fun checkBox(
        @androidx.annotation.StringRes textRes: Int,
        checked: Boolean
    ): ThemeCheckBox = ThemeCheckBox(this).apply {
        setText(textRes)
        isChecked = checked
    }

    /** 原 `AppCompatSpinner`（`android:entries` + `android:theme="@style/Spinner"`）。 */
    private fun spinner(@androidx.annotation.ArrayRes entriesRes: Int): AppCompatSpinner =
        AppCompatSpinner(ContextThemeWrapper(this, R.style.Spinner)).apply {
            adapter = ArrayAdapter(
                this@RssSourceEditActivity,
                android.R.layout.simple_spinner_item,
                resources.getStringArray(entriesRes)
            ).apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        }

    /** 原 `edit_parse_concurrency`（60dp / 居中 / 数字键盘 / 最长 2 位 / hint 0）。 */
    private fun createParseConcurrencyEdit(): EditText =
        AppCompatEditText(ContextThemeWrapper(this, R.style.Spinner)).apply {
            layoutParams = LinearLayout.LayoutParams(
                60.dpToPx(), LinearLayout.LayoutParams.WRAP_CONTENT
            )
            gravity = Gravity.CENTER
            hint = "0"
            inputType = InputType.TYPE_CLASS_NUMBER
            filters = arrayOf(InputFilter.LengthFilter(2))
        }

    /** 原 `tab_layout`（36dp 高 + elevation 3dp；底色/指示器色由 `initView` 按主题覆写）。 */
    private fun createTabLayout(): TabLayout = TabLayout(this).apply {
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 36.dpToPx()
        )
        elevation = 3.dpToPx().toFloat()
    }

    /** 原 `recycler_view`（`clipToPadding=false`；布局管理器与 adapter 由 `initView` 装配）。 */
    private fun createRecyclerView(): RecyclerView = RecyclerView(this).apply {
        clipToPadding = false
    }

    /**
     * 溢出菜单（F2 落地第 3 处）：11 项按「用户心智」分四组 + 组标题。
     *
     * 分组依据：账户与变量（要登录/要变量的操作）→ 编辑辅助（写规则时的即时开关）→
     * 导入 · 导出（成对的进出通道，二维码导入与二维码分享相邻）→ 工具（日志/帮助）。
     */
    private fun buildMenuActions(): List<MenuAction> {
        val actions = mutableListOf<MenuAction>()
        fun addGroup(@androidx.annotation.StringRes titleRes: Int) {
            actions += MenuAction(
                title = getString(titleRes),
                header = true,
                onClick = {}
            )
        }
        // 组 1：账户与变量
        addGroup(R.string.source_menu_group_account)
        // 代码/保存/调试已恢复为一级图标（3.2），不再进入溢出菜单
        if (!getRssSource().loginUrl.isNullOrBlank()) {
            actions += MenuAction(
                Icons.Filled.Login,
                getString(R.string.login),
                onClick = {
                    saveSource {
                        startActivity<SourceLoginActivity> {
                            putExtra("type", "rssSource")
                            putExtra("key", it.sourceUrl)
                        }
                    }
                }
            )
        }
        actions += MenuAction(
            Icons.Filled.Tune,
            getString(R.string.set_source_variable),
            onClick = { setSourceVariable() }
        )
        actions += MenuAction(
            Icons.Filled.History,
            getString(R.string.cookie),
            onClick = { viewModel.clearCookie(getRssSource().sourceUrl) }
        )
        // 组 2：编辑辅助
        addGroup(R.string.source_menu_group_edit)
        actions += MenuAction(
            Icons.Filled.ToggleOn,
            getString(R.string.auto_complete),
            checked = viewModel.autoComplete,
            onClick = { viewModel.autoComplete = !viewModel.autoComplete }
        )
        // 组 3：导入 · 导出（拷贝/粘贴成对，二维码导入/分享成对）
        addGroup(R.string.source_menu_group_io)
        actions += MenuAction(
            Icons.Filled.ContentCopy,
            getString(R.string.copy_source),
            onClick = { sendToClip(GSON.toJson(getRssSource())) }
        )
        actions += MenuAction(
            Icons.Filled.Description,
            getString(R.string.paste_source),
            onClick = { viewModel.pasteSource { upSourceView(it) } }
        )
        actions += MenuAction(
            Icons.Filled.QrCodeScanner,
            getString(R.string.import_by_qr_code),
            onClick = { qrCodeResult.launch() }
        )
        actions += MenuAction(
            Icons.Filled.Share,
            getString(R.string.str_share),
            onClick = { share(GSON.toJson(getRssSource())) }
        )
        actions += MenuAction(
            Icons.Filled.QrCodeScanner,
            getString(R.string.qr_share),
            onClick = {
                shareWithQr(
                    GSON.toJson(getRssSource()),
                    getString(R.string.share_rss_source),
                    ErrorCorrectionLevel.L
                )
            }
        )
        // 组 4：工具
        addGroup(R.string.source_menu_group_tools)
        actions += MenuAction(
            Icons.Filled.History,
            getString(R.string.log),
            onClick = { showDialogFragment<AppLogDialog>() }
        )
        actions += MenuAction(
            Icons.Filled.Help,
            getString(R.string.help),
            onClick = { showHelp("rssRuleHelp") }
        )
        return actions.toList()
    }

    /**
     * F155：保存前必填校验 + 失败定位（返回 false 时已把错误落到字段并切到对应 Tab）。
     *
     * 原实现把校验放在 ViewModel（`sourceUrl/sourceName` 空即抛异常）→ 只弹一句 toast，
     * 用户需自己在 4 个 Tab 数十个字段里找错字段；此处把「定位」补在页面上，校验口径不变。
     */
    private fun saveSource(onSaved: (RssSource) -> Unit) {
        val source = getRssSource()
        val blankKey = when {
            source.sourceName.isBlank() -> "sourceName"
            source.sourceUrl.isBlank() -> "sourceUrl"
            else -> null
        }
        if (blankKey == null) {
            clearFieldErrors()
            viewModel.save(source, onSaved)
            return
        }
        locateField(blankKey)
    }

    /** F155：把错误落到指定字段并滚动到可见位置（必填字段均在「基本」Tab）。 */
    private fun locateField(key: String) {
        val entity = sourceEntities.firstOrNull { it.key == key } ?: return
        sourceEntities.forEach { it.error = null }
        entity.error = getString(R.string.source_required_hint)
        tabLayoutView.selectTab(tabLayoutView.getTabAt(0))
        setEditEntities(0)
        val index = adapter.indexOfKey(key)
        if (index >= 0) {
            adapter.notifyItemChanged(index)
            recyclerView.post { recyclerView.scrollToPosition(index) }
        }
    }

    /** F155：清除全部字段错误态（校验通过后再保存，避免红框残留）。 */
    private fun clearFieldErrors() {
        if (sourceEntities.none { it.error != null }) return
        sourceEntities.forEach { it.error = null }
        adapter.notifyDataSetChanged()
    }

    private val textEditLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            val view = window.decorView.findFocus()
            if (view is EditText) {
                result.data?.getStringExtra("text")?.let {
                    view.setText(it)
                }
                result.data?.getIntExtra("cursorPosition", -1)?.takeIf { it in 0 ..< view.text.length }?.let {
                    view.setSelection(it)
                }
            } else {
                toastOnUi(R.string.focus_lost_on_textbox)
            }
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
        }
        else {
            toastOnUi(R.string.please_focus_cursor_on_textbox)
        }
    }

    private fun initView() {
        tabLayoutView.addTab(tabLayoutView.newTab().apply {
            setText(R.string.source_tab_base)
        })
        tabLayoutView.addTab(tabLayoutView.newTab().apply {
            setText(R.string.source_tab_start)
        })
        tabLayoutView.addTab(tabLayoutView.newTab().apply {
            setText(R.string.source_tab_list)
        })
        tabLayoutView.addTab(tabLayoutView.newTab().apply {
            text = "WEB_VIEW"
        })
        recyclerView.setEdgeEffectColor(primaryColor)
        val createSpanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
            override fun getSpanSize(position: Int): Int = when (adapter.getItemViewType(position)) {
                EditEntity.ViewType.checkBox -> 1 //CheckBox 占1个span
                else -> 2 //占2个span（整行）
            }
        }
        val gridLayoutManager = if (adapter.editEntityMaxLine < 999) {
            object : GridLayoutManager(this, 2) {
                init {
                    spanSizeLookup = createSpanSizeLookup
                }
                override fun requestChildRectangleOnScreen(parent: RecyclerView, child: View, rect: Rect, immediate: Boolean, focusedChildVisible: Boolean) = false
                override fun requestChildRectangleOnScreen(parent: RecyclerView, child: View, rect: Rect, immediate: Boolean) = false
            }
        } else {
            GridLayoutManager(this, 2).apply {
                spanSizeLookup = createSpanSizeLookup
            }
        }
        recyclerView.layoutManager = gridLayoutManager
        recyclerView.adapter = adapter
        recyclerView.viewTreeObserver.addOnGlobalFocusChangeListener { _, newFocus ->
            if (newFocus is EditText) {
                newFocus.postDelayed({ sendText("") }, 120)
            }
        }
        tabLayoutView.setBackgroundColor(backgroundColor)
        tabLayoutView.setSelectedTabIndicatorColor(accentColor)
        tabLayoutView.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabReselected(tab: TabLayout.Tab?) {

            }

            override fun onTabUnselected(tab: TabLayout.Tab?) {

            }

            override fun onTabSelected(tab: TabLayout.Tab?) {
                setEditEntities(tab?.position)
            }
        })
        // 监听源类型切换：type=2 为视频源，刷新 Adapter 以显示/隐藏 textVideoOnly 项
        spType.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                adapter.currentSourceType = position
                adapter.notifyDataSetChanged()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        // CE 5.2：insets 监听锚点由「XML 里的 recyclerView」改为合成壳 root（同一套 window insets；
        // 组合内 AndroidView 子视图不保证收到 insets 派发）⇒ 监听逻辑与 padding 落点保持等价
        binding.root.setOnApplyWindowInsetsListenerCompat { _, windowInsets ->
            val navigationBarHeight = windowInsets.navigationBarHeight
            val imeHeight = windowInsets.imeHeight
            recyclerView.bottomPadding = if (imeHeight == 0) navigationBarHeight else 0
            softKeyboardTool.initialPadding = imeHeight
            windowInsets
        }
    }

    private fun setEditEntities(tabPosition: Int?) {
        when (tabPosition) {
            1 -> adapter.editEntities = startEntities
            2 -> adapter.editEntities = listEntities
            3 -> adapter.editEntities = webViewEntities
            else -> adapter.editEntities = sourceEntities
        }
        recyclerView.scrollToPosition(0)
        window.decorView.rootView.clearFocus()
    }

    private fun upSourceView(rssSource: RssSource?) {
        val rs = rssSource ?: RssSource()
        rs.let {
            cbIsEnable.isChecked = rs.enabled
            cbSingleUrl.isChecked = rs.singleUrl
            cbIsEnableCookie.isChecked = rs.enabledCookieJar == true
            cbIsEnablePreload.isChecked = rs.preload
            if (rs.type !in 0..<spType.count) {
                rs.type = 0
            }
            spType.setSelection(rs.type)
            if (rs.articleStyle !in 0..<lyType.count) {
                rs.articleStyle = 0
            }
            lyType.setSelection(rs.articleStyle)
            // Issue-1 修复：单源未配置时直接显示系统全局配置值
            // 设计：parseConcurrency=0 表示未单独配置，应显示继承的全局 AppConfig.rssParseConcurrency 值
            editParseConcurrency.setText(
                if (rs.parseConcurrency > 0) rs.parseConcurrency.toString()
                else AppConfig.rssParseConcurrency.toString()
            )
        }
        sourceEntities.clear()
        sourceEntities.apply {
            // F155：sourceName/sourceUrl 是保存校验的必填项（ViewModel.save 口径），label 显式标注
            add(EditEntity("sourceName", rs.sourceName, R.string.source_name, required = true))
            add(EditEntity("sourceUrl", rs.sourceUrl, R.string.source_url, required = true))
            add(EditEntity("sourceIcon", rs.sourceIcon, R.string.source_icon))
            add(EditEntity("sourceGroup", rs.sourceGroup, R.string.source_group))
            add(EditEntity("sourceComment", rs.sourceComment, R.string.comment))
            add(EditEntity("searchUrl", rs.searchUrl, R.string.r_search_url))
            add(EditEntity("sortUrl", rs.sortUrl, R.string.sort_url))
            add(EditEntity("loginUrl", rs.loginUrl, R.string.login_url))
            add(EditEntity("loginUi", rs.loginUi, R.string.login_ui))
            add(EditEntity("loginCheckJs", rs.loginCheckJs, R.string.login_check_js))
            add(EditEntity("coverDecodeJs", rs.coverDecodeJs, R.string.cover_decode_js))
            add(EditEntity("header", rs.header, R.string.source_http_header))
            add(EditEntity("variableComment", rs.variableComment, R.string.variable_comment))
            add(EditEntity("concurrentRate", rs.concurrentRate, R.string.concurrent_rate))
            add(EditEntity("parseConcurrency", if (rs.parseConcurrency > 0) rs.parseConcurrency.toString() else "", R.string.source_parse_concurrency))
            add(EditEntity("jsLib", rs.jsLib, "jsLib"))
        }
        startEntities.clear()
        startEntities.apply {
            add(EditEntity("startHtml", rs.startHtml, R.string.r_startHtml))
            add(EditEntity("startStyle", rs.startStyle, R.string.r_startStyle))
            add(EditEntity("startJs", rs.startJs, R.string.r_startJs))
            add(EditEntity("preloadJs", rs.preloadJs, R.string.r_preloadJs))
        }
        listEntities.clear()
        listEntities.apply {
            add(EditEntity("ruleArticles", rs.ruleArticles, R.string.r_articles))
            add(EditEntity("ruleNextPage", rs.ruleNextPage, R.string.r_next))
            add(EditEntity("ruleTitle", rs.ruleTitle, R.string.r_title))
            add(EditEntity("rulePubDate", rs.rulePubDate, R.string.r_date))
            add(EditEntity("ruleDescription", rs.ruleDescription, R.string.r_description))
            add(EditEntity("ruleImage", rs.ruleImage, R.string.r_image))
            add(EditEntity("ruleLink", rs.ruleLink, R.string.r_link))
        }
        webViewEntities.clear()
        webViewEntities.apply {
            add(
                EditEntity(
                    "enableJs",
                    rs.enableJs.toString(),
                    R.string.enable_js,
                    EditEntity.ViewType.checkBox
                )
            )
            add(
                EditEntity(
                    "loadWithBaseUrl",
                    rs.loadWithBaseUrl.toString(),
                    R.string.load_with_base_url,
                    EditEntity.ViewType.checkBox
                )
            )
            add(
                 EditEntity(
                     "showWebLog",
                     rs.showWebLog.toString(),
                     R.string.load_with_web_log,
                     EditEntity.ViewType.checkBox
                 )
             )
            add(
                EditEntity(
                    "cacheFirst",
                    rs.cacheFirst.toString(),
                    R.string.cache_first,
                    EditEntity.ViewType.checkBox
                )
            )
            add(EditEntity("ruleContent", rs.ruleContent, R.string.r_content))
            add(EditEntity("ruleRoutes", rs.ruleRoutes, R.string.r_routes, EditEntity.ViewType.textVideoOnly))
            add(EditEntity("ruleEpisodes", rs.ruleEpisodes, R.string.r_episodes, EditEntity.ViewType.textVideoOnly))
            add(EditEntity("style", rs.style, R.string.r_style))
            add(EditEntity("injectJs", rs.injectJs, R.string.r_inject_js))
            add(EditEntity("contentWhitelist", rs.contentWhitelist, R.string.c_whitelist))
            add(EditEntity("contentBlacklist", rs.contentBlacklist, R.string.c_blacklist))
            add(
                EditEntity(
                    "shouldOverrideUrlLoading",
                    rs.shouldOverrideUrlLoading,
                    "url跳转拦截(js, 返回true拦截,js变量url,可以通过js打开url,比如调用阅读搜索,添加书架等,简化规则写法,不用webView js注入)"
                )
            )
        }
        tabLayoutView.selectTab(tabLayoutView.getTabAt(0))
        setEditEntities(0)
    }

    private fun getRssSource(): RssSource {
        val source = viewModel.rssSource?.copy() ?: RssSource()
        source.enabled = cbIsEnable.isChecked
        source.singleUrl = cbSingleUrl.isChecked
        source.enabledCookieJar = cbIsEnableCookie.isChecked
        source.preload = cbIsEnablePreload.isChecked
        source.type = spType.selectedItemPosition
        source.articleStyle = lyType.selectedItemPosition
        // Issue-5 修复：保存单源解析并发配置（空值或0=用全局配置）
        source.parseConcurrency = editParseConcurrency.text.toString()
            .trim().toIntOrNull()?.coerceIn(0, 32) ?: 0
        sourceEntities.forEach {
            it.value = it.value?.takeIf { s -> s.isNotBlank() }
            when (it.key) {
                "sourceName" -> source.sourceName = it.value ?: ""
                "sourceUrl" -> source.sourceUrl = it.value ?: ""
                "sourceIcon" -> source.sourceIcon = it.value ?: ""
                "sourceGroup" -> source.sourceGroup = it.value
                "sourceComment" -> source.sourceComment = it.value
                "loginUrl" -> source.loginUrl = it.value
                "loginUi" -> source.loginUi = it.value
                "loginCheckJs" -> source.loginCheckJs = it.value
                "coverDecodeJs" -> source.coverDecodeJs = it.value
                "header" -> source.header = it.value
                "variableComment" -> source.variableComment = it.value
                "concurrentRate" -> source.concurrentRate = it.value
                "parseConcurrency" -> source.parseConcurrency =
                    it.value?.toIntOrNull()?.coerceIn(0, 20) ?: 0
                "searchUrl" -> source.searchUrl = it.value
                "sortUrl" -> source.sortUrl = it.value
                "jsLib" -> source.jsLib = it.value
            }
        }
        startEntities.forEach {
            it.value = it.value?.takeIf { s -> s.isNotBlank() }
            when (it.key) {
                "startHtml" -> source.startHtml = it.value
                "startStyle" -> source.startStyle = it.value
                "startJs" -> source.startJs = it.value
                "preloadJs" -> source.preloadJs = it.value
            }
        }
        listEntities.forEach {
            it.value = it.value?.takeIf { s -> s.isNotBlank() }
            when (it.key) {
                "ruleArticles" -> source.ruleArticles = it.value
                "ruleNextPage" -> source.ruleNextPage =
                    viewModel.ruleComplete(it.value, source.ruleArticles, 2)

                "ruleTitle" -> source.ruleTitle =
                    viewModel.ruleComplete(it.value, source.ruleArticles)

                "rulePubDate" -> source.rulePubDate =
                    viewModel.ruleComplete(it.value, source.ruleArticles)

                "ruleDescription" -> source.ruleDescription =
                    viewModel.ruleComplete(it.value, source.ruleArticles)

                "ruleImage" -> source.ruleImage =
                    viewModel.ruleComplete(it.value, source.ruleArticles, 3)

                "ruleLink" -> source.ruleLink =
                    viewModel.ruleComplete(it.value, source.ruleArticles)
            }
        }
        webViewEntities.forEach {
            it.value = it.value?.takeIf { s -> s.isNotBlank() }
            when (it.key) {
                "enableJs" -> source.enableJs = it.value.isTrue()
                "loadWithBaseUrl" -> source.loadWithBaseUrl = it.value.isTrue()
                "showWebLog" -> source.showWebLog = it.value.isTrue()
                "cacheFirst" -> source.cacheFirst = it.value.isTrue()
                "ruleContent" -> source.ruleContent =
                    viewModel.ruleComplete(it.value, source.ruleArticles)

                "ruleRoutes" -> source.ruleRoutes = it.value
                "ruleEpisodes" -> source.ruleEpisodes = it.value

                "style" -> source.style = it.value
                "injectJs" -> source.injectJs = it.value
                "contentWhitelist" -> source.contentWhitelist = it.value
                "contentBlacklist" -> source.contentBlacklist = it.value
                "shouldOverrideUrlLoading" -> source.shouldOverrideUrlLoading = it.value
            }
        }
        return source
    }

    private fun setSourceVariable() {
        viewModel.save(getRssSource()) { source ->
            lifecycleScope.launch {
                val comment =
                    source.getDisplayVariableComment("源变量可在js中通过source.getVariable()获取")
                val variable = withContext(Dispatchers.IO) { source.getVariable() }
                showDialogFragment(
                    VariableDialog(
                        getString(R.string.set_source_variable),
                        source.getKey(),
                        variable,
                        comment
                    )
                )
            }
        }
    }

    override fun setVariable(key: String, variable: String?) {
        viewModel.rssSource?.setVariable(variable)
    }

    override fun helpActions(): List<SelectItem<String>> {
        return arrayListOf(
            SelectItem("插入URL参数", "urlOption"),
            SelectItem("订阅源教程", "ruleHelp"),
            SelectItem("js教程", "jsHelp"),
            SelectItem("正则教程", "regexHelp"),
            SelectItem("选择文件", "selectFile"),
        )
    }

    override fun onHelpActionSelect(action: String) {
        when (action) {
            "urlOption" -> UrlOptionDialog(this) {
                sendText(it)
            }.show()

            "ruleHelp" -> showHelp("rssRuleHelp")
            "jsHelp" -> showHelp("jsHelp")
            "regexHelp" -> showHelp("regexHelp")
            "selectFile" -> selectDoc.launch {
                mode = HandleFileContract.FILE
            }
        }
    }

    override fun sendText(text: String) {
        val view = window.decorView.findFocus()
        if (view is EditText) {
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
            if (adapter.editEntityMaxLine >= 999) {
                view.post {
                    val editTextLocation = IntArray(2)
                    view.getLocationOnScreen(editTextLocation)
                    val recyclerViewLocation = IntArray(2)
                    recyclerView.getLocationOnScreen(recyclerViewLocation)
                    val layout = view.layout
                    if (layout != null) {
                        val line = layout.getLineForOffset(end)
                        val cursorYInEditText = layout.getLineTop(line)
                        // 光标相对于屏幕的位置
                        val cursorYOnScreen = editTextLocation[1] + cursorYInEditText
                        // 光标相对于RecyclerView的位置
                        val cursorYInRecyclerView = cursorYOnScreen - recyclerViewLocation[1]
                        val recyclerViewBottom = recyclerView.height - 120 //考虑键盘的经验值
                        // 如果光标不在可见范围内，则滚动到光标位置
                        if (cursorYInRecyclerView !in 0..recyclerViewBottom) {
                            val scrollDistance = cursorYInRecyclerView - recyclerViewBottom / 3
                            if (scrollDistance > 0 && recyclerView.canScrollVertically(1) || scrollDistance < 0 && recyclerView.canScrollVertically(-1)) {
                                recyclerView.smoothScrollBy(0, scrollDistance)
                            }
                        }
                    }
                }
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
