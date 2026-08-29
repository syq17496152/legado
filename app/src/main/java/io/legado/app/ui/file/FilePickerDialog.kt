package io.legado.app.ui.file

import android.content.DialogInterface
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.viewModels
import io.legado.app.R
import io.legado.app.ui.file.HandleFileContract.Companion.FILE
import io.legado.app.ui.file.utils.FilePickerIcon
import io.legado.app.ui.widget.compose.AppDialogFrame
import io.legado.app.ui.widget.compose.AppDialogSize
import io.legado.app.ui.widget.compose.AppDialogStyle
import io.legado.app.ui.widget.compose.ComposeDialogFragment
import io.legado.app.ui.widget.compose.LegadoComposeTheme
import io.legado.app.ui.widget.compose.LegadoMiuixActionButton
import io.legado.app.ui.widget.compose.rememberAppDialogStyle
import io.legado.app.ui.widget.compose.showComposeTextFormDialog
import io.legado.app.ui.widget.compose.toMiuixPalette
import io.legado.app.utils.FileUtils
import io.legado.app.utils.toastOnUi
import java.io.File

/**
 * 文件选择器（View→Compose 迁移）
 * 双 RecyclerView（路径面包屑 + 文件列表）→ Compose Row(horizontalScroll) + LazyColumn；
 * 对外接口不变：[show]/[tag]/[CallBack]，浏览/选择/新建文件夹/onDismiss 结束宿主 Activity 语义保持一致。
 */
class FilePickerDialog() : ComposeDialogFragment() {

    companion object {
        const val tag = "FileChooserDialog"

        fun show(
            manager: FragmentManager,
            mode: Int = FILE,
            title: String? = null,
            initPath: String? = null,
            isShowHideDir: Boolean = false,
            allowExtensions: Array<String>? = null,
        ) {
            FilePickerDialog().apply {
                val bundle = Bundle()
                bundle.putInt("mode", mode)
                bundle.putString("title", title)
                bundle.putBoolean("isShowHideDir", isShowHideDir)
                bundle.putString("initPath", initPath)
                bundle.putStringArray("allowExtensions", allowExtensions)
                arguments = bundle
            }.show(manager, tag)
        }
    }

    override val dialogSize: AppDialogSize = AppDialogSize.Management

    override val dialogWindowAnimations: Int = R.style.AnimDialogFade

    private val viewModel by viewModels<FilePickerViewModel>()
    private val dirParent = ".."

