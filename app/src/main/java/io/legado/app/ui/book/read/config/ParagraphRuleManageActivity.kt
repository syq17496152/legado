package io.legado.app.ui.book.read.config

import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.SimpleItemAnimator
import androidx.viewbinding.ViewBinding
import com.google.gson.reflect.TypeToken
import io.legado.app.R
import io.legado.app.base.BaseActivity
import io.legado.app.base.attachComposeContent
import io.legado.app.base.composeShell
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookParagraphRule
import io.legado.app.data.entities.ParagraphRule
import io.legado.app.data.entities.ParagraphRuleVar
import io.legado.app.databinding.ItemThemePackageBinding
import io.legado.app.help.http.newCallResponseBody
import io.legado.app.help.http.okHttpClient
import io.legado.app.lib.theme.UiCorner
import io.legado.app.lib.theme.accentColor
import io.legado.app.lib.theme.applyUiBodyTypefaceDeep
import io.legado.app.lib.theme.applyUiLabelStyle
import io.legado.app.lib.theme.applyUiSectionTitleStyle
import io.legado.app.lib.theme.primaryTextColor
import io.legado.app.lib.theme.secondaryTextColor
import io.legado.app.lib.theme.themeCardColorOrDefault
import io.legado.app.lib.theme.themeMutedColorOrDefault
import io.legado.app.lib.theme.uiTypeface
import io.legado.app.model.ReadBook
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.ui.login.SourceLoginActivity
import io.legado.app.ui.theme.LegadoTheme
import io.legado.app.ui.widget.components.GlassTopAppBar
import io.legado.app.ui.widget.components.MenuAction
import io.legado.app.ui.widget.components.TopBarActionRow
import io.legado.app.ui.widget.compose.showComposeChoiceListDialog
import io.legado.app.ui.widget.compose.showComposeConfirmDialog
import io.legado.app.ui.widget.compose.showComposeTextInputDialog
import io.legado.app.ui.widget.recycler.ItemTouchCallback
import io.legado.app.utils.GSON
import io.legado.app.utils.dpToPx
import io.legado.app.utils.fromJsonArray
import io.legado.app.utils.fromJsonObject
import io.legado.app.utils.readText
import io.legado.app.utils.sendToClip
import io.legado.app.utils.showHelp
import io.legado.app.utils.startActivity
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.writeText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ParagraphRuleManageActivity : BaseActivity<ViewBinding>(), ItemTouchCallback.Callback {

    // 原 activity_theme_manage.xml 已退役（CE-a #8）：composeShell 合成壳 + attachComposeContent 单源；
    // 顶栏由 installGlassTopBar 运行时注入（首插 ComposeView + 移除 R.id.title_bar 锚点）改为**页内直接渲染**
    override val binding: ViewBinding by lazy { composeShell(this) }

    /** 原 XML 字段节点的程序化等价物（14 个管理页共用布局已退役，见 [ThemeManageShellViews]） */
    private val shell by lazy { ThemeManageShellViews(this) }

    private val adapter = RuleAdapter()
    private var bookUrl: String? = null
    private var book: Book? = null
    private var enabledIds: Set<Long> = emptySet()
    private var refreshOnResume = false

    /** F51：排序手柄与「长按卡片」共用的拖拽控制器（手柄按下即 startDrag） */
    private var itemTouchHelper: ItemTouchHelper? = null

    private val importDoc = registerForActivityResult(HandleFileContract()) { result ->
        result.uri?.let { uri ->
            lifecycleScope.launch {
                kotlin.runCatching {
                    parseImportedRules(uri.readText(this@ParagraphRuleManageActivity))
                }.onSuccess { rules ->
                    if (rules.isEmpty()) {
                        toastOnUi(R.string.wrong_format)
                    } else {
                        withContext(Dispatchers.IO) { insertImportedRules(rules) }
                        load()
                        toastOnUi(R.string.success)
                    }
                }.onFailure {
                    toastOnUi(it.localizedMessage ?: getString(R.string.wrong_format))
                }
            }
        }
    }

    private val exportRuleResult = registerForActivityResult(HandleFileContract()) { result ->
        result.uri?.let { uri ->
            val url = uri.toString()
            if (url.startsWith("http://", true) || url.startsWith("https://", true)) {
                showComposeConfirmDialog(
                    title = getString(R.string.upload_url),
                    message = url,
                    positiveText = getString(R.string.copy_text),
                    negativeText = getString(R.string.cancel),
                    onPositive = {
                        sendToClip(url)
                        toastOnUi(R.string.copy_complete)
                    }
                )
            } else {
                toastOnUi(R.string.export_success)
            }
        }
    }

    // ui-theme-governance-polish P6：管理族宿主接入背景透明度（1.5 封闭清单成员）
    override fun manageBackgroundAlphaEnabled(): Boolean = true

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        bookUrl = intent.getStringExtra("bookUrl") ?: ReadBook.book?.bookUrl
        book = bookUrl?.let { appDb.bookDao.getBook(it) }
        initComposeContent()
        initView()
        load()
    }

    override fun onResume() {
        super.onResume()
        if (refreshOnResume) {
            refreshOnResume = false
            refreshReadBookIfNeeded()
        }
        load()
    }

    /**
     * CE-a #8：Compose 承载页面骨架（顶栏 + 共用管理族内容区）。
     *
     * 与原 XML 的对应关系：`title_bar` → 页内 `GlassTopAppBar`；其余节点（`tab_bar` / `tv_summary` /
     * `recycler_view` / `btn_add`）由 [ThemeManageShellViews] 单源装配，以 `AndroidView` 原样托管
     * （本页在 `onActivityCreated` 内就要配置 adapter / ItemTouchHelper / 点击，故必须宿主字段持有同一实例）。
     */
    @OptIn(ExperimentalMaterial3Api::class)
    private fun initComposeContent() {
        binding.root.attachComposeContent {
            Column(modifier = Modifier.fillMaxSize()) {
                // ---- 顶栏（原 installGlassTopBar 注入的 GlassTopAppBar：标题/返回/动作逐项不变）----
                LegadoTheme {
                    GlassTopAppBar(
                        title = getString(R.string.paragraph_rule_manage),
                        navIcon = Icons.AutoMirrored.Filled.ArrowBack,
                        onNavClick = { finish() },
                        actions = { TopBarActionRow(topBarActions()) }
                    )
                }
                // ---- 原共用布局内容区（`recycler_view` 的 `weight=1` 语义由 Compose 权重表达）----
                AndroidView(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    factory = { shell.root }
                )
            }
        }
    }

    /** 顶栏动作（原 `installGlassTopBar` 的 actionsProvider：导入/帮助逐项不变）。 */
    private fun topBarActions(): List<MenuAction> {
        return listOf(
                    MenuAction(
                        iconRes = R.drawable.ic_import,
                        title = getString(R.string.import_str),
                        alwaysShow = true
                    ) { showImportActions() },
                    MenuAction(
                        iconRes = R.drawable.ic_help,
                        title = getString(R.string.help),
                        alwaysShow = true
                    ) { showHelp("paragraphRuleHelp") }
        )
    }

    private fun initView() = shell.run {
        tabBar.visibility = View.GONE
        btnAdd.text = getString(R.string.add)
        btnAdd.background = UiCorner.actionSelector(
            themeCardColorOrDefault(),
            themeMutedColorOrDefault(),
            UiCorner.actionRadius(this@ParagraphRuleManageActivity)
        )
        btnAdd.setOnClickListener { showAddActions() }
        tvSummary.applyUiLabelStyle(this@ParagraphRuleManageActivity)
        tvSummary.setTextColor(secondaryTextColor)
        recyclerView.layoutManager = LinearLayoutManager(this@ParagraphRuleManageActivity)
        recyclerView.adapter = adapter
        (recyclerView.itemAnimator as? SimpleItemAnimator)?.supportsChangeAnimations = false
        // F51：手柄起拖与「长按卡片」共用同一个 ItemTouchHelper（两条入口通向同一排序通道）
        itemTouchHelper = ItemTouchHelper(ItemTouchCallback(this@ParagraphRuleManageActivity).apply {
            isCanDrag = true
        }).also { it.attachToRecyclerView(recyclerView) }
        root.applyUiBodyTypefaceDeep(this@ParagraphRuleManageActivity.uiTypeface())
    }

    private fun showAddActions() {
        showComposeChoiceListDialog(
            title = getString(R.string.paragraph_rule_manage),
            labels = listOf(getString(R.string.add), getString(R.string.import_str), getString(R.string.import_on_line))
        ) { index ->
            when (index) {
                0 -> openEditRule()
                1 -> launchImportFile()
                2 -> showImportUrlDialog()
            }
        }
    }

    private fun showImportActions() {
        showComposeChoiceListDialog(
            title = getString(R.string.import_str),
            labels = listOf(getString(R.string.import_str), getString(R.string.import_on_line))
        ) { index ->
            when (index) {
                0 -> launchImportFile()
                1 -> showImportUrlDialog()
            }
        }
    }

    private fun launchImportFile() {
        importDoc.launch {
            mode = HandleFileContract.FILE
            title = getString(R.string.import_str)
            allowExtensions = arrayOf("json")
        }
    }

    private fun showImportUrlDialog() {
        showComposeTextInputDialog(
            title = getString(R.string.import_on_line),
            hint = "https://...",
            onPositive = { value ->
                val url = value.trim()
                if (url.isNotEmpty()) importRulesFromUrl(url)
            }
        )
    }

    private fun importRulesFromUrl(url: String) {
        // F52 导入进度闭环：慢网络/大文件原先零反馈（用户以为卡死会重复触发）⇒ 摘要位即时切「正在导入规则…」
        // 并禁用「添加」按钮，结束后按真实状态重写摘要（成功另给「已导入 N 条」计数）
        shell.tvSummary.text = getString(R.string.paragraph_rule_importing)
        shell.btnAdd.isEnabled = false
        lifecycleScope.launch {
            kotlin.runCatching {
                val text = withContext(Dispatchers.IO) {
                    okHttpClient.newCallResponseBody { url(url) }.use { it.string() }
                }
                parseImportedRules(text)
            }.onSuccess { rules ->
                shell.btnAdd.isEnabled = true
                if (rules.isEmpty()) {
                    load()
                    toastOnUi(R.string.wrong_format)
                } else {
                    withContext(Dispatchers.IO) { insertImportedRules(rules) }
                    load()
                    toastOnUi(getString(R.string.paragraph_rule_imported_count, rules.size))
                }
            }.onFailure {
                shell.btnAdd.isEnabled = true
                load()
                toastOnUi(it.localizedMessage ?: getString(R.string.wrong_format))
            }
        }
    }

    private fun load() {
        lifecycleScope.launch {
            val rules = withContext(Dispatchers.IO) {
                bookUrl?.let { enabledIds = appDb.paragraphRuleDao.enabledRuleIdsForBook(it).toSet() }
                appDb.paragraphRuleDao.all()
            }
            adapter.items = rules
            // F50 空态操作化：两种空态都补「下一步该做什么」的引导（主操作由底部「添加」承担）
            shell.tvSummary.text = when {
                bookUrl == null -> getString(R.string.paragraph_rule_no_book_hint) + "\n" +
                        getString(R.string.paragraph_rule_no_book_guide)
                rules.isEmpty() -> getString(R.string.paragraph_rule_empty) + "\n" +
                        getString(R.string.paragraph_rule_empty_guide)
                else -> getString(R.string.paragraph_rule_manage_summary)
            }
        }
    }

    private fun toggle(rule: ParagraphRule, enable: Boolean) {
        val url = bookUrl
        if (url == null) {
            toastOnUi(R.string.paragraph_rule_no_book_hint)
            return
        }
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                if (enable) {
                    appDb.paragraphRuleDao.insertBookRule(
                        BookParagraphRule(url, rule.id, true, adapter.items.indexOfFirst { it.id == rule.id })
                    )
                } else {
                    appDb.paragraphRuleDao.deleteBookRule(url, rule.id)
                }
            }
            refreshReadBookIfNeeded()
            load()
        }
    }

    private fun login(rule: ParagraphRule) {
        refreshOnResume = true
        startActivity<SourceLoginActivity> {
            putExtra("bookType", -1)
            putExtra("type", "paragraphRule")
            putExtra("key", rule.id.toString())
            bookUrl?.let { putExtra("bookUrl", it) }
            putExtra("chapterIndex", ReadBook.durChapterIndex)
        }
    }

    private fun openEditRule(ruleId: Long? = null) {
        refreshOnResume = true
        startActivity<ParagraphRuleEditActivity> {
            ruleId?.let { putExtra("id", it) }
        }
    }

    private fun showActions(rule: ParagraphRule) {
        val actions = listOf(
            Action.EDIT,
            Action.EXPORT,
            Action.COPY,
            Action.VARS,
            Action.DELETE
        )
        showComposeChoiceListDialog(rule.displayName(), actions.map { getString(it.titleRes) }) { index ->
            when (actions.getOrNull(index)) {
                Action.EDIT -> openEditRule(rule.id)
                Action.EXPORT -> exportRule(rule)
                Action.COPY -> sendToClip(serializeRule(rule))
                Action.VARS -> editVars(rule)
                Action.DELETE -> deleteRule(rule)
                null -> Unit
            }
        }
    }

    private fun editVars(rule: ParagraphRule) {
        lifecycleScope.launch {
            val raw = withContext(Dispatchers.IO) {
                GSON.toJson(appDb.paragraphRuleDao.vars(rule.id).associate { it.name to it.value })
            }
            showComposeTextInputDialog(
                title = getString(R.string.paragraph_rule_vars),
                hint = getString(R.string.paragraph_rule_vars_json),
                initialValue = raw,
                onPositive = { text -> saveVars(rule.id, text) }
            )
        }
    }

    private fun saveVars(ruleId: Long, raw: String) {
        lifecycleScope.launch {
            kotlin.runCatching {
                val type = object : TypeToken<Map<String, String>>() {}.type
                val values: Map<String, String> = if (raw.isBlank()) emptyMap() else GSON.fromJson(raw, type)
                withContext(Dispatchers.IO) {
                    appDb.paragraphRuleDao.deleteVars(ruleId)
                    values.forEach { (key, value) ->
                        appDb.paragraphRuleDao.putVar(ParagraphRuleVar(ruleId, key, value))
                    }
                }
            }.onSuccess {
                toastOnUi(R.string.success)
                refreshReadBookIfNeeded()
            }.onFailure {
                toastOnUi(it.localizedMessage ?: getString(R.string.wrong_format))
            }
        }
    }

    private fun parseImportedRules(raw: String): List<ParagraphRule> {
        val text = raw.trim()
        if (text.isBlank()) return emptyList()
        GSON.fromJsonArray<ParagraphRule>(text).getOrNull()?.let { return it }
        GSON.fromJsonObject<ParagraphRule>(text).getOrNull()?.let { return listOf(it) }
        return emptyList()
    }

    private fun insertImportedRules(rules: List<ParagraphRule>) {
        var order = (appDb.paragraphRuleDao.maxOrder() ?: 0) + 1
        rules.forEach { imported ->
            val rule = imported.copy(id = 0L, order = order++, updateTime = System.currentTimeMillis())
            appDb.paragraphRuleDao.insert(rule)
        }
    }

    private fun exportRule(rule: ParagraphRule) {
        exportRuleResult.launch {
            mode = HandleFileContract.EXPORT
            fileData = HandleFileContract.FileData(
                "paragraphRule-${rule.displayName()}.json",
                serializeRule(rule).toByteArray(),
                "application/json"
            )
        }
    }

    private fun serializeRule(rule: ParagraphRule): String = GSON.toJson(rule)

    private fun deleteRule(rule: ParagraphRule) {
        showComposeConfirmDialog(
            title = getString(R.string.draw),
            message = getString(R.string.sure_del) + "\n" + rule.displayName(),
            positiveText = getString(R.string.yes),
            negativeText = getString(R.string.no),
            onPositive = {
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) { appDb.paragraphRuleDao.deleteWithRelations(rule) }
                    refreshReadBookIfNeeded()
                    load()
                }
            }
        )
    }

    override fun swap(srcPosition: Int, targetPosition: Int): Boolean {
        if (srcPosition !in adapter.items.indices || targetPosition !in adapter.items.indices) return false
        val mutable = adapter.items.toMutableList()
        val item = mutable.removeAt(srcPosition)
        mutable.add(targetPosition, item)
        adapter.items = mutable
        adapter.notifyItemMoved(srcPosition, targetPosition)
        return true
    }

    override fun onClearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                adapter.items.forEachIndexed { index, rule ->
                    if (rule.order != index) appDb.paragraphRuleDao.update(rule.copy(order = index, updateTime = System.currentTimeMillis()))
                    bookUrl?.let { url ->
                        if (enabledIds.contains(rule.id)) {
                            appDb.paragraphRuleDao.insertBookRule(BookParagraphRule(url, rule.id, true, index))
                        }
                    }
                }
            }
            refreshReadBookIfNeeded()
            load()
        }
    }

    private fun refreshReadBookIfNeeded() {
        if (bookUrl != ReadBook.book?.bookUrl) return
        ReadBook.invalidateParagraphRuleLayout()
        ReadBook.callBack?.get()?.upContent(resetPageOffset = false)
        ReadBook.loadContent(resetPageOffset = false)
    }

    private inner class RuleAdapter : RecyclerView.Adapter<RuleAdapter.Holder>() {
        var items: List<ParagraphRule> = emptyList()
            set(value) {
                field = value
                notifyDataSetChanged()
            }

        init { setHasStableIds(true) }

        override fun getItemId(position: Int): Long = items[position].id
        override fun getItemCount(): Int = items.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            return Holder(ItemThemePackageBinding.inflate(LayoutInflater.from(parent.context), parent, false))
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            holder.bind(items[position])
        }

        inner class Holder(private val itemBinding: ItemThemePackageBinding) : RecyclerView.ViewHolder(itemBinding.root) {
            fun bind(rule: ParagraphRule) = itemBinding.run {
                val enabled = enabledIds.contains(rule.id)
                root.background = UiCorner.panelRounded(
                    this@ParagraphRuleManageActivity,
                    themeCardColorOrDefault(),
                    UiCorner.panelRadius(this@ParagraphRuleManageActivity)
                )
                cardPreview.visibility = View.GONE
                tvSource.visibility = View.GONE
                (layInfo.layoutParams as? ViewGroup.MarginLayoutParams)?.let {
                    it.marginStart = 0
                    layInfo.layoutParams = it
                }
                tvName.text = rule.displayName()
                tvInfo.text = getString(if (enabled) R.string.paragraph_rule_enabled_for_book else R.string.paragraph_rule_disabled_for_book)
                tvName.applyUiSectionTitleStyle(this@ParagraphRuleManageActivity)
                tvInfo.applyUiLabelStyle(this@ParagraphRuleManageActivity)
                tvInfo.setTextColor(secondaryTextColor)
                listOf(btnApply, btnEdit, btnMore).forEach {
                    it.background = UiCorner.actionSelector(
                        Color.TRANSPARENT,
                        themeMutedColorOrDefault(),
                        UiCorner.actionRadius(this@ParagraphRuleManageActivity)
                    )
                    it.typeface = this@ParagraphRuleManageActivity.uiTypeface()
                }
                btnApply.text = getString(if (enabled) R.string.disable else R.string.enable)
                btnEdit.text = getString(R.string.login)
                btnMore.text = getString(R.string.more)
                btnApply.setTextColor(accentColor)
                btnEdit.setTextColor(primaryTextColor)
                btnMore.setTextColor(primaryTextColor)
                btnApply.setOnClickListener { toggle(rule, !enabled) }
                btnEdit.setOnClickListener { login(rule) }
                btnMore.setOnClickListener { showActions(rule) }
                root.setOnClickListener { showActions(rule) }
                // F51：卡右侧常驻拖拽手柄——「可排序」从隐藏态变可见态；按下手柄即起拖
                ensureDragHandle().setOnTouchListener { _, event ->
                    if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                        itemTouchHelper?.startDrag(this@Holder)
                    }
                    false
                }
            }

            /**
             * F51：按需把拖拽手柄插到卡右侧（`ic_drag_handle` 为全站既有排序资产）。
             *
             * 插入而非改 `item_theme_package.xml`：该布局被多个管理页共用，改布局会外溢；
             * 插入到本页 Holder 的复用视图上同样只做一次（按 tag 去重）。
             */
            private fun ensureDragHandle(): ImageView {
                val parent = itemBinding.root as LinearLayout
                parent.findViewWithTag<ImageView>(DRAG_HANDLE_TAG)?.let { return it }
                return ImageView(this@ParagraphRuleManageActivity).apply {
                    tag = DRAG_HANDLE_TAG
                    setImageResource(R.drawable.ic_drag_handle)
                    setColorFilter(secondaryTextColor)
                    contentDescription = getString(R.string.paragraph_rule_drag_handle)
                    setPadding(10.dpToPx(), 0, 2.dpToPx(), 0)
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    ).apply { gravity = Gravity.CENTER_VERTICAL }
                    parent.addView(this)
                }
            }
        }
    }

    private enum class Action(val titleRes: Int) {
        EDIT(R.string.edit),
        EXPORT(R.string.export_str),
        COPY(R.string.copy_rule),
        VARS(R.string.paragraph_rule_vars),
        DELETE(R.string.delete)
    }

    companion object {
        /** F51：拖拽手柄在复用视图上的去重 tag（同一 Holder 多次 bind 只插一次） */
        private const val DRAG_HANDLE_TAG = "paragraph_rule_drag_handle"
    }
}
