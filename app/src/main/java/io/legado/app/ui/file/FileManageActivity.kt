package io.legado.app.ui.file

import android.os.Bundle
import androidx.activity.addCallback
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.FileProvider
import io.legado.app.base.VMBaseActivity
import io.legado.app.constant.AppConst
import io.legado.app.R
import io.legado.app.databinding.ActivityFileManageBinding
import io.legado.app.ui.theme.LegadoTheme
import io.legado.app.utils.openFileUri
import io.legado.app.utils.viewbindingdelegate.viewBinding
import java.io.File

class FileManageActivity : VMBaseActivity<ActivityFileManageBinding, FileManageViewModel>() {

    override val binding by viewBinding(ActivityFileManageBinding::inflate)
    override val viewModel by viewModels<FileManageViewModel>()
    private val dirParent = ".."

    // Compose 桥接状态
    private var composeItems by mutableStateOf(listOf<FileManageDisplayItem>())
    private var composePathSegments by mutableStateOf(listOf<String>())
    private var searchQuery by mutableStateOf("")
    private var isLoading by mutableStateOf(true)
    private val currentFiles = arrayListOf<File>()

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        initComposeHost()
        viewModel.upFiles(viewModel.rootDoc)
        onBackPressedDispatcher.addCallback(this) {
            if (viewModel.lastDir != viewModel.rootDoc) {
                gotoLastDir()
                return@addCallback
            }
            finish()
        }
    }

    private fun initComposeHost() {
        binding.composeHost.setContent {
            LegadoTheme {
                FileManageScreen(
                    items = composeItems,
                    pathSegments = composePathSegments,
                    isLoading = isLoading,
                    searchQuery = searchQuery,
                    onSearchQueryChange = { searchQuery = it },
                    onBack = { finish() },
                    onUpDir = { gotoLastDir() },
                    onOpenDir = { openDir(it) },
                    onOpenFile = { openFile(it) },
                    onJumpPath = { jumpPath(it) },
                    onDelete = { delFile(it) }
                )
            }
        }
    }

    override fun observeLiveBus() {
        viewModel.filesLiveData.observe(this) {
            isLoading = false
            searchQuery = ""
            currentFiles.clear()
            currentFiles.addAll(it)
            refreshComposeItems()
        }
    }

    private fun refreshComposeItems() {
        composePathSegments = buildList {
            add(getString(R.string.root))
            addAll(viewModel.subDocs.map { it.name })
        }
        composeItems = currentFiles
            .filter { file ->
                val query = searchQuery
                file.name == dirParent || query.isEmpty() || file.name.contains(query)
            }
            .map { file ->
                FileManageDisplayItem(
                    path = file.absolutePath,
                    name = file.name,
                    isUpDir = file == viewModel.lastDir,
                    isDir = file.isDirectory
                )
            }
    }

    private fun gotoLastDir() {
        viewModel.subDocs.removeLastOrNull()
        viewModel.upFiles(viewModel.lastDir)
    }

    private fun openDir(index: Int) {
        val file = currentFiles.getOrNull(index) ?: return
        if (file == viewModel.lastDir) {
            gotoLastDir()
        } else if (file.isDirectory) {
            viewModel.subDocs.add(file)
            viewModel.upFiles(file)
        }
    }

    private fun openFile(index: Int) {
        val file = currentFiles.getOrNull(index) ?: return
        if (file.isFile) {
            openFileUri(
                FileProvider.getUriForFile(
                    this@FileManageActivity,
                    AppConst.authority,
                    file
                )
            )
        }
    }

    private fun jumpPath(index: Int) {
        if (index <= 0) {
            viewModel.subDocs.clear()
            viewModel.upFiles(viewModel.rootDoc)
        } else {
            viewModel.subDocs = viewModel.subDocs.subList(0, index)
            viewModel.upFiles(viewModel.subDocs.lastOrNull())
        }
    }

    private fun delFile(index: Int) {
        val file = currentFiles.getOrNull(index) ?: return
        if (file == viewModel.lastDir) return
        viewModel.delFile(file)
    }
}
