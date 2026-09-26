package io.legado.app.ui.book.read.config

import android.graphics.Typeface
import android.os.Bundle
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.SimpleItemAnimator
import androidx.viewbinding.ViewBinding
import io.legado.app.R
import io.legado.app.base.BaseActivity
import io.legado.app.base.attachComposeContent
import io.legado.app.base.composeShell
import io.legado.app.data.appDb
import io.legado.app.data.entities.AiReadAloudUsageRecord
import io.legado.app.lib.theme.UiCorner
import io.legado.app.lib.theme.accentColor
import io.legado.app.lib.theme.applyUiBodyTypefaceDeep
import io.legado.app.lib.theme.primaryTextColor
import io.legado.app.lib.theme.secondaryTextColor
import io.legado.app.lib.theme.themeCardColorOrDefault
import io.legado.app.lib.theme.themeMutedColorOrDefault
import io.legado.app.lib.theme.uiTypeface
import io.legado.app.model.ReadBook
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.ui.theme.LegadoTheme
import io.legado.app.ui.widget.compose.AppSemanticColors
import io.legado.app.ui.widget.components.GlassTopAppBar
import io.legado.app.ui.widget.components.MenuAction
import io.legado.app.ui.widget.components.TopBarActionRow
import io.legado.app.ui.widget.compose.ComposeActionListDialog
import io.legado.app.ui.widget.compose.ComposeConfirmDialog
import io.legado.app.utils.ColorUtils
import io.legado.app.utils.FileUtils
import io.legado.app.utils.GSON
import io.legado.app.utils.dpToPx
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.writeToOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class AiReadAloudUsageRecordActivity : BaseActivity<ViewBinding>() {

    // 原 activity_theme_manage.xml 已退役（CE-a #8）：composeShell 合成壳 + attachComposeContent 单源；
    // 顶栏由 installGlassTopBar 运行时注入（首插 ComposeView + 移除 R.id.title_bar 锚点）改为**页内直接渲染**
    override val binding: ViewBinding by lazy { composeShell(this) }

    /** 原 XML 字段节点的程序化等价物（14 个管理页共用布局已退役，见 [ThemeManageShellViews]） */
    private val shell by lazy { ThemeManageShellViews(this) }

    private val adapter = UsageAdapter()
    private val selectedIds = linkedSetOf<Long>()
    private var records: List<AiReadAloudUsageRecord> = emptyList()
    private var typeFilter: String = ""
    private var currentBookOnly = false

    // F31：选中态批量操作栏按需插入（**条件挂载**；原 activity_theme_manage.xml 为 14 页共用，
    // 运行时插栏即已共享容器，不改共用 XML ⇒ 其他页零影响；现共用 XML 已退役，容器改由
    // [ThemeManageShellViews] 单源装配，插栏语义与插入锚点保持不变）。
    // F30 摘要则完全不新增 View（改用 Spannable 多行），规避共用容器下动态插入视图的高度测量异常
    // （实测该页插入视图会被撑满整屏、把列表挤出可视区）。
    private var batchBar: LinearLayout? = null
    private var summaryContainer: LinearLayout? = null

    /** F31：导出选中记录 → 走既有「选择保存位置」通道（与自动任务导出同源，落用户可见位置） */
    private val exportResult = registerForActivityResult(HandleFileContract()) { result ->
        result.uri?.let { toastOnUi(getString(R.string.ai_usage_export_done, it.toString())) }
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        initComposeContent()
        initView()
        load()
    }

    /**
     * CE-a #8：Compose 承载页面骨架（顶栏 + 共用管理族内容区）。
     *
     * 与原 XML 的对应关系：`title_bar` → 页内 `GlassTopAppBar`；其余节点（`tab_bar` / `btn_day` /
     * `btn_night` / `tv_summary` / `recycler_view` / `btn_add`）由 [ThemeManageShellViews] 单源装配，
     * 以 `AndroidView` 原样托管（本页在 `onActivityCreated` 内就要配置 adapter / 点击 / 动态插入栏，
     * 故必须宿主字段持有同一实例）。
     */
    @OptIn(ExperimentalMaterial3Api::class)
    private fun initComposeContent() {
        binding.root.attachComposeContent {
            Column(modifier = Modifier.fillMaxSize()) {
                // ---- 顶栏（原 installGlassTopBar 注入的 GlassTopAppBar：标题/返回/动作逐项不变）----
                LegadoTheme {
                    GlassTopAppBar(
                        title = "消耗记录", // 简化说明:沿承原实现硬编码标题，无既有字符串资源 | 升级路径:补资源后替换
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

    /** 顶栏动作（原 `installGlassTopBar` 的 actionsProvider：筛选/书架过滤/删除/清空逐项不变）。 */
    private fun topBarActions(): List<MenuAction> {
        return listOf(
                    MenuAction(
                        iconRes = R.drawable.ic_screen,
                        title = getString(R.string.filter),
                        alwaysShow = true
                    ) { showTypeFilter() },
                    MenuAction(
                        // 既有缺陷修复：原用 `ic_bottom_books`（**StateListDrawable** selector），
                        // 顶栏经 `painterResource` 渲染只接受矢量/栅格 ⇒ 组合期抛
                        // IllegalArgumentException「Only VectorDrawables and rasterized asset types...」
                        // ⇒ 整页在顶栏合成时崩溃（真机实证）。改取同一族的**未选中矢量**资产。
                        iconRes = R.drawable.ic_bottom_books_e,
                        title = getString(R.string.bookshelf),
                        alwaysShow = true
                    ) {
                        currentBookOnly = !currentBookOnly
                        selectedIds.clear()
                        load()
                    },
                    MenuAction(
                        iconRes = R.drawable.ic_outline_delete,
                        title = getString(R.string.delete),
                        alwaysShow = true
                    ) { deleteSelected() },
                    MenuAction(
                        iconRes = R.drawable.ic_clear_all,
                        title = getString(R.string.clear),
                        alwaysShow = true
                    ) { confirmClearAll() }
        )
    }

    private fun initView() = shell.run {
        tabBar.visibility = View.GONE
        btnAdd.text = "全选"
        btnAdd.background = UiCorner.actionSelector(
            themeCardColorOrDefault(),
            themeMutedColorOrDefault(),
            UiCorner.actionRadius(this@AiReadAloudUsageRecordActivity)
        )
        btnAdd.setOnClickListener { toggleSelectAll() }
        tvSummary.setTextColor(secondaryTextColor)
        recyclerView.layoutManager = LinearLayoutManager(this@AiReadAloudUsageRecordActivity)
        recyclerView.adapter = adapter
        (recyclerView.itemAnimator as? SimpleItemAnimator)?.supportsChangeAnimations = false
        initSummarySection()
        root.applyUiBodyTypefaceDeep(uiTypeface())
    }

    /**
     * F30/F31 落点：批量操作栏按需插入共用容器；**摘要不新增 View**（Spannable 多行承载）。
     *
     * 为什么摘要不用自建视图：宿主共用容器（原 `activity_theme_manage.xml`，现 [ThemeManageShellViews]）
     * 的 `recyclerView` 为 `height=0dp + weight=1`；实测动态插入的同级视图会被**测量成整屏高度**，
     * 把列表与「全选」整体挤出可视区（真机层级取证：插入视图 bounds = (27,183)-(693,1280)）。
     * 故摘要改为在既有 `tvSummary` 内用 Spannable 排两行 + 按模型明细，零新增视图、零布局风险。
     */
    private fun initSummarySection() {
        val container = shell.tvSummary.parent as? LinearLayout ?: return
        summaryContainer = container
        batchBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                marginStart = 16.dpToPx()
                marginEnd = 16.dpToPx()
                bottomMargin = 8.dpToPx()
            }
        }
    }

    private fun load() {
        lifecycleScope.launch {
            val bookUrl = if (currentBookOnly) ReadBook.book?.bookUrl.orEmpty() else ""
            val loaded = withContext(Dispatchers.IO) {
                appDb.aiReadAloudUsageRecordDao.list(typeFilter, bookUrl)
            }
            records = loaded
            selectedIds.retainAll(records.mapTo(hashSetOf()) { it.id })
            adapter.submit(records)
            updateSummary()
        }
    }

    /**
     * F30：摘要拆行 + 主指标强调（**零新增视图**）。
     *
     * 第 1 行 = 范围 · 条数（旧实现把 8 段拼一行，390px 屏必然截断，总 Token 被挤掉）；
     * 第 2 行 = 输入 / 缓存 / 输出 / 总（「总」以 accent + 加粗强调，一眼可扫）；
     * 第 3 行 = 按模型拆分（上限 [MAX_MODEL_LINES] 行，超出以「…等 N 个模型」收口）。
     * 已选数不占用摘要（由 F31 批量栏承担）。
     */
    private fun updateSummary() {
        val totalInput = records.sumOf { it.inputTokens.toLong() }
        val totalCached = records.sumOf { it.cachedInputTokens.toLong() }
        val totalOutput = records.sumOf { it.outputTokens.toLong() }
        // 实体字段为 Int，累加一律转 Long：token 量级可达 10^9，Int 累加会溢出
        val total = records.sumOf {
            (it.totalTokens.takeIf { value -> value > 0 } ?: (it.inputTokens + it.outputTokens)).toLong()
        }
        val typeText = typeLabel(typeFilter).takeIf { typeFilter.isNotBlank() } ?: "全部类型"
        val bookText = if (currentBookOnly) "当前书" else "全部书籍"
        val line1 = "$typeText · $bookText · ${records.size} 条"
        val totalLabel = "${getString(R.string.ai_usage_chip_total)} ${formatTokens(total)}"
        val line2 = buildString {
            append(getString(R.string.ai_usage_chip_input)).append(' ').append(formatTokens(totalInput))
            if (totalCached > 0) {
                append(" · ").append(getString(R.string.ai_usage_chip_cached))
                    .append(' ').append(formatTokens(totalCached))
            }
            append(" · ").append(getString(R.string.ai_usage_chip_output))
                .append(' ').append(formatTokens(totalOutput))
            append(" · ").append(totalLabel)
        }
        val modelLine = buildModelLine()
        val text = if (modelLine.isEmpty()) "$line1\n$line2" else "$line1\n$line2\n$modelLine"
        shell.tvSummary.text = emphasizeTotal(text, totalLabel, line1.length + 1 + line2.length - totalLabel.length)
        renderBatchBar()
    }

    /** 第 3 行「按模型拆分」：按总 Token 降序，超 [MAX_MODEL_LINES] 个以「…等 N 个模型」收口 */
    private fun buildModelLine(): String {
        if (records.isEmpty()) return ""
        val byModel = records
            .groupBy { it.modelId.ifBlank { getString(R.string.ai_usage_model_detail) } }
            .mapValues { entry ->
                entry.value.sumOf {
                    (it.totalTokens.takeIf { value -> value > 0 } ?: (it.inputTokens + it.outputTokens)).toLong()
                }
            }.entries.sortedByDescending { it.value }
        val head = "· " + byModel.take(MAX_MODEL_LINES)
            .joinToString(" · ") { "${it.key} ${formatTokens(it.value)}" }
        return if (byModel.size > MAX_MODEL_LINES) {
            "$head · …等 ${byModel.size} 个模型"
        } else {
            head
        }
    }

    /** 把「总 N」段着 accent 色并加粗（主指标强调；其余保持次级色） */
    private fun emphasizeTotal(text: String, totalLabel: String, start: Int): CharSequence {
        val spanned = SpannableString(text)
        val from = start.coerceIn(0, text.length)
        val to = (from + totalLabel.length).coerceAtMost(text.length)
        if (to > from) {
            spanned.setSpan(
                ForegroundColorSpan(accentColor), from, to, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            spanned.setSpan(
                StyleSpan(Typeface.BOLD), from, to, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
        return spanned
    }

    /** F31：已选 > 0 时在列表底部浮现批量操作栏（删除选中 / 导出），破坏性操作走 danger 语义色 */
    private fun renderBatchBar() {
        val bar = batchBar ?: return
        val container = summaryContainer ?: return
        if (selectedIds.isEmpty()) {
            // 条件挂载（而非 visibility=GONE）：共用容器下对同一实例做 gone↔visible 切换实测
            // 不渲染且 a11y 树无节点（与 F30 同源坑）⇒ 一律 addView/removeView
            container.removeView(bar)
            return
        }
        if (bar.parent == null) {
            val addIndex = container.indexOfChild(shell.btnAdd)
            val target = if (addIndex >= 0) addIndex else container.childCount
            container.addView(bar, target.coerceIn(0, container.childCount))
        }
        bar.removeAllViews()
        val danger = AppSemanticColors.Danger.toArgb()
        bar.addView(buildBatchAction(
            getString(R.string.ai_usage_delete_selected, selectedIds.size),
            danger, ColorUtils.adjustAlpha(danger, CHIP_EMPHASIS_ALPHA)
        ) { deleteSelected() })
        bar.addView(buildBatchAction(
            getString(R.string.ai_usage_export_selected),
            primaryTextColor, themeCardColorOrDefault()
        ) { exportSelected() })
    }

    private fun buildBatchAction(
        title: String,
        textColor: Int,
        background: Int,
        onClick: () -> Unit
    ): TextView {
        val radius = UiCorner.actionRadius(this)
        return TextView(this).apply {
            text = title
            textSize = 13f
            typeface = uiTypeface()
            gravity = Gravity.CENTER
            setTextColor(textColor)
            this.background = UiCorner.actionSelector(background, themeMutedColorOrDefault(), radius)
            setPadding(12.dpToPx(), 8.dpToPx(), 12.dpToPx(), 8.dpToPx())
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = 8.dpToPx()
            }
            setOnClickListener { onClick() }
        }
    }

    /** F31：导出选中记录为 JSON，交给既有「选择保存位置」通道（用户可见落点） */
    private fun exportSelected() {
        val ids = selectedIds.toList()
        if (ids.isEmpty()) return
        lifecycleScope.launch {
            val file = withContext(Dispatchers.IO) {
                kotlin.runCatching {
                    val idSet = ids.toHashSet()
                    val export = records.filter { idSet.contains(it.id) }
                    val path = "${filesDir}/${EXPORT_FILE_NAME}"
                    FileUtils.delete(path)
                    val target = FileUtils.createFileWithReplace(path)
                    target.outputStream().buffered().use { GSON.writeToOutputStream(it, export) }
                    target
                }.getOrNull()
            }
            if (file == null) {
                toastOnUi(getString(R.string.wrong_format))
                return@launch
            }
            exportResult.launch {
                mode = HandleFileContract.EXPORT
                fileData = HandleFileContract.FileData(EXPORT_FILE_NAME, file, "application/json")
            }
        }
    }

    private fun showTypeFilter() {
        val types = listOf(
            "" to "全部",
            AiReadAloudUsageRecord.TYPE_ROLE to "多角色",
            AiReadAloudUsageRecord.TYPE_BGM to "配乐",
            AiReadAloudUsageRecord.TYPE_SFX to "音效",
            AiReadAloudUsageRecord.TYPE_AUDIO to "音频分析"
        )
        showDialogFragment(
            ComposeActionListDialog.create(
                title = "类型筛选",
                labels = types.map { if (it.first == typeFilter) "${it.second} ✓" else it.second },
                negativeText = getString(R.string.cancel),
                onSelected = { index ->
                    types.getOrNull(index)?.let { selected ->
                        typeFilter = selected.first
                        selectedIds.clear()
                        load()
                    }
                }
            )
        )
    }

    private fun toggleSelectAll() {
        if (records.isEmpty()) return
        if (selectedIds.size == records.size) {
            selectedIds.clear()
        } else {
            selectedIds.clear()
            records.mapTo(selectedIds) { it.id }
        }
        adapter.submit(records)
        updateSummary()
    }

    private fun deleteSelected() {
        val ids = selectedIds.toList()
        if (ids.isEmpty()) {
            toastOnUi("请先长按选择记录")
            return
        }
        showDialogFragment(
            ComposeConfirmDialog.create(
                title = "删除记录",
                message = "确定删除选中的 ${ids.size} 条消耗记录？",
                positiveText = getString(R.string.delete),
                negativeText = getString(R.string.cancel),
                dangerPositive = true,
                onPositive = {
                    lifecycleScope.launch(Dispatchers.IO) {
                        appDb.aiReadAloudUsageRecordDao.deleteByIds(ids)
                        selectedIds.clear()
                        launch(Dispatchers.Main) { load() }
                    }
                }
            )
        )
    }

    private fun confirmClearAll() {
        showDialogFragment(
            ComposeConfirmDialog.create(
                title = "清空记录",
                message = "确定清空所有朗读 AI 消耗记录？",
                positiveText = getString(R.string.clear),
                negativeText = getString(R.string.cancel),
                dangerPositive = true,
                onPositive = {
                    lifecycleScope.launch(Dispatchers.IO) {
                        appDb.aiReadAloudUsageRecordDao.clear()
                        selectedIds.clear()
                        launch(Dispatchers.Main) { load() }
                    }
                }
            )
        )
    }

    private fun showRecordActions(record: AiReadAloudUsageRecord) {
        showDialogFragment(
            ComposeActionListDialog.create(
                title = typeLabel(record.type),
                labels = listOf("删除"),
                dangerIndices = setOf(0),
                negativeText = getString(R.string.cancel),
                onSelected = { index ->
                    if (index == 0) {
                        lifecycleScope.launch(Dispatchers.IO) {
                            appDb.aiReadAloudUsageRecordDao.delete(record.id)
                            selectedIds.remove(record.id)
                            launch(Dispatchers.Main) { load() }
                        }
                    }
                }
            )
        )
    }

    private inner class UsageAdapter : RecyclerView.Adapter<UsageHolder>() {
        private var items: List<AiReadAloudUsageRecord> = emptyList()

        fun submit(value: List<AiReadAloudUsageRecord>) {
            items = value
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): UsageHolder {
            val root = LinearLayout(parent.context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(14.dpToPx(), 12.dpToPx(), 14.dpToPx(), 12.dpToPx())
                background = UiCorner.actionSelector(
                    parent.context.themeCardColorOrDefault(),
                    parent.context.themeMutedColorOrDefault(),
                    UiCorner.panelRadius(parent.context)
                )
            }
            val title = TextView(parent.context).apply {
                textSize = 15f
                setTextColor(primaryTextColor)
                typeface = uiTypeface()
            }
            val sub = TextView(parent.context).apply {
                textSize = 12f
                setTextColor(secondaryTextColor)
                typeface = uiTypeface()
                gravity = Gravity.START
            }
            root.addView(title, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            root.addView(sub, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = 4.dpToPx()
            })
            root.layoutParams = RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = 10.dpToPx()
            }
            return UsageHolder(root, title, sub)
        }

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(holder: UsageHolder, position: Int) {
            holder.bind(items[position])
        }
    }

    private inner class UsageHolder(
        itemView: View,
        private val title: TextView,
        private val sub: TextView
    ) : RecyclerView.ViewHolder(itemView) {

        fun bind(record: AiReadAloudUsageRecord) {
            val selected = record.id in selectedIds
            title.text = buildString {
                if (selected) append("✓ ")
                append(typeLabel(record.type))
                append(" · ")
                append(statusLabel(record.status))
                if (record.batchName.isNotBlank()) append(" · ").append(record.batchName)
            }
            sub.text = buildList {
                add(record.chapterTitle.ifBlank { record.bookName.ifBlank { "未知章节" } })
                add(formatTime(record.createdAt))
                if (record.modelId.isNotBlank()) add(record.modelId)
                add("输入 ${record.inputTokens}")
                if (record.cachedInputTokens > 0) add("缓存 ${record.cachedInputTokens}")
                add("输出 ${record.outputTokens}")
                add("总 ${record.totalTokens.takeIf { it > 0 } ?: (record.inputTokens + record.outputTokens)}")
                if (record.error.isNotBlank()) add(record.error)
            }.joinToString(" · ")
            itemView.setOnClickListener {
                if (selectedIds.isNotEmpty()) {
                    toggle(record.id)
                } else {
                    showRecordActions(record)
                }
            }
            itemView.setOnLongClickListener {
                toggle(record.id)
                true
            }
        }

        private fun toggle(id: Long) {
            if (!selectedIds.add(id)) selectedIds.remove(id)
            adapter.submit(records)
            updateSummary()
        }
    }

    companion object {
        /** Chip 强调底透明度（danger 弱底胶囊，避免与卡片底色冲突） */
        private const val CHIP_EMPHASIS_ALPHA = 0.14f

        /** F30：摘要「按模型拆分」行最多列出几个模型（超出以「…等 N 个模型」收口，防摘要膨胀） */
        private const val MAX_MODEL_LINES = 3

        /** F31 导出文件名（选中记录导出为 JSON，交给「选择保存位置」通道） */
        private const val EXPORT_FILE_NAME = "aiReadAloudUsageSelection.json"

        /** Token 量级常在 10^5~10^7，千分位后一眼可读（F30 摘要主指标要求「一眼可扫」） */
        private fun formatTokens(value: Long): String = String.format(Locale.getDefault(), "%,d", value)

        private val timeFormat = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())

        private fun typeLabel(type: String): String = when (type) {
            AiReadAloudUsageRecord.TYPE_ROLE -> "多角色"
            AiReadAloudUsageRecord.TYPE_BGM -> "配乐"
            AiReadAloudUsageRecord.TYPE_SFX -> "音效"
            AiReadAloudUsageRecord.TYPE_AUDIO -> "音频分析"
            else -> "全部"
        }

        private fun statusLabel(status: String): String = when (status) {
            AiReadAloudUsageRecord.STATUS_SUCCESS -> "成功"
            AiReadAloudUsageRecord.STATUS_FAILED -> "失败"
            AiReadAloudUsageRecord.STATUS_CACHE -> "缓存"
            else -> status.ifBlank { "未知" }
        }

        private fun formatTime(time: Long): String {
            return if (time > 0) timeFormat.format(Date(time)) else "-"
        }
    }
}
