package io.legado.app.ui.config

import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import androidx.core.net.toUri
import android.view.Menu
import android.view.MenuItem
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.History
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.jaredrummler.android.colorpicker.ColorPickerDialog
import com.jaredrummler.android.colorpicker.ColorPickerDialogListener
import io.legado.app.R
import io.legado.app.base.BaseActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.viewbinding.ViewBinding
import io.legado.app.base.attachComposeContent
import io.legado.app.base.composeShell
import io.legado.app.ui.theme.LegadoTheme
import io.legado.app.ui.widget.components.GlassTopAppBar
import io.legado.app.ui.widget.components.TopBarActionRow
import io.legado.app.constant.EventBus
import io.legado.app.help.AppCloudStorage
import io.legado.app.help.config.AppearanceKitManager
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.TopBarConfig
import io.legado.app.lib.cloud.CloudStorageType
import io.legado.app.ui.book.cache.WebDavTaskManager
import io.legado.app.ui.book.cache.WebDavTaskStatus
import io.legado.app.ui.book.cache.WebDavTaskType
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.ui.image.ImageCropContract
import io.legado.app.ui.theme.bodyTertiary
import io.legado.app.ui.widget.components.MenuAction
import io.legado.app.ui.widget.components.installGlassTopBar
import io.legado.app.ui.widget.compose.AppManagementMenuAction
import io.legado.app.ui.widget.compose.AppPackageManageItemCard
import io.legado.app.ui.widget.compose.AppPackageManageScreen
import io.legado.app.ui.widget.compose.rememberAppManagementPalette
import io.legado.app.ui.widget.compose.showComposeActionListDialog
import io.legado.app.ui.widget.compose.showComposeConfirmDialog
import io.legado.app.ui.widget.compose.showComposeNumberPickerDialog
import io.legado.app.ui.widget.compose.showComposeSingleChoiceDialog
import io.legado.app.utils.ImageCropHelper
import io.legado.app.utils.ImageTypeUtils
import io.legado.app.utils.dismissDialogFragment
import io.legado.app.utils.externalFiles
import io.legado.app.utils.getFile
import io.legado.app.utils.observeEvent
import io.legado.app.utils.postEvent
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class TopBarManageActivity : BaseActivity<ViewBinding>(),
    ColorPickerDialogListener {

    // 原 activity_theme_manage.xml 已退役（CE-a #8）：composeShell 合成壳 + attachComposeContent 单源；
    // 顶栏由 installGlassTopBar 运行时注入改为**页内直接渲染**
    override val binding: ViewBinding by lazy { composeShell(this) }

    private var entriesState by mutableStateOf<List<TopBarConfig.Entry>>(emptyList())
    private var activeDirNameState by mutableStateOf(TopBarConfig.DEFAULT_DIR_NAME)
    private var isNightMode by mutableStateOf(false)
    private var summaryTextState by mutableStateOf("")
    private var editingEntry: TopBarConfig.Entry? = null
    private var pendingConfig: TopBarConfig.Config? = null
    private var pendingColorTarget = 0
    private var pendingWallpaperCropRequest: ImageCropHelper.Request? = null
    private val handledWebDavTasks = mutableSetOf<String>()
    private var loadVersion = 0
    private var cloudContainerId: String? = null
    // W3.1：S3 容器按钮动态显隐改为 Compose 状态驱动（原 AppCompatImageButton isVisible 方式随顶栏迁移废弃）
    private var containerActionVisible by mutableStateOf(false)
    // F136①：远端套装下载期间的卡片忙态（key = dirName_isNightMode，值 = 触发该次下载的动作）
    private var busyStates by mutableStateOf<Map<String, TopBarBusyAction>>(emptyMap())
    // F136②：保存回执（分级：已保存 / 已保存并全局生效）
    private var receiptState by mutableStateOf<TopBarSaveReceipt?>(null)
    private val dateFormat by lazy { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()) }

    private val importPackage = registerForActivityResult(HandleFileContract()) {
        it.uri?.let(::importPackage)
    }

    private val exportPackage = registerForActivityResult(HandleFileContract()) {
        it.uri?.let { toastOnUi(R.string.export_success) }
    }

    private val selectWallpaper = registerForActivityResult(HandleFileContract()) {
        it.uri?.let(::startWallpaperCrop)
    }

    private val cropWallpaper = registerForActivityResult(ImageCropContract()) { result ->
        pendingWallpaperCropRequest = null
        if (result == null) return@registerForActivityResult
        if (File(result.path).exists()) {
            pendingConfig?.let { config ->
                config.wallpaperPath = result.path
                config.wallpaperCropLeft = result.cropLeft
                config.wallpaperCropTop = result.cropTop
                config.wallpaperCropRight = result.cropRight
                config.wallpaperCropBottom = result.cropBottom
            }
            refreshEditDialog()
        } else {
            toastOnUi(getString(R.string.image_crop_failed, getString(R.string.unknown)))
        }
    }

    // ui-theme-governance-polish P6：管理族宿主接入背景透明度（1.5 封闭清单成员）
    override fun manageBackgroundAlphaEnabled(): Boolean = true

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        initTopBar()
        initComposeContent()
        loadPackages()
        observeWebDavTasks()
    }

    override fun onResume() {
        super.onResume()
        invalidateOptionsMenu()
    }

    // T8③（theme-arch-gap）：RECREATE 由基类统一订阅（整体重建后 loadPackages 随 onCreate 重载），
    // 自订阅冗余已删

    @OptIn(ExperimentalMaterial3Api::class)
    private fun initComposeContent() {
        binding.root.attachComposeContent {
            Column(modifier = Modifier.fillMaxSize()) {
                // ---- 顶栏（原 installGlassTopBar 注入的 GlassTopAppBar：标题/返回/动作逐项不变）----
                LegadoTheme {
                    GlassTopAppBar(
                        title = getString(R.string.top_bar_manage),
                        navIcon = Icons.AutoMirrored.Filled.ArrowBack,
                        onNavClick = { finish() },
                        actions = { TopBarActionRow(topBarActions()) }
                    )
                }
                Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
                TopBarManageScreen(
                    entries = entriesState,
                    activeDirName = activeDirNameState,
                    isNightMode = isNightMode,
                    summaryText = summaryTextState,
                    dateFormat = dateFormat,
                    onSwitchDayNight = { night ->
                        if (night != isNightMode) {
                            isNightMode = night
                            loadPackages()
                        }
                    },
                    onAdd = ::showAddDialog,
                    onApply = ::applyPackage,
                    onEdit = { entry -> showEditDialog(entry) },
                    entryActions = ::entryActions,
                    entryBusy = { entry -> busyStates[entryKey(entry)] },
                    busyText = stringResource(R.string.top_bar_downloading),
                    receipt = receiptState,
                    onReceiptDismiss = { receiptState = null }
                )
                }
            }
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

    // W3.1：顶栏运行时替换为 GlassTopAppBar（透壁纸语义，W1 模式）。S3 容器/同步任务保留一级图标语义，
    // 容器按钮显隐由 containerActionVisible 状态驱动（替代原 AppCompatImageButton.isVisible）
    private fun initTopBar() {
        // 顶栏改为页内渲染（见 initComposeContent）；此处只做容器按钮等状态准备
        updateContainerMenu()
    }

    /** 顶栏动作（原 `installGlassTopBar` 的 actionsProvider：S3 容器按云类型显隐 + 同步任务）。 */
    private fun topBarActions(): List<MenuAction> =
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
                ) { showTopBarSyncTasks() }
            )
        }

    override fun onCompatCreateOptionsMenu(menu: Menu): Boolean {
        return true
    }

    override fun onCompatOptionsItemSelected(item: MenuItem): Boolean {
        return super.onCompatOptionsItemSelected(item)
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

    // W3.1：容器切换 ModernActionPopup(View 锚点弹出)→showComposeActionListDialog（对齐 ThemeManage W1 模式）
    private fun showContainerSelector() {
        lifecycleScope.launch {
            val containers = withContext(Dispatchers.IO) {
                AppCloudStorage.listContainers().filter { it.enabled }
            }
            if (containers.isEmpty()) {
                toastOnUi(R.string.cloud_storage_config_required)
                return@launch
            }
            val selected = cloudContainerId
                ?: AppCloudStorage.selectedContainer(CLOUD_SCOPE)?.id
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
                    TopBarConfig.loadEntries(
                        this@TopBarManageActivity,
                        isNightMode,
                        includeRemote = true,
                        cloudContainerId,
                        CLOUD_SCOPE
                    )
                }
            }.onSuccess {
                if (version != loadVersion || isFinishing || isDestroyed) return@onSuccess
                entriesState = it
                activeDirNameState = TopBarConfig.activeDirName(isNightMode)
                summaryTextState = getString(R.string.top_bar_manage_summary)
            }.onFailure {
                if (version != loadVersion || isFinishing || isDestroyed) return@onFailure
                summaryTextState = it.localizedMessage.orEmpty()
            }
        }
    }

    // F136①：卡片忙态 key 与列表 key 同口径（dirName_isNightMode）
    private fun entryKey(entry: TopBarConfig.Entry): String =
        "${entry.dirName}_${entry.config.isNightMode}"

    private fun beginBusy(entry: TopBarConfig.Entry, action: TopBarBusyAction) {
        busyStates = busyStates + (entryKey(entry) to action)
    }

    private fun endBusy(entry: TopBarConfig.Entry) {
        busyStates = busyStates - entryKey(entry)
    }

    private fun showEditDialog(entry: TopBarConfig.Entry?) {
        val base = entry ?: TopBarConfig.Entry(
            TopBarConfig.defaultConfig(this, isNightMode).copy(name = nextPackageName()),
            TopBarConfig.Source.LOCAL,
            ""
        )
        if (entry != null && base.dirName == TopBarConfig.DEFAULT_DIR_NAME) {
            toastOnUi(R.string.navigation_bar_default_readonly)
            return
        }
        if (base.localDir == null && entry != null && base.source == TopBarConfig.Source.REMOTE) {
            // F136①：远端套装下载期间按钮转忙态，避免无反馈导致连点重复下载
            if (busyStates.containsKey(entryKey(base))) return
            beginBusy(base, TopBarBusyAction.EDIT)
            lifecycleScope.launch {
                kotlin.runCatching {
                    withContext(Dispatchers.IO) {
                        TopBarConfig.download(base, cloudContainerId, CLOUD_SCOPE)
                    }
                }.onSuccess {
                    showEditDialog(it)
                }.onFailure {
                    toastOnUi(it.localizedMessage)
                }
                endBusy(base)
            }
            return
        }
        editingEntry = base
        pendingConfig = base.config.copy()
        showPendingEditDialog()
    }

    private fun refreshEditDialog() {
        if (pendingConfig == null) return
        showPendingEditDialog()
    }

    private fun showPendingEditDialog() {
        val config = pendingConfig ?: return
        dismissDialogFragment<TopBarEditDialog>()
        showDialogFragment(
            TopBarEditDialog.create(
                config = config,
                onNameChanged = { name ->
                    pendingConfig?.name = name
                },
                onStyleChanged = { style ->
                    pendingConfig?.style = style
                    if (style == TopBarConfig.STYLE_REGULAR) {
                        pendingConfig?.let { c ->
                            if (c.backgroundColor == null) {
                                c.backgroundColor = TopBarConfig.defaultBackgroundColor(c.isNightMode)
                            }
                            if (c.cornerScale == null) {
                                c.cornerScale = 0f
                            }
                            if (c.tagBarColor == null) {
                                c.tagBarColor = Color.WHITE
                            }
                            if (c.tagBarAlpha == 100) {
                                c.tagBarAlpha = 0
                            }
                        }
                    }
                },
                onShowStyleSelector = {
                    showComposeSingleChoiceDialog(
                        title = getString(R.string.top_bar_style),
                        labels = listOf(
                            getString(R.string.top_bar_style_default),
                            getString(R.string.top_bar_style_regular)
                        ),
                        selectedIndex = if (pendingConfig?.style == TopBarConfig.STYLE_REGULAR) 1 else 0
                    ) { index ->
                        val newStyle = when (index) {
                            1 -> TopBarConfig.STYLE_REGULAR
                            else -> TopBarConfig.STYLE_DEFAULT
                        }
                        pendingConfig?.style = newStyle
                        if (newStyle == TopBarConfig.STYLE_REGULAR) {
                            pendingConfig?.let { c ->
                                if (c.backgroundColor == null) {
                                    c.backgroundColor = TopBarConfig.defaultBackgroundColor(c.isNightMode)
                                }
                                if (c.cornerScale == null) {
                                    c.cornerScale = 0f
                                }
                                if (c.tagBarColor == null) {
                                    c.tagBarColor = Color.WHITE
                                }
                                if (c.tagBarAlpha == 100) {
                                    c.tagBarAlpha = 0
                                }
                            }
                        }
                        refreshEditDialog()
                    }
                },
                onShowCornerScalePicker = { current ->
                    showComposeNumberPickerDialog(
                        title = getString(R.string.top_bar_corner_scale),
                        value = ((current ?: 1f).coerceIn(0f, 3f) * 10).toInt(),
                        minValue = 0,
                        maxValue = 30,
                        isDecimalMode = true,
                        onValue = { value ->
                            pendingConfig?.cornerScale = (value / 10f).coerceIn(0f, 3f)
                            refreshEditDialog()
                        }
                    )
                },
                onShowColorPicker = { target, color ->
                    pendingColorTarget = target
                    ColorPickerDialog.newBuilder()
                        .setDialogId(target)
                        .setColor(color)
                        .setShowAlphaSlider(false)
                        .setDialogType(ColorPickerDialog.TYPE_CUSTOM)
                        .show(this@TopBarManageActivity)
                },
                onShowWallpaperSelector = {
                    showWallpaperSelector()
                },
                onShowWallpaperAlphaPicker = { current ->
                    showComposeNumberPickerDialog(
                        title = getString(R.string.top_bar_wallpaper_alpha),
                        value = current,
                        minValue = 0,
                        maxValue = 100,
                        onValue = { value ->
                            pendingConfig?.wallpaperAlpha = value.coerceIn(0, 100)
                            refreshEditDialog()
                        }
                    )
                },
                onShowFilterDefaultSelector = { current ->
                    showComposeSingleChoiceDialog(
                        title = getString(R.string.top_bar_filter_default),
                        labels = listOf(
                            getString(R.string.top_bar_filter_default_collapsed),
                            getString(R.string.top_bar_filter_default_expanded)
                        ),
                        selectedIndex = if (current) 1 else 0
                    ) { index ->
                        pendingConfig?.expandFiltersByDefault = index == 1
                        refreshEditDialog()
                    }
                },
                onToggleFilterToggleHidden = { hidden ->
                    pendingConfig?.hideFilterToggleWhenExpanded = hidden
                    refreshEditDialog()
                },
                onShowTagBarAlphaPicker = { current ->
                    showComposeNumberPickerDialog(
                        title = getString(R.string.top_bar_tag_bar_alpha),
                        value = current,
                        minValue = 0,
                        maxValue = 100,
                        onValue = { value ->
                            pendingConfig?.tagBarAlpha = value.coerceIn(0, 100)
                            refreshEditDialog()
                        }
                    )
                },
                onShowTagSelectedAlphaPicker = { current ->
                    showComposeNumberPickerDialog(
                        title = getString(R.string.top_bar_tag_selected_alpha),
                        value = current,
                        minValue = 0,
                        maxValue = 100,
                        onValue = { value ->
                            pendingConfig?.tagSelectedAlpha = value.coerceIn(0, 100)
                            refreshEditDialog()
                        }
                    )
                },
                onToggleSearchInDefaultStyle = { enabled ->
                    pendingConfig?.showSearchInDefaultStyle = enabled
                    refreshEditDialog()
                },
                onSave = { name ->
                    pendingConfig?.name = name.trim()
                    saveEditingPackage()
                },
                onCancel = {}
            )
        )
    }

    private fun showWallpaperSelector() {
        val hasWallpaper = !pendingConfig?.wallpaperPath.isNullOrBlank()
        val options = buildList {
            add(getString(R.string.theme_image_select))
            if (hasWallpaper) add(getString(R.string.theme_image_delete))
        }
        showComposeActionListDialog(
            title = getString(R.string.top_bar_wallpaper),
            labels = options
        ) { index ->
            if (index == 0) {
                selectWallpaper.launch {
                    mode = HandleFileContract.IMAGE
                    title = getString(R.string.top_bar_wallpaper)
                }
            } else {
                pendingConfig?.wallpaperPath = null
                clearPendingWallpaperCrop()
                refreshEditDialog()
            }
        }
    }

    private fun entryActions(entry: TopBarConfig.Entry): List<AppManagementMenuAction> {
        val actions = buildList {
            add(Action.APPLY)
            if (entry.dirName != TopBarConfig.DEFAULT_DIR_NAME) {
                add(Action.EDIT)
                add(Action.EXPORT)
                if (entry.source != TopBarConfig.Source.REMOTE) add(Action.UPLOAD)
                if (entry.source != TopBarConfig.Source.LOCAL) add(Action.DOWNLOAD)
                if (entry.source != TopBarConfig.Source.REMOTE) add(Action.DELETE_LOCAL)
                if (entry.source != TopBarConfig.Source.LOCAL) add(Action.DELETE_REMOTE)
                if (entry.source == TopBarConfig.Source.BOTH) add(Action.DELETE_BOTH)
            }
        }
        return actions.map { action ->
            AppManagementMenuAction(
                text = getString(action.titleRes),
                danger = action in setOf(Action.DELETE_LOCAL, Action.DELETE_REMOTE, Action.DELETE_BOTH)
            ) {
                when (action) {
                    Action.APPLY -> applyPackage(entry)
                    Action.EDIT -> showEditDialog(entry)
                    Action.EXPORT -> exportPackage(entry)
                    Action.UPLOAD -> enqueueUpload(entry)
                    Action.DOWNLOAD -> runAction {
                        TopBarConfig.download(entry, cloudContainerId, CLOUD_SCOPE)
                    }
                    Action.DELETE_LOCAL -> confirmDelete(
                        entry,
                        getString(R.string.navigation_bar_delete_local_confirm)
                    ) {
                        TopBarConfig.deleteLocal(entry)
                        postEvent(EventBus.TOP_BAR_CHANGED, entry.config.isNightMode)
                    }
                    Action.DELETE_REMOTE -> confirmDelete(
                        entry,
                        getString(R.string.navigation_bar_delete_remote_confirm)
                    ) {
                        TopBarConfig.deleteRemote(entry, cloudContainerId, CLOUD_SCOPE)
                    }
                    Action.DELETE_BOTH -> confirmDelete(
                        entry,
                        getString(R.string.navigation_bar_delete_both_confirm)
                    ) {
                        TopBarConfig.delete(entry, cloudContainerId, CLOUD_SCOPE)
                        postEvent(EventBus.TOP_BAR_CHANGED, entry.config.isNightMode)
                    }
                }
            }
        }
    }

    private fun enqueueUpload(entry: TopBarConfig.Entry) {
        val queued = enqueueUploadTask(entry)
        toastOnUi(
            if (queued) R.string.cache_manage_upload_queued
            else R.string.cache_manage_webdav_task_duplicate
        )
        if (queued) {
            showTopBarSyncTasks()
        }
    }

    private fun enqueueUploadIfNeeded(entry: TopBarConfig.Entry): Boolean {
        if (!AppConfig.syncThemePackages) return false
        return enqueueUploadTask(entry)
    }

    private fun enqueueUploadTask(entry: TopBarConfig.Entry): Boolean {
        return WebDavTaskManager.enqueueUpload(
            key = "top_bar_upload:${entry.config.isNightMode}:${entry.dirName}",
            name = entry.config.name,
            type = WebDavTaskType.TOP_BAR_PACKAGE_UPLOAD,
            runningMessage = getString(R.string.navigation_bar_upload)
        ) {
            TopBarConfig.upload(entry, cloudContainerId, CLOUD_SCOPE)
        }
    }

    private fun observeWebDavTasks() {
        seedHandledWebDavTasks(WebDavTaskType.TOP_BAR_PACKAGE_UPLOAD)
        lifecycleScope.launch {
            WebDavTaskManager.states.collectLatest { states ->
                var shouldReload = false
                var failedMessage: String? = null
                states.values
                    .filter { it.type == WebDavTaskType.TOP_BAR_PACKAGE_UPLOAD }
                    .filter {
                        it.status == WebDavTaskStatus.COMPLETED ||
                            it.status == WebDavTaskStatus.FAILED
                    }
                    .forEach { state ->
                        if (handledWebDavTasks.add(
                                webDavTaskHandleKey(state.key, state.status)
                            )
                        ) {
                            shouldReload = true
                            if (state.status == WebDavTaskStatus.FAILED) {
                                failedMessage = state.message
                            }
                        }
                    }
                if (shouldReload) {
                    loadPackages()
                    failedMessage?.let {
                        toastOnUi(getString(R.string.theme_sync_failed, it))
                    }
                }
            }
        }
    }

    private fun seedHandledWebDavTasks(type: WebDavTaskType) {
        WebDavTaskManager.states.value.values
            .filter { it.type == type }
            .filter {
                it.status == WebDavTaskStatus.COMPLETED ||
                    it.status == WebDavTaskStatus.FAILED
            }
            .forEach { handledWebDavTasks.add(webDavTaskHandleKey(it.key, it.status)) }
    }

    private fun webDavTaskHandleKey(key: String, status: WebDavTaskStatus): String {
        return "$key:$status"
    }

    private fun showTopBarSyncTasks() {
        showPackageSyncTaskDialog(setOf(WebDavTaskType.TOP_BAR_PACKAGE_UPLOAD))
    }

    private fun applyPackage(entry: TopBarConfig.Entry) {
        // F136①：仅远端套装需先下载，此期间「应用」按钮转忙态
        val needsDownload = entry.source == TopBarConfig.Source.REMOTE
        if (needsDownload) {
            if (busyStates.containsKey(entryKey(entry))) return
            beginBusy(entry, TopBarBusyAction.APPLY)
        }
        lifecycleScope.launch {
            kotlin.runCatching {
                withContext(Dispatchers.IO) {
                    if (needsDownload) {
                        TopBarConfig.download(entry, cloudContainerId, CLOUD_SCOPE)
                    } else {
                        entry
                    }
                }
            }.onSuccess {
                TopBarConfig.apply(it)
                AppearanceKitManager.syncCurrentTopBarRef(it.config.isNightMode, it)
                postEvent(EventBus.TOP_BAR_CHANGED, it.config.isNightMode)
                loadPackages()
            }.onFailure {
                toastOnUi(it.localizedMessage)
            }
            if (needsDownload) endBusy(entry)
        }
    }

    private fun confirmDelete(
        entry: TopBarConfig.Entry,
        message: String,
        block: suspend () -> Unit
    ) {
        showComposeConfirmDialog(
            title = getString(R.string.delete),
            message = message,
            positiveText = getString(R.string.delete),
            negativeText = getString(R.string.cancel),
            dangerPositive = true,
            onPositive = { runAction(block) }
        )
    }

    private fun runAction(block: suspend () -> Unit) {
        lifecycleScope.launch {
            kotlin.runCatching { withContext(Dispatchers.IO) { block() } }
                .onSuccess { toastOnUi(R.string.success) }
                .onFailure { toastOnUi(it.localizedMessage) }
            loadPackages()
        }
    }

    private fun exportPackage(entry: TopBarConfig.Entry) {
        lifecycleScope.launch {
            kotlin.runCatching {
                withContext(Dispatchers.IO) { TopBarConfig.exportZip(entry) }
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
                val file = externalFiles.getFile(
                    "topBarImports",
                    "import_${System.currentTimeMillis()}.zip"
                )
                file.parentFile?.mkdirs()
                contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(file).use { output -> input.copyTo(output) }
                } ?: throw IllegalArgumentException(getString(R.string.theme_zip_read_failed))
                withContext(Dispatchers.IO) { TopBarConfig.importZip(file) }
            }.onSuccess {
                toastOnUi(R.string.success)
                loadPackages()
                if (enqueueUploadIfNeeded(it)) {
                    showTopBarSyncTasks()
                }
            }.onFailure {
                toastOnUi(it.localizedMessage)
            }
        }
    }

    private fun startWallpaperCrop(uri: Uri) {
        val metrics = resources.displayMetrics
        val sourceFile = copyWallpaperSource(uri) ?: return
        val animatedFile = sourceFile.takeIf(ImageTypeUtils::isAnimatedImage)
        if (animatedFile == null) {
            sourceFile.delete()
        }
        val request = ImageCropHelper.buildRequest(
            context = this,
            sourceUri = animatedFile?.toUri() ?: uri,
            requestCode = REQUEST_WALLPAPER,
            aspectWidth = metrics.widthPixels.coerceAtLeast(1),
            aspectHeight = (220 * metrics.density).toInt().coerceAtLeast(1),
            dirName = "topBarWallpapers",
            prefix = "top_bar",
            targetWidth = 1600
        )
        pendingWallpaperCropRequest = request
        cropWallpaper.launch(
            if (animatedFile != null) {
                request.params.copy(
                    outputPath = animatedFile.absolutePath,
                    viewportOnly = true
                )
            } else {
                clearPendingWallpaperCrop()
                request.params
            }
        )
    }

    private fun copyWallpaperSource(uri: Uri): File? {
        return kotlin.runCatching {
            val dir = externalFiles.getFile("topBarWallpapers").apply { mkdirs() }
            val suffix = if (
                contentResolver.getType(uri).equals("image/gif", ignoreCase = true) ||
                uri.lastPathSegment?.substringBefore('?')?.endsWith(".gif", ignoreCase = true) == true
            ) {
                "gif"
            } else {
                "img"
            }
            val file = File(dir, "top_bar_source_${System.currentTimeMillis()}.$suffix")
            contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(file).use { output -> input.copyTo(output) }
            } ?: error(getString(R.string.error_image_url_empty))
            file.takeIf { it.exists() && it.length() > 0L }
                ?: error(getString(R.string.error_decode_bitmap))
        }.onFailure {
            toastOnUi(getString(R.string.image_crop_failed, it.localizedMessage ?: getString(R.string.unknown)))
        }.getOrNull()
    }

    private fun clearPendingWallpaperCrop() {
        pendingConfig?.wallpaperCropLeft = null
        pendingConfig?.wallpaperCropTop = null
        pendingConfig?.wallpaperCropRight = null
        pendingConfig?.wallpaperCropBottom = null
    }

    private fun nextPackageName(): String {
        val base = getString(R.string.top_bar_custom_name)
        val usedNames = entriesState.map { it.config.name }.toSet()
        if (base !in usedNames) return base
        for (index in 2..999) {
            val name = "$base $index"
            if (name !in usedNames) return name
        }
        return "$base ${System.currentTimeMillis()}"
    }

    private fun saveEditingPackage() {
        val config = pendingConfig ?: return
        val oldEntry = editingEntry
        lifecycleScope.launch {
            kotlin.runCatching {
                withContext(Dispatchers.IO) {
                    TopBarConfig.addOrUpdate(
                        config,
                        oldEntry.takeIf { it?.dirName != TopBarConfig.DEFAULT_DIR_NAME }
                    )
                }
            }.onSuccess {
                // F136②：回执分级——仅当本次保存会即时生效（默认套装 / 当前应用套装）才提示「全局生效」
                val applied = oldEntry?.dirName == TopBarConfig.DEFAULT_DIR_NAME ||
                    it.dirName == TopBarConfig.activeDirName(it.config.isNightMode)
                if (applied) {
                    TopBarConfig.apply(it)
                    AppearanceKitManager.syncCurrentTopBarRef(it.config.isNightMode, it)
                    postEvent(EventBus.TOP_BAR_CHANGED, it.config.isNightMode)
                }
                receiptState = TopBarSaveReceipt(
                    id = System.currentTimeMillis(),
                    text = getString(
                        if (applied) R.string.top_bar_saved_applied else R.string.theme_saved_local
                    ),
                    applied = applied
                )
                loadPackages()
                if (enqueueUploadIfNeeded(it)) {
                    showTopBarSyncTasks()
                }
            }.onFailure {
                toastOnUi(it.localizedMessage)
            }
        }
    }

    override fun onColorSelected(dialogId: Int, color: Int) {
        val config = pendingConfig ?: return
        when (dialogId) {
            COLOR_BACKGROUND -> config.backgroundColor = color
            COLOR_TAG_BAR -> config.tagBarColor = color
            COLOR_TAG_SELECTED -> config.tagSelectedColor = color
        }
        refreshEditDialog()
    }

    override fun onDialogDismissed(dialogId: Int) {
        pendingColorTarget = 0
    }

    private enum class Action(val titleRes: Int) {
        APPLY(R.string.theme_apply),
        EDIT(R.string.edit),
        EXPORT(R.string.export),
        UPLOAD(R.string.navigation_bar_upload),
        DOWNLOAD(R.string.action_download),
        DELETE_LOCAL(R.string.theme_delete_local),
        DELETE_REMOTE(R.string.theme_delete_remote),
        DELETE_BOTH(R.string.theme_delete_both)
    }

    private companion object {
        private const val CLOUD_SCOPE = "theme"
        const val COLOR_TAG_BAR = 5101
        const val COLOR_TAG_SELECTED = 5102
        const val REQUEST_WALLPAPER = 5103
        const val COLOR_BACKGROUND = 5104
    }
}

