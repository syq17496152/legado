package io.legado.app.ui.book.source.edit

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.EditText
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.annotation.RequiresApi
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.lifecycleScope
import androidx.viewbinding.ViewBinding
import com.google.android.material.tabs.TabLayout
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import io.legado.app.R
import io.legado.app.base.VMBaseActivity
import io.legado.app.base.attachComposeContent
import io.legado.app.base.composeShell
import io.legado.app.constant.AppLog
import io.legado.app.constant.BookSourceType
import io.legado.app.data.appDb
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.rule.BookInfoRule
import io.legado.app.data.entities.rule.ContentRule
import io.legado.app.data.entities.rule.ExploreRule
import io.legado.app.data.entities.rule.SearchRule
import io.legado.app.data.entities.rule.TocRule
import io.legado.app.help.config.LocalConfig
import io.legado.app.lib.dialogs.SelectItem
import io.legado.app.lib.dialogs.selector
import io.legado.app.lib.theme.accentColor
import io.legado.app.lib.theme.backgroundColor
import io.legado.app.ui.widget.compose.showComposeConfirmDialog
import io.legado.app.ui.widget.compose.showComposeChoiceListDialog
import io.legado.app.lib.theme.primaryColor
import io.legado.app.ui.about.AppLogDialog
import io.legado.app.ui.book.search.SearchActivity
import io.legado.app.ui.book.source.debug.BookSourceDebugActivity
import io.legado.app.ui.code.CodeEditActivity
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.ui.login.SourceLoginActivity
import io.legado.app.ui.qrcode.QrCodeResult
import io.legado.app.ui.theme.LegadoTheme
import io.legado.app.ui.widget.components.GlassTopAppBar
import io.legado.app.ui.widget.components.InlineGuideBar
import io.legado.app.ui.widget.components.MenuAction
import io.legado.app.ui.widget.components.TopBarActionRow
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
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import splitties.views.bottomPadding

