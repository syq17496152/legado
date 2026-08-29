package io.legado.app.ui.book.read.config

import android.graphics.Color
import android.content.Intent
import android.app.Activity
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
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
import io.legado.app.ui.widget.MainTopBarView
import io.legado.app.ui.widget.recycler.ItemTouchCallback
import io.legado.app.utils.GSON
import io.legado.app.utils.applyStatusBarPadding
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

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        initView()
        load()
    }

    override fun onResume() {
        super.onResume()
        load()
    }

    /** subpage-topbar-unify: 子页头部统一为 MainTopBarView(Mode.SUB)，原「重置」工具栏菜单改为 action 插槽图标。 */
    private fun initTopBar() = binding.titleBar.run {
        applyStatusBarPadding(withInitialPadding = true)
        setMode(MainTopBarView.Mode.SUB)
        setTitle(getString(R.string.read_menu_button_manage))
        setSearchEntryVisible(false)
        titleSelect.setOnClickListener { finish() }
        addActionButton(R.drawable.ic_restore, R.string.reset) { resetLayout() }
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
        ItemTouchHelper(ItemTouchCallback(this@ReadMenuButtonManageActivity).apply {
            isCanDrag = true
        }).attachToRecyclerView(recyclerView)
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
        updateTabs()
    }

    private fun load() {
        layout = ReadMenuButtonConfig.load(this)
        customButtons = appDb.readMenuCustomButtonDao.all().associateBy { it.id }
        adapter.items = currentRow()
        binding.tvSummary.text = getString(R.string.read_menu_button_summary)
        updateTabs()
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

    override fun onClearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
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

        fun bind(ref: ReadMenuButtonConfig.ButtonRef) = itemBinding.run {
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
    }
}

private fun ReadMenuButtonConfig.ButtonRef.withIconPath(
    nightIcon: Boolean,
    path: String
): ReadMenuButtonConfig.ButtonRef {
    return if (nightIcon) copy(nightIconPath = path) else copy(iconPath = path)
}
