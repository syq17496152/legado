package io.legado.app.ui.config

import android.net.Uri
import android.os.Bundle
import androidx.compose.foundation.layout.Box
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
import androidx.lifecycle.lifecycleScope
import androidx.viewbinding.ViewBinding
import io.legado.app.R
import io.legado.app.base.BaseActivity
import io.legado.app.base.attachComposeContent
import io.legado.app.base.composeShell
import io.legado.app.help.AppCloudStorage
import io.legado.app.help.config.CoverCollectionManager
import io.legado.app.lib.cloud.CloudStorageType
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.ui.theme.LegadoTheme
import io.legado.app.ui.widget.components.GlassTopAppBar
import io.legado.app.ui.widget.components.MenuAction
import io.legado.app.ui.widget.components.TopBarActionRow
import io.legado.app.ui.widget.compose.AppManagementMenuAction
import io.legado.app.ui.widget.compose.showComposeChoiceListDialog
import io.legado.app.ui.widget.compose.showComposeTextInputDialog
import io.legado.app.utils.externalFiles
import io.legado.app.utils.getFile
import io.legado.app.utils.startActivity
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

class CoverCollectionManageActivity : BaseActivity<ViewBinding>() {

    // 原 activity_cover_collection_manage.xml 已退役（CE-b，含顶栏包 §4.1）：
    // composeShell 合成壳 + attachComposeContent 单源；顶栏由 installGlassTopBar 的运行时注入改为页内直接渲染
    override val binding: ViewBinding by lazy { composeShell(this) }

    private val isNightState = mutableStateOf(false)
    private val entriesState = mutableStateOf<List<CoverCollectionManager.Entry>>(emptyList())
    private var cloudContainerId: String? = null
    // W5.3：S3 容器按钮显隐改 Compose 状态驱动（对齐 TopBarManage 3.1 模式），顶栏迁 installGlassTopBar
    private var containerActionVisible by mutableStateOf(false)
    private val importZip = registerForActivityResult(HandleFileContract()) {
        it.uri?.let { uri -> importZip(uri) }
    }
    private val exportZip = registerForActivityResult(HandleFileContract()) {
        it.uri?.let { toastOnUi(R.string.export_success) }
    }

