package io.legado.app.ui.book.source.edit

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.EditText
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.annotation.RequiresApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import io.legado.app.ui.widget.components.AppShapes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
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
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.ToggleOn
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import io.legado.app.R
import io.legado.app.base.VMBaseActivity
import io.legado.app.constant.AppLog
import io.legado.app.constant.BookSourceType
import io.legado.app.data.appDb
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.rule.BookInfoRule
import io.legado.app.data.entities.rule.ContentRule
import io.legado.app.data.entities.rule.ExploreRule
import io.legado.app.data.entities.rule.SearchRule
import io.legado.app.data.entities.rule.TocRule
import io.legado.app.databinding.ActivityBookSourceEditBinding
import io.legado.app.help.config.LocalConfig
import io.legado.app.lib.dialogs.SelectItem
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.dialogs.selector
import io.legado.app.lib.theme.primaryColor
import io.legado.app.ui.about.AppLogDialog
import io.legado.app.ui.book.search.SearchActivity
import io.legado.app.ui.book.source.debug.BookSourceDebugActivity
import io.legado.app.ui.code.CodeEditActivity
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.ui.login.SourceLoginActivity
import io.legado.app.ui.qrcode.QrCodeResult
import io.legado.app.ui.theme.LegadoTheme
import io.legado.app.ui.widget.components.AppDropdownMenu
import io.legado.app.ui.widget.components.GlassTopAppBar
import io.legado.app.ui.widget.components.MenuAction
import io.legado.app.ui.widget.components.SettingsCard
import io.legado.app.ui.widget.dialog.UrlOptionDialog
import io.legado.app.ui.widget.dialog.VariableDialog
import io.legado.app.ui.widget.keyboard.KeyboardToolPop
import io.legado.app.ui.widget.recycler.NoChildScrollLinearLayoutManager
import io.legado.app.ui.widget.text.EditEntity
import io.legado.app.utils.GSON
import io.legado.app.utils.imeHeight
import io.legado.app.utils.isContentScheme
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
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import splitties.views.bottomPadding

