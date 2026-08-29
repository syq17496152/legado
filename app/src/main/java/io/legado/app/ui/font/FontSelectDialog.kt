package io.legado.app.ui.font

import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.constant.AppLog
import io.legado.app.constant.PreferKey
import io.legado.app.help.config.AppConfig
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.lib.dialogs.SelectItem
import io.legado.app.lib.permission.Permissions
import io.legado.app.lib.permission.PermissionsCompat
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.ui.theme.LegadoTheme
import io.legado.app.ui.widget.compose.AppDialogFrame
import io.legado.app.ui.widget.compose.AppDialogSize
import io.legado.app.ui.widget.compose.ComposeDialogFragment
import io.legado.app.ui.widget.compose.LegadoMiuixActionButton
import io.legado.app.ui.widget.compose.LegadoMiuixCard
import io.legado.app.ui.widget.compose.LegadoMiuixPalette
import io.legado.app.ui.widget.compose.rememberAppDialogStyle
import io.legado.app.ui.widget.compose.showComposeSingleChoiceDialog
import io.legado.app.ui.widget.compose.toMiuixPalette
import io.legado.app.utils.FileDoc
import io.legado.app.utils.FileUtils
import io.legado.app.utils.RealPathUtil
import io.legado.app.utils.cnCompare
import io.legado.app.utils.externalFiles
import io.legado.app.utils.getPrefString
import io.legado.app.utils.isContentScheme
import io.legado.app.utils.list
import io.legado.app.utils.listFileDocs
import io.legado.app.utils.printOnDebug
import io.legado.app.utils.putPrefString
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.launch
import java.io.File
import java.net.URLDecoder

/**
 * 字体选择对话框
 *
 * 迁移说明：原 BaseDialogFragment(R.layout.dialog_font_select)（Toolbar + RecyclerView + FontAdapter）
 * 整体迁移为 ComposeDialogFragment + AppDialogFrame + LazyColumn：
 * - 字体扫描/加载/合并/排序逻辑保持等价
 * - 字体项保留「字体自身渲染预览 + 当前字体勾选」
 * - 原 Toolbar 菜单两项（默认字体 / 其他文件夹）迁移为底部操作按钮
 */
class FontSelectDialog : ComposeDialogFragment(), FontAdapter.CallBack {

    override val dialogSize: AppDialogSize = AppDialogSize.Management

    private val fontRegex = Regex("(?i).*\\.[ot]tf")
    private var fonts by mutableStateOf(listOf<FileDoc>())

