package io.legado.app.ui.config

import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.jaredrummler.android.colorpicker.ColorPickerDialog
import com.jaredrummler.android.colorpicker.ColorPickerDialogListener
import io.legado.app.R
import io.legado.app.base.BaseActivity
import io.legado.app.constant.EventBus
import io.legado.app.databinding.ActivityThemeManageBinding
import io.legado.app.help.AppCloudStorage
import io.legado.app.help.config.AppearanceKitManager
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.MainBottomNavConfig
import io.legado.app.help.config.NavigationBarIconConfig
import io.legado.app.lib.cloud.CloudStorageType
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.dialogs.selector
import io.legado.app.lib.theme.UiCorner
import io.legado.app.lib.theme.applyUiBodyTypefaceDeep
import io.legado.app.lib.theme.primaryTextColor
import io.legado.app.lib.theme.themeCardColorOrDefault
import io.legado.app.lib.theme.themeMutedColorOrDefault
import io.legado.app.lib.theme.uiTypeface
import io.legado.app.ui.book.cache.WebDavTaskManager
import io.legado.app.ui.book.cache.WebDavTaskStatus
import io.legado.app.ui.book.cache.WebDavTaskType
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.ui.image.ImageCropContract
import io.legado.app.ui.widget.ModernActionPopup
import io.legado.app.ui.widget.compose.AppManagementMenuAction
import io.legado.app.ui.widget.compose.AppManagementListRow
import io.legado.app.ui.widget.compose.rememberAppManagementPalette
import io.legado.app.ui.widget.compose.AppPackageManageItemCard
import io.legado.app.ui.widget.compose.AppPackageManageScreen
import io.legado.app.ui.widget.compose.AppPackageManageSettingCard
import io.legado.app.ui.widget.compose.showComposeActionListDialog
import io.legado.app.ui.widget.number.NumberPickerDialog
import io.legado.app.utils.ImageCropHelper
import io.legado.app.utils.externalFiles
import io.legado.app.utils.getFile
import io.legado.app.utils.observeEvent
import io.legado.app.utils.postEvent
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class NavigationBarManageActivity : BaseActivity<ActivityThemeManageBinding>(), ColorPickerDialogListener {

    override val binding by viewBinding(ActivityThemeManageBinding::inflate)

    private var entriesState by mutableStateOf<List<NavigationBarIconConfig.Entry>>(emptyList())
    private var activeDirNameState by mutableStateOf(NavigationBarIconConfig.DEFAULT_DIR_NAME)
    private var summaryTextState by mutableStateOf("")
    private var isNightMode by mutableStateOf(false)
    private var bottomNavItemsState by mutableStateOf(MainBottomNavConfig.items())
    private var editingEntry: NavigationBarIconConfig.Entry? = null
    private var editingDialog: LinearLayout? = null
    private var pendingConfig: NavigationBarIconConfig.Config? = null
    private var pendingColorTarget = 0
    private var pendingIconRequest: IconRequest? = null
    private var pendingSidebarBackgroundEntry: NavigationBarIconConfig.Entry? = null
    private var pendingBottomWallpaperCropRequest: ImageCropHelper.Request? = null
    private val handledWebDavTasks = mutableSetOf<String>()
    private var loadVersion = 0
    private var cloudContainerId: String? = null
    private var containerMenuItem: MenuItem? = null
    private var containerMenuPopup: ModernActionPopup.Handle? = null
    private val dateFormat by lazy { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()) }

    private val selectIcon = registerForActivityResult(HandleFileContract()) { result ->
        val request = pendingIconRequest?.takeIf { it.code == result.requestCode } ?: return@registerForActivityResult
        val uri = result.uri ?: return@registerForActivityResult
        lifecycleScope.launch {
            kotlin.runCatching {
                withContext(Dispatchers.IO) {
                    if (request.single) {
                        NavigationBarIconConfig.saveSingleIconToPackage(
                            this@NavigationBarManageActivity,
                            uri,
                            request.entry,
                            request.item.key,
                            resources.getDimensionPixelSize(R.dimen.main_sidebar_search_icon_size)
                        )
                    } else {
                        NavigationBarIconConfig.saveIconToPackage(
                            this@NavigationBarManageActivity,
                            uri,
                            request.entry,
                            request.item.key,
                            request.selected,
                            resources.getDimensionPixelSize(R.dimen.main_bottom_nav_icon_size)
                        )
                    }
                }
            }.onSuccess {
                editingEntry = it
                pendingConfig = it.config.copy(icons = it.config.icons.toMutableMap())
                notifyAppliedIfNeeded(it)
                refreshEditDialog()
                loadPackages()
                toastOnUi(R.string.success)
            }.onFailure {
                toastOnUi(it.localizedMessage ?: getString(R.string.navigation_icon_decode_failed))
            }
        }
    }

    private val importPackage = registerForActivityResult(HandleFileContract()) {
        it.uri?.let { uri -> importPackage(uri) }
    }

    private val exportPackage = registerForActivityResult(HandleFileContract()) {
        it.uri?.let {
            toastOnUi(R.string.export_success)
        }
    }

    private val selectSidebarBackground = registerForActivityResult(HandleFileContract()) { result ->
        val entry = pendingSidebarBackgroundEntry ?: return@registerForActivityResult
        val uri = result.uri ?: return@registerForActivityResult
        val metrics = resources.displayMetrics
        val isLandscape = metrics.widthPixels > metrics.heightPixels
        val sidebarWidth = if (isLandscape) {
            (metrics.widthPixels * 0.33f).toInt()
        } else {
            (metrics.widthPixels * 0.66f).toInt()
        }.coerceAtLeast(1)
        val request = ImageCropHelper.buildRequest(
            context = this,
            sourceUri = uri,
            requestCode = requestSidebarBackground,
            aspectWidth = sidebarWidth,
            aspectHeight = metrics.heightPixels.coerceAtLeast(1),
            dirName = "navigationBarSidebarBackground",
            prefix = "sidebar_bg",
            targetWidth = 1440
        )
        pendingSidebarBackgroundEntry = entry
        cropSidebarBackground.launch(request.params)
    }

    private val cropSidebarBackground = registerForActivityResult(ImageCropContract()) { result ->
        val entry = pendingSidebarBackgroundEntry ?: return@registerForActivityResult
        pendingSidebarBackgroundEntry = null
        if (result == null) {
            return@registerForActivityResult
        }
        val resultPath = result.path
        if (!File(resultPath).exists()) {
            toastOnUi(getString(R.string.image_crop_failed, getString(R.string.unknown)))
            return@registerForActivityResult
        }
        lifecycleScope.launch {
            kotlin.runCatching {
                withContext(Dispatchers.IO) {
                    NavigationBarIconConfig.saveSidebarBackgroundToPackage(
                        this@NavigationBarManageActivity,
                        resultPath,
                        entry
                    )
                }
            }.onSuccess {
                editingEntry = it
                pendingConfig = it.config.copy(icons = it.config.icons.toMutableMap())
                notifyAppliedIfNeeded(it)
                refreshEditDialog()
                loadPackages()
                toastOnUi(R.string.success)
            }.onFailure {
                toastOnUi(it.localizedMessage ?: getString(R.string.navigation_icon_decode_failed))
            }
        }
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        binding.titleBar.title = getString(R.string.navigation_bar_manage)
        initView()
        loadPackages()
        observeWebDavTasks()
    }

    override fun onResume() {
        super.onResume()
        bottomNavItemsState = MainBottomNavConfig.items()
        invalidateOptionsMenu()
    }

    override fun observeLiveBus() {
        observeEvent<String>(EventBus.RECREATE) {
            loadPackages()
        }
    }

    private fun initView() {
        val container = binding.recyclerView.parent as? ViewGroup ?: return
        val index = container.indexOfChild(binding.recyclerView)
        container.removeView(binding.recyclerView)
        container.removeView(binding.tabBar)
        container.removeView(binding.tvSummary)
        container.removeView(binding.btnAdd)
        val cv = ComposeView(this).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
            setContent {
                NavigationBarPackageManageScreen(
                    entries = entriesState,
                    activeDirName = activeDirNameState,
                    isNightMode = isNightMode,
                    summaryText = summaryTextState,
                    bottomNavSummary = bottomNavSummary(bottomNavItemsState),
                    onSwitchDayNight = { night ->
                        if (night != isNightMode) {
                            isNightMode = night
                            loadPackages()
                        }
                    },
                    onAdd = ::showAddDialog,
                    onManageBottomNavItems = ::showBottomNavItemsDialog,
                    onApply = ::applyPackage,
                    onEdit = { entry -> showEditDialog(entry) },
                    entryInfo = ::entryInfo,
                    entryActions = ::entryActions
                )
            }
        }
        container.addView(cv, index.coerceAtMost(container.childCount))
    }

    override fun onCompatCreateOptionsMenu(menu: Menu): Boolean {
        containerMenuItem = menu.add(0, MENU_CONTAINER, 0, R.string.s3_bucket).apply {
            setIcon(R.drawable.ic_outline_cloud_24)
            setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
            setActionView(R.layout.view_action_button)
            actionView?.let { view ->
                view.contentDescription = title
                view.findViewById<ImageButton>(R.id.item)?.setImageDrawable(icon)
                view.setOnClickListener { showContainerSelector(view) }
            }
        }
        menu.add(0, MENU_SYNC_TASKS, 1, R.string.package_sync_task_menu).apply {
            setIcon(R.drawable.ic_history)
            setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        }
        updateContainerMenu()
        return true
    }

    override fun onCompatOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            MENU_CONTAINER -> {
                showContainerSelector(item.actionView)
                true
            }
            MENU_SYNC_TASKS -> {
                showNavigationBarSyncTasks()
                true
            }
            else -> super.onCompatOptionsItemSelected(item)
        }
    }

    private val selectBottomWallpaper = registerForActivityResult(HandleFileContract()) {
        it.uri?.let(::startBottomWallpaperCrop)
    }

    private val cropBottomWallpaper = registerForActivityResult(ImageCropContract()) { result ->
        pendingBottomWallpaperCropRequest = null
        if (result == null) return@registerForActivityResult
        if (File(result.path).exists()) {
            pendingConfig?.wallpaperPath = result.path
            refreshEditDialog()
        } else {
            toastOnUi(getString(R.string.image_crop_failed, getString(R.string.unknown)))
        }
    }
    private fun showAddDialog() {
        showComposeActionListDialog(
            title = getString(R.string.theme_add),
            labels = listOf(
                getString(R.string.theme_manual_config),
                getString(R.string.theme_import_zip)
            )
        ) { index ->
            when (index) {
                0 -> showEditDialog(null)
                1 -> importPackage.launch {
                    mode = HandleFileContract.FILE
                    title = getString(R.string.theme_import_zip)
                    allowExtensions = arrayOf("zip")
                }
            }
        }
    }

    private fun showBottomNavItemsDialog() {
        alert(R.string.bottom_bar_items_manage) {
            customView {
                ComposeView(this@NavigationBarManageActivity).apply {
                    setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
                    setContent {
                        BottomNavItemsManageContent(
                            initialItems = bottomNavItemsState,
                            onItemsChange = ::saveBottomNavItems
                        )
                    }
                }
            }
            okButton()
        }
    }

    private fun saveBottomNavItems(items: List<MainBottomNavConfig.ItemState>) {
        val normalized = items.map { item ->
            val spec = MainBottomNavConfig.spec(item.key)
            if (spec?.lockedVisible == true) {
                item.copy(visible = true)
            } else {
                item
            }
        }
        MainBottomNavConfig.save(normalized)
        bottomNavItemsState = MainBottomNavConfig.items()
        postEvent(EventBus.NOTIFY_MAIN, true)
    }

    private fun bottomNavSummary(items: List<MainBottomNavConfig.ItemState>): String {
        val visible = items.filter { it.visible || MainBottomNavConfig.spec(it.key)?.lockedVisible == true }
        return visible.joinToString(" / ") { item ->
            MainBottomNavConfig.spec(item.key)?.let { getString(it.titleRes) } ?: item.key
        }
    }

    private fun updateContainerMenu() {
        val containers = AppCloudStorage.listContainers().filter { it.enabled }
        val item = containerMenuItem ?: return
        if (AppCloudStorage.type != CloudStorageType.S3) {
            cloudContainerId = containers.firstOrNull()?.id
            item.isVisible = false
            return
        }
        cloudContainerId = AppCloudStorage.selectedContainer(CLOUD_SCOPE)?.id
            ?: containers.firstOrNull()?.id
        item.isVisible = true
        val title = containers.firstOrNull { it.id == cloudContainerId }
            ?.let(AppCloudStorage::containerDisplayLabel)
            ?: getString(R.string.s3_bucket)
        item.title = title
        item.actionView?.contentDescription = title
    }

    private fun showContainerSelector(anchor: View? = null) {
        lifecycleScope.launch {
            val containers = withContext(Dispatchers.IO) { AppCloudStorage.listContainers().filter { it.enabled } }
            if (containers.isEmpty()) {
                toastOnUi(R.string.cloud_storage_config_required)
                return@launch
            }
            val selected = cloudContainerId ?: AppCloudStorage.selectedContainer(CLOUD_SCOPE)?.id
            val popupAnchor = anchor ?: containerMenuItem?.actionView ?: binding.titleBar.toolbar
            val actions = containers.map { container ->
                ModernActionPopup.Action(AppCloudStorage.containerDisplayLabel(container)) {
                    if (container.id == selected) return@Action
                    AppCloudStorage.selectContainer(CLOUD_SCOPE, container.id)
                    cloudContainerId = container.id
                    updateContainerMenu()
                    loadPackages()
                }
            }
            containerMenuPopup = ModernActionPopup.show(popupAnchor, actions, containerMenuPopup)
        }
    }
    private fun loadPackages() {
        val version = ++loadVersion
        lifecycleScope.launch {
            kotlin.runCatching {
                withContext(Dispatchers.IO) {
                    NavigationBarIconConfig.loadEntries(isNightMode, includeRemote = true, cloudContainerId, CLOUD_SCOPE)
                }
            }.onSuccess {
                if (version != loadVersion || isFinishing || isDestroyed) return@onSuccess
                entriesState = it
                activeDirNameState = NavigationBarIconConfig.activeDirName(isNightMode)
                summaryTextState = if (it.size <= 1) {
                    getString(R.string.navigation_bar_package_empty)
                } else {
                    getString(R.string.navigation_bar_package_summary)
                }
            }.onFailure {
                if (version != loadVersion || isFinishing || isDestroyed) return@onFailure
                summaryTextState = it.localizedMessage.orEmpty()
            }
        }
    }

    private fun showEditDialog(entry: NavigationBarIconConfig.Entry?) {
        val base = entry ?: NavigationBarIconConfig.Entry(
            NavigationBarIconConfig.Config(
                name = nextPackageName(),
                isNightMode = isNightMode,
                layoutMode = AppConfig.bottomBarLayoutMode,
                sidebarGravity = AppConfig.bottomBarSidebarGravity,
                effectMode = AppConfig.bottomBarEffectMode,
                opacity = if (AppConfig.bottomBarEffectMode == "frosted") AppConfig.frostedGlassLevel else AppConfig.liquidGlassLevel
            ),
            NavigationBarIconConfig.Source.LOCAL,
            ""
        )
        if (base.dirName == NavigationBarIconConfig.DEFAULT_DIR_NAME) {
            toastOnUi(R.string.navigation_bar_default_readonly)
            return
        }
        if (base.localDir == null && entry != null && base.source == NavigationBarIconConfig.Source.REMOTE) {
            lifecycleScope.launch {
                kotlin.runCatching {
                    withContext(Dispatchers.IO) { NavigationBarIconConfig.download(base, cloudContainerId, CLOUD_SCOPE) }
                }.onSuccess {
                    showEditDialog(it)
                }.onFailure {
                    toastOnUi(it.localizedMessage)
                }
            }
            return
        }
        editingEntry = base
        pendingConfig = editingEntry!!.config.copy(icons = editingEntry!!.config.icons.toMutableMap())
        val root = buildEditView()
        editingDialog = root
        alert(R.string.navigation_bar_edit) {
            customView { editDialogScrollContainer(root) }
            okButton {
                saveEditingPackage()
            }
            cancelButton()
        }
    }

    private fun editDialogScrollContainer(content: View): ScrollView {
        return object : ScrollView(this) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                val maxHeight = (resources.displayMetrics.heightPixels * 0.68f).toInt()
                    .coerceAtLeast(320.dp)
                val limitedHeightSpec = View.MeasureSpec.makeMeasureSpec(maxHeight, View.MeasureSpec.AT_MOST)
                super.onMeasure(widthMeasureSpec, limitedHeightSpec)
            }
        }.apply {
            isFillViewport = false
            isVerticalScrollBarEnabled = true
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            addView(
                content,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }
    }

    private fun buildEditView(): LinearLayout {
        val config = pendingConfig!!
        normalizeStandardBottomConfig(config)
        val currentEntry = editingEntry
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(2, 2, 2, 4)
            applyUiBodyTypefaceDeep(this@NavigationBarManageActivity.uiTypeface())
            addView(PackageManageUi.nameInput(this@NavigationBarManageActivity, config.name, getString(R.string.navigation_bar_name)))
            addView(optionRow(getString(R.string.bottom_bar_layout_mode), layoutModeLabel(config.layoutMode)) {
                selector(
                    getString(R.string.bottom_bar_layout_mode),
                    listOf(
                        getString(R.string.bottom_bar_layout_floating),
                        getString(R.string.bottom_bar_layout_standard),
                        getString(R.string.bottom_bar_layout_sidebar)
                    )
                ) { _, index ->
                    config.layoutMode = when (index) {
                        1 -> "standard"
                        2 -> "sidebar"
                        else -> "floating"
                    }
                    normalizeStandardBottomConfig(config)
                    refreshEditDialog()
                }
            })
            if (config.layoutMode != "sidebar") {
                if (config.layoutMode == "floating") {
                    addView(optionRow(getString(R.string.bottom_bar_material_mode), effectModeLabel(config.effectMode)) {
                        selector(
                            getString(R.string.bottom_bar_material_mode),
                            listOf(
                                getString(R.string.bottom_bar_effect_solid),
                                getString(R.string.bottom_bar_effect_glass),
                                getString(R.string.bottom_bar_effect_frosted)
                            )
                        ) { _, index ->
                            config.effectMode = when (index) {
                                0 -> "solid"
                                2 -> "frosted"
                                else -> "glass"
                            }
                            refreshEditDialog()
                        }
                    })
                    addView(optionRow(
                        getString(R.string.search),
                        getString(if (config.hideSearchInFloatingStyle) R.string.disabled else R.string.enabled)
                    ) {
                        config.hideSearchInFloatingStyle = !config.hideSearchInFloatingStyle
                        refreshEditDialog()
                    })
                }
                if (config.layoutMode == "standard") {
                    addView(optionRow(getString(R.string.bottom_bar_wallpaper), wallpaperLabel(config.wallpaperPath)) {
                        showBottomWallpaperSelector()
                    })
                }
                addView(optionRow(getString(R.string.bottom_bar_opacity), "${config.opacity}%") {
                    showAlphaPicker(getString(R.string.bottom_bar_opacity), config.opacity) {
                        config.opacity = it
                    }
                })
                addView(optionRow(
                    getString(R.string.bottom_bar_border_color),
                    config.borderColor?.let(::colorLabel) ?: getString(R.string.disable)
                ) {
                    showOptionalColorSelector(
                        getString(R.string.bottom_bar_border_color),
                        config.borderColor,
                        COLOR_BORDER
                    )
                })
                addView(optionRow(getString(R.string.bottom_bar_border_alpha), "${config.borderAlpha}%") {
                    showAlphaPicker(getString(R.string.bottom_bar_border_alpha), config.borderAlpha) {
                        config.borderAlpha = it
                    }
                })
            } else {
                addView(optionRow(
                    getString(R.string.navigation_bar_sidebar_background),
                    if (config.sidebarBackgroundPath.isNullOrBlank()) {
                        getString(R.string.select_image)
                    } else {
                        getString(R.string.theme_image_selected)
                    }
                ) {
                    selector(
                        getString(R.string.navigation_bar_sidebar_background),
                        buildList {
                            add(getString(R.string.select_image))
                            if (!config.sidebarBackgroundPath.isNullOrBlank()) {
                                add(getString(R.string.delete))
                            }
                        }
                    ) { _, index ->
                        if (index == 0) {
                            pendingSidebarBackgroundEntry = currentEntry
                            selectSidebarBackground.launch {
                                mode = HandleFileContract.IMAGE
                                title = getString(R.string.navigation_bar_sidebar_background)
                            }
                        } else if (currentEntry != null) {
                            editingEntry = NavigationBarIconConfig.clearSidebarBackground(currentEntry)
                            pendingConfig = editingEntry!!.config.copy(icons = editingEntry!!.config.icons.toMutableMap())
                            notifyAppliedIfNeeded(editingEntry!!)
                            refreshEditDialog()
                            loadPackages()
                        }
                    }
                })
            }
            NavigationBarIconConfig.items
                .filter { config.layoutMode == "sidebar" || it.key != "ai" }
                .let { NavigationBarIconConfig.extraItems + it }
                .forEach { item ->
                    if (item.menuId == 0) {
                        addView(singleIconRow(item))
                    } else {
                        addView(iconRow(item))
                    }
                }
        }
    }

    private fun refreshEditDialog() {
        val root = editingDialog ?: return
        root.removeAllViews()
        buildEditView().let { rebuilt ->
            while (rebuilt.childCount > 0) {
                root.addView(rebuilt.getChildAt(0).also { rebuilt.removeView(it) })
            }
        }
    }

    private fun showBottomWallpaperSelector() {
        val hasWallpaper = !pendingConfig?.wallpaperPath.isNullOrBlank()
        selector(
            getString(R.string.bottom_bar_wallpaper),
            buildList {
                add(getString(R.string.theme_image_select))
                if (hasWallpaper) add(getString(R.string.theme_image_delete))
            }
        ) { _, index ->
            if (index == 0) {
                selectBottomWallpaper.launch {
                    mode = HandleFileContract.IMAGE
                    title = getString(R.string.bottom_bar_wallpaper)
                }
            } else {
                pendingConfig?.wallpaperPath = null
                refreshEditDialog()
            }
        }
    }

    private fun startBottomWallpaperCrop(uri: Uri) {
        val metrics = resources.displayMetrics
        val request = ImageCropHelper.buildRequest(
            context = this,
            sourceUri = uri,
            requestCode = requestBottomWallpaper,
            aspectWidth = metrics.widthPixels.coerceAtLeast(1),
            aspectHeight = (96 * metrics.density).toInt().coerceAtLeast(1),
            dirName = "bottomBarWallpapers",
            prefix = "bottom_bar",
            targetWidth = 1600
        )
        pendingBottomWallpaperCropRequest = request
        cropBottomWallpaper.launch(request.params)
    }

    private fun normalizeStandardBottomConfig(config: NavigationBarIconConfig.Config) {
        if (config.layoutMode == "standard") {
            config.effectMode = "solid"
        }
    }

    private fun optionRow(title: String, value: String, onClick: () -> Unit): View {
        return PackageManageUi.optionRow(this, title, value, onClick)
    }

    private fun showAlphaPicker(title: String, value: Int, apply: (Int) -> Unit) {
        NumberPickerDialog(this)
            .setTitle(title)
            .setMinValue(0)
            .setMaxValue(100)
            .setValue(value)
            .show {
                apply(it.coerceIn(0, 100))
                refreshEditDialog()
            }
    }

    private fun showOptionalColorSelector(title: String, color: Int?, target: Int) {
        selector(title, listOf(getString(R.string.disable), getString(R.string.select_color))) { _, index ->
            if (index == 0) {
                if (target == COLOR_BORDER) {
                    pendingConfig?.borderColor = null
                }
                refreshEditDialog()
            } else {
                showColorPicker(target, color ?: ContextCompat.getColor(this, R.color.accent))
            }
        }
    }

    private fun showColorPicker(target: Int, color: Int) {
        pendingColorTarget = target
        ColorPickerDialog.newBuilder()
            .setDialogId(target)
            .setColor(color)
            .setShowAlphaSlider(false)
            .setDialogType(ColorPickerDialog.TYPE_CUSTOM)
            .show(this)
    }

    override fun onColorSelected(dialogId: Int, color: Int) {
        if (dialogId == COLOR_BORDER) {
            pendingConfig?.borderColor = color
            refreshEditDialog()
        }
    }

    override fun onDialogDismissed(dialogId: Int) {
        pendingColorTarget = 0
    }

    private fun iconRow(item: NavigationBarIconConfig.NavItem): View {
        val entry = editingEntry ?: return View(this)
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(14.dp, 8.dp, 14.dp, 8.dp)
            background = UiCorner.opaqueRounded(
                context.themeCardColorOrDefault(),
                UiCorner.actionRadius(context)
            )
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = 8.dp
            }
            addView(TextView(context).apply {
                setText(item.titleRes)
                textSize = 15f
                setTextColor(primaryTextColor)
                typeface = this@NavigationBarManageActivity.uiTypeface()
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(previewButton(entry, item, false))
            addView(previewButton(entry, item, true))
        }
    }

    private fun singleIconRow(item: NavigationBarIconConfig.NavItem): View {
        val entry = editingEntry ?: return View(this)
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(14.dp, 8.dp, 14.dp, 8.dp)
            background = UiCorner.opaqueRounded(
                context.themeCardColorOrDefault(),
                UiCorner.actionRadius(context)
            )
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = 8.dp
            }
            addView(TextView(context).apply {
                setText(item.titleRes)
                textSize = 15f
                setTextColor(primaryTextColor)
                typeface = this@NavigationBarManageActivity.uiTypeface()
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(singlePreviewButton(entry, item))
        }
    }

    private fun singlePreviewButton(entry: NavigationBarIconConfig.Entry, item: NavigationBarIconConfig.NavItem): ImageView {
        return ImageView(this).apply {
            contentDescription = getString(item.titleRes)
            setPadding(8.dp, 8.dp, 8.dp, 8.dp)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setImageDrawable(NavigationBarIconConfig.previewSingleDrawable(this@NavigationBarManageActivity, entry, item))
            background = UiCorner.actionSelector(
                context.themeMutedColorOrDefault(),
                context.themeCardColorOrDefault(),
                UiCorner.actionRadius(context)
            )
            layoutParams = LinearLayout.LayoutParams(44.dp, 44.dp).apply { marginStart = 8.dp }
            setOnClickListener {
                selector(
                    contentDescription,
                    listOf(getString(R.string.select_image), getString(R.string.delete))
                ) { _, index ->
                    if (index == 0) {
                        val code = requestSingleIconBase + NavigationBarIconConfig.extraItems.indexOf(item)
                        pendingIconRequest = IconRequest(code, entry, item, selected = false, single = true)
                        selectIcon.launch {
                            mode = HandleFileContract.FILE
                            requestCode = code
                            title = getString(R.string.navigation_icon_select_file)
                            allowExtensions = arrayOf("ico", "svg", "png", "jpg", "jpeg")
                        }
                    } else {
                        editingEntry = NavigationBarIconConfig.clearSingleIcon(entry, item.key)
                        pendingConfig = editingEntry!!.config.copy(icons = editingEntry!!.config.icons.toMutableMap())
                        notifyAppliedIfNeeded(editingEntry!!)
                        refreshEditDialog()
                        loadPackages()
                    }
                }
            }
        }
    }

    private fun previewButton(entry: NavigationBarIconConfig.Entry, item: NavigationBarIconConfig.NavItem, selected: Boolean): ImageView {
        return ImageView(this).apply {
            contentDescription = getString(if (selected) R.string.navigation_icon_selected else R.string.navigation_icon_normal)
            setPadding(8.dp, 8.dp, 8.dp, 8.dp)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setImageDrawable(NavigationBarIconConfig.previewDrawable(this@NavigationBarManageActivity, entry, item, selected))
            background = UiCorner.actionSelector(
                context.themeMutedColorOrDefault(),
                context.themeCardColorOrDefault(),
                UiCorner.actionRadius(context)
            )
            layoutParams = LinearLayout.LayoutParams(44.dp, 44.dp).apply { marginStart = 8.dp }
            setOnClickListener {
                selector(
                    contentDescription,
                    listOf(getString(R.string.select_image), getString(R.string.delete))
                ) { _, index ->
                    if (index == 0) {
                        val code = NavigationBarIconConfig.items.indexOf(item) * 2 + if (selected) 1 else 0
                        pendingIconRequest = IconRequest(code, entry, item, selected)
                        selectIcon.launch {
                            mode = HandleFileContract.FILE
                            requestCode = code
                            title = getString(R.string.navigation_icon_select_file)
                            allowExtensions = arrayOf("ico", "svg", "png", "jpg", "jpeg")
                        }
                    } else {
                        editingEntry = NavigationBarIconConfig.clearIcon(entry, item.key, selected)
                        pendingConfig = editingEntry!!.config.copy(icons = editingEntry!!.config.icons.toMutableMap())
                        notifyAppliedIfNeeded(editingEntry!!)
                        refreshEditDialog()
                        loadPackages()
                    }
                }
            }
        }
    }

    private fun saveEditingPackage() {
        val config = pendingConfig ?: return
        normalizeStandardBottomConfig(config)
        val name = editingDialog?.findViewWithTag<EditText>("name")?.text?.toString()?.trim().orEmpty()
        lifecycleScope.launch {
            kotlin.runCatching {
                withContext(Dispatchers.IO) {
                    NavigationBarIconConfig.addOrUpdate(config.copy(name = name), editingEntry)
                }
            }.onSuccess {
                if (notifyAppliedIfNeeded(it)) {
                    AppearanceKitManager.syncCurrentNavigationBarRef(it.config.isNightMode, it)
                }
                toastOnUi(R.string.theme_saved_local)
                loadPackages()
                if (enqueueUploadIfNeeded(it)) {
                    showNavigationBarSyncTasks()
                }
            }.onFailure {
                toastOnUi(it.localizedMessage)
            }
        }
    }

    private fun entryActions(entry: NavigationBarIconConfig.Entry): List<AppManagementMenuAction> {
        val actions = buildList {
            add(NavAction.APPLY)
            if (entry.dirName != NavigationBarIconConfig.DEFAULT_DIR_NAME) {
                add(NavAction.EDIT)
                add(NavAction.EXPORT)
                if (entry.source != NavigationBarIconConfig.Source.REMOTE) add(NavAction.UPLOAD)
                if (entry.source != NavigationBarIconConfig.Source.LOCAL) add(NavAction.DOWNLOAD)
                if (entry.source != NavigationBarIconConfig.Source.REMOTE) add(NavAction.DELETE_LOCAL)
                if (entry.source != NavigationBarIconConfig.Source.LOCAL) add(NavAction.DELETE_REMOTE)
                if (entry.source == NavigationBarIconConfig.Source.BOTH) add(NavAction.DELETE_BOTH)
            }
        }
        return actions.map { action ->
            AppManagementMenuAction(
                text = getString(action.titleRes),
                danger = action.name.startsWith("DELETE")
            ) {
                when (action) {
                    NavAction.APPLY -> applyPackage(entry)
                    NavAction.EDIT -> showEditDialog(entry)
                    NavAction.EXPORT -> exportPackage(entry)
                    NavAction.UPLOAD -> enqueueUpload(entry)
                    NavAction.DOWNLOAD -> runAction {
                        NavigationBarIconConfig.download(entry, cloudContainerId, CLOUD_SCOPE)
                    }
                    NavAction.DELETE_LOCAL -> confirmDelete(
                        entry,
                        getString(R.string.navigation_bar_delete_local_confirm)
                    ) {
                        NavigationBarIconConfig.deleteLocal(entry)
                        postEvent(EventBus.NAVIGATION_BAR_CHANGED, entry.config.isNightMode)
                    }
                    NavAction.DELETE_REMOTE -> confirmDelete(
                        entry,
                        getString(R.string.navigation_bar_delete_remote_confirm)
                    ) {
                        NavigationBarIconConfig.deleteRemote(entry, cloudContainerId, CLOUD_SCOPE)
                    }
                    NavAction.DELETE_BOTH -> confirmDelete(
                        entry,
                        getString(R.string.navigation_bar_delete_both_confirm)
                    ) {
                        NavigationBarIconConfig.delete(entry, cloudContainerId, CLOUD_SCOPE)
                        postEvent(EventBus.NAVIGATION_BAR_CHANGED, entry.config.isNightMode)
                    }
                }
            }
        }
    }

    private fun confirmDelete(
        entry: NavigationBarIconConfig.Entry,
        message: String,
        block: suspend () -> Unit
    ) {
        alert(getString(R.string.delete), message) {
            yesButton {
                runAction(block)
            }
            noButton()
        }
    }

    private fun applyPackage(entry: NavigationBarIconConfig.Entry) {
        lifecycleScope.launch {
            kotlin.runCatching {
                withContext(Dispatchers.IO) { if (entry.source == NavigationBarIconConfig.Source.REMOTE) NavigationBarIconConfig.download(entry, cloudContainerId, CLOUD_SCOPE) else entry }
            }.onSuccess {
                NavigationBarIconConfig.apply(it)
                AppearanceKitManager.syncCurrentNavigationBarRef(it.config.isNightMode, it)
                postEvent(EventBus.NAVIGATION_BAR_CHANGED, it.config.isNightMode)
                loadPackages()
            }.onFailure {
                toastOnUi(it.localizedMessage)
            }
        }
    }

    private fun enqueueUpload(entry: NavigationBarIconConfig.Entry) {
        val queued = enqueueUploadTask(entry)
        toastOnUi(if (queued) R.string.cache_manage_upload_queued else R.string.cache_manage_webdav_task_duplicate)
        if (queued) {
            showNavigationBarSyncTasks()
        }
    }

    private fun enqueueUploadIfNeeded(entry: NavigationBarIconConfig.Entry): Boolean {
        if (!AppConfig.syncThemePackages) return false
        return enqueueUploadTask(entry)
    }

    private fun enqueueUploadTask(entry: NavigationBarIconConfig.Entry): Boolean {
        return WebDavTaskManager.enqueueUpload(
            key = "navigation_bar_upload:${entry.config.isNightMode}:${entry.dirName}",
            name = entry.config.name,
            type = WebDavTaskType.NAVIGATION_BAR_PACKAGE_UPLOAD,
            runningMessage = getString(R.string.navigation_bar_upload)
        ) {
            NavigationBarIconConfig.upload(entry, cloudContainerId, CLOUD_SCOPE)
        }
    }

    private fun observeWebDavTasks() {
        seedHandledWebDavTasks(WebDavTaskType.NAVIGATION_BAR_PACKAGE_UPLOAD)
        lifecycleScope.launch {
            WebDavTaskManager.states.collectLatest { states ->
                var shouldReload = false
                var failedMessage: String? = null
                states.values
                    .filter { it.type == WebDavTaskType.NAVIGATION_BAR_PACKAGE_UPLOAD }
                    .filter { it.status == WebDavTaskStatus.COMPLETED || it.status == WebDavTaskStatus.FAILED }
                    .forEach { state ->
                        if (handledWebDavTasks.add(webDavTaskHandleKey(state.key, state.status))) {
                            shouldReload = true
                            if (state.status == WebDavTaskStatus.FAILED) {
                                failedMessage = state.message
                            }
                        }
                    }
                if (shouldReload) {
                    loadPackages()
                    failedMessage?.let { toastOnUi(getString(R.string.theme_sync_failed, it)) }
                }
            }
        }
    }

    private fun seedHandledWebDavTasks(type: WebDavTaskType) {
        WebDavTaskManager.states.value.values
            .filter { it.type == type }
            .filter { it.status == WebDavTaskStatus.COMPLETED || it.status == WebDavTaskStatus.FAILED }
            .forEach { handledWebDavTasks.add(webDavTaskHandleKey(it.key, it.status)) }
    }

    private fun webDavTaskHandleKey(key: String, status: WebDavTaskStatus): String {
        return "$key:$status"
    }

    private fun showNavigationBarSyncTasks() {
        showPackageSyncTaskDialog(setOf(WebDavTaskType.NAVIGATION_BAR_PACKAGE_UPLOAD))
    }

    private fun exportPackage(entry: NavigationBarIconConfig.Entry) {
        lifecycleScope.launch {
            kotlin.runCatching {
                withContext(Dispatchers.IO) { NavigationBarIconConfig.exportZip(entry) }
            }.onSuccess { zip ->
                exportPackage.launch {
                    mode = HandleFileContract.EXPORT
                    showUploadUrl = false
                    fileData = HandleFileContract.FileData(zip.name, zip, "application/zip")
                }
            }.onFailure {
                toastOnUi(it.localizedMessage)
            }
        }
    }

    private fun importPackage(uri: Uri) {
        lifecycleScope.launch {
            kotlin.runCatching {
                val file = externalFiles.getFile("navigationBarImports", "import_${System.currentTimeMillis()}.zip")
                file.parentFile?.mkdirs()
                contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(file).use { output -> input.copyTo(output) }
                } ?: throw IllegalArgumentException(getString(R.string.theme_zip_read_failed))
                withContext(Dispatchers.IO) { NavigationBarIconConfig.importPackage(file) }
            }.onSuccess {
                toastOnUi(R.string.success)
                loadPackages()
                if (enqueueUploadIfNeeded(it)) {
                    showNavigationBarSyncTasks()
                }
            }.onFailure {
                toastOnUi(it.localizedMessage)
            }
        }
    }

    private fun runAction(block: suspend () -> Unit) {
        lifecycleScope.launch {
            kotlin.runCatching { withContext(Dispatchers.IO) { block() } }
                .onSuccess {
                    toastOnUi(R.string.success)
                }
                .onFailure { toastOnUi(it.localizedMessage) }
            loadPackages()
        }
    }

    private fun notifyAppliedIfNeeded(entry: NavigationBarIconConfig.Entry): Boolean {
        if (entry.dirName == NavigationBarIconConfig.activeDirName(entry.config.isNightMode)) {
            NavigationBarIconConfig.apply(entry)
            postEvent(EventBus.NAVIGATION_BAR_CHANGED, entry.config.isNightMode)
            return true
        }
        return false
    }

    private fun effectModeLabel(value: String): String {
        return when (value) {
            "solid" -> getString(R.string.bottom_bar_effect_solid)
            "frosted" -> getString(R.string.bottom_bar_effect_frosted)
            else -> getString(R.string.bottom_bar_effect_glass)
        }
    }

    private fun layoutModeLabel(value: String): String {
        return when (value) {
            "sidebar" -> getString(R.string.bottom_bar_layout_sidebar)
            "standard" -> getString(R.string.bottom_bar_layout_standard)
            else -> getString(R.string.bottom_bar_layout_floating)
        }
    }

    private fun colorLabel(color: Int): String {
        return "#${Integer.toHexString(color).takeLast(6).uppercase(Locale.ROOT)}"
    }

    private fun wallpaperLabel(path: String?): String {
        return if (path.isNullOrBlank()) getString(R.string.theme_image_select) else getString(R.string.theme_image_selected)
    }

    private fun entryInfo(entry: NavigationBarIconConfig.Entry): String {
        return buildString {
            append(layoutModeLabel(entry.config.layoutMode))
            if (entry.config.layoutMode == "floating") {
                append(" \u00B7 ")
                append(effectModeLabel(entry.config.effectMode))
            }
            if (entry.config.layoutMode != "sidebar") {
                append(" \u00B7 ")
                append(getString(R.string.bottom_bar_opacity))
                append(" ")
                append(entry.config.opacity)
                append("%")
                if (entry.config.layoutMode == "standard" && !entry.config.wallpaperPath.isNullOrBlank()) {
                    append(" \u00B7 ")
                    append(getString(R.string.bottom_bar_wallpaper))
                }
            }
            if (entry.config.updatedAt > 0) {
                append(" \u00B7 ")
                append(dateFormat.format(Date(maxOf(entry.config.updatedAt, entry.remoteUpdatedAt))))
            }
        }
    }

    private fun nextPackageName(): String {
        val base = getString(R.string.navigation_bar_custom_name)
        val usedNames = entriesState.map { it.config.name }.toSet()
        if (base !in usedNames) return base
        for (index in 2..999) {
            val name = "$base $index"
            if (name !in usedNames) return name
        }
        return "$base ${System.currentTimeMillis()}"
    }

    private data class IconRequest(
        val code: Int,
        val entry: NavigationBarIconConfig.Entry,
        val item: NavigationBarIconConfig.NavItem,
        val selected: Boolean,
        val single: Boolean = false
    )

    private companion object {
        private const val CLOUD_SCOPE = "theme"
        private const val MENU_CONTAINER = 0x5401
        private const val MENU_SYNC_TASKS = 0x5402
        const val requestSidebarBackground = 7001
        const val COLOR_BORDER = 7002
        const val requestBottomWallpaper = 7003
        const val requestSingleIconBase = 7100
    }

    private enum class NavAction(val titleRes: Int) {
        APPLY(R.string.theme_apply),
        EDIT(R.string.edit),
        EXPORT(R.string.export),
        UPLOAD(R.string.navigation_bar_upload),
        DOWNLOAD(R.string.action_download),
        DELETE_LOCAL(R.string.theme_delete_local),
        DELETE_REMOTE(R.string.theme_delete_remote),
        DELETE_BOTH(R.string.theme_delete_both)
    }
    private val Int.dp: Int get() = (this * resources.displayMetrics.density).toInt()
}