class BookSourceEditActivity :
    VMBaseActivity<ViewBinding, BookSourceEditViewModel>(),
    KeyboardToolPop.CallBack,
    VariableDialog.Callback {

    // 原 activity_book_source_edit.xml 已退役（CE-a）：composeShell 合成壳 + attachComposeContent 单源；
    // 顶栏由 installGlassTopBar 运行时注入（首插 ComposeView + 移除 R.id.title_bar 锚点）改为**页内直接渲染**
    override val binding: ViewBinding by lazy { composeShell(this) }
    override val viewModel by viewModels<BookSourceEditViewModel>()

    /** 原 XML 各段节点的程序化等价物（见 [BookSourceEditShellViews]；RecyclerView/TabLayout 需在
     *  `onActivityCreated` 就被配置 ⇒ 必须由装配类提前建好、宿主以字段持有） */
    private val shell by lazy { BookSourceEditShellViews(this) }

    private val adapter by lazy { BookSourceEditAdapter() }
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
        initComposeContent()
        initView()
        initRuleHelpGuide()
        viewModel.initData(intent) {
            upSourceView(viewModel.bookSource)
        }
    }

    /** 优化 2（F）：规则帮助首显形态——一次性引导条（替代进页即弹全屏帮助，教育入口保留、编辑流不被打断） */
    private var ruleHelpGuideVisible by mutableStateOf(false)

    /**
     * 优化 2：引导条显隐判定。
     *
     * 「一次性」由既有 `LocalConfig.ruleHelpVersionIsLast` 承担（`isLastVersion` **读时写入** ⇒ 只能读一次，
     * 不新增偏好键）。⚠️ 正因为是「读时写入」：冷启动期 App 会发 `EventBus.RECREATE` 令本页在**出帧前重建**，
     * 新实例再读该旗标已成 true（首实例已消费）⇒ 直接重读恒为 false（实测引导槽高恒 0、永不出现）。
     * 故判定落在**进程内单例** [RuleHelpGuideOnce] 上：重建按已定状态复原，进程结束即释放
     * （下次冷启动旗标已消费 ⇒ 自然不再打扰）。
     */
    private fun initRuleHelpGuide() {
        RuleHelpGuideOnce.armed = RuleHelpGuideOnce.armed ?: !LocalConfig.ruleHelpVersionIsLast
        ruleHelpGuideVisible = RuleHelpGuideOnce.armed == true
        // 合成策略由装配单源（BookSourceEditShellViews）统一设置，宿主不再自设
        shell.cvRuleHelpGuide.setContent {
            LegadoTheme {
                if (ruleHelpGuideVisible) {
                    InlineGuideBar(
                        text = getString(R.string.book_source_rule_help_guide),
                        onAction = {
                            showHelp("ruleHelp")
                            dismissRuleHelpGuide()
                        },
                        onDismiss = { dismissRuleHelpGuide() },
                    )
                }
            }
        }
    }

    private fun dismissRuleHelpGuide() {
        RuleHelpGuideOnce.armed = false
        ruleHelpGuideVisible = false
    }

    /** CE-a（2026-09-26）：顶栏已从 installGlassTopBar 运行时注入改为**页内直接渲染**（见 initComposeContent）。 */
    @OptIn(ExperimentalMaterial3Api::class)
    private fun initComposeContent() {
        binding.root.attachComposeContent {
            Column(modifier = Modifier.fillMaxSize()) {
                // ---- 顶栏（原 installGlassTopBar 注入的 GlassTopAppBar：标题/返回/动作逐项不变）----
                LegadoTheme {
                    GlassTopAppBar(
                        title = getString(R.string.edit_book_source),
                        navIcon = Icons.AutoMirrored.Filled.ArrowBack,
                        onNavClick = { finish() },
                        actions = { TopBarActionRow(topBarActions()) }
                    )
                }
                // ---- 规则帮助引导条槽（原 cv_rule_help_guide）----
                AndroidView(
                    modifier = Modifier.fillMaxWidth(),
                    factory = { shell.cvRuleHelpGuide }
                )
                // ---- 基础信息行（原 HorizontalScrollView#1）----
                AndroidView(
                    modifier = Modifier.fillMaxWidth(),
                    factory = { shell.basicRow }
                )
                // ---- 开关行（原 HorizontalScrollView#2）----
                AndroidView(
                    modifier = Modifier.fillMaxWidth(),
                    factory = { shell.switchRow }
                )
                // ---- TabLayout（原 tab_layout：36dp 高 + elevation 3dp）----
                AndroidView(
                    modifier = Modifier.fillMaxWidth(),
                    factory = { shell.tabLayout }
                )
                // ---- RecyclerView（原 recycler_view：占剩余高度；`0dp` 高度语义由 Compose weight 表达）----
                AndroidView(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    factory = { shell.recyclerView }
                )
            }
        }
    }

    /**
     * 顶栏动作（原 `actionsProvider`：代码/保存/调试一级图标 + 三组溢出菜单，条目与行为逐项不变）。
     *
     * 溢出菜单分组按「用户心智（对源的操作 / 进出通道 / 诊断与帮助）」而非代码顺序；
     * 12 项文案逐字沿用 R.string 现值 ⇒ 零文案改动。
     */
    private fun topBarActions(): List<MenuAction> =
        buildList {
            add(
                MenuAction(
                    iconRes = R.drawable.ic_code,
                    title = getString(R.string.edit_content),
                    alwaysShow = true
                ) { onFullEditClicked() }
            )
            add(
                MenuAction(
                    iconRes = R.drawable.ic_save,
                    title = getString(R.string.action_save),
                    alwaysShow = true
                ) {
                    saveSource(getSource()) {
                        setResult(RESULT_OK, Intent().putExtra("origin", it.bookSourceUrl))
                        finish()
                    }
                }
            )
            add(
                MenuAction(
                    iconRes = R.drawable.ic_bug_report_outline,
                    title = getString(R.string.debug_source),
                    alwaysShow = true
                ) {
                    saveSource(getSource()) { source ->
                        startActivity<BookSourceDebugActivity> {
                            putExtra("key", source.bookSourceUrl)
                        }
                    }
                }
            )
            fun addGroup(@androidx.annotation.StringRes titleRes: Int) {
                add(MenuAction(title = getString(titleRes), header = true, onClick = {}))
            }
            // 组 1：源操作
            addGroup(R.string.book_source_menu_group_ops)
            // 登录入口条件显隐（原 prepare 中 menu_login.isVisible 逻辑平移）
            if (!getSource().loginUrl.isNullOrBlank()) {
                add(
                    MenuAction(title = getString(R.string.login)) {
                        handleSourceEditMenuAction(R.id.menu_login)
                    }
                )
            }
            add(MenuAction(title = getString(R.string.search)) { handleSourceEditMenuAction(R.id.menu_search) })
            add(MenuAction(title = getString(R.string.cookie)) { handleSourceEditMenuAction(R.id.menu_clear_cookie) })
            add(
                MenuAction(
                    title = getString(R.string.auto_complete),
                    checked = viewModel.autoComplete
                ) { handleSourceEditMenuAction(R.id.menu_auto_complete) }
            )
            // 组 2：导入 · 导出 · 分享（拷贝/粘贴成对，二维码导入/二维码分享/字符串分享相邻）
            addGroup(R.string.book_source_menu_group_share)
            add(MenuAction(title = getString(R.string.copy_source)) { handleSourceEditMenuAction(R.id.menu_copy_source) })
            add(MenuAction(title = getString(R.string.paste_source)) { handleSourceEditMenuAction(R.id.menu_paste_source) })
            add(MenuAction(title = getString(R.string.import_by_qr_code)) { handleSourceEditMenuAction(R.id.menu_qr_code_camera) })
            add(MenuAction(title = getString(R.string.qr_share)) { handleSourceEditMenuAction(R.id.menu_share_qr) })
            add(MenuAction(title = getString(R.string.str_share)) { handleSourceEditMenuAction(R.id.menu_share_str) })
            // 组 3：诊断与帮助
            addGroup(R.string.book_source_menu_group_diag)
            add(MenuAction(title = getString(R.string.set_source_variable)) { handleSourceEditMenuAction(R.id.menu_set_source_variable) })
            add(MenuAction(title = getString(R.string.log)) { handleSourceEditMenuAction(R.id.menu_log) })
            add(MenuAction(title = getString(R.string.help)) { handleSourceEditMenuAction(R.id.menu_help) })
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

    // W7.2：原 onCompatOptionsItemSelected 改私有分发（溢出菜单 MenuAction 直调，itemId 语义不变）
    private fun handleSourceEditMenuAction(itemId: Int) {
        when (itemId) {
            R.id.menu_fullscreen_edit -> onFullEditClicked()

            R.id.menu_save -> saveSource(getSource()) {
                setResult(RESULT_OK, Intent().putExtra("origin", it.bookSourceUrl))
                finish()
            }

            R.id.menu_debug_source -> saveSource(getSource()) { source ->
                startActivity<BookSourceDebugActivity> {
                    putExtra("key", source.bookSourceUrl)
                }
            }

            R.id.menu_clear_cookie -> viewModel.clearCookie(getSource().bookSourceUrl)
            R.id.menu_auto_complete -> viewModel.autoComplete = !viewModel.autoComplete
            R.id.menu_copy_source -> sendToClip(GSON.toJson(getSource()))
            R.id.menu_paste_source -> viewModel.pasteSource { upSourceView(it) }
            R.id.menu_qr_code_camera -> qrCodeResult.launch()
            R.id.menu_share_str -> share(GSON.toJson(getSource()))
            R.id.menu_share_qr -> shareWithQr(
                GSON.toJson(getSource()),
                getString(R.string.share_book_source),
                ErrorCorrectionLevel.L
            )

            R.id.menu_log -> showDialogFragment<AppLogDialog>()
            R.id.menu_help -> showHelp("ruleHelp")
            R.id.menu_login -> saveSource(getSource()) { source ->
                startActivity<SourceLoginActivity> {
                    putExtra("type", "bookSource")
                    putExtra("key", source.bookSourceUrl)
                }
            }

            R.id.menu_set_source_variable -> setSourceVariable()
            R.id.menu_search -> saveSource(getSource()) { source ->
                SearchActivity.start(this, source)
            }

        }
    }

    private fun initView() {
        shell.tabLayout.addTab(shell.tabLayout.newTab().apply {
            setText(R.string.source_tab_base)
        })
        shell.tabLayout.addTab(shell.tabLayout.newTab().apply {
            setText(R.string.source_tab_search)
        })
        shell.tabLayout.addTab(shell.tabLayout.newTab().apply {
            setText(R.string.source_tab_find)
        })
        shell.tabLayout.addTab(shell.tabLayout.newTab().apply {
            setText(R.string.source_tab_info)
        })
        shell.tabLayout.addTab(shell.tabLayout.newTab().apply {
            setText(R.string.source_tab_toc)
        })
        shell.tabLayout.addTab(shell.tabLayout.newTab().apply {
            setText(R.string.source_tab_content)
        })
        shell.recyclerView.setEdgeEffectColor(primaryColor)
        if (adapter.editEntityMaxLine < 999) {
            shell.recyclerView.layoutManager = NoChildScrollLinearLayoutManager(this) //启用后会阻止RecyclerView跟随光标滚动,行数少时,用的TextView跟随
        }
        shell.recyclerView.adapter = adapter
        shell.recyclerView.viewTreeObserver.addOnGlobalFocusChangeListener { _, newFocus ->
            if (newFocus is EditText) {
                newFocus.postDelayed({ sendText("") }, 120)
            }
        }
        shell.tabLayout.setBackgroundColor(backgroundColor)
        shell.tabLayout.setSelectedTabIndicatorColor(accentColor)
        shell.tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabReselected(tab: TabLayout.Tab?) {

            }

            override fun onTabUnselected(tab: TabLayout.Tab?) {

            }

            override fun onTabSelected(tab: TabLayout.Tab?) {
                setEditEntities(tab?.position)
            }
        })
        shell.recyclerView.setOnApplyWindowInsetsListenerCompat { view, windowInsets ->
            val navigationBarHeight = windowInsets.navigationBarHeight
            val imeHeight = windowInsets.imeHeight
            view.bottomPadding = if (imeHeight == 0) navigationBarHeight else 0
            softKeyboardTool.initialPadding = imeHeight
            windowInsets
        }
    }

    override fun finish() {
        val source = getSource()
        if (!source.equal(viewModel.bookSource ?: BookSource())) {
            // 既有缺陷修复（本页 §0 记录的「文案语义错位」）：原「是 / 否」两键对不上「继续编辑 / 退出」两种意图，
            // 改为动词式按钮「继续编辑 / 放弃修改」，标题与正文沿用现值（标题=退出，正文=尚未保存，是否继续编辑）
            showComposeConfirmDialog(
                title = getString(R.string.exit),
                message = getString(R.string.exit_no_save),
                positiveText = getString(R.string.edit_continue),
                negativeText = getString(R.string.edit_discard),
                onPositive = { /* 停留当前页，不退出 */ },
                onNegative = { super.finish() }
            )
        } else {
            super.finish()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        softKeyboardTool.dismiss()
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
        shell.recyclerView.scrollToPosition(0)
        window.decorView.rootView.clearFocus()
    }

    private fun upSourceView(bookSource: BookSource?) {
        val bs = bookSource ?: BookSource()
        bs.let {
            shell.cbIsEnable.isChecked = it.enabled
            shell.cbIsEnableExplore.isChecked = it.enabledExplore
            shell.cbIsEnableCookie.isChecked = it.enabledCookieJar ?: false
            shell.spType.setSelection(
                when (it.bookSourceType) {
                    BookSourceType.video -> 4
                    BookSourceType.file -> 3
                    BookSourceType.image -> 2
                    BookSourceType.audio -> 1
                    else -> 0
                }
            )
            shell.cbIsEventListener.isChecked = it.eventListener
            shell.cbIsCustomButton.isChecked = it.customButton
        }
        // 基本信息
        sourceEntities.clear()
        sourceEntities.apply {
            // F155 同构（2026-09-22）：bookSourceUrl / bookSourceName 是 ViewModel.save 的保存校验
            // 必填项 ⇒ label 显式标星号，避免「哪几个必填」靠记忆
            add(EditEntity("bookSourceUrl", bs.bookSourceUrl, R.string.source_url, required = true))
            add(EditEntity("bookSourceName", bs.bookSourceName, R.string.source_name, required = true))
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
        shell.tabLayout.selectTab(shell.tabLayout.getTabAt(0))
        setEditEntities(0)
    }

    private fun getSource(): BookSource {
        val source = viewModel.bookSource?.copy() ?: BookSource()
        source.enabled = shell.cbIsEnable.isChecked
        source.enabledExplore = shell.cbIsEnableExplore.isChecked
        source.enabledCookieJar = shell.cbIsEnableCookie.isChecked
        source.bookSourceType = when (shell.spType.selectedItemPosition) {
            4 -> BookSourceType.video
            3 -> BookSourceType.file
            2 -> BookSourceType.image
            1 -> BookSourceType.audio
            else -> BookSourceType.default
        }
        source.eventListener = shell.cbIsEventListener.isChecked
        source.customButton = shell.cbIsCustomButton.isChecked
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
            showComposeChoiceListDialog(
                title = "选择分组",
                labels = groups
            ) { index ->
                sendText(groups[index])
            }
        }
    }

    override fun helpActions(): List<SelectItem<String>> {
        val helpActions = arrayListOf(
            SelectItem("插入URL参数", "urlOption"),
            SelectItem("书源教程", "ruleHelp"),
            SelectItem("js教程", "jsHelp"),
            SelectItem("正则教程", "regexHelp"),
        )
        val view = window.decorView.findFocus()
        if (view is EditText) {
            when (view.getTag(R.id.tag)) {
                "bookSourceGroup" -> {
                    helpActions.add(
                        SelectItem("插入分组", "addGroup")
                    )
                }

                else -> {
                    helpActions.add(
                        SelectItem("选择文件", "selectFile")
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
                    shell.recyclerView.getLocationOnScreen(recyclerViewLocation)
                    val layout = view.layout
                    if (layout != null) {
                        val line = layout.getLineForOffset(end)
                        val cursorYInEditText = layout.getLineTop(line)
                        // 光标相对于屏幕的位置
                        val cursorYOnScreen = editTextLocation[1] + cursorYInEditText
                        // 光标相对于RecyclerView的位置
                        val cursorYInRecyclerView = cursorYOnScreen - recyclerViewLocation[1]
                        val recyclerViewBottom = shell.recyclerView.height - 120 //考虑键盘的经验值
                        // 如果光标不在可见范围内，则滚动到光标位置
                        if (cursorYInRecyclerView !in 0..recyclerViewBottom) {
                            val scrollDistance = cursorYInRecyclerView - recyclerViewBottom / 3
                            if (scrollDistance > 0 && shell.recyclerView.canScrollVertically(1) || scrollDistance < 0 && shell.recyclerView.canScrollVertically(-1)) {
                                shell.recyclerView.smoothScrollBy(0, scrollDistance)
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * F155 同构（书源编辑页，2026-09-22）：把错误落到指定字段并滚动到可见位置。
     *
     * 必填字段均在「源」Tab（position 0）⇒ 先切 Tab 再定位；切 Tab 会触发
     * `onTabSelected → setEditEntities(0)`，此处再显式调一次保证顺序确定。
     */
    private fun locateField(key: String) {
        val entity = sourceEntities.firstOrNull { it.key == key } ?: return
        sourceEntities.forEach { it.error = null }
        entity.error = getString(R.string.source_required_hint)
        shell.tabLayout.selectTab(shell.tabLayout.getTabAt(0))
        setEditEntities(0)
        val index = adapter.indexOfKey(key)
        if (index >= 0) {
            adapter.notifyItemChanged(index)
            shell.recyclerView.post { shell.recyclerView.scrollToPosition(index) }
        }
    }

    /** F155 同构：清除全部字段错误态（校验通过后再保存，避免红框残留）。 */
    private fun clearFieldErrors() {
        if (sourceEntities.none { it.error != null }) return
        sourceEntities.forEach { it.error = null }
        adapter.notifyDataSetChanged()
    }

    private fun saveSource(
        source: BookSource,
        onSuccess: ((BookSource) -> Unit)? = null
    ) {
        // F155 同构：保存前必填校验 + 失败定位——原实现把校验放在 ViewModel（bookSourceUrl/
        // bookSourceName 空即抛 NoStackTraceException）⇒ 只弹一句 toast，用户需自己在 6 个 Tab
        // 数十个字段里找错字段。此处把「定位」补在页面上，**校验口径不变**（与订阅源编辑页同构）。
        val blankKey = when {
            source.bookSourceUrl.isBlank() -> "bookSourceUrl"
            source.bookSourceName.isBlank() -> "bookSourceName"
            else -> null
        }
        if (blankKey != null) {
            locateField(blankKey)
            return
        }
        clearFieldErrors()
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
                        showComposeConfirmDialog(
                            title = getString(R.string.migrate_book_origin_title),
                            message = getString(R.string.migrate_book_origin_msg),
                            positiveText = getString(R.string.migrate_book_origin_yes),
                            negativeText = getString(R.string.migrate_book_origin_no),
                            onPositive = {
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
                            },
                            onNegative = { onSuccess?.invoke(savedSource) }
                        )
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
        saveSource(getSource()) { source ->
            lifecycleScope.launch {
                val comment =
                    source.getDisplayVariableComment("源变量可在js中通过source.getVariable()获取")
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

/** 优化 2：引导条显隐的**进程内**一次性判定（版本旗标「读时写入」，重建后不可再读；进程结束即释放） */
private object RuleHelpGuideOnce {
    var armed: Boolean? = null
}