    private val selectFontDir = registerForActivityResult(HandleFileContract()) {
        it.uri?.let { uri ->
            if (uri.isContentScheme()) {
                putPrefString(PreferKey.fontFolder, uri.toString())
                val doc = DocumentFile.fromTreeUri(requireContext(), uri)
                if (doc != null) {
                    loadFontFiles(FileDoc.fromDocumentFile(doc))
                } else {
                    RealPathUtil.getPath(requireContext(), uri)?.let { path ->
                        loadFontFilesByPermission(path)
                    }
                }
            } else {
                uri.path?.let { path ->
                    putPrefString(PreferKey.fontFolder, path)
                    loadFontFilesByPermission(path)
                }
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                LegadoTheme {
                    val style = rememberAppDialogStyle()
                    val palette = style.toMiuixPalette()
                    val curName = remember {
                        val curFontPath = callBack?.curFontPath ?: ""
                        kotlin.runCatching {
                            URLDecoder.decode(curFontPath, "utf-8")
                        }.getOrNull()?.substringAfterLast(File.separator)
                    }
                    LaunchedEffect(Unit) {
                        initFontFolder()
                    }
                    AppDialogFrame(
                        title = stringResource(R.string.select_font),
                        scrollContent = false,
                        content = {
                            LazyColumn(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 460.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                items(fonts) { item ->
                                    FontItemRow(
                                        item = item,
                                        curName = curName,
                                        palette = palette,
                                        onClick = { onFontSelect(item) }
                                    )
                                }
                            }
                        },
                        actions = {
                            LegadoMiuixActionButton(
                                text = stringResource(R.string.default_font),
                                palette = palette,
                                onClick = { showSystemTypefaceDialog() }
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            LegadoMiuixActionButton(
                                text = stringResource(R.string.other_folder),
                                palette = palette,
                                onClick = { openFolder() }
                            )
                        }
                    )
                }
            }
        }
    }

    /**
     * 原标题（默认字体）菜单：系统字体单选
     */
    private fun showSystemTypefaceDialog() {
        val requireContext = requireContext()
        showComposeSingleChoiceDialog(
            title = getString(R.string.system_typeface),
            labels = requireContext.resources.getStringArray(R.array.system_typefaces).toList(),
            selectedIndex = AppConfig.systemTypefaces,
            positiveText = getString(android.R.string.ok),
            negativeText = getString(android.R.string.cancel),
            onPositive = { i ->
                AppConfig.systemTypefaces = i
                onDefaultFontChange()
            }
        )
    }

    private fun openFolder() {
        lifecycleScope.launch {
            val defaultPath = "SD${File.separator}Fonts"
            selectFontDir.launch {
                otherActions = arrayListOf(SelectItem(defaultPath, -1))
            }
        }
    }

    /**
     * 原启动加载逻辑（onFragmentCreated），迁移为 LaunchedEffect 调用
     */
    private fun initFontFolder() {
        val fontPath = getPrefString(PreferKey.fontFolder)
        if (fontPath.isNullOrEmpty()) {
            openFolder()
        } else {
            if (fontPath.isContentScheme()) {
                val doc = DocumentFile.fromTreeUri(requireContext(), Uri.parse(fontPath))
                if (doc?.canRead() == true) {
                    loadFontFiles(FileDoc.fromDocumentFile(doc))
                } else {
                    openFolder()
                }
            } else {
                loadFontFilesByPermission(fontPath)
            }
        }
    }

    private fun getLocalFonts(): ArrayList<FileDoc> {
        val path = FileUtils.getPath(requireContext().externalFiles, "font")
        return File(path).listFileDocs {
            it.name.matches(fontRegex)
        }
    }

    private fun loadFontFilesByPermission(path: String) {
        PermissionsCompat.Builder()
            .addPermissions(*Permissions.Group.STORAGE)
            .rationale(R.string.tip_perm_request_storage)
            .onGranted {
                loadFontFiles(
                    FileDoc.fromFile(File(path))
                )
            }
            .request()
    }

    private fun loadFontFiles(fileDoc: FileDoc) {
        Coroutine.async(scope = lifecycleScope) {
            val fontItems = fileDoc.list {
                it.name.matches(fontRegex)
            } ?: ArrayList()
            mergeFontItems(fontItems, getLocalFonts())
        }.onSuccess {
            fonts = it
        }.onError {
            AppLog.put("加载字体文件失败\n${it.localizedMessage}", it)
            toastOnUi("getFontFiles:${it.localizedMessage}")
        }
    }

    private fun mergeFontItems(
        items1: ArrayList<FileDoc>,
        items2: ArrayList<FileDoc>
    ): List<FileDoc> {
        val items = ArrayList(items1)
        items2.forEach { item2 ->
            var isInFirst = false
            items1.forEach for1@{ item1 ->
                if (item2.name == item1.name) {
                    isInFirst = true
                    return@for1
                }
            }
            if (!isInFirst) {
                items.add(item2)
            }
        }
        return items.sortedWith { o1, o2 ->
            o1.name.cnCompare(o2.name)
        }
    }

    override fun onFontSelect(docItem: FileDoc) {
        Coroutine.async(scope = lifecycleScope) {
            callBack?.selectFont(docItem.toString())
        }.onSuccess {
            dismissAllowingStateLoss()
        }
    }

    private fun onDefaultFontChange() {
        callBack?.selectFont("")
    }

    private val callBack: CallBack?
        get() = (parentFragment as? CallBack) ?: (activity as? CallBack)

    interface CallBack {
        fun selectFont(path: String)
        val curFontPath: String
        val applySystemTypefaceOnDefault: Boolean get() = false
    }
}

@Composable
private fun FontItemRow(
    item: FileDoc,
    curName: String?,
    palette: LegadoMiuixPalette,
    onClick: () -> Unit
) {
    val context = LocalContext.current
    val typeface = remember(item.uri) {
        kotlin.runCatching {
            if (item.isContentScheme) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.contentResolver
                        .openFileDescriptor(item.uri, "r")?.use {
                            Typeface.Builder(it.fileDescriptor).build()
                        }
                } else {
                    Typeface.createFromFile(RealPathUtil.getPath(context, item.uri))
                }
            } else {
                Typeface.createFromFile(item.uri.path!!)
            }
        }.onFailure {
            it.printOnDebug()
            AppLog.put("读取字体 ${item.name} 出错\n${it.localizedMessage}", it, true)
        }.getOrNull()
    }
    LegadoMiuixCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        color = palette.surfaceVariant,
        contentColor = palette.primaryText,
        cornerRadius = palette.actionRadius ?: 9.dp,
        insidePadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = item.name,
                modifier = Modifier.weight(1f),
                color = palette.primaryText,
                fontFamily = typeface?.let { FontFamily(it) },
                fontSize = MaterialTheme.typography.bodyMedium.fontSize,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (item.name == curName) {
                Spacer(modifier = Modifier.width(8.dp))
                Icon(
                    imageVector = Icons.Filled.Check,
                    contentDescription = null,
                    tint = palette.accent,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}