@Composable
private fun NavigationBarPackageManageScreen(
    entries: List<NavigationBarIconConfig.Entry>,
    activeDirName: String,
    isNightMode: Boolean,
    summaryText: String,
    bottomNavSummary: String,
    onSwitchDayNight: (Boolean) -> Unit,
    onAdd: () -> Unit,
    onManageBottomNavItems: () -> Unit,
    onApply: (NavigationBarIconConfig.Entry) -> Unit,
    onEdit: (NavigationBarIconConfig.Entry) -> Unit,
    entryInfo: (NavigationBarIconConfig.Entry) -> String,
    entryActions: (NavigationBarIconConfig.Entry) -> List<AppManagementMenuAction>
) {
    val applyText = stringResource(R.string.theme_apply)
    val appliedText = stringResource(R.string.theme_applied_state)
    val editText = stringResource(R.string.edit)
    AppPackageManageScreen(
        isNightMode = isNightMode,
        summaryText = summaryText,
        addText = stringResource(R.string.theme_add),
        onSwitchDayNight = onSwitchDayNight,
        onAdd = onAdd,
        headerContent = { palette ->
            item(key = "main_bottom_bar_items") {
                AppPackageManageSettingCard(
                    title = stringResource(R.string.bottom_bar_items_manage),
                    info = bottomNavSummary,
                    valueText = stringResource(R.string.edit),
                    palette = palette,
                    onClick = onManageBottomNavItems
                )
            }
        }
    ) { palette ->
        items(
            entries,
            key = { "${it.dirName}_${it.config.isNightMode}" }
        ) { entry ->
            val isActive = entry.dirName == activeDirName
            AppPackageManageItemCard(
                title = entry.config.name,
                info = entryInfo(entry),
                isActive = isActive,
                canEdit = entry.dirName != NavigationBarIconConfig.DEFAULT_DIR_NAME,
                applyText = if (isActive) appliedText else applyText,
                editText = editText,
                moreActions = entryActions(entry),
                palette = palette,
                onApply = { onApply(entry) },
                onEdit = { onEdit(entry) }
            )
        }
    }
}