// Compose screen

/** F136①：卡片忙态来源动作（远端套装下载由「应用」还是「编辑」触发）。 */
private enum class TopBarBusyAction { APPLY, EDIT }

/** F136②：保存回执（[applied] = 保存后是否即时全局生效，决定强调层级）。 */
private data class TopBarSaveReceipt(val id: Long, val text: String, val applied: Boolean)

@Composable
private fun TopBarManageScreen(
    entries: List<TopBarConfig.Entry>,
    activeDirName: String,
    isNightMode: Boolean,
    summaryText: String,
    dateFormat: SimpleDateFormat,
    onSwitchDayNight: (Boolean) -> Unit,
    onAdd: () -> Unit,
    onApply: (TopBarConfig.Entry) -> Unit,
    onEdit: (TopBarConfig.Entry) -> Unit,
    entryActions: (TopBarConfig.Entry) -> List<AppManagementMenuAction>,
    entryBusy: (TopBarConfig.Entry) -> TopBarBusyAction?,
    busyText: String,
    receipt: TopBarSaveReceipt?,
    onReceiptDismiss: () -> Unit
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
        bannerContent = if (receipt != null) {
            { TopBarSaveReceiptBanner(receipt, onReceiptDismiss) }
        } else {
            null
        }
    ) { palette ->
        items(
            entries,
            key = { "${it.dirName}_${it.config.isNightMode}" }
        ) { entry ->
            val isActive = entry.dirName == activeDirName
            val busy = entryBusy(entry)
            AppPackageManageItemCard(
                title = entry.config.name,
                info = topBarPackageInfo(entry, dateFormat),
                isActive = isActive,
                canEdit = entry.dirName != TopBarConfig.DEFAULT_DIR_NAME,
                applyText = if (isActive) appliedText else applyText,
                editText = editText,
                moreActions = entryActions(entry),
                palette = palette,
                onApply = { onApply(entry) },
                onEdit = { onEdit(entry) },
                applyLoading = busy == TopBarBusyAction.APPLY,
                editLoading = busy == TopBarBusyAction.EDIT,
                busyText = busyText
            )
        }
    }
}

