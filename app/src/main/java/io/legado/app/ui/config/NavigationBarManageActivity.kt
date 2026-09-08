package io.legado.app.ui.config

import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.History
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.annotation.StringRes
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
import io.legado.app.ui.book.cache.WebDavTaskManager
import io.legado.app.ui.book.cache.WebDavTaskStatus
import io.legado.app.ui.book.cache.WebDavTaskType
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.ui.image.ImageCropContract
import io.legado.app.ui.widget.components.MenuAction
import io.legado.app.ui.widget.components.installGlassTopBar
import io.legado.app.ui.widget.compose.AppDialogFrame
import io.legado.app.ui.widget.compose.AppDialogSize
import io.legado.app.ui.widget.compose.AppManagementListRow
import io.legado.app.ui.widget.compose.AppManagementMenuAction
import io.legado.app.ui.widget.compose.ComposeDialogFragment
import io.legado.app.ui.widget.compose.AppPackageManageItemCard
import io.legado.app.ui.widget.compose.AppPackageManageScreen
import io.legado.app.ui.widget.compose.AppPackageManageSettingCard
import io.legado.app.ui.widget.compose.LegadoMiuixActionButton
import io.legado.app.ui.widget.compose.rememberAppDialogStyle
import io.legado.app.ui.widget.compose.rememberAppManagementPalette
import io.legado.app.ui.widget.compose.toMiuixPalette
import io.legado.app.ui.widget.compose.showComposeActionListDialog
import io.legado.app.ui.widget.compose.showComposeChoiceListDialog
import io.legado.app.ui.widget.compose.showComposeConfirmDialog
import io.legado.app.ui.widget.compose.showComposeNumberPickerDialog
import io.legado.app.utils.ImageCropHelper
import io.legado.app.utils.externalFiles
import io.legado.app.utils.getFile
import io.legado.app.utils.observeEvent
import io.legado.app.utils.postEvent
import io.legado.app.utils.showDialogFragment
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
    // W3.2：编辑弹框 Compose 化——原 editingDialog(View) 全量重建改为 editVersion 快照计数驱动重组
    private var editVersion by mutableIntStateOf(0)
    private var pendingConfig: NavigationBarIconConfig.Config? = null
    private var pendingColorTarget = 0
    private var pendingIconRequest: IconRequest? = null
    private var pendingSidebarBackgroundEntry: NavigationBarIconConfig.Entry? = null
    private var pendingBottomWallpaperCropRequest: ImageCropHelper.Request? = null
    private val handledWebDavTasks = mutableSetOf<String>()
    private var loadVersion = 0
    private var cloudContainerId: String? = null
    // W3.2：S3 容器按钮显隐改 Compose 状态驱动（对齐 TopBarManage 3.1 模式）
    private var containerActionVisible by mutableStateOf(false)
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

    // ui-theme-governance-polish P6：管理族宿主接入背景透明度（1.5 封闭清单成员）
    override fun manageBackgroundAlphaEnabled(): Boolean = true

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        initTopBar()
        initView()
        loadPackages()
        observeWebDavTasks()
    }

    override fun onResume() {
        super.onResume()
        bottomNavItemsState = MainBottomNavConfig.items()
        invalidateOptionsMenu()
    }

    // T8②（theme-arch-gap）：RECREATE 由基类统一订阅（本页不豁免→整体重建，
    // 重建后 onCreate→initView/loadPackages 已重载），自订阅 loadPackages 冗余已删

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

    /** subpage-topbar-unify: 子页头部统一为 MainTopBarView(Mode.SUB)，容器切换/同步任务改为 action 插槽图标。 */
    // W3.2：顶栏运行时替换为 GlassTopAppBar（透壁纸语义，W1 模式）。S3 容器/同步任务保留一级图标语义
    private fun initTopBar() {
        installGlassTopBar(
            binding,
            titleProvider = { getString(R.string.navigation_bar_manage) },
            actionsProvider = {
                buildList {
                    if (containerActionVisible) {
                        add(
                            MenuAction(
                                iconRes = R.drawable.ic_outline_cloud_24,
                                title = getString(R.string.s3_bucket),
                                alwaysShow = true
                            ) { showContainerSelector() }
                        )
                    }
                    add(
                        MenuAction(
                            iconRes = R.drawable.ic_history,
                            title = getString(R.string.package_sync_task_menu),
                            alwaysShow = true
                        ) { showNavigationBarSyncTasks() }
                    )
                }
            },
            onBack = { finish() }
        )
        updateContainerMenu()
    }

    override fun onCompatCreateOptionsMenu(menu: Menu): Boolean {
        return true
    }

    override fun onCompatOptionsItemSelected(item: MenuItem): Boolean {
        return super.onCompatOptionsItemSelected(item)
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
        // W3.2：alert(customView 塞 ComposeView)→ComposeDialogFragment 壳（MC-7）
        showDialogFragment(
            NavigationBarItemsDialog.create(
                initialItems = bottomNavItemsState,
                onItemsChange = ::saveBottomNavItems
            )
        )
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
        if (AppCloudStorage.type != CloudStorageType.S3) {
            cloudContainerId = containers.firstOrNull()?.id
            containerActionVisible = false
            return
        }
        cloudContainerId = AppCloudStorage.selectedContainer(CLOUD_SCOPE)?.id
            ?: containers.firstOrNull()?.id
        containerActionVisible = true
    }

    // W3.2：ModernActionPopup(View 锚点)→showComposeActionListDialog（对齐 TopBarManage 3.1 模式）
    private fun showContainerSelector() {
        lifecycleScope.launch {
            val containers = withContext(Dispatchers.IO) { AppCloudStorage.listContainers().filter { it.enabled } }
            if (containers.isEmpty()) {
                toastOnUi(R.string.cloud_storage_config_required)
                return@launch
            }
            val selected = cloudContainerId ?: AppCloudStorage.selectedContainer(CLOUD_SCOPE)?.id
            showComposeActionListDialog(
                title = getString(R.string.s3_bucket),
                labels = containers.map { AppCloudStorage.containerDisplayLabel(it) }
            ) { index ->
                val container = containers.getOrNull(index) ?: return@showComposeActionListDialog
                if (container.id == selected) return@showComposeActionListDialog
                AppCloudStorage.selectContainer(CLOUD_SCOPE, container.id)
                cloudContainerId = container.id
                updateContainerMenu()
                loadPackages()
            }
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
        refreshEditDialog()
        // W3.2：alert(customView=ScrollView 包动态 LinearLayout)→ComposeDialogFragment 壳（MC-7）
        showDialogFragment(
            NavigationBarEditDialog.create(
                title = getString(R.string.navigation_bar_edit),
                initialName = pendingConfig?.name.orEmpty(),
                rowsProvider = ::buildNavBarEditRows,
                iconRowsProvider = ::buildNavBarIconRows,
                onSave = { name ->
                    saveEditingPackage(name)
                }
            )
        )
    }

    // W3.2：原 editDialogScrollContainer（ScrollView 高度限制壳）随 View 弹框废弃，滚动由 AppDialogFrame scrollContent 承担

    // W3.2：原 buildEditView(View) 改为行数据构建器，由 NavigationBarEditDialog Compose 渲染。
    // 显式读取 editVersion 建立重组依赖：任意配置修改后 refreshEditDialog 自增即触发行数据重建。
    private fun buildNavBarEditRows(): List<NavBarEditRow> {
        val config = pendingConfig!!
        normalizeStandardBottomConfig(config)
        val currentEntry = editingEntry
        editVersion
        return buildList {
            add(
                NavBarEditRow(getString(R.string.bottom_bar_layout_mode), layoutModeLabel(config.layoutMode)) {
                    showComposeChoiceListDialog(
                        getString(R.string.bottom_bar_layout_mode),
                        listOf(
                            getString(R.string.bottom_bar_layout_floating),
                            getString(R.string.bottom_bar_layout_standard),
                            getString(R.string.bottom_bar_layout_sidebar)
                        )
                    ) { index ->
                        config.layoutMode = when (index) {
                            1 -> "standard"
                            2 -> "sidebar"
                            else -> "floating"
                        }
                        normalizeStandardBottomConfig(config)
                        refreshEditDialog()
                    }
                }
            )
            if (config.layoutMode != "sidebar") {
                if (config.layoutMode == "floating") {
                    add(
                        NavBarEditRow(getString(R.string.bottom_bar_material_mode), effectModeLabel(config.effectMode)) {
                            showComposeChoiceListDialog(
                                getString(R.string.bottom_bar_material_mode),
                                listOf(
                                    getString(R.string.bottom_bar_effect_solid),
                                    getString(R.string.bottom_bar_effect_glass),
                                    getString(R.string.bottom_bar_effect_frosted)
                                )
                            ) { index ->
                                config.effectMode = when (index) {
                                    0 -> "solid"
                                    2 -> "frosted"
                                    else -> "glass"
                                }
                                refreshEditDialog()
                            }
                        }
                    )
                    add(
                        NavBarEditRow(
                            getString(R.string.search),
                            getString(if (config.hideSearchInFloatingStyle) R.string.disabled else R.string.enabled)
                        ) {
                            config.hideSearchInFloatingStyle = !config.hideSearchInFloatingStyle
                            refreshEditDialog()
                        }
                    )
                }
                if (config.layoutMode == "standard") {
                    add(
                        NavBarEditRow(getString(R.string.bottom_bar_wallpaper), wallpaperLabel(config.wallpaperPath)) {
                            showBottomWallpaperSelector()
                        }
                    )
                }
                add(
                    NavBarEditRow(getString(R.string.bottom_bar_opacity), "${config.opacity}%") {
                        showAlphaPicker(getString(R.string.bottom_bar_opacity), config.opacity) {
                            config.opacity = it
                        }
                    }
                )
                add(
                    NavBarEditRow(
                        getString(R.string.bottom_bar_border_color),
                        config.borderColor?.let(::colorLabel) ?: getString(R.string.disable)
                    ) {
                        showOptionalColorSelector(
                            getString(R.string.bottom_bar_border_color),
                            config.borderColor,
                            COLOR_BORDER
                        )
                    }
                )
                add(
                    NavBarEditRow(getString(R.string.bottom_bar_border_alpha), "${config.borderAlpha}%") {
                        showAlphaPicker(getString(R.string.bottom_bar_border_alpha), config.borderAlpha) {
                            config.borderAlpha = it
                        }
                    }
                )
            } else {
                add(
                    NavBarEditRow(
                        getString(R.string.navigation_bar_sidebar_background),
                        if (config.sidebarBackgroundPath.isNullOrBlank()) {
                            getString(R.string.select_image)
                        } else {
                            getString(R.string.theme_image_selected)
                        }
                    ) {
                        showComposeChoiceListDialog(
                            getString(R.string.navigation_bar_sidebar_background),
                            buildList {
                                add(getString(R.string.select_image))
                                if (!config.sidebarBackgroundPath.isNullOrBlank()) {
                                    add(getString(R.string.delete))
                                }
                            }
                        ) { index ->
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
                    }
                )
            }
        }
    }

    // W3.2：原 View 全量重建改为版本计数——Dialog 行数据 provider 读取本状态，自增即触发重组
    private fun refreshEditDialog() {
        editVersion++
    }

    private fun showBottomWallpaperSelector() {
        val hasWallpaper = !pendingConfig?.wallpaperPath.isNullOrBlank()
        showComposeChoiceListDialog(
            getString(R.string.bottom_bar_wallpaper),
            buildList {
                add(getString(R.string.theme_image_select))
                if (hasWallpaper) add(getString(R.string.theme_image_delete))
            }
        ) { index ->
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

    // W3.2：原 optionRow（PackageManageUi View 行助手）随 View 弹框废弃

    // W3.2：NumberPickerDialog(View)→showComposeNumberPickerDialog（MC 门禁统一弹框基线）
    private fun showAlphaPicker(title: String, value: Int, apply: (Int) -> Unit) {
        showComposeNumberPickerDialog(
            title = title,
            value = value,
            minValue = 0,
            maxValue = 100,
            onValue = { picked ->
                apply(picked.coerceIn(0, 100))
                refreshEditDialog()
            }
        )
    }

    private fun showOptionalColorSelector(title: String, color: Int?, target: Int) {
        showComposeChoiceListDialog(title, listOf(getString(R.string.disable), getString(R.string.select_color))) { index ->
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

    // W3.2：原 iconRow/singleIconRow/previewButton/singlePreviewButton(View) 改为图标行数据构建器
    private fun buildNavBarIconRows(): List<NavBarIconRow> {
        val entry = editingEntry ?: return emptyList()
        val config = pendingConfig ?: return emptyList()
        editVersion
        return NavigationBarIconConfig.items
            .filter { config.layoutMode == "sidebar" || it.key != "ai" }
            .let { NavigationBarIconConfig.extraItems + it }
            .map { item ->
                val previews = if (item.menuId == 0) {
                    listOf(navIconPreview(entry, item, single = true))
                } else {
                    listOf(
                        navIconPreview(entry, item, single = false, selected = false),
                        navIconPreview(entry, item, single = false, selected = true)
                    )
                }
                NavBarIconRow(titleRes = item.titleRes, previews = previews)
            }
    }

    private fun navIconPreview(
        entry: NavigationBarIconConfig.Entry,
        item: NavigationBarIconConfig.NavItem,
        single: Boolean,
        selected: Boolean = false
    ): NavBarIconPreview {
        val drawable = if (single) {
            NavigationBarIconConfig.previewSingleDrawable(this, entry, item)
        } else {
            NavigationBarIconConfig.previewDrawable(this, entry, item, selected)
        }
        val desc = getString(
            when {
                single -> item.titleRes
                selected -> R.string.navigation_icon_selected
                else -> R.string.navigation_icon_normal
            }
        )
        return NavBarIconPreview(drawable, desc) {
            if (single) {
                showComposeChoiceListDialog(desc, listOf(getString(R.string.select_image), getString(R.string.delete))) { index ->
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
            } else {
                showComposeChoiceListDialog(desc, listOf(getString(R.string.select_image), getString(R.string.delete))) { index ->
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

    // W3.2：名称改由 Dialog 输入框回传（原 editingDialog.findViewWithTag("name") View 取值废弃）
    private fun saveEditingPackage(name: String) {
        val config = pendingConfig ?: return
        normalizeStandardBottomConfig(config)
        lifecycleScope.launch {
            kotlin.runCatching {
                withContext(Dispatchers.IO) {
                    NavigationBarIconConfig.addOrUpdate(config.copy(name = name.trim()), editingEntry)
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
        showComposeConfirmDialog(
            title = getString(R.string.delete),
            message = message,
            positiveText = getString(R.string.yes),
            negativeText = getString(R.string.no),
            dangerPositive = true,
            onPositive = {
                runAction(block)
            }
        )
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

/**
 * W3.2 编辑弹框行数据（原 buildEditView 的 optionRow View 行的数据化）
 */
data class NavBarEditRow(
    val title: String,
    val value: String,
    val onClick: () -> Unit
)

/**
 * W3.2 图标预览单元（原 previewButton/singlePreviewButton ImageView 的数据化）
 * drawable 由 NavigationBarIconConfig.preview*Drawable 生成，Compose 侧 AndroidView 包装渲染
 */
data class NavBarIconPreview(
    val drawable: Drawable?,
    val contentDesc: String,
    val onClick: () -> Unit
)

data class NavBarIconRow(
    @StringRes val titleRes: Int,
    val previews: List<NavBarIconPreview>
)

/**
 * W3.2：底栏主题编辑弹框（替代原 alert{customView=ScrollView 包 buildEditView}，MC-7 合规）
 * 纯壳设计：行数据/图标数据由 Activity provider 提供（读取 Activity.editVersion 建立重组依赖），
 * 配置交互回调直接走 Activity 方法，保存时回传名称（原 EditText findViewWithTag 取值废弃）
 */
class NavigationBarEditDialog : ComposeDialogFragment() {

    override val dialogSize: AppDialogSize = AppDialogSize.Management

    private var dialogTitle: String = ""
    private var initialName: String = ""
    private var rowsProvider: (() -> List<NavBarEditRow>)? = null
    private var iconRowsProvider: (() -> List<NavBarIconRow>)? = null
    private var onSave: ((String) -> Unit)? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                val style = rememberAppDialogStyle()
                CompositionLocalProvider(
                    LocalTextStyle provides LocalTextStyle.current.copy(fontFamily = style.bodyFontFamily)
                ) {
                    var name by rememberSaveable { mutableStateOf(initialName) }
                    AppDialogFrame(
                        title = dialogTitle,
                        content = {
                            Column {
                                OutlinedTextField(
                                    value = name,
                                    onValueChange = { name = it },
                                    label = { Text(stringResource(R.string.navigation_bar_name)) },
                                    singleLine = true,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp, vertical = 4.dp)
                                )
                                val rows = rowsProvider?.invoke().orEmpty()
                                val palette = rememberAppManagementPalette()
                                rows.forEach { row ->
                                    AppManagementListRow(
                                        title = row.title,
                                        subtitle = row.value,
                                        palette = palette,
                                        onClick = row.onClick
                                    )
                                }
                                val iconRows = iconRowsProvider?.invoke().orEmpty()
                                iconRows.forEach { iconRow ->
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 16.dp, vertical = 6.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = stringResource(iconRow.titleRes),
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurface,
                                            modifier = Modifier.weight(1f)
                                        )
                                        iconRow.previews.forEach { preview ->
                                            AndroidView(
                                                factory = { context ->
                                                    ImageView(context).apply {
                                                        scaleType = ImageView.ScaleType.CENTER_INSIDE
                                                        val d = context.resources.displayMetrics.density
                                                        setPadding((8 * d).toInt(), (8 * d).toInt(), (8 * d).toInt(), (8 * d).toInt())
                                                    }
                                                },
                                                update = { iv ->
                                                    iv.contentDescription = preview.contentDesc
                                                    iv.setImageDrawable(preview.drawable)
                                                },
                                                modifier = Modifier
                                                    .size(44.dp)
                                                    .clip(RoundedCornerShape(8.dp))
                                                    .clickable(onClick = preview.onClick)
                                            )
                                            Spacer(modifier = Modifier.width(8.dp))
                                        }
                                    }
                                }
                            }
                        },
                        actions = {
                            val miuixPalette = style.toMiuixPalette()
                            LegadoMiuixActionButton(
                                text = stringResource(R.string.cancel),
                                palette = miuixPalette,
                                onClick = { dismissAllowingStateLoss() },
                                cornerRadius = style.actionRadius
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            LegadoMiuixActionButton(
                                text = stringResource(R.string.ok),
                                palette = miuixPalette,
                                onClick = {
                                    dismissAllowingStateLoss()
                                    onSave?.invoke(name)
                                },
                                primary = true,
                                cornerRadius = style.actionRadius
                            )
                        }
                    )
                }
            }
        }
    }

    companion object {
        fun create(
            title: String,
            initialName: String,
            rowsProvider: () -> List<NavBarEditRow>,
            iconRowsProvider: () -> List<NavBarIconRow>,
            onSave: (String) -> Unit
        ): NavigationBarEditDialog {
            return NavigationBarEditDialog().apply {
                this.dialogTitle = title
                this.initialName = initialName
                this.rowsProvider = rowsProvider
                this.iconRowsProvider = iconRowsProvider
                this.onSave = onSave
            }
        }
    }
}

/**
 * W3.2：底栏栏项管理弹框壳（替代原 alert{customView 塞 ComposeView}，MC-7 合规）
 * 内容复用既有 [BottomNavItemsManageContent]（拖拽排序+可见开关），确定按钮仅关闭（改动即时保存）
 */
class NavigationBarItemsDialog : ComposeDialogFragment() {

    override val dialogSize: AppDialogSize = AppDialogSize.Form

    private var initialItems: List<MainBottomNavConfig.ItemState> = emptyList()
    private var onItemsChange: ((List<MainBottomNavConfig.ItemState>) -> Unit)? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val changeCallback = onItemsChange
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                val style = rememberAppDialogStyle()
                CompositionLocalProvider(
                    LocalTextStyle provides LocalTextStyle.current.copy(fontFamily = style.bodyFontFamily)
                ) {
                    AppDialogFrame(
                        title = stringResource(R.string.bottom_bar_items_manage),
                        content = {
                            BottomNavItemsManageContent(
                                initialItems = initialItems,
                                onItemsChange = { changeCallback?.invoke(it) }
                            )
                        },
                        actions = {
                            LegadoMiuixActionButton(
                                text = stringResource(R.string.ok),
                                palette = style.toMiuixPalette(),
                                onClick = { dismissAllowingStateLoss() },
                                primary = true,
                                cornerRadius = style.actionRadius
                            )
                        }
                    )
                }
            }
        }
    }

    companion object {
        fun create(
            initialItems: List<MainBottomNavConfig.ItemState>,
            onItemsChange: (List<MainBottomNavConfig.ItemState>) -> Unit
        ): NavigationBarItemsDialog {
            return NavigationBarItemsDialog().apply {
                this.initialItems = initialItems
                this.onItemsChange = onItemsChange
            }
        }
    }
}
