package io.legado.app.ui.book.read.config

import android.graphics.Color
import android.content.Intent
import android.app.Activity
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.SimpleItemAnimator
import io.legado.app.R
import io.legado.app.base.BaseActivity
import io.legado.app.constant.EventBus
import io.legado.app.data.appDb
import io.legado.app.data.entities.ReadMenuCustomButton
import io.legado.app.databinding.ActivityThemeManageBinding
import io.legado.app.databinding.ItemThemePackageBinding
import io.legado.app.help.http.newCallResponseBody
import io.legado.app.help.http.okHttpClient
import io.legado.app.ui.book.read.MENU_BUTTONS_PER_PAGE
import io.legado.app.ui.widget.compose.showComposeChoiceListDialog
import io.legado.app.ui.widget.compose.showComposeConfirmDialog
import io.legado.app.ui.widget.compose.showComposeTextInputDialog
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
import io.legado.app.ui.book.read.ReadMenuButtonIconHelper
import io.legado.app.ui.book.read.ReadMenuButtonConfig
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.ui.widget.components.MenuAction
import io.legado.app.ui.widget.components.installGlassTopBar
import io.legado.app.ui.widget.recycler.ItemTouchCallback
import io.legado.app.utils.GSON
import io.legado.app.utils.dpToPx
import io.legado.app.utils.fromJsonArray
import io.legado.app.utils.fromJsonObject
import io.legado.app.utils.postEvent
import io.legado.app.utils.readText
import io.legado.app.utils.sendToClip
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ReadMenuButtonManageActivity : BaseActivity<ActivityThemeManageBinding>(),
    ItemTouchCallback.Callback {

    override val binding by viewBinding(ActivityThemeManageBinding::inflate)
    private val adapter = ButtonAdapter()
    private var layout = ReadMenuButtonConfig.defaultLayout()
    private var rowIndex = 0
    private var customButtons: Map<Long, ReadMenuCustomButton> = emptyMap()
    private var rowOrderDirty = false
    private var pendingIconRequest: IconRequest? = null

    /** F51：拖拽控制器提升为字段——卡片右侧手柄按下时要能主动起拖（`startDrag`） */
    private lateinit var itemTouchHelper: ItemTouchHelper

    /** F60：列表底部「阅读页实际效果」预览条（只读，运行时插入，见 [ensurePreviewBar]） */
    private var previewBar: LinearLayout? = null

    private val editCustomButton = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val id = result.data?.getLongExtra("id", 0L)?.takeIf { it > 0 } ?: return@registerForActivityResult
            addButton(ReadMenuButtonConfig.ButtonRef(ReadMenuButtonConfig.TYPE_CUSTOM, id.toString()))
        } else {
            load()
        }
    }

    private val selectIconFile = registerForActivityResult(HandleFileContract()) { result ->
        val request = pendingIconRequest?.takeIf { it.requestCode == result.requestCode } ?: return@registerForActivityResult
        val uri = result.uri ?: return@registerForActivityResult
        lifecycleScope.launch {
            kotlin.runCatching {
                withContext(Dispatchers.IO) {
                    ReadMenuButtonIconHelper.saveIcon(this@ReadMenuButtonManageActivity, uri, request.oldPath())
                }
            }.onSuccess { path ->
                updateButtonRef(request.ref, request.ref.withIconPath(request.nightIcon, path))
                toastOnUi(R.string.success)
            }.onFailure {
                toastOnUi(it.localizedMessage ?: getString(R.string.navigation_icon_decode_failed))
            }
            pendingIconRequest = null
        }
    }

    private val importCustomButton = registerForActivityResult(HandleFileContract()) { result ->
        result.uri?.let { uri ->
            lifecycleScope.launch {
                kotlin.runCatching {
                    parseImportedButtons(uri.readText(this@ReadMenuButtonManageActivity))
                }.onSuccess { buttons ->
                    importButtons(buttons)
                }.onFailure {
                    toastOnUi(it.localizedMessage ?: getString(R.string.wrong_format))
                }
            }
        }
    }

    private val exportCustomButton = registerForActivityResult(HandleFileContract()) { result ->
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
        initView()
        load()
    }

    override fun onResume() {
        super.onResume()
        load()
    }

    // W7.2（Delta 3→1）：顶栏归一 installGlassTopBar（原 MainTopBarView Mode.SUB 消亡）
    private fun initTopBar() {
        installGlassTopBar(
            binding,
            titleProvider = { getString(R.string.read_menu_button_manage) },
            actionsProvider = {
                listOf(
                    MenuAction(
                        iconRes = R.drawable.ic_restore,
                        title = getString(R.string.reset),
                        alwaysShow = true
                    ) { resetLayout() }
                )
            },
            onBack = { finish() }
        )
    }

    private fun initView() = binding.run {
        tabBar.background = UiCorner.opaqueRounded(
            themeMutedColorOrDefault(),
            UiCorner.panelRadius(this@ReadMenuButtonManageActivity)
        )
        listOf(btnDay, btnNight).forEach {
            it.background = UiCorner.actionSelector(
                Color.TRANSPARENT,
                themeCardColorOrDefault(),
                UiCorner.actionRadius(this@ReadMenuButtonManageActivity)
            )
        }
        btnDay.text = getString(R.string.read_menu_first_row)
        btnNight.text = getString(R.string.read_menu_second_row)
        btnAdd.text = getString(R.string.read_menu_add_button)
        btnAdd.background = UiCorner.actionSelector(
            themeCardColorOrDefault(),
            themeMutedColorOrDefault(),
            UiCorner.actionRadius(this@ReadMenuButtonManageActivity)
        )
        btnAdd.setOnClickListener { showAddButtonDialog() }
        tvSummary.applyUiLabelStyle(this@ReadMenuButtonManageActivity)
        tvSummary.setTextColor(secondaryTextColor)
        recyclerView.layoutManager = LinearLayoutManager(this@ReadMenuButtonManageActivity)
        recyclerView.adapter = adapter
        (recyclerView.itemAnimator as? SimpleItemAnimator)?.supportsChangeAnimations = false
        itemTouchHelper = ItemTouchHelper(ItemTouchCallback(this@ReadMenuButtonManageActivity).apply {
            isCanDrag = true
        }).also { it.attachToRecyclerView(recyclerView) }
        btnDay.setOnClickListener {
            if (rowIndex != 0) {
                rowIndex = 0
                load()
            }
        }
        btnNight.setOnClickListener {
            if (rowIndex != 1) {
                rowIndex = 1
                load()
            }
        }
        root.applyUiBodyTypefaceDeep(this@ReadMenuButtonManageActivity.uiTypeface())
        ensurePreviewBar()
        updateTabs()
    }

    private fun load() {
        layout = ReadMenuButtonConfig.load(this)
        customButtons = appDb.readMenuCustomButtonDao.all().associateBy { it.id }
        adapter.items = currentRow()
        binding.tvSummary.text = getString(R.string.read_menu_button_summary)
        updateTabs()
        refreshPreview()
    }

    private fun updateTabs() = binding.run {
        btnDay.isSelected = rowIndex == 0
        btnNight.isSelected = rowIndex == 1
        btnDay.setTextColor(if (rowIndex == 0) accentColor else primaryTextColor)
        btnNight.setTextColor(if (rowIndex == 1) accentColor else primaryTextColor)
    }

    private fun currentRow(): List<ReadMenuButtonConfig.ButtonRef> {
        return if (rowIndex == 0) layout.firstRow else layout.secondRow
    }

    // ==================== F60 实机预览条 ====================

    /**
     * F60：列表底部常驻一条「阅读页实际效果」预览条（只读）。
     *
     * 本页的核心产出是「阅读菜单长什么样」，但管理视图（卡片列表）与结果视图（菜单 2×4 网格）
     * 分属两个 Tab、完全割裂 ⇒ 每次调序/换行都要「返回阅读页 → 调出菜单 → 看 → 回来再改」。
     * 预览条把这条反馈回路缩成「改 → 瞄一眼」。
     *
     * 两条既有铁律决定实现形态：
     * ① 不新增 View 布局页（`ui_gate` 禁 `R.layout.*`）⇒ 全部代码构建；
     * ② `activity_theme_manage.xml` 被 14 个管理页共用 ⇒ **不改 XML**，只在本页运行时插到
     *    「添加」按钮之前（该布局里 `recycler_view` 带 `weight=1`，插在它之后不会挤列表）。
     *
     * 只读：不挂任何点击/长按监听——避免出现第二处编辑入口（与列表编辑职责分离）。
     */
    private fun ensurePreviewBar(): LinearLayout? {
        previewBar?.let { return it }
        val parent = binding.root as? LinearLayout ?: return null
        val title = TextView(this).apply {
            text = getString(R.string.read_menu_preview_title)
            textSize = 12f
            setTextColor(secondaryTextColor)
            typeface = uiTypeface()
        }
        val rows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            isClickable = false
            isFocusable = false
            addView(
                title,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
            addView(
                rows,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = 4.dpToPx() }
            )
        }
        val params = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            leftMargin = 16.dpToPx()
            rightMargin = 16.dpToPx()
            topMargin = 4.dpToPx()
            bottomMargin = 8.dpToPx()
        }
        val addIndex = parent.indexOfChild(binding.btnAdd).takeIf { it >= 0 } ?: parent.childCount
        parent.addView(bar, addIndex, params)
        previewBar = bar
        return bar
    }

    /** 预览条内容随 `layout` 重建（每次 [load] 调用；数据源与阅读菜单同为 `ReadMenuButtonConfig`） */
    private fun refreshPreview() {
        val rows = previewBar?.getChildAt(1) as? LinearLayout ?: return
        rows.removeAllViews()
        addPreviewRow(rows, layout.firstRow)
        addPreviewRow(rows, layout.secondRow)
    }

    private fun addPreviewRow(container: LinearLayout, row: List<ReadMenuButtonConfig.ButtonRef>) {
        if (row.isEmpty()) return
        val rowView = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        row.take(MENU_BUTTONS_PER_PAGE).forEach { ref ->
            rowView.addView(
                previewCell(ref),
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            )
        }
        container.addView(
            rowView,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 4.dpToPx() }
        )
        // 超出每页上限的部分在阅读菜单里是「左右翻页」而非折叠 ⇒ 提示口径按真实行为写（蓝图原文「折叠」失实）
        if (row.size > MENU_BUTTONS_PER_PAGE) {
            container.addView(
                TextView(this).apply {
                    text = getString(R.string.read_menu_preview_more, row.size - MENU_BUTTONS_PER_PAGE)
                    textSize = 11f
                    setTextColor(secondaryTextColor)
                    gravity = Gravity.END
                    typeface = uiTypeface()
                },
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }
    }

    private fun previewCell(ref: ReadMenuButtonConfig.ButtonRef): LinearLayout {
        val iconSize = 20.dpToPx()
        val icon = ImageView(this).apply {
            setImageDrawable(
                ReadMenuButtonIconHelper.drawable(
                    this@ReadMenuButtonManageActivity,
                    ref,
                    buttonIconRes(ref),
                    ref.id.toLongOrNull()?.let { customButtons[it]?.iconPath }
                )
            )
            setColorFilter(primaryTextColor)
        }
        val label = TextView(this).apply {
            text = buttonTitle(ref)
            textSize = 10f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            gravity = Gravity.CENTER
            setTextColor(secondaryTextColor)
            typeface = uiTypeface()
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            addView(icon, LinearLayout.LayoutParams(iconSize, iconSize))
            addView(
                label,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }
    }

    private fun saveCurrentRow(row: List<ReadMenuButtonConfig.ButtonRef>) {
        layout = if (rowIndex == 0) {
            layout.copy(firstRow = row)
        } else {
            layout.copy(secondRow = row)
        }
        ReadMenuButtonConfig.save(this, layout)
        notifyReadMenuChanged()
        adapter.items = currentRow()
    }

    private fun saveLayout(newLayout: ReadMenuButtonConfig.ButtonLayout) {
        layout = newLayout
        ReadMenuButtonConfig.save(this, layout)
        notifyReadMenuChanged()
        adapter.items = currentRow()
    }

    private fun notifyReadMenuChanged() {
        postEvent(EventBus.READ_MENU_BUTTON_CHANGED, true)
    }

    private fun showAddButtonDialog() {
        val usedIds = (layout.firstRow + layout.secondRow)
            .filter { it.type == ReadMenuButtonConfig.TYPE_BUILTIN }
            .map { it.id }
            .toSet()
        val candidates = buildList {
            builtinCandidates()
                .filterNot { it.id in usedIds }
                .forEach { add(AddCandidate(buttonTitle(it), AddAction.AddRef(it))) }
            val usedCustomIds = (layout.firstRow + layout.secondRow)
                .filter { it.type == ReadMenuButtonConfig.TYPE_CUSTOM }
                .mapNotNull { it.id.toLongOrNull() }
                .toSet()
            customButtons.values
                .forEach { button ->
                    val usedMark = if (button.id in usedCustomIds) " (${getString(R.string.theme_source_using)})" else ""
                    add(
                        AddCandidate(
                            "${getString(R.string.read_menu_existing_custom_button)}：${button.displayName()}$usedMark  \u22EE",
                            AddAction.ExistingCustom(button)
                        )
                    )
                }
            add(AddCandidate(getString(R.string.read_menu_create_custom_button), AddAction.CreateCustom))
            add(AddCandidate(getString(R.string.import_str), AddAction.ImportCustom))
        }
        if (candidates.isEmpty()) {
            toastOnUi(R.string.read_menu_no_available_button)
            return
        }
        showComposeChoiceListDialog(
            title = getString(R.string.read_menu_add_button),
            labels = candidates.map { it.title }
        ) { index ->
            when (val action = candidates[index].action) {
                is AddAction.AddRef -> addButton(action.ref)
                is AddAction.ExistingCustom -> showExistingCustomButtonActions(action.button)
                AddAction.CreateCustom -> openCustomButtonEdit()
                AddAction.ImportCustom -> showImportButtonActions()
            }
        }
    }

    private fun customButtonRef(button: ReadMenuCustomButton): ReadMenuButtonConfig.ButtonRef {
        return ReadMenuButtonConfig.ButtonRef(
            ReadMenuButtonConfig.TYPE_CUSTOM,
            button.id.toString()
        )
    }

    private fun addButton(ref: ReadMenuButtonConfig.ButtonRef) {
        load()
        val row = currentRow().toMutableList()
        if (row.any { it.type == ref.type && it.id == ref.id }) return
        row.add(ref)
        saveCurrentRow(row)
    }

    private fun builtinCandidates(): List<ReadMenuButtonConfig.ButtonRef> {
        return buildList {
            add(ReadMenuButtonConfig.builtin(ReadMenuButtonConfig.Builtin.SEARCH))
            add(ReadMenuButtonConfig.builtin(ReadMenuButtonConfig.Builtin.AUTO_PAGE))
            add(ReadMenuButtonConfig.builtin(ReadMenuButtonConfig.Builtin.REPLACE_RULE))
            add(ReadMenuButtonConfig.builtin(ReadMenuButtonConfig.Builtin.NIGHT_THEME))
            add(ReadMenuButtonConfig.builtin(ReadMenuButtonConfig.Builtin.CATALOG))
            add(ReadMenuButtonConfig.builtin(ReadMenuButtonConfig.Builtin.READ_ALOUD))
            add(ReadMenuButtonConfig.builtin(ReadMenuButtonConfig.Builtin.READ_STYLE))
            add(ReadMenuButtonConfig.builtin(ReadMenuButtonConfig.Builtin.SETTING))
            add(ReadMenuButtonConfig.builtin(ReadMenuButtonConfig.Builtin.READ_ASSISTANT))
            add(ReadMenuButtonConfig.builtin(ReadMenuButtonConfig.Builtin.AI_SUMMARY))
            add(ReadMenuButtonConfig.builtin(ReadMenuButtonConfig.Builtin.PARAGRAPH_RULES))
            add(ReadMenuButtonConfig.builtin(ReadMenuButtonConfig.Builtin.BUBBLE))
            add(ReadMenuButtonConfig.builtin(ReadMenuButtonConfig.Builtin.CHARACTERS))
        }
    }

    private fun moveToOtherRow(ref: ReadMenuButtonConfig.ButtonRef) {
        val first = layout.firstRow.toMutableList()
        val second = layout.secondRow.toMutableList()
        if (rowIndex == 0) {
            first.remove(ref)
            second.add(ref)
        } else {
            second.remove(ref)
            first.add(ref)
        }
        saveLayout(ReadMenuButtonConfig.ButtonLayout(first, second))
    }

    private fun openCustomButtonEdit(id: Long = 0L) {
        editCustomButton.launch(Intent(this, ReadMenuCustomButtonEditActivity::class.java).apply {
            if (id > 0) putExtra("id", id)
        })
    }

    private fun showImportButtonActions() {
        showComposeChoiceListDialog(
            title = getString(R.string.import_str),
            labels = listOf(getString(R.string.import_str), getString(R.string.import_on_line))
        ) { index ->
            when (index) {
                0 -> launchImportButtonFile()
                1 -> showImportButtonUrlDialog()
            }
        }
    }

    private fun launchImportButtonFile() {
        importCustomButton.launch {
            mode = HandleFileContract.FILE
            title = getString(R.string.import_str)
            allowExtensions = arrayOf("json")
        }
    }

    private fun showImportButtonUrlDialog() {
        showComposeTextInputDialog(
            title = getString(R.string.import_on_line),
            hint = "https://...",
            positiveText = getString(android.R.string.ok),
            negativeText = getString(R.string.cancel),
            onPositive = { input ->
                val url = input.trim()
                if (url.isNotEmpty()) importButtonsFromUrl(url)
            }
        )
    }

    private fun importButtonsFromUrl(url: String) {
        lifecycleScope.launch {
            kotlin.runCatching {
                val text = withContext(Dispatchers.IO) {
                    okHttpClient.newCallResponseBody { url(url) }.use { it.string() }
                }
                parseImportedButtons(text)
            }.onSuccess { buttons ->
                importButtons(buttons)
            }.onFailure {
                toastOnUi(it.localizedMessage ?: getString(R.string.wrong_format))
            }
        }
    }

    private fun parseImportedButtons(raw: String): List<ReadMenuCustomButton> {
        val text = raw.trim()
        if (text.isBlank()) return emptyList()
        GSON.fromJsonArray<ReadMenuCustomButton>(text).getOrNull()?.let { return it }
        GSON.fromJsonObject<ReadMenuCustomButton>(text).getOrNull()?.let { return listOf(it) }
        return emptyList()
    }

    private fun importButtons(buttons: List<ReadMenuCustomButton>) {
        if (buttons.isEmpty()) {
            toastOnUi(R.string.wrong_format)
            return
        }
        lifecycleScope.launch {
            val importedIds = withContext(Dispatchers.IO) {
                var order = (appDb.readMenuCustomButtonDao.maxOrder() ?: 0) + 1
                buttons.mapNotNull { imported ->
                    val normalized = imported.copy(
                        id = 0L,
                        name = imported.name.trim(),
                        order = order++,
                        updateTime = System.currentTimeMillis()
                    )
                    if (normalized.name.isBlank() || normalized.script.isBlank()) {
                        null
                    } else {
                        appDb.readMenuCustomButtonDao.insert(normalized)
                    }
                }
            }
            if (importedIds.isEmpty()) {
                toastOnUi(R.string.wrong_format)
                return@launch
            }
            load()
            val row = currentRow().toMutableList()
            importedIds.forEach { id ->
                val ref = ReadMenuButtonConfig.ButtonRef(ReadMenuButtonConfig.TYPE_CUSTOM, id.toString())
                if (row.none { it.type == ref.type && it.id == ref.id }) {
                    row.add(ref)
                }
            }
            saveCurrentRow(row)
            toastOnUi(R.string.success)
        }
    }

    private fun exportButton(button: ReadMenuCustomButton) {
        exportCustomButton.launch {
            mode = HandleFileContract.EXPORT
            fileData = HandleFileContract.FileData(
                "${sanitizeFileName(button.displayName())}.json",
                GSON.toJson(button).toByteArray(),
                "application/json"
            )
        }
    }

    private fun sanitizeFileName(name: String): String {
        return name.trim().ifBlank { "read_menu_custom_button" }
            .replace(Regex("""[\\/:*?"<>|]"""), "_")
    }

    private fun deleteButton(ref: ReadMenuButtonConfig.ButtonRef) {
        showComposeConfirmDialog(
            title = getString(R.string.delete),
            message = getString(R.string.del_msg),
            positiveText = getString(R.string.yes),
            negativeText = getString(R.string.no),
            dangerPositive = true,
            onPositive = {
                val row = currentRow().toMutableList()
                row.remove(ref)
                saveCurrentRow(row)
            }
        )
    }

    private fun deleteCustomButton(button: ReadMenuCustomButton) {
        val ref = customButtonRef(button)
        showComposeConfirmDialog(
            title = getString(R.string.delete),
            message = button.displayName(),
            positiveText = getString(R.string.yes),
            negativeText = getString(R.string.no),
            dangerPositive = true,
            onPositive = {
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) {
                        appDb.readMenuCustomButtonDao.delete(button)
                    }
                    val first = layout.firstRow.filterNot { it.type == ref.type && it.id == ref.id }
                    val second = layout.secondRow.filterNot { it.type == ref.type && it.id == ref.id }
                    saveLayout(ReadMenuButtonConfig.ButtonLayout(first, second))
                    toastOnUi(R.string.delete_success)
                }
            }
        )
    }

    private fun showExistingCustomButtonActions(button: ReadMenuCustomButton) {
        val ref = customButtonRef(button)
        val alreadyAdded = (layout.firstRow + layout.secondRow).any {
            it.type == ref.type && it.id == ref.id
        }
        val actions = buildList<Pair<String, () -> Unit>> {
            if (!alreadyAdded) {
                add(getString(R.string.add) to { addButton(ref) })
            }
            add(getString(R.string.edit) to { openCustomButtonEdit(button.id) })
            add(getString(R.string.delete) to { deleteCustomButton(button) })
            add(getString(R.string.export) to { exportButton(button) })
        }
        showComposeChoiceListDialog(
            title = button.displayName(),
            labels = actions.map { it.first }
        ) { index ->
            actions[index].second.invoke()
        }
    }

    private fun resetLayout() {
        showComposeConfirmDialog(
            title = getString(R.string.reset),
            message = getString(R.string.del_msg),
            positiveText = getString(R.string.yes),
            negativeText = getString(R.string.no),
            onPositive = {
                saveLayout(ReadMenuButtonConfig.defaultLayout())
            }
        )
    }

    override fun swap(srcPosition: Int, targetPosition: Int): Boolean {
        val moved = adapter.moveItem(srcPosition, targetPosition)
        if (moved) rowOrderDirty = true
        return moved
    }

    /**
     * F51：拖拽中给被拖卡片抬升 + accent 描边（由 `ItemTouchHelper.onSelectedChanged` 转发）。
     *
     * 复位**不能**放这里——平台收起拖拽时不保证再回调本方法，故复位统一放 [onClearView]。
     */
    override fun onDragStateChanged(viewHolder: RecyclerView.ViewHolder?, actionState: Int) {
        if (actionState != ItemTouchHelper.ACTION_STATE_DRAG) return
        viewHolder?.itemView?.apply {
            elevation = 6.dpToPx().toFloat()
            background = UiCorner.opaqueRoundedStroke(
                themeCardColorOrDefault(),
                UiCorner.panelRadius(this@ReadMenuButtonManageActivity),
                2.dpToPx(),
                accentColor
            )
        }
    }

    override fun onClearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
        // F51：无论是否发生位移都要复位拖拽视觉（下面「未位移即 return」的分支不会重建卡片）
        viewHolder.itemView.elevation = 0f
        viewHolder.itemView.background = UiCorner.opaqueRounded(
            themeCardColorOrDefault(),
            UiCorner.panelRadius(this)
        )
        if (!rowOrderDirty) return
        rowOrderDirty = false
        saveCurrentRow(adapter.items)
    }

    private fun showIconOptions(ref: ReadMenuButtonConfig.ButtonRef) {
        val isNightButton = ref.type == ReadMenuButtonConfig.TYPE_BUILTIN &&
                ref.id == ReadMenuButtonConfig.Builtin.NIGHT_THEME
        if (isNightButton) {
            showComposeChoiceListDialog(
                title = getString(R.string.change_icon),
                labels = listOf(
                    getString(R.string.read_menu_icon_set_day),
                    getString(R.string.read_menu_icon_set_night),
                    getString(R.string.read_menu_icon_clear_day),
                    getString(R.string.read_menu_icon_clear_night)
                )
            ) { index ->
                when (index) {
                    0 -> selectIcon(ref, nightIcon = false)
                    1 -> selectIcon(ref, nightIcon = true)
                    2 -> clearIcon(ref, nightIcon = false)
                    3 -> clearIcon(ref, nightIcon = true)
                }
            }
        } else {
            showComposeChoiceListDialog(
                title = getString(R.string.change_icon),
                labels = listOf(getString(R.string.change_icon), getString(R.string.clear))
            ) { index ->
                if (index == 0) selectIcon(ref, nightIcon = false) else clearIcon(ref, nightIcon = false)
            }
        }
    }

    private fun selectIcon(ref: ReadMenuButtonConfig.ButtonRef, nightIcon: Boolean) {
        pendingIconRequest = IconRequest(ref, nightIcon, ICON_REQUEST)
        selectIconFile.launch {
            mode = HandleFileContract.FILE
            title = getString(R.string.read_menu_icon_select_file)
            requestCode = ICON_REQUEST
            allowExtensions = arrayOf("png", "jpg", "jpeg", "webp", "bmp", "svg")
        }
    }

    private fun clearIcon(ref: ReadMenuButtonConfig.ButtonRef, nightIcon: Boolean) {
        ReadMenuButtonIconHelper.clearIcon(if (nightIcon) ref.nightIconPath else ref.iconPath)
        updateButtonRef(ref, ref.withIconPath(nightIcon, ""))
        toastOnUi(R.string.success)
    }

    private fun updateButtonRef(
        oldRef: ReadMenuButtonConfig.ButtonRef,
        newRef: ReadMenuButtonConfig.ButtonRef
    ) {
        val row = currentRow().toMutableList()
        val index = row.indexOf(oldRef)
        if (index < 0) return
        row[index] = newRef
        saveCurrentRow(row)
    }

    private fun buttonTitle(ref: ReadMenuButtonConfig.ButtonRef): String {
        ref.titleOverride.trim().takeIf { it.isNotBlank() }?.let { return it }
        if (ref.type == ReadMenuButtonConfig.TYPE_CUSTOM) {
            return ref.id.toLongOrNull()?.let { customButtons[it]?.displayName() } ?: ref.id
        }
        return when (ref.id) {
            ReadMenuButtonConfig.Builtin.SEARCH -> getString(R.string.search_content)
            ReadMenuButtonConfig.Builtin.AUTO_PAGE -> getString(R.string.auto_next_page)
            ReadMenuButtonConfig.Builtin.REPLACE_RULE -> getString(R.string.replace_rule_title)
            ReadMenuButtonConfig.Builtin.NIGHT_THEME -> getString(R.string.dark_theme)
            ReadMenuButtonConfig.Builtin.CATALOG -> getString(R.string.chapter_list)
            ReadMenuButtonConfig.Builtin.READ_ALOUD -> getString(R.string.read_aloud)
            ReadMenuButtonConfig.Builtin.READ_STYLE -> getString(R.string.interface_setting)
            ReadMenuButtonConfig.Builtin.SETTING -> getString(R.string.setting)
            ReadMenuButtonConfig.Builtin.READ_ASSISTANT -> getString(R.string.ai_assistant)
            ReadMenuButtonConfig.Builtin.AI_SUMMARY -> "AI总结"
            ReadMenuButtonConfig.Builtin.PARAGRAPH_RULES -> getString(R.string.paragraph_rule)
            ReadMenuButtonConfig.Builtin.BUBBLE -> "气泡"
            ReadMenuButtonConfig.Builtin.CHARACTERS -> "角色"
            else -> ref.id
        }
    }

    private fun buttonIconRes(ref: ReadMenuButtonConfig.ButtonRef): Int {
        if (ref.type == ReadMenuButtonConfig.TYPE_CUSTOM) return R.drawable.ic_custom
        return when (ref.id) {
            ReadMenuButtonConfig.Builtin.SEARCH -> R.drawable.ic_search
            ReadMenuButtonConfig.Builtin.AUTO_PAGE -> R.drawable.ic_auto_page
            ReadMenuButtonConfig.Builtin.REPLACE_RULE -> R.drawable.ic_find_replace
            ReadMenuButtonConfig.Builtin.NIGHT_THEME -> R.drawable.ic_brightness
            ReadMenuButtonConfig.Builtin.CATALOG -> R.drawable.ic_toc
            ReadMenuButtonConfig.Builtin.READ_ALOUD -> R.drawable.ic_read_aloud
            ReadMenuButtonConfig.Builtin.READ_STYLE -> R.drawable.ic_interface_setting
            ReadMenuButtonConfig.Builtin.SETTING -> R.drawable.ic_settings
            ReadMenuButtonConfig.Builtin.READ_ASSISTANT -> R.drawable.ic_bottom_ai_assistant
            ReadMenuButtonConfig.Builtin.AI_SUMMARY -> R.drawable.ic_bottom_ai
            ReadMenuButtonConfig.Builtin.PARAGRAPH_RULES -> R.drawable.ic_code
            ReadMenuButtonConfig.Builtin.BUBBLE -> R.drawable.ic_bubble_chart
            ReadMenuButtonConfig.Builtin.CHARACTERS -> R.drawable.ic_bottom_person
            else -> R.drawable.ic_custom
        }
    }

    private inner class ButtonAdapter : RecyclerView.Adapter<ButtonViewHolder>() {
        private var itemList: List<ReadMenuButtonConfig.ButtonRef> = emptyList()

        var items: List<ReadMenuButtonConfig.ButtonRef>
            get() = itemList
            set(value) {
                itemList = value
                notifyDataSetChanged()
            }

        fun moveItem(srcPosition: Int, targetPosition: Int): Boolean {
            if (srcPosition !in itemList.indices || targetPosition !in itemList.indices) return false
            if (srcPosition == targetPosition) return true
            val mutable = itemList.toMutableList()
            val item = mutable.removeAt(srcPosition)
            mutable.add(targetPosition, item)
            itemList = mutable
            notifyItemMoved(srcPosition, targetPosition)
            return true
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ButtonViewHolder {
            return ButtonViewHolder(
                ItemThemePackageBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            )
        }

        override fun getItemCount(): Int = itemList.size

        override fun onBindViewHolder(holder: ButtonViewHolder, position: Int) {
            holder.bind(itemList[position])
        }
    }

    private inner class ButtonViewHolder(
        private val itemBinding: ItemThemePackageBinding
    ) : RecyclerView.ViewHolder(itemBinding.root) {

        /**
         * 复用视图会换绑 ⇒ 手柄/图标位的监听必须读「当前绑定的 ref」，不能闭包捕获 bind 时的旧 ref。
         */
        private var boundRef: ReadMenuButtonConfig.ButtonRef? = null

        fun bind(ref: ReadMenuButtonConfig.ButtonRef) = itemBinding.run {
            boundRef = ref
            root.background = UiCorner.opaqueRounded(
                themeCardColorOrDefault(),
                UiCorner.panelRadius(this@ReadMenuButtonManageActivity)
            )
            ivPreview.setImageDrawable(
                ReadMenuButtonIconHelper.drawable(
                    this@ReadMenuButtonManageActivity,
                    ref,
                    buttonIconRes(ref),
                    ref.id.toLongOrNull()?.let { customButtons[it]?.iconPath }
                )
            )
            ivPreview.setColorFilter(primaryTextColor)
            ivPreview.setBackgroundColor(Color.TRANSPARENT)
            ivPreview.setOnClickListener { showIconOptions(ref) }
            tvName.text = buttonTitle(ref)
            tvSource.text = if (ref.type == ReadMenuButtonConfig.TYPE_CUSTOM) {
                getString(R.string.read_menu_custom_button)
            } else {
                getString(R.string.read_menu_builtin_button)
            }
            tvInfo.text = getString(
                if (rowIndex == 0) R.string.read_menu_first_row else R.string.read_menu_second_row
            )
            tvName.applyUiSectionTitleStyle(this@ReadMenuButtonManageActivity)
            tvInfo.applyUiLabelStyle(this@ReadMenuButtonManageActivity)
            tvSource.setTextColor(secondaryTextColor)
            btnApply.text = getString(
                if (rowIndex == 0) R.string.read_menu_move_to_second
                else R.string.read_menu_move_to_first
            )
            btnEdit.visibility = if (ref.type == ReadMenuButtonConfig.TYPE_CUSTOM) {
                View.VISIBLE
            } else {
                View.GONE
            }
            btnEdit.text = getString(R.string.edit)
            btnMore.text = getString(R.string.delete)
            btnMore.contentDescription = getString(R.string.delete)
            listOf(btnApply, btnEdit, btnMore).forEach {
                it.background = UiCorner.actionSelector(
                    themeMutedColorOrDefault(),
                    themeCardColorOrDefault(),
                    UiCorner.actionRadius(this@ReadMenuButtonManageActivity)
                )
                it.typeface = this@ReadMenuButtonManageActivity.uiTypeface()
            }
            btnApply.setOnClickListener { moveToOtherRow(ref) }
            btnEdit.setOnClickListener {
                ref.id.toLongOrNull()?.let { openCustomButtonEdit(it) }
            }
            btnMore.setOnClickListener { deleteButton(ref) }
            // F51：卡右侧常驻拖拽手柄——「可排序」从隐藏态变可见态；按下手柄即起拖
            ensureDragHandle().setOnTouchListener { _, event ->
                if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                    itemTouchHelper.startDrag(this@ButtonViewHolder)
                }
                false
            }
            // F61：夜间按钮的日/夜双图标状态浮到卡上
            bindNightIconSlots(ref)
        }

        /**
         * F51：按需把拖拽手柄插到卡右侧（`ic_drag_handle` 为全站既有排序资产）。
         *
         * 插入而非改 `item_theme_package.xml`：该布局被多个管理页共用，改布局会外溢；
         * 插到本页 Holder 的复用视图上同样只做一次（按 tag 去重）。
         */
        private fun ensureDragHandle(): ImageView {
            val parent = itemBinding.root as LinearLayout
            parent.findViewWithTag<ImageView>(DRAG_HANDLE_TAG)?.let { return it }
            return ImageView(this@ReadMenuButtonManageActivity).apply {
                tag = DRAG_HANDLE_TAG
                setImageResource(R.drawable.ic_drag_handle)
                setColorFilter(secondaryTextColor)
                contentDescription = getString(R.string.read_menu_drag_handle)
                setPadding(10.dpToPx(), 0, 2.dpToPx(), 0)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                ).apply { gravity = Gravity.CENTER_VERTICAL }
                parent.addView(this)
            }
        }

        /**
         * F61：把「夜间按钮配了哪几个图标」从「逐个点开图标菜单才知道」浮到卡上。
         *
         * 「配了几个图标」是本页独有的状态维度，不可见会让人误以为没配置成功、或反复进菜单确认；
         * 只对**具备日/夜双图标能力**的按钮（内置夜间按钮）展示，普通卡不加信息（保持信息密度）。
         * 同样不改共用卡布局 ⇒ 运行时插到 `lay_info` 的「行信息」之后。
         */
        private fun bindNightIconSlots(ref: ReadMenuButtonConfig.ButtonRef) {
            val layInfo = itemBinding.layInfo
            val isNightButton = ref.type == ReadMenuButtonConfig.TYPE_BUILTIN &&
                    ref.id == ReadMenuButtonConfig.Builtin.NIGHT_THEME
            val slotRow = layInfo.findViewWithTag<LinearLayout>(NIGHT_ICON_ROW_TAG)
            if (!isNightButton) {
                slotRow?.visibility = View.GONE
                return
            }
            val row = slotRow ?: buildNightIconRow()
            row.visibility = View.VISIBLE
            (row.getChildAt(1) as? ImageView)?.refreshNightIconSlot(ref, nightIcon = false)
            (row.getChildAt(2) as? ImageView)?.refreshNightIconSlot(ref, nightIcon = true)
        }

        private fun buildNightIconRow(): LinearLayout = itemBinding.run {
            val slotSize = 24.dpToPx()
            val label = TextView(this@ReadMenuButtonManageActivity).apply {
                text = getString(R.string.read_menu_night_icons)
                textSize = 12f
                setTextColor(secondaryTextColor)
                typeface = uiTypeface()
            }
            val daySlot = ImageView(this@ReadMenuButtonManageActivity).apply {
                contentDescription = getString(R.string.read_menu_night_icon_day)
                setOnClickListener { boundRef?.let { selectIcon(it, nightIcon = false) } }
            }
            val nightSlot = ImageView(this@ReadMenuButtonManageActivity).apply {
                contentDescription = getString(R.string.read_menu_night_icon_night)
                setOnClickListener { boundRef?.let { selectIcon(it, nightIcon = true) } }
            }
            val row = LinearLayout(this@ReadMenuButtonManageActivity).apply {
                tag = NIGHT_ICON_ROW_TAG
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(
                    label,
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                )
                addView(
                    daySlot,
                    LinearLayout.LayoutParams(slotSize, slotSize).apply { leftMargin = 10.dpToPx() }
                )
                addView(
                    nightSlot,
                    LinearLayout.LayoutParams(slotSize, slotSize).apply { leftMargin = 6.dpToPx() }
                )
            }
            val insertIndex = layInfo.indexOfChild(tvInfo).takeIf { it >= 0 }?.plus(1) ?: layInfo.childCount
            layInfo.addView(
                row,
                insertIndex,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = 4.dpToPx() }
            )
            row
        }

        /** 已配置 ⇒ 显示该图标的真实缩略；未配置 ⇒ 显示「＋」占位并降透明度（点即去设置） */
        private fun ImageView.refreshNightIconSlot(
            ref: ReadMenuButtonConfig.ButtonRef,
            nightIcon: Boolean
        ) {
            val path = if (nightIcon) ref.nightIconPath else ref.iconPath
            val configured = path.isNotBlank()
            setImageDrawable(
                ReadMenuButtonIconHelper.drawableFromPath(
                    this@ReadMenuButtonManageActivity,
                    path.takeIf { configured },
                    if (configured) buttonIconRes(ref) else R.drawable.ic_add
                )
            )
            setColorFilter(if (configured) primaryTextColor else secondaryTextColor)
            alpha = if (configured) 1f else 0.6f
            contentDescription = buildString {
                append(
                    getString(
                        if (nightIcon) R.string.read_menu_night_icon_night
                        else R.string.read_menu_night_icon_day
                    )
                )
                if (!configured) append("：").append(getString(R.string.read_menu_night_icon_unset))
            }
        }
    }

    private data class AddCandidate(
        val title: String,
        val action: AddAction
    )

    private sealed interface AddAction {
        data class AddRef(val ref: ReadMenuButtonConfig.ButtonRef) : AddAction
        data class ExistingCustom(val button: ReadMenuCustomButton) : AddAction
        data object CreateCustom : AddAction
        data object ImportCustom : AddAction
    }

    private data class IconRequest(
        val ref: ReadMenuButtonConfig.ButtonRef,
        val nightIcon: Boolean,
        val requestCode: Int
    ) {
        fun oldPath(): String = if (nightIcon) ref.nightIconPath else ref.iconPath
    }

    companion object {
        private const val ICON_REQUEST = 1001

        /** F51：拖拽手柄在复用视图上的去重 tag（同一 Holder 多次 bind 只插一次） */
        private const val DRAG_HANDLE_TAG = "read_menu_button_drag_handle"

        /** F61：日/夜双图标位在复用卡上的去重 tag */
        private const val NIGHT_ICON_ROW_TAG = "read_menu_button_night_icon_row"
    }
}

private fun ReadMenuButtonConfig.ButtonRef.withIconPath(
    nightIcon: Boolean,
    path: String
): ReadMenuButtonConfig.ButtonRef {
    return if (nightIcon) copy(nightIconPath = path) else copy(iconPath = path)
}