/** F136②：保存回执条——应用中套装用 accent 强调，非应用套装走次级文字；3.6s 自动消退。 */
@Composable
private fun TopBarSaveReceiptBanner(
    receipt: TopBarSaveReceipt,
    onDismiss: () -> Unit
) {
    val palette = rememberAppManagementPalette()
    LaunchedEffect(receipt.id) {
        delay(3600)
        onDismiss()
    }
    Text(
        text = receipt.text,
        color = if (receipt.applied) palette.settings.accent else palette.settings.secondaryText,
        fontSize = MaterialTheme.typography.bodyTertiary.fontSize,
        fontWeight = if (receipt.applied) FontWeight.SemiBold else FontWeight.Normal,
        maxLines = 1,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, top = 6.dp, end = 16.dp)
    )
}

@Composable
private fun topBarPackageInfo(
    entry: TopBarConfig.Entry,
    dateFormat: SimpleDateFormat
): String {
    return buildString {
        append(topBarStyleLabel(entry.config.style))
        append(" \u00B7 ")
        append(stringResource(R.string.top_bar_tag_bar_alpha))
        append(" ")
        append(entry.config.tagBarAlpha)
        append("%")
        if (entry.config.updatedAt > 0) {
            append(" \u00B7 ")
            append(
                dateFormat.format(
                    Date(maxOf(entry.config.updatedAt, entry.remoteUpdatedAt))
                )
            )
        }
    }
}

@Composable
private fun topBarStyleLabel(style: String): String {
    return stringResource(
        when (style) {
            TopBarConfig.STYLE_REGULAR -> R.string.top_bar_style_regular
            else -> R.string.top_bar_style_default
        }
    )
}
