package io.legado.app.ui.main.rss

import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.data.appDb
import io.legado.app.data.entities.SourceGroupCover
import io.legado.app.ui.adapter.FolderItem
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.ui.widget.compose.AppDialogFrame
import io.legado.app.ui.widget.compose.AppDialogSize
import io.legado.app.ui.widget.compose.BookCoverImage
import io.legado.app.ui.widget.compose.ComposeDialogFragment
import io.legado.app.ui.widget.compose.LegadoComposeTheme
import io.legado.app.ui.widget.compose.LegadoMiuixActionButton
import io.legado.app.ui.widget.compose.rememberAppDialogStyle
import io.legado.app.ui.widget.compose.toMiuixPalette
import io.legado.app.ui.widget.image.CoverImageView
import io.legado.app.utils.FileUtils
import io.legado.app.utils.MD5Utils
import io.legado.app.utils.externalFiles
import io.legado.app.utils.inputStream
import io.legado.app.utils.readUri
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import splitties.init.appCtx
import java.io.FileOutputStream

/**
 * 订阅文件夹封面编辑弹框（rss-folder-cover-dialog-align：对齐书架 GroupEditDialog 的能力与样式）。
 *
 * - 标准 Compose 弹框体系：ComposeDialogFragment + AppDialogFrame + rememberAppDialogStyle + LegadoMiuixActionButton
 * - 封面预览：BookCoverImage（DETAIL 样式 90×120dp，对齐书架弹框），点预览区选图
 * - 选图：HandleFileContract(IMAGE)，http/https 直存 URL（对齐书架语义）；本地文件 readUri→MD5→covers/
 * - 编辑态：coverPath 仅暂存，确定才落库（非空 upsert / 空 delete，delete 幂等）；取消/dismiss 零落库
 * - 存储：source_group_covers 表 KIND_RSS + groupKey（沿用特殊 key 约定），弹框不直改 RssFragment 状态，
 *   落库后经 [onCoverApplied] 回调宿主 patch folderComposeCovers 触发网格重组
 */
class RssFolderCoverDialog() : ComposeDialogFragment() {

    constructor(folder: FolderItem) : this() {
        arguments = Bundle().apply {
            putString("groupKey", folder.groupKey)
            putString("groupLabel", folder.groupLabel)
        }
    }

    private val groupKey: String
        get() = requireArguments().getString("groupKey").orEmpty()
    private val groupLabel: String
        get() = requireArguments().getString("groupLabel").orEmpty()

    private var coverPath by mutableStateOf<String?>(null)

    /** 封面落库后的宿主回调：(groupKey, coverPath)；恢复默认时 path 为 null */
    var onCoverApplied: ((String, String?) -> Unit)? = null

    private val selectImage = registerForActivityResult(HandleFileContract()) { result ->
        val uri = result.uri ?: return@registerForActivityResult
        if (uri.scheme?.lowercase() in listOf("http", "https")) {
            coverPath = uri.toString()
            return@registerForActivityResult
        }
        readUri(uri) { fileDoc, inputStream ->
            try {
                var file = requireContext().externalFiles
                val suffix = if (fileDoc.name.contains(".9.png", true)) {
                    ".9.png"
                } else {
                    "." + fileDoc.name.substringAfterLast(".")
                }
                val fileName = result.uri.inputStream(requireContext()).getOrThrow().use { tmp ->
                    MD5Utils.md5Encode(tmp) + suffix
                }
                file = FileUtils.createFileIfNotExist(file, "covers", fileName)
                FileOutputStream(file).use { outputStream ->
                    inputStream.copyTo(outputStream)
                }
                coverPath = file.absolutePath
            } catch (e: Exception) {
                appCtx.toastOnUi(e.localizedMessage)
            }
        }
    }

    override val dialogSize: AppDialogSize = AppDialogSize.Form
    override val dialogGravity: Int = Gravity.CENTER
    override val dialogWindowAnimations: Int = R.style.AnimDialogCenter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        // 初始化预览：从数据库当前值起步（不受上次未保存操作影响）
        viewLifecycleOwner.lifecycleScope.launch {
            kotlin.runCatching {
                appDb.sourceGroupCoverDao.getSourceGroupCover(
                    SourceGroupCover.KIND_RSS, groupKey
                )
            }.onSuccess { cover ->
                coverPath = cover?.cover
            }
        }
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                LegadoComposeTheme {
                    RssFolderCoverContent(
                        folderLabel = groupLabel,
                        coverPath = coverPath,
                        onSelectImage = {
                            selectImage.launch {
                                mode = HandleFileContract.IMAGE
                            }
                        },
                        onRestoreDefault = { coverPath = null },
                        onSave = ::applyCover,
                        onCancel = { dismiss() }
                    )
                }
            }
        }
    }

    private fun applyCover() {
        val nextPath = coverPath
        viewLifecycleOwner.lifecycleScope.launch {
            kotlin.runCatching {
                withContext(Dispatchers.IO) {
                    if (nextPath != null) {
                        appDb.sourceGroupCoverDao.upsert(
                            SourceGroupCover(SourceGroupCover.KIND_RSS, groupKey, nextPath)
                        )
                    } else {
                        // 恢复默认：delete 幂等，无需区分原值是否存在
                        appDb.sourceGroupCoverDao.delete(SourceGroupCover.KIND_RSS, groupKey)
                    }
                }
            }.onFailure {
                if (it is CancellationException) throw it
                appCtx.toastOnUi(it.localizedMessage)
            }.onSuccess {
                onCoverApplied?.invoke(groupKey, nextPath)
                dismiss()
            }
        }
    }
}

@Composable
private fun RssFolderCoverContent(
    folderLabel: String,
    coverPath: String?,
    onSelectImage: () -> Unit,
    onRestoreDefault: () -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit
) {
    val style = rememberAppDialogStyle()
    val palette = style.toMiuixPalette()

    AppDialogFrame(
        title = stringResource(R.string.img_cover),
        content = {
            Column(modifier = Modifier.fillMaxWidth()) {
                // 封面预览区（对齐书架 GroupEditDialog：点击选图）
                Box(
                    modifier = Modifier
                        .size(width = 90.dp, height = 120.dp)
                        .align(Alignment.CenterHorizontally)
                        .clickable { onSelectImage() }
                ) {
                    BookCoverImage(
                        path = coverPath,
                        name = folderLabel,
                        author = null,
                        sourceOrigin = null,
                        modifier = Modifier.fillMaxSize(),
                        style = CoverImageView.CoverStyle.DETAIL,
                        loadOnlyWifi = false,
                        preferThumb = false,
                        allowNameOverlay = true,
                        fillBounds = true
                    )
                }
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = stringResource(R.string.select_image),
                    color = style.secondaryText,
                    fontFamily = style.bodyFontFamily,
                    modifier = Modifier.align(Alignment.CenterHorizontally)
                )
            }
        },
        actions = {
            // 编辑中已有封面内容时才提供恢复默认（无内容点击无意义，防误触）
            if (!coverPath.isNullOrBlank()) {
                LegadoMiuixActionButton(
                    text = stringResource(R.string.restore_default),
                    palette = palette,
                    onClick = onRestoreDefault,
                    cornerRadius = style.actionRadius
                )
            }
            LegadoMiuixActionButton(
                text = stringResource(R.string.cancel),
                palette = palette,
                onClick = onCancel,
                cornerRadius = style.actionRadius
            )
            LegadoMiuixActionButton(
                text = stringResource(R.string.ok),
                palette = palette,
                onClick = onSave,
                primary = true,
                cornerRadius = style.actionRadius
            )
        }
    )
}