    // ui-theme-governance-polish P6：管理族宿主接入背景透明度（1.5 封闭清单成员）
    override fun manageBackgroundAlphaEnabled(): Boolean = true

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        initComposeContent()
        loadCollections()
    }

    @OptIn(ExperimentalMaterial3Api::class)
    private fun initComposeContent() {
        binding.root.attachComposeContent {
            Column(modifier = Modifier.fillMaxSize()) {
                // ---- 顶栏（原 installGlassTopBar 注入的 GlassTopAppBar：标题/返回/条件云容器动作逐项不变）----
                LegadoTheme {
                    GlassTopAppBar(
                        title = getString(R.string.cover_collection_manage),
                        navIcon = Icons.AutoMirrored.Filled.ArrowBack,
                        onNavClick = { finish() },
                        actions = {
                            TopBarActionRow(
                                if (containerActionVisible) {
                                    listOf(
                                        MenuAction(
                                            iconRes = R.drawable.ic_outline_cloud_24,
                                            title = getString(R.string.s3_bucket),
                                            alwaysShow = true
                                        ) { showContainerSelector() }
                                    )
                                } else {
                                    emptyList()
                                }
                            )
                        }
                    )
                }
                // ---- 原 recycler_view 位置（清壳后手拼的 ComposeView）----
                // 修复：原 XML 的 `btn_add`（「创建图集」）是**可见但不可点击**的重复按钮（页面内已有真实入口），
                // 且以 match_parent 高度的手拼 ComposeView 挤在根 LinearLayout 尾部 ⇒ 内容区被挤占 96px；
                // 换装后由 `weight(1f)` 承载屏幕内容，该残留死节点随壳退役（已入 updateLog）
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                ) {
                    CoverCollectionManageScreen(
                        isNight = isNightState.value,
                        entries = entriesState.value,
                        onTabChanged = { night ->
                            isNightState.value = night
                            loadCollections()
                        },
                        onItemClick = ::openDetail,
                        itemActions = ::coverActions,
                        onAddClick = ::showAddActions
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        updateContainerMenu()
        loadCollections()
    }

    private fun updateContainerMenu() {
        val containers = AppCloudStorage.listContainers().filter { it.enabled }
        if (AppCloudStorage.type != CloudStorageType.S3) {
            cloudContainerId = containers.firstOrNull()?.id
            containerActionVisible = false
            return
        }
        cloudContainerId = AppCloudStorage.selectedContainer(CLOUD_SCOPE)?.id
        containerActionVisible = true
    }

    private fun showContainerSelector() {
        lifecycleScope.launch {
            val containers = withContext(Dispatchers.IO) { AppCloudStorage.listContainers().filter { it.enabled } }
            if (containers.isEmpty()) {
                toastOnUi(R.string.cloud_storage_config_required)
                return@launch
            }
            val selected = cloudContainerId ?: AppCloudStorage.selectedContainer(CLOUD_SCOPE)?.id
            showComposeChoiceListDialog(
                title = getString(R.string.s3_bucket),
                labels = containers.map(AppCloudStorage::containerDisplayLabel),
                selectedIndex = containers.indexOfFirst { it.id == selected }
            ) { index ->
                val container = containers.getOrNull(index) ?: return@showComposeChoiceListDialog
                if (container.id == selected) return@showComposeChoiceListDialog
                AppCloudStorage.selectContainer(CLOUD_SCOPE, container.id)
                cloudContainerId = container.id
                updateContainerMenu()
                loadCollections()
            }
        }
    }

    private fun loadCollections() {
        lifecycleScope.launch {
            val items = CoverCollectionManager.loadEntries(isNightState.value, cloudContainerId, CLOUD_SCOPE)
            entriesState.value = items
        }
    }

    private fun showAddActions() {
        showComposeChoiceListDialog(
            title = getString(R.string.add),
            labels = arrayListOf(
                getString(R.string.cover_collection_add),
                getString(R.string.cover_collection_import_zip)
            )
        ) { index ->
            when (index) {
                0 -> showCreateDialog()
                1 -> importZip.launch {
                    mode = HandleFileContract.FILE
                    allowExtensions = arrayOf("zip")
                }
            }
        }
    }

    private fun showCreateDialog() {
        showComposeTextInputDialog(
            title = getString(R.string.cover_collection_name),
            hint = getString(R.string.cover_collection_name),
            onPositive = { name ->
                lifecycleScope.launch {
                    kotlin.runCatching {
                        CoverCollectionManager.create(name, isNightState.value)
                    }.onFailure {
                        toastOnUi(it.localizedMessage)
                    }
                    loadCollections()
                }
            }
        )
    }

    private fun showRenameDialog(entry: CoverCollectionManager.Entry) {
        if (entry.source == CoverCollectionManager.Source.REMOTE) return
        showComposeTextInputDialog(
            title = getString(R.string.cover_collection_name),
            hint = getString(R.string.cover_collection_name),
            initialValue = entry.collection.name,
            onPositive = { name ->
                lifecycleScope.launch {
                    kotlin.runCatching { CoverCollectionManager.rename(entry.collection, name) }
                        .onFailure { toastOnUi(it.localizedMessage) }
                    loadCollections()
                }
            }
        )
    }

    private fun importZip(uri: Uri) {
        lifecycleScope.launch {
            kotlin.runCatching {
                val file = withContext(Dispatchers.IO) {
                    val dir = externalFiles.getFile("coverCollectionImports").apply { mkdirs() }
                    val target = File(dir, "cover_${System.currentTimeMillis()}.zip")
                    contentResolver.openInputStream(uri)?.use { input ->
                        FileOutputStream(target).use { output -> input.copyTo(output) }
                    }
                    target
                }
                CoverCollectionManager.importPackage(this@CoverCollectionManageActivity, file, isNightState.value)
            }.onFailure {
                toastOnUi(it.localizedMessage)
            }
            loadCollections()
        }
    }

    private fun openDetail(entry: CoverCollectionManager.Entry) {
        if (entry.source == CoverCollectionManager.Source.REMOTE) {
            runAction { CoverCollectionManager.download(entry, cloudContainerId, CLOUD_SCOPE) }
            return
        }
        val item = entry.collection
        startActivity<CoverCollectionDetailActivity> {
            putExtra("isNight", item.isNight)
            putExtra("id", item.id)
        }
    }

    private fun exportCollection(entry: CoverCollectionManager.Entry) {
        lifecycleScope.launch {
            kotlin.runCatching { CoverCollectionManager.exportZip(entry) }
                .onSuccess { zipFile ->
                    exportZip.launch {
                        mode = HandleFileContract.EXPORT
                        showUploadUrl = false
                        fileData = HandleFileContract.FileData(zipFile.name, zipFile, "application/zip")
                    }
                }
                .onFailure { toastOnUi(it.localizedMessage) }
        }
    }

    private fun runAction(block: suspend () -> Unit) {
        lifecycleScope.launch {
            kotlin.runCatching { block() }
                .onFailure { toastOnUi(it.localizedMessage) }
            loadCollections()
        }
    }

    private fun coverActions(entry: CoverCollectionManager.Entry): List<AppManagementMenuAction> {
        val actions = buildList {
            if (entry.source != CoverCollectionManager.Source.REMOTE) add(CoverAction.RENAME)
            if (entry.source != CoverCollectionManager.Source.REMOTE) add(CoverAction.EXPORT)
            if (entry.source != CoverCollectionManager.Source.REMOTE) add(CoverAction.UPLOAD)
            if (entry.source != CoverCollectionManager.Source.LOCAL) add(CoverAction.DOWNLOAD)
            if (entry.source != CoverCollectionManager.Source.REMOTE) add(CoverAction.DELETE_LOCAL)
            if (entry.source != CoverCollectionManager.Source.LOCAL) add(CoverAction.DELETE_REMOTE)
        }
        return actions.map { action ->
            AppManagementMenuAction(
                text = getString(action.titleRes),
                danger = action == CoverAction.DELETE_LOCAL || action == CoverAction.DELETE_REMOTE
            ) {
                when (action) {
                    CoverAction.RENAME -> showRenameDialog(entry)
                    CoverAction.EXPORT -> exportCollection(entry)
                    CoverAction.UPLOAD -> runAction { CoverCollectionManager.upload(entry, cloudContainerId, CLOUD_SCOPE) }
                    CoverAction.DOWNLOAD -> runAction { CoverCollectionManager.download(entry, cloudContainerId, CLOUD_SCOPE) }
                    CoverAction.DELETE_LOCAL -> runAction { CoverCollectionManager.deleteLocal(entry) }
                    CoverAction.DELETE_REMOTE -> runAction { CoverCollectionManager.deleteRemote(entry, cloudContainerId, CLOUD_SCOPE) }
                }
            }
        }
    }

    private companion object {
        private const val CLOUD_SCOPE = "coverCollection"
    }

    private enum class CoverAction(val titleRes: Int) {
        RENAME(R.string.edit),
        EXPORT(R.string.theme_export_zip),
        UPLOAD(R.string.theme_upload_remote),
        DOWNLOAD(R.string.theme_download_local),
        DELETE_LOCAL(R.string.theme_delete_local),
        DELETE_REMOTE(R.string.theme_delete_remote)
    }
}