    private var filesState by mutableStateOf<List<File>>(emptyList())
    private var selectFileState by mutableStateOf<File?>(null)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        viewModel.filesLiveData.observe(viewLifecycleOwner) {
            selectFileState = null
            filesState = it
        }
        viewModel.initData(arguments)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                LegadoComposeTheme {
                    FilePickerContent()
                }
            }
        }
    }

    private fun setResultData(path: String) {
        val data = Intent().setData(Uri.fromFile(File(path)))
        (parentFragment as? CallBack)?.onResult(data)
        (activity as? CallBack)?.onResult(data)
    }

    override fun onDismiss(dialog: DialogInterface) {
        super.onDismiss(dialog)
        activity?.finish()
    }

    interface CallBack {
        fun onResult(data: Intent)
    }

    @Composable
    private fun FilePickerContent() {
        val style = rememberAppDialogStyle()
        val palette = style.toMiuixPalette()
        var navVersion by remember { mutableIntStateOf(0) }
        val dialogTitle = arguments?.getString("title") ?: if (viewModel.isSelectDir) {
            stringResource(R.string.folder_chooser)
        } else {
            stringResource(R.string.file_chooser)
        }

        fun isFileEnabled(item: File): Boolean {
            if (item.isDirectory || item == viewModel.lastDir) return true
            if (viewModel.isSelectDir) return false
            return viewModel.allowExtensions?.let {
                it.isEmpty() || it.contains(FileUtils.getExtension(item.path))
            } ?: true
        }

        fun navigateTo(file: File?) {
            viewModel.upFiles(file)
            navVersion++
        }

        fun onFileClick(item: File) {
            when {
                item == viewModel.lastDir -> {
                    viewModel.subDocs.removeLastOrNull()
                    navigateTo(viewModel.subDocs.lastOrNull() ?: viewModel.rootDoc)
                }
                item.isDirectory -> {
                    viewModel.subDocs.add(item)
                    navigateTo(item)
                }
                viewModel.isSelectFile && isFileEnabled(item) -> {
                    selectFileState = item
                }
            }
        }

        fun onOkClick() {
            if (viewModel.isSelectDir) {
                viewModel.lastDir?.let {
                    setResultData(it.path)
                    dismissAllowingStateLoss()
                }
            } else {
                val file = selectFileState
                if (file == null) {
                    toastOnUi("请选择文件")
                } else {
                    setResultData(file.path)
                    dismissAllowingStateLoss()
                }
            }
        }

        fun showCreateFolderDialog() {
            showComposeTextFormDialog(
                title = getString(R.string.create_folder),
                labels = listOf("文件夹名"),
                initialValues = listOf(""),
                positiveText = getString(android.R.string.ok),
                negativeText = getString(android.R.string.cancel),
                validateInput = { values ->
                    if (values.getOrNull(0)?.trim().isNullOrBlank()) {
                        toastOnUi("文件夹名不能为空")
                        return@showComposeTextFormDialog false
                    }
                    true
                },
                onPositive = { values ->
                    values.getOrNull(0)?.trim()?.takeIf { it.isNotEmpty() }?.let {
                        viewModel.createFolder(it)
                    }
                }
            )
        }

        val subDocs = remember(navVersion) { viewModel.subDocs.toList() }

        AppDialogFrame(
            title = dialogTitle,
            scrollContent = false,
            content = {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    PathChip(
                        text = "root",
                        style = style,
                        onClick = {
                            viewModel.subDocs.clear()
                            navigateTo(viewModel.rootDoc)
                        }
                    )
                    subDocs.forEachIndexed { index, dir ->
                        PathChip(
                            text = dir.name,
                            style = style,
                            onClick = {
                                viewModel.subDocs = viewModel.subDocs
                                    .subList(0, index + 1)
                                    .toMutableList()
                                navigateTo(viewModel.subDocs.lastOrNull())
                            }
                        )
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 400.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    items(items = filesState, key = { it.absolutePath }) { file ->
                        FileRow(
                            file = file,
                            style = style,
                            selected = file == selectFileState,
                            enabled = isFileEnabled(file),
                            onClick = { onFileClick(file) }
                        )
                    }
                }
            },
            actions = {
                LegadoMiuixActionButton(
                    text = stringResource(R.string.create_folder),
                    palette = palette,
                    onClick = { showCreateFolderDialog() },
                    cornerRadius = style.actionRadius
                )
                Spacer(modifier = Modifier.width(8.dp))
                LegadoMiuixActionButton(
                    text = stringResource(R.string.ok),
                    palette = palette,
                    onClick = { onOkClick() },
                    primary = true,
                    cornerRadius = style.actionRadius
                )
            }
        )
    }

    @Composable
    private fun FileRow(
        file: File,
        style: AppDialogStyle,
        selected: Boolean,
        enabled: Boolean,
        onClick: () -> Unit
    ) {
        val isUpDir = file == viewModel.lastDir
        val iconBytes = when {
            isUpDir -> FilePickerIcon.getUpDir()
            file.isDirectory -> FilePickerIcon.getFolder()
            else -> FilePickerIcon.getFile()
        }
        val label = if (isUpDir) dirParent else file.name
        val textColor = if (enabled) style.primaryText else style.secondaryText
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    color = if (selected) style.accent.copy(alpha = 0.14f) else Color.Transparent,
                    shape = RoundedCornerShape(style.actionRadius)
                )
                .clickable(onClick = onClick)
                .padding(horizontal = 10.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val bitmap = remember(iconBytes) {
                BitmapFactory.decodeByteArray(iconBytes, 0, iconBytes.size)?.asImageBitmap()
            }
            if (bitmap != null) {
                Image(
                    bitmap = bitmap,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
            }
            Text(
                text = label,
                color = textColor,
                fontSize = 14.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }

    @Composable
    private fun PathChip(
        text: String,
        style: AppDialogStyle,
        onClick: () -> Unit
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = text,
                color = style.primaryText,
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .clip(RoundedCornerShape(style.actionRadius))
                    .clickable(onClick = onClick)
                    .padding(horizontal = 4.dp, vertical = 4.dp)
            )
            val arrowBitmap = rememberArrowBitmap()
            if (arrowBitmap != null) {
                Image(
                    bitmap = arrowBitmap,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp)
                )
            }
        }
    }

    @Composable
    private fun rememberArrowBitmap(): ImageBitmap? {
        return remember {
            val bytes = FilePickerIcon.getArrow()
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
        }
    }
}