class BookSourceEditActivity :
    VMBaseActivity<ActivityBookSourceEditBinding, BookSourceEditViewModel>(),
    KeyboardToolPop.CallBack,
    VariableDialog.Callback {

    override val binding by viewBinding(ActivityBookSourceEditBinding::inflate)
    override val viewModel by viewModels<BookSourceEditViewModel>()

    private val adapter by lazy { BookSourceEditAdapter() }
    private val recyclerView by lazy { RecyclerView(this) }
    private var menuExpanded by mutableStateOf(false)
    private var typeMenuExpanded by mutableStateOf(false)
    private var typeIndex by mutableIntStateOf(0)
    private var isEnable by mutableStateOf(true)
    private var isEnableExplore by mutableStateOf(true)
    private var isEnableCookie by mutableStateOf(true)
    private var isEventListener by mutableStateOf(false)
    private var isCustomButton by mutableStateOf(false)
    private val sourceEntities: ArrayList<EditEntity> = ArrayList()
    private val searchEntities: ArrayList<EditEntity> = ArrayList()
    private val exploreEntities: ArrayList<EditEntity> = ArrayList()
    private val infoEntities: ArrayList<EditEntity> = ArrayList()
    private val tocEntities: ArrayList<EditEntity> = ArrayList()
    private val contentEntities: ArrayList<EditEntity> = ArrayList()

    //    private val reviewEntities: ArrayList<EditEntity> = ArrayList()
    private val qrCodeResult = registerForActivityResult(QrCodeResult()) {
        it ?: return@registerForActivityResult
        viewModel.importSource(it) { source ->
            upSourceView(source)
        }
    }
    private val selectDoc = registerForActivityResult(HandleFileContract()) {
        it.uri?.let { uri ->
            if (uri.isContentScheme()) {
                sendText(uri.toString())
            } else {
                sendText(uri.path.toString())
            }
        }
    }

    private val softKeyboardTool by lazy {
        KeyboardToolPop(this, lifecycleScope, binding.root, this)
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        softKeyboardTool.attachToWindow(window)
        initView()
        initComposeTopBar()
        viewModel.initData(intent) {
            upSourceView(viewModel.bookSource)
            restoreState(savedInstanceState)
        }
    }

    override fun onPostCreate(savedInstanceState: Bundle?) {
        super.onPostCreate(savedInstanceState)
        if (!LocalConfig.ruleHelpVersionIsLast) {
            showHelp("ruleHelp")
        }
    }

    private fun initComposeTopBar() {
        binding.composeTopBar.setContent {
            LegadoTheme {
                GlassTopAppBar(
                    title = getString(R.string.edit_book_source),
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

    private fun buildMenuActions(): List<MenuAction> {
        val actions = mutableListOf<MenuAction>()
        actions += MenuAction(
            Icons.Filled.Code,
            getString(R.string.edit_content),
            onClick = { onFullEditClicked() }
        )
        actions += MenuAction(
            Icons.Filled.Save,
            getString(R.string.action_save),
            onClick = {
                saveSource(getSource()) {
                    setResult(RESULT_OK, Intent().putExtra("origin", it.bookSourceUrl))
                    finish()
                }
            }
        )
        actions += MenuAction(
            Icons.Filled.BugReport,
            getString(R.string.debug_source),
            onClick = {
                saveSource(getSource()) { source ->
                    startActivity<BookSourceDebugActivity> {
                        putExtra("key", source.bookSourceUrl)
                    }
                }
            }
        )
        if (!getSource().loginUrl.isNullOrBlank()) {
            actions += MenuAction(
                Icons.Filled.Login,
                getString(R.string.login),
                onClick = {
                    saveSource(getSource()) { source ->
                        startActivity<SourceLoginActivity> {
                            putExtra("type", "bookSource")
                            putExtra("key", source.bookSourceUrl)
                        }
                    }
                }
            )
        }
        actions += MenuAction(
            Icons.Filled.Search,
            getString(R.string.search),
            onClick = {
                saveSource(getSource()) { source ->
                    SearchActivity.start(this, source)
                }
            }
        )
        actions += MenuAction(
            Icons.Filled.History,
            getString(R.string.cookie),
            onClick = { viewModel.clearCookie(getSource().bookSourceUrl) }
        )
        actions += MenuAction(
            Icons.Filled.ToggleOn,
            getString(R.string.auto_complete),
            checked = viewModel.autoComplete,
            onClick = { viewModel.autoComplete = !viewModel.autoComplete }
        )
        actions += MenuAction(
            Icons.Filled.ContentCopy,
            getString(R.string.copy_source),
            onClick = { sendToClip(GSON.toJson(getSource())) }
        )
        actions += MenuAction(
            Icons.Filled.Description,
            getString(R.string.paste_source),
            onClick = { viewModel.pasteSource { upSourceView(it) } }
        )
        actions += MenuAction(
            Icons.Filled.Tune,
            getString(R.string.set_source_variable),
            onClick = { setSourceVariable() }
        )
        actions += MenuAction(
            Icons.Filled.QrCodeScanner,
            getString(R.string.import_by_qr_code),
            onClick = { qrCodeResult.launch() }
        )
        actions += MenuAction(
            Icons.Filled.QrCodeScanner,
            getString(R.string.qr_share),
            onClick = {
                shareWithQr(
                    GSON.toJson(getSource()),
                    getString(R.string.share_book_source),
                    ErrorCorrectionLevel.L
                )
            }
        )
        actions += MenuAction(
            Icons.Filled.Share,
            getString(R.string.str_share),
            onClick = { share(GSON.toJson(getSource())) }
        )
        actions += MenuAction(
            Icons.Filled.History,
            getString(R.string.log),
            onClick = { showDialogFragment<AppLogDialog>() }
        )
        actions += MenuAction(
            Icons.Filled.Help,
            getString(R.string.help),
            onClick = { showHelp("ruleHelp") }
        )
        return actions.filterNotNull().toList()
    }

    private fun initView() {
        initComposeQuickToolbar()
        initComposeTabBar()
        recyclerView.setEdgeEffectColor(primaryColor)
        // 兜底：默认 sourceEditMaxLine=Int.MAX_VALUE(≥999) 时也需 layoutManager，
        // 否则字段区不 measure 任何 item（改造前由布局 XML 声明兜底，改代码动态创建后丢失）
        recyclerView.layoutManager = if (adapter.editEntityMaxLine < 999) {
            NoChildScrollLinearLayoutManager(this) //行数少时,用的TextView跟随,阻止跟随光标滚动
        } else {
            LinearLayoutManager(this) //行数多/默认时标准滚动
        }
        recyclerView.adapter = adapter
        recyclerView.viewTreeObserver.addOnGlobalFocusChangeListener { _, newFocus ->
            if (newFocus is EditText) {
                newFocus.postDelayed({ sendText("") }, 120)
            }
        }
        recyclerView.setOnApplyWindowInsetsListenerCompat { view, windowInsets ->
            val navigationBarHeight = windowInsets.navigationBarHeight
            val imeHeight = windowInsets.imeHeight
            view.bottomPadding = if (imeHeight == 0) navigationBarHeight else 0
            softKeyboardTool.initialPadding = imeHeight
            windowInsets
        }
        initComposeFields()
        initComposeBottomBar()
    }

    private var selectedTabIndex by mutableIntStateOf(0)

    @OptIn(ExperimentalMaterial3Api::class)
    private fun initComposeTabBar() {
        binding.composeTabBar.setContent {
            LegadoTheme {
                val tabLabels = listOf(
                    R.string.source_tab_base,
                    R.string.source_tab_search,
                    R.string.source_tab_find,
                    R.string.source_tab_info,
                    R.string.source_tab_toc,
                    R.string.source_tab_content
                )
                val current = selectedTabIndex
                PrimaryScrollableTabRow(
                    selectedTabIndex = current,
                    edgePadding = 8.dp
                ) {
                    tabLabels.forEachIndexed { index, label ->
                        Tab(
                            selected = index == current,
                            onClick = { selectedTabIndex = index; setEditEntities(index) },
                            text = { Text(text = stringResource(label)) }
                        )
                    }
                }
            }
        }
    }

    // S3 阶段3：顶部快捷工具条 SettingsCard 分组 Compose 化（类型 Spinner + 开关组，删死字段 cb_is_enable_review）
    // 修复（v2）：类型不再用全宽 SettingsClickRow——其内部 fillMaxWidth() 在 FlowRow 中会占满整行，
    // 导致「类型」独占一行、其余开关全部换行到下面。改为紧凑「标签+值+下拉箭头」可点击行，
    // 与「启用/发现/自动保存Cookie」同一 FlowRow 内自然同行（还原原版第一行结构），事件监听/定制按钮自动换第二行
    @OptIn(ExperimentalLayoutApi::class)
    private fun initComposeQuickToolbar() {
        binding.composeQuickToolbar.setContent {
            LegadoTheme {
                val typeLabels = resources.getStringArray(R.array.book_type)
                SettingsCard {
                    // 类型+五个开关全部放入 FlowRow 自动换行：类型/启用/发现/自动保存Cookie 第一行 → 事件监听/定制按钮 第二行
                    FlowRow(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalArrangement = Arrangement.spacedBy(0.dp)
                    ) {
                        // 类型：紧凑「标签+值+下拉箭头」可点击行，点击弹菜单（不用全宽 SettingsClickRow，避免独占一行）
                        Box {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .clip(AppShapes.Chip)
                                    .clickable { typeMenuExpanded = true }
                                    .padding(horizontal = 6.dp)
                                    .height(40.dp)
                            ) {
                                Text(
                                    text = getString(R.string.book_type),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = typeLabels.getOrNull(typeIndex) ?: "",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Icon(
                                    imageVector = Icons.Filled.ArrowDropDown,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            DropdownMenu(
                                expanded = typeMenuExpanded,
                                onDismissRequest = { typeMenuExpanded = false }
                            ) {
                                typeLabels.forEachIndexed { index, label ->
                                    DropdownMenuItem(
                                        text = { Text(text = label) },
                                        onClick = {
                                            typeIndex = index
                                            typeMenuExpanded = false
                                        }
                                    )
                                }
                            }
                        }
                        // 5 开关按原版 ThemeCheckBox 样式（CheckBox+文字 朴素横排）
                        // 内联 Checkbox+Row 避免 kapt 对 @Composable 函数生成 NonExistentClass 桩错误
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.height(40.dp)) {
                            Checkbox(checked = isEnable, onCheckedChange = { isEnable = it },
                                colors = CheckboxDefaults.colors(checkedColor = MaterialTheme.colorScheme.primary, checkmarkColor = MaterialTheme.colorScheme.onPrimary))
                            Spacer(modifier = Modifier.width(2.dp))
                            Text(text = getString(R.string.is_enable), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                        }
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.height(40.dp)) {
                            Checkbox(checked = isEnableExplore, onCheckedChange = { isEnableExplore = it },
                                colors = CheckboxDefaults.colors(checkedColor = MaterialTheme.colorScheme.primary, checkmarkColor = MaterialTheme.colorScheme.onPrimary))
                            Spacer(modifier = Modifier.width(2.dp))
                            Text(text = getString(R.string.discovery), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                        }
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.height(40.dp)) {
                            Checkbox(checked = isEnableCookie, onCheckedChange = { isEnableCookie = it },
                                colors = CheckboxDefaults.colors(checkedColor = MaterialTheme.colorScheme.primary, checkmarkColor = MaterialTheme.colorScheme.onPrimary))
                            Spacer(modifier = Modifier.width(2.dp))
                            Text(text = getString(R.string.auto_save_cookie), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                        }
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.height(40.dp)) {
                            Checkbox(checked = isEventListener, onCheckedChange = { isEventListener = it },
                                colors = CheckboxDefaults.colors(checkedColor = MaterialTheme.colorScheme.primary, checkmarkColor = MaterialTheme.colorScheme.onPrimary))
                            Spacer(modifier = Modifier.width(2.dp))
                            Text(text = getString(R.string.is_event_listener), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                        }
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.height(40.dp)) {
                            Checkbox(checked = isCustomButton, onCheckedChange = { isCustomButton = it },
                                colors = CheckboxDefaults.colors(checkedColor = MaterialTheme.colorScheme.primary, checkmarkColor = MaterialTheme.colorScheme.onPrimary))
                            Spacer(modifier = Modifier.width(2.dp))
                            Text(text = getString(R.string.custom_button), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                        }
                    }
                }
            }
        }
    }

    // S3 阶段3：字段区（保留 EditText 行内编辑）
    // 简化说明：不套 SettingsCard——其内层 Column 不撑满高度且带 16dp 水平内边距+圆角，
    // 会导致 RecyclerView 高度塌陷（内容展示不出）与行内编辑被圆角/内边距裁剪（布局错乱），
    // 字段区必须全宽可滚动。
    private fun initComposeFields() {
        binding.composeFields.setContent {
            LegadoTheme {
                AndroidView(
                    factory = { recyclerView },
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }

    // S3 阶段3：底部保存/取消栏（12dp 圆角 48dp 高）
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
                        onClick = {
                            saveSource(getSource()) {
                                setResult(RESULT_OK, Intent().putExtra("origin", it.bookSourceUrl))
                                finish()
                            }
                        },
                        modifier = Modifier.weight(1f).height(48.dp),
                        shape = AppShapes.Button
                    ) {
                        Text(text = getString(R.string.action_save))
                    }
                }
            }
        }
    }

    override fun finish() {
        val source = getSource()
        if (!source.equal(viewModel.bookSource ?: BookSource())) {
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

    override fun onDestroy() {
        super.onDestroy()
        softKeyboardTool.dismiss()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        saveEditEntities(outState, "sourceEntities", sourceEntities)
        saveEditEntities(outState, "searchEntities", searchEntities)
        saveEditEntities(outState, "exploreEntities", exploreEntities)
        saveEditEntities(outState, "infoEntities", infoEntities)
        saveEditEntities(outState, "tocEntities", tocEntities)
        saveEditEntities(outState, "contentEntities", contentEntities)
        outState.putInt(KEY_SELECTED_TAB, selectedTabIndex)
        super.onSaveInstanceState(outState)
    }

    private fun saveEditEntities(outState: Bundle, key: String, list: ArrayList<EditEntity>) {
        outState.putString(key, if (list.isEmpty()) null else GSON.toJson(list))
    }

    private fun restoreEditEntities(savedState: Bundle?, key: String, list: ArrayList<EditEntity>) {
        val json = savedState?.getString(key) ?: return
        val saved: Array<EditEntity>? = runCatching {
            GSON.fromJson(json, Array<EditEntity>::class.java)
        }.getOrNull()
        if (saved != null && saved.size == list.size) {
            saved.forEachIndexed { i, entity ->
                if (list[i].key == entity.key) {
                    list[i].value = entity.value
                }
            }
        }
        // 尺寸不匹配时保留当前 upSourceView 填充值，避免 key 错位覆盖
    }

    private fun restoreState(savedInstanceState: Bundle?) {
        if (savedInstanceState == null) return
        restoreEditEntities(savedInstanceState, "sourceEntities", sourceEntities)
        restoreEditEntities(savedInstanceState, "searchEntities", searchEntities)
        restoreEditEntities(savedInstanceState, "exploreEntities", exploreEntities)
        restoreEditEntities(savedInstanceState, "infoEntities", infoEntities)
        restoreEditEntities(savedInstanceState, "tocEntities", tocEntities)
        restoreEditEntities(savedInstanceState, "contentEntities", contentEntities)
        val tab = savedInstanceState.getInt(KEY_SELECTED_TAB, 0)
        if (tab in 0..5) {
            selectedTabIndex = tab
            setEditEntities(tab)
        }
    }

    companion object {
        private const val KEY_SELECTED_TAB = "selectedTabIndex"
    }

    private fun setEditEntities(tabPosition: Int?) {
        adapter.editEntities = when (tabPosition) {
            1 -> searchEntities
            2 -> exploreEntities
            3 -> infoEntities
            4 -> tocEntities
            5 -> contentEntities
//            6 -> reviewEntities
            else -> sourceEntities
        }
        recyclerView.scrollToPosition(0)
        window.decorView.rootView.clearFocus()
    }

    private fun upSourceView(bookSource: BookSource?) {
        val bs = bookSource ?: BookSource()
        bs.let {
            isEnable = it.enabled
            isEnableExplore = it.enabledExplore
            isEnableCookie = it.enabledCookieJar ?: false
            typeIndex = when (it.bookSourceType) {
                BookSourceType.video -> 4
                BookSourceType.file -> 3
                BookSourceType.image -> 2
                BookSourceType.audio -> 1
                else -> 0
            }
            isEventListener = it.eventListener
            isCustomButton = it.customButton
        }
        // 基本信息
        sourceEntities.clear()
        sourceEntities.apply {
            add(EditEntity("bookSourceUrl", bs.bookSourceUrl, R.string.source_url))
            add(EditEntity("bookSourceName", bs.bookSourceName, R.string.source_name))
            add(EditEntity("bookSourceGroup", bs.bookSourceGroup, R.string.source_group))
            add(EditEntity("bookSourceComment", bs.bookSourceComment, R.string.comment))
            add(EditEntity("loginUrl", bs.loginUrl, R.string.login_url))
            add(EditEntity("loginUi", bs.loginUi, R.string.login_ui))
            add(EditEntity("loginCheckJs", bs.loginCheckJs, R.string.login_check_js))
            add(EditEntity("coverDecodeJs", bs.coverDecodeJs, R.string.cover_decode_js))
            add(EditEntity("bookUrlPattern", bs.bookUrlPattern, R.string.book_url_pattern))
            add(EditEntity("header", bs.header, R.string.source_http_header))
            add(EditEntity("variableComment", bs.variableComment, R.string.variable_comment))
            add(EditEntity("concurrentRate", bs.concurrentRate, R.string.concurrent_rate))
            add(EditEntity("jsLib", bs.jsLib, "jsLib"))
        }
        // 搜索
        val sr = bs.getSearchRule()
        searchEntities.clear()
        searchEntities.apply {
            add(EditEntity("searchUrl", bs.searchUrl, R.string.r_search_url))
            add(EditEntity("checkKeyWord", sr.checkKeyWord, R.string.check_key_word))
            add(EditEntity("bookList", sr.bookList, R.string.r_book_list))
            add(EditEntity("name", sr.name, R.string.r_book_name))
            add(EditEntity("author", sr.author, R.string.r_author))
            add(EditEntity("kind", sr.kind, R.string.rule_book_kind))
            add(EditEntity("wordCount", sr.wordCount, R.string.rule_word_count))
            add(EditEntity("lastChapter", sr.lastChapter, R.string.rule_last_chapter))
            add(EditEntity("intro", sr.intro, R.string.rule_book_intro))
            add(EditEntity("coverUrl", sr.coverUrl, R.string.rule_cover_url))
            add(EditEntity("bookUrl", sr.bookUrl, R.string.r_book_url))
        }
        // 发现
        val er = bs.getExploreRule()
        exploreEntities.clear()
        exploreEntities.apply {
            add(EditEntity("exploreUrl", bs.exploreUrl, R.string.r_find_url))
            add(EditEntity("bookList", er.bookList, R.string.r_book_list))
            add(EditEntity("name", er.name, R.string.r_book_name))
            add(EditEntity("author", er.author, R.string.r_author))
            add(EditEntity("kind", er.kind, R.string.rule_book_kind))
            add(EditEntity("wordCount", er.wordCount, R.string.rule_word_count))
            add(EditEntity("lastChapter", er.lastChapter, R.string.rule_last_chapter))
            add(EditEntity("intro", er.intro, R.string.rule_book_intro))
            add(EditEntity("coverUrl", er.coverUrl, R.string.rule_cover_url))
            add(EditEntity("bookUrl", er.bookUrl, R.string.r_book_url))
        }
        // 详情页
        val ir = bs.getBookInfoRule()
        infoEntities.clear()
        infoEntities.apply {
            add(EditEntity("init", ir.init, R.string.rule_book_info_init))
            add(EditEntity("name", ir.name, R.string.r_book_name))
            add(EditEntity("author", ir.author, R.string.r_author))
            add(EditEntity("kind", ir.kind, R.string.rule_book_kind))
            add(EditEntity("wordCount", ir.wordCount, R.string.rule_word_count))
            add(EditEntity("lastChapter", ir.lastChapter, R.string.rule_last_chapter))
            add(EditEntity("intro", ir.intro, R.string.rule_book_intro))
            add(EditEntity("coverUrl", ir.coverUrl, R.string.rule_cover_url))
            add(EditEntity("tocUrl", ir.tocUrl, R.string.rule_toc_url))
            add(EditEntity("canReName", ir.canReName, R.string.rule_can_re_name))
            add(EditEntity("downloadUrls", ir.downloadUrls, R.string.download_url_rule))
        }
        // 目录页
        val tr = bs.getTocRule()
        tocEntities.clear()
        tocEntities.apply {
            add(EditEntity("preUpdateJs", tr.preUpdateJs, R.string.pre_update_js))
            add(EditEntity("chapterList", tr.chapterList, R.string.rule_chapter_list))
            add(EditEntity("chapterName", tr.chapterName, R.string.rule_chapter_name))
            add(EditEntity("chapterUrl", tr.chapterUrl, R.string.rule_chapter_url))
            add(EditEntity("formatJs", tr.formatJs, R.string.format_js_rule))
            add(EditEntity("isVolume", tr.isVolume, R.string.rule_is_volume))
            add(EditEntity("updateTime", tr.updateTime, R.string.rule_update_time))
            add(EditEntity("isVip", tr.isVip, R.string.rule_is_vip))
            add(EditEntity("isPay", tr.isPay, R.string.rule_is_pay))
            add(EditEntity("nextTocUrl", tr.nextTocUrl, R.string.rule_next_toc_url))
        }
        // 正文页
        val cr = bs.getContentRule()
        contentEntities.clear()
        contentEntities.apply {
            add(EditEntity("content", cr.content, R.string.rule_book_content))
            add(EditEntity("nextContentUrl", cr.nextContentUrl, R.string.rule_next_content))
            add(EditEntity("subContent", cr.subContent, R.string.rule_sub_content))
            add(EditEntity("replaceRegex", cr.replaceRegex, R.string.rule_replace_regex))
            add(EditEntity("title", cr.title, R.string.rule_chapter_name))
            add(EditEntity("sourceRegex", cr.sourceRegex, R.string.rule_source_regex))
            add(EditEntity("imageStyle", cr.imageStyle, R.string.rule_image_style))
            add(EditEntity("imageDecode", cr.imageDecode, R.string.rule_image_decode))
            add(EditEntity("webJs", cr.webJs, R.string.rule_web_js))
            add(EditEntity("payAction", cr.payAction, R.string.rule_pay_action))
            add(EditEntity("callBackJs", cr.callBackJs, R.string.rule_call_back))
        }
        // 段评
//        val rr = bs.getReviewRule()
//        reviewEntities.clear()
//        reviewEntities.apply {
//            add(EditEntity("reviewUrl", rr.reviewUrl, R.string.rule_review_url))
//            add(EditEntity("avatarRule", rr.avatarRule, R.string.rule_avatar))
//            add(EditEntity("contentRule", rr.contentRule, R.string.rule_review_content))
//            add(EditEntity("postTimeRule", rr.postTimeRule, R.string.rule_post_time))
//            add(EditEntity("reviewQuoteUrl", rr.reviewQuoteUrl, R.string.rule_review_quote))
//            add(EditEntity("voteUpUrl", rr.voteUpUrl, R.string.review_vote_up))
//            add(EditEntity("voteDownUrl", rr.voteDownUrl, R.string.review_vote_down))
//            add(EditEntity("postReviewUrl", rr.postReviewUrl, R.string.post_review_url))
//            add(EditEntity("postQuoteUrl", rr.postQuoteUrl, R.string.post_quote_url))
//            add(EditEntity("deleteUrl", rr.deleteUrl, R.string.delete_review_url))
//        }
        selectedTabIndex = 0
        setEditEntities(0)
    }

    private fun getSource(): BookSource {
        val source = viewModel.bookSource?.copy() ?: BookSource()
        source.enabled = isEnable
        source.enabledExplore = isEnableExplore
        source.enabledCookieJar = isEnableCookie
        source.bookSourceType = when (typeIndex) {
            4 -> BookSourceType.video
            3 -> BookSourceType.file
            2 -> BookSourceType.image
            1 -> BookSourceType.audio
            else -> BookSourceType.default
        }
        source.eventListener = isEventListener
        source.customButton = isCustomButton
        val searchRule = SearchRule()
        val exploreRule = ExploreRule()
        val bookInfoRule = BookInfoRule()
        val tocRule = TocRule()
        val contentRule = ContentRule()
//        val reviewRule = ReviewRule()
        sourceEntities.forEach {
            it.value = it.value?.takeIf { s -> s.isNotBlank() }
            when (it.key) {
                "bookSourceUrl" -> source.bookSourceUrl = it.value ?: ""
                "bookSourceName" -> source.bookSourceName = it.value ?: ""
                "bookSourceGroup" -> source.bookSourceGroup = it.value
                "loginUrl" -> source.loginUrl = it.value
                "loginUi" -> source.loginUi = it.value
                "loginCheckJs" -> source.loginCheckJs = it.value
                "coverDecodeJs" -> source.coverDecodeJs = it.value
                "bookUrlPattern" -> source.bookUrlPattern = it.value
                "header" -> source.header = it.value
                "bookSourceComment" -> source.bookSourceComment = it.value
                "concurrentRate" -> source.concurrentRate = it.value
                "variableComment" -> source.variableComment = it.value
                "jsLib" -> source.jsLib = it.value
            }
        }
        searchEntities.forEach {
            it.value = it.value?.takeIf { s -> s.isNotBlank() }
            when (it.key) {
                "searchUrl" -> source.searchUrl = it.value
                "checkKeyWord" -> searchRule.checkKeyWord = it.value
                "bookList" -> searchRule.bookList = it.value
                "name" -> searchRule.name =
                    viewModel.ruleComplete(it.value, searchRule.bookList)

                "author" -> searchRule.author =
                    viewModel.ruleComplete(it.value, searchRule.bookList)

                "kind" -> searchRule.kind =
                    viewModel.ruleComplete(it.value, searchRule.bookList)

                "intro" -> searchRule.intro =
                    viewModel.ruleComplete(it.value, searchRule.bookList)

//                "updateTime" -> searchRule.updateTime =
//                    viewModel.ruleComplete(it.value, searchRule.bookList)

                "wordCount" -> searchRule.wordCount =
                    viewModel.ruleComplete(it.value, searchRule.bookList)

                "lastChapter" -> searchRule.lastChapter =
                    viewModel.ruleComplete(it.value, searchRule.bookList)

                "coverUrl" -> searchRule.coverUrl =
                    viewModel.ruleComplete(it.value, searchRule.bookList, 3)

                "bookUrl" -> searchRule.bookUrl =
                    viewModel.ruleComplete(it.value, searchRule.bookList, 2)
            }
        }
        exploreEntities.forEach {
            it.value = it.value?.takeIf { s -> s.isNotBlank() }
            when (it.key) {
                "exploreUrl" -> source.exploreUrl = it.value
                "bookList" -> exploreRule.bookList = it.value
                "name" -> exploreRule.name =
                    viewModel.ruleComplete(it.value, exploreRule.bookList)

                "author" -> exploreRule.author =
                    viewModel.ruleComplete(it.value, exploreRule.bookList)

                "kind" -> exploreRule.kind =
                    viewModel.ruleComplete(it.value, exploreRule.bookList)

                "intro" -> exploreRule.intro =
                    viewModel.ruleComplete(it.value, exploreRule.bookList)

//                "updateTime" -> exploreRule.updateTime =
//                    viewModel.ruleComplete(it.value, exploreRule.bookList)

                "wordCount" -> exploreRule.wordCount =
                    viewModel.ruleComplete(it.value, exploreRule.bookList)

                "lastChapter" -> exploreRule.lastChapter =
                    viewModel.ruleComplete(it.value, exploreRule.bookList)

                "coverUrl" -> exploreRule.coverUrl =
                    viewModel.ruleComplete(it.value, exploreRule.bookList, 3)

                "bookUrl" -> exploreRule.bookUrl =
                    viewModel.ruleComplete(it.value, exploreRule.bookList, 2)
            }
        }
        infoEntities.forEach {
            it.value = it.value?.takeIf { s -> s.isNotBlank() }
            when (it.key) {
                "init" -> bookInfoRule.init = it.value
                "name" -> bookInfoRule.name = viewModel.ruleComplete(it.value, bookInfoRule.init)
                "author" -> bookInfoRule.author =
                    viewModel.ruleComplete(it.value, bookInfoRule.init)

                "kind" -> bookInfoRule.kind =
                    viewModel.ruleComplete(it.value, bookInfoRule.init)

                "intro" -> bookInfoRule.intro =
                    viewModel.ruleComplete(it.value, bookInfoRule.init)

//                "updateTime" -> bookInfoRule.updateTime =
//                    viewModel.ruleComplete(it.value, bookInfoRule.init)

                "wordCount" -> bookInfoRule.wordCount =
                    viewModel.ruleComplete(it.value, bookInfoRule.init)

                "lastChapter" -> bookInfoRule.lastChapter =
                    viewModel.ruleComplete(it.value, bookInfoRule.init)

                "coverUrl" -> bookInfoRule.coverUrl =
                    viewModel.ruleComplete(it.value, bookInfoRule.init, 3)

                "tocUrl" -> bookInfoRule.tocUrl =
                    viewModel.ruleComplete(it.value, bookInfoRule.init, 2)

                "canReName" -> bookInfoRule.canReName = it.value
                "downloadUrls" -> bookInfoRule.downloadUrls =
                    viewModel.ruleComplete(it.value, bookInfoRule.init)
            }
        }
        tocEntities.forEach {
            it.value = it.value?.takeIf { s -> s.isNotBlank() }
            when (it.key) {
                "preUpdateJs" -> tocRule.preUpdateJs = it.value
                "chapterList" -> tocRule.chapterList = it.value
                "chapterName" -> tocRule.chapterName =
                    viewModel.ruleComplete(it.value, tocRule.chapterList)

                "chapterUrl" -> tocRule.chapterUrl =
                    viewModel.ruleComplete(it.value, tocRule.chapterList, 2)

                "formatJs" -> tocRule.formatJs = it.value
                "isVolume" -> tocRule.isVolume = it.value
                "updateTime" -> tocRule.updateTime = it.value
                "isVip" -> tocRule.isVip = it.value
                "isPay" -> tocRule.isPay = it.value
                "nextTocUrl" -> tocRule.nextTocUrl =
                    viewModel.ruleComplete(it.value, tocRule.chapterList, 2)
            }
        }
        contentEntities.forEach {
            it.value = it.value?.takeIf { s -> s.isNotBlank() }
            when (it.key) {
                "content" -> contentRule.content = viewModel.ruleComplete(it.value)
                "nextContentUrl" -> contentRule.nextContentUrl =
                    viewModel.ruleComplete(it.value, type = 2)
                "subContent" -> contentRule.subContent = viewModel.ruleComplete(it.value)
                "title" -> contentRule.title = viewModel.ruleComplete(it.value)

                "webJs" -> contentRule.webJs = it.value
                "sourceRegex" -> contentRule.sourceRegex = it.value
                "replaceRegex" -> contentRule.replaceRegex = it.value
                "imageStyle" -> contentRule.imageStyle = it.value
                "imageDecode" -> contentRule.imageDecode = it.value
                "payAction" -> contentRule.payAction = it.value
                "callBackJs" -> contentRule.callBackJs = it.value
            }
        }
//        reviewEntities.forEach {
//            when (it.key) {
//                "reviewUrl" -> reviewRule.reviewUrl = it.value
//                "avatarRule" -> reviewRule.avatarRule =
//                    viewModel.ruleComplete(it.value, reviewRule.reviewUrl, 3)
//
//                "contentRule" -> reviewRule.contentRule =
//                    viewModel.ruleComplete(it.value, reviewRule.reviewUrl)
//
//                "postTimeRule" -> reviewRule.postTimeRule =
//                    viewModel.ruleComplete(it.value, reviewRule.reviewUrl)
//
//                "reviewQuoteUrl" -> reviewRule.reviewQuoteUrl =
//                    viewModel.ruleComplete(it.value, reviewRule.reviewUrl, 2)
//
//                "voteUpUrl" -> reviewRule.voteUpUrl = it.value
//                "voteDownUrl" -> reviewRule.voteDownUrl = it.value
//                "postReviewUrl" -> reviewRule.postReviewUrl = it.value
//                "postQuoteUrl" -> reviewRule.postQuoteUrl = it.value
//                "deleteUrl" -> reviewRule.deleteUrl = it.value
//            }
//        }
        source.ruleSearch = searchRule
        source.ruleExplore = exploreRule
        source.ruleBookInfo = bookInfoRule
        source.ruleToc = tocRule
        source.ruleContent = contentRule
//        source.ruleReview = reviewRule
        return source
    }

    private fun alertGroups() {
        lifecycleScope.launch {
            val groups = withContext(IO) {
                appDb.bookSourceDao.allGroups()
            }
            selector(groups) { _, s, _ ->
                sendText(s)
            }
        }
    }

    override fun helpActions(): List<SelectItem<String>> {
        val helpActions = arrayListOf(
            SelectItem(getString(R.string.insert_url_param), "urlOption"),
            SelectItem(getString(R.string.book_source_tutorial), "ruleHelp"),
            SelectItem(getString(R.string.js_tutorial), "jsHelp"),
            SelectItem(getString(R.string.regex_tutorial), "regexHelp"),
        )
        val view = window.decorView.findFocus()
        if (view is EditText) {
            when (view.getTag(R.id.tag)) {
                "bookSourceGroup" -> {
                    helpActions.add(
                        SelectItem(getString(R.string.insert_group), "addGroup")
                    )
                }

                else -> {
                    helpActions.add(
                        SelectItem(getString(R.string.select_file), "selectFile")
                    )
                }
            }
        }
        return helpActions
    }

    override fun onHelpActionSelect(action: String) {
        when (action) {
            "addGroup" -> alertGroups()
            "urlOption" -> UrlOptionDialog(this) { sendText(it) }.show()
            "ruleHelp" -> showHelp("ruleHelp")
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

    private fun saveSource(
        source: BookSource,
        onSuccess: ((BookSource) -> Unit)? = null
    ) {
        val oldUrl = viewModel.bookSource?.bookSourceUrl
        val urlChanged = !oldUrl.isNullOrBlank() && oldUrl != source.bookSourceUrl
        viewModel.save(source) { savedSource ->
            if (urlChanged && oldUrl != null) {
                AppLog.putDebugWithTag(
                    AppLog.TAG_BOOK_ORIGIN_MIGRATE,
                    "检测到书源URL变更: oldUrl=$oldUrl newUrl=${savedSource.bookSourceUrl} 将检查书架书籍迁移",
                    level = AppLog.Level.INFO
                )
                lifecycleScope.launch {
                    val hasBooks = withContext(IO) { appDb.bookDao.hasBookByOrigin(oldUrl) }
                    if (hasBooks) {
                        alert(R.string.migrate_book_origin_title) {
                            setMessage(R.string.migrate_book_origin_msg)
                            positiveButton(R.string.migrate_book_origin_yes) {
                                lifecycleScope.launch {
                                    val affected = withContext(IO) {
                                        appDb.bookDao.updateOrigin(oldUrl, savedSource.bookSourceUrl)
                                    }
                                    AppLog.putDebugWithTag(
                                        AppLog.TAG_BOOK_ORIGIN_MIGRATE,
                                        "书源URL迁移完成: oldUrl=$oldUrl newUrl=${savedSource.bookSourceUrl} 受影响书籍=$affected",
                                        level = AppLog.Level.INFO
                                    )
                                    onSuccess?.invoke(savedSource)
                                }
                            }
                            negativeButton(R.string.migrate_book_origin_no) {
                                onSuccess?.invoke(savedSource)
                            }
                        }
                    } else {
                        onSuccess?.invoke(savedSource)
                    }
                }
            } else {
                onSuccess?.invoke(savedSource)
            }
        }
    }

    private fun setSourceVariable() {
        viewModel.save(getSource()) { source ->
            lifecycleScope.launch {
                val comment =
                    source.getDisplayVariableComment(getString(R.string.source_variable_comment))
                val variable = withContext(IO) { source.getVariable() }
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
        viewModel.bookSource?.setVariable(variable)
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