@Composable
private fun BottomNavItemsManageContent(
    initialItems: List<MainBottomNavConfig.ItemState>,
    onItemsChange: (List<MainBottomNavConfig.ItemState>) -> Unit
) {
    val palette = rememberAppManagementPalette()
    val listState = rememberLazyListState()
    var orderedItems by remember { mutableStateOf(initialItems) }
    LaunchedEffect(initialItems) {
        orderedItems = initialItems
    }
    val reorderState = rememberReorderableLazyListState(listState) { from, to ->
        orderedItems = orderedItems.toMutableList().apply {
            add(to.index, removeAt(from.index))
        }
    }
    LazyColumn(
        state = listState,
        modifier = Modifier
            .heightIn(max = 460.dp)
            .padding(bottom = 4.dp)
    ) {
        items(
            items = orderedItems,
            key = { it.key }
        ) { item ->
            val spec = MainBottomNavConfig.spec(item.key) ?: return@items
            val locked = spec.lockedVisible
            ReorderableItem(reorderState, key = item.key) {
                AppManagementListRow(
                    title = stringResource(spec.titleRes),
                    subtitle = if (locked) {
                        stringResource(R.string.bottom_bar_item_locked)
                    } else if (item.visible) {
                        stringResource(R.string.enabled)
                    } else {
                        stringResource(R.string.bottom_bar_item_hidden)
                    },
                    palette = palette,
                    switchChecked = if (locked) null else item.visible,
                    onSwitchChange = if (locked) null else { checked ->
                        val next = orderedItems.map { current ->
                            if (current.key == item.key) current.copy(visible = checked) else current
                        }
                        orderedItems = next
                        onItemsChange(next)
                    },
                    leadingContent = {
                        Icon(
                            painter = painterResource(R.drawable.ic_drag_handle),
                            contentDescription = stringResource(R.string.sort),
                            tint = palette.settings.secondaryText,
                            modifier = Modifier
                                .padding(end = 8.dp)
                                .draggableHandle(
                                    onDragStopped = { onItemsChange(orderedItems) }
                                )
                        )
                    }
                )
            }
        }
    }
}
