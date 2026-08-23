package io.legado.app.ui.config

import android.net.Uri
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.ViewGroup
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.base.BaseActivity
import io.legado.app.databinding.ActivityCoverCollectionManageBinding
import io.legado.app.help.AppCloudStorage
import io.legado.app.help.config.CoverCollectionManager
import io.legado.app.lib.cloud.CloudStorageType
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.ui.widget.compose.AppManagementMenuAction
import io.legado.app.ui.widget.compose.showComposeChoiceListDialog
import io.legado.app.ui.widget.compose.showComposeTextInputDialog
import io.legado.app.utils.externalFiles
import io.legado.app.utils.getFile
import io.legado.app.utils.startActivity
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

class CoverCollectionManageActivity : BaseActivity<ActivityCoverCollectionManageBinding>() {

    override val binding by viewBinding(ActivityCoverCollectionManageBinding::inflate)

    private val isNightState = mutableStateOf(false)
    private val entriesState = mutableStateOf<List<CoverCollectionManager.Entry>>(emptyList())
    private var cloudContainerId: String? = null
    private var containerMenuItem: MenuItem? = null
    private val importZip = registerForActivityResult(HandleFileContract()) {
        it.uri?.let { uri -> importZip(uri) }
    }
    private val exportZip = registerForActivityResult(HandleFileContract()) {
        it.uri?.let { toastOnUi(R.string.export_success) }
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        initComposeContent()
        loadCollections()
    }

    private fun initComposeContent() {
        val container = binding.recyclerView.parent as? ViewGroup ?: return
        // Remove TabBar, RecyclerView, and AddButton (keep TitleBar at index 0)
        while (container.childCount > 1) {
            container.removeViewAt(1)
        }
        val cv = ComposeView(this).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setContent {
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
        container.addView(cv)
    }

    override fun onResume() {
        super.onResume()
        invalidateOptionsMenu()
        loadCollections()
    }

    override fun onCompatCreateOptionsMenu(menu: Menu): Boolean {
        containerMenuItem = menu.add(0, MENU_CONTAINER, 0, R.string.s3_bucket).apply {
            setIcon(R.drawable.ic_outline_cloud_24)
            setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        }
        updateContainerMenu()
        return true
    }

    override fun onCompatOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            MENU_CONTAINER -> {
                showContainerSelector()
                true
            }
            else -> super.onCompatOptionsItemSelected(item)
        }
    }

    private fun updateContainerMenu() {
        val item = containerMenuItem ?: return
        val containers = AppCloudStorage.listContainers().filter { it.enabled }
        if (AppCloudStorage.type != CloudStorageType.S3) {
            cloudContainerId = containers.firstOrNull()?.id
            item.isVisible = false
            return
        }
        cloudContainerId = AppCloudStorage.selectedContainer(CLOUD_SCOPE)?.id
        item.isVisible = true
        item.title = containers.firstOrNull { it.id == cloudContainerId }
            ?.let(AppCloudStorage::containerDisplayLabel)
            ?: getString(R.string.s3_bucket)
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
        private const val MENU_CONTAINER = 0x5401
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
