package io.legado.app.ui.book.group

import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.fragment.app.viewModels
import io.legado.app.R
import io.legado.app.data.entities.BookGroup
import io.legado.app.lib.dialogs.alert
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.ui.widget.compose.AppDialogFrame
import io.legado.app.ui.widget.compose.AppDialogSize
import io.legado.app.ui.widget.compose.AppDialogStyle
import io.legado.app.ui.widget.compose.ComposeDialogFragment
import io.legado.app.ui.widget.compose.LegadoComposeTheme
import io.legado.app.ui.widget.compose.LegadoMiuixActionButton
import io.legado.app.ui.widget.compose.BookCoverImage
import io.legado.app.ui.widget.compose.rememberAppDialogStyle
import io.legado.app.ui.widget.compose.toMiuixPalette
import io.legado.app.ui.widget.image.CoverImageView
import io.legado.app.utils.FileUtils
import io.legado.app.utils.MD5Utils
import io.legado.app.utils.externalFiles
import io.legado.app.utils.inputStream
import io.legado.app.utils.readUri
import io.legado.app.utils.toastOnUi
import splitties.init.appCtx
import java.io.FileOutputStream

class GroupEditDialog() : ComposeDialogFragment() {

    constructor(bookGroup: BookGroup? = null) : this() {
        arguments = Bundle().apply {
            putParcelable("group", bookGroup?.copy())
        }
    }

    private val viewModel by viewModels<GroupViewModel>()
    private var coverPath by mutableStateOf<String?>(null)

    private val selectImage = registerForActivityResult(HandleFileContract()) {
        val uri = it.uri ?: return@registerForActivityResult
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
                val fileName = it.uri.inputStream(requireContext()).getOrThrow().use { tmp ->
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
        @Suppress("DEPRECATION")
        val initialGroup: BookGroup? = arguments?.getParcelable("group")
        coverPath = initialGroup?.cover
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                LegadoComposeTheme {
                    GroupEditContent(
                        existingGroup = initialGroup,
                        coverPath = coverPath,
                        onSelectImage = {
                            selectImage.launch {
                                mode = HandleFileContract.IMAGE
                            }
                        },
                        onClearCover = { coverPath = null },
                        onSave = { name, sort, refresh, onlyRead ->
                            if (name.isBlank()) {
                                requireContext().toastOnUi("分组名称不能为空")
                                return@GroupEditContent
                            }
                            initialGroup?.let {
                                it.groupName = name
                                it.cover = coverPath
                                it.bookSort = sort
                                it.enableRefresh = refresh
                                it.onlyUpdateRead = onlyRead
                                viewModel.upGroup(it) { dismiss() }
                            } ?: viewModel.addGroup(
                                name, sort, refresh, onlyRead, coverPath
                            ) { dismiss() }
                        },
                        onDelete = {
                            initialGroup?.let { group ->
                                alert(R.string.delete, R.string.sure_del) {
                                    yesButton {
                                        viewModel.delGroup(group) { dismiss() }
                                    }
                                    noButton()
                                }
                            }
                        },
                        onCancel = { dismiss() }
                    )
                }
            }
        }
    }
}

@Composable
private fun GroupEditContent(
    existingGroup: BookGroup?,
    coverPath: String?,
    onSelectImage: () -> Unit,
    onClearCover: () -> Unit,
    onSave: (name: String, sort: Int, refresh: Boolean, onlyRead: Boolean) -> Unit,
    onDelete: () -> Unit,
    onCancel: () -> Unit
) {
    val style = rememberAppDialogStyle()
    val palette = style.toMiuixPalette()
    val sortOptions = stringArrayResource(R.array.book_sort)

    var groupName by remember { mutableStateOf(existingGroup?.groupName.orEmpty()) }
    // bookSort: -1=默认; spinner index = bookSort + 1
    var sortIndex by remember {
        mutableIntStateOf(
            ((existingGroup?.bookSort ?: -1) + 1).coerceIn(0, sortOptions.size - 1)
        )
    }
    var enableRefresh by remember { mutableStateOf(existingGroup?.enableRefresh ?: true) }
    var onlyUpdateRead by remember { mutableStateOf(existingGroup?.onlyUpdateRead ?: false) }

    val canDelete = existingGroup != null &&
        (existingGroup.groupId > 0 || existingGroup.groupId == Long.MIN_VALUE)

    AppDialogFrame(
        title = if (existingGroup == null) {
            stringResource(R.string.add_group)
        } else {
            stringResource(R.string.edit)
        },
        content = {
            Column(modifier = Modifier.fillMaxWidth()) {
                // 封面图
                Box(
                    modifier = Modifier
                        .size(width = 90.dp, height = 120.dp)
                        .clickable {
                            if (!coverPath.isNullOrBlank()) {
                                // 已有封面：选择切换或删除（简化为直接选图）
                                onSelectImage()
                            } else {
                                onSelectImage()
                            }
                        }
                ) {
                    BookCoverImage(
                        path = coverPath,
                        name = groupName,
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
                Spacer(modifier = Modifier.height(14.dp))

                OutlinedTextField(
                    value = groupName,
                    onValueChange = { groupName = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.group_name)) },
                    singleLine = true,
                    shape = RoundedCornerShape(style.actionRadius),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = style.primaryText,
                        unfocusedTextColor = style.primaryText,
                        focusedContainerColor = style.fieldSurface,
                        unfocusedContainerColor = style.fieldSurface,
                        cursorColor = style.accent,
                        focusedBorderColor = Color.Transparent,
                        unfocusedBorderColor = Color.Transparent,
                        focusedLabelColor = style.accent,
                        unfocusedLabelColor = style.secondaryText
                    ),
                    textStyle = LocalTextStyle.current.copy(
                        color = style.primaryText,
                        fontFamily = style.bodyFontFamily
                    )
                )
                Spacer(modifier = Modifier.height(12.dp))

                // 排序选择
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stringResource(R.string.sort),
                        color = style.primaryText,
                        fontFamily = style.bodyFontFamily
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    SortDropdown(
                        options = sortOptions.toList(),
                        selectedIndex = sortIndex,
                        onSelect = { sortIndex = it },
                        style = style
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))

                // 复选框
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { enableRefresh = !enableRefresh },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = enableRefresh,
                        onCheckedChange = { enableRefresh = it },
                        colors = CheckboxDefaults.colors(
                            checkedColor = style.accent,
                            uncheckedColor = style.secondaryText
                        )
                    )
                    Text(
                        text = stringResource(R.string.refresh),
                        color = style.primaryText,
                        fontFamily = style.bodyFontFamily
                    )
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onlyUpdateRead = !onlyUpdateRead },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = onlyUpdateRead,
                        onCheckedChange = { onlyUpdateRead = it },
                        colors = CheckboxDefaults.colors(
                            checkedColor = style.accent,
                            uncheckedColor = style.secondaryText
                        )
                    )
                    Text(
                        text = stringResource(R.string.only_update_read),
                        color = style.primaryText,
                        fontFamily = style.bodyFontFamily
                    )
                }
            }
        },
        actions = {
            if (canDelete) {
                LegadoMiuixActionButton(
                    text = stringResource(R.string.delete),
                    palette = palette,
                    onClick = onDelete,
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
                onClick = {
                    onSave(groupName, sortIndex - 1, enableRefresh, onlyUpdateRead)
                },
                primary = true,
                cornerRadius = style.actionRadius
            )
        }
    )
}

@Composable
private fun SortDropdown(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    style: AppDialogStyle
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        Text(
            text = options.getOrNull(selectedIndex) ?: options.first(),
            color = style.accent,
            fontFamily = style.bodyFontFamily,
            modifier = Modifier
                .clip(RoundedCornerShape(style.actionRadius))
                .background(style.fieldSurface)
                .clickable { expanded = true }
                .padding(horizontal = 12.dp, vertical = 6.dp)
        )
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            options.forEachIndexed { index, label ->
                DropdownMenuItem(
                    text = {
                        Text(
                            text = label,
                            color = style.primaryText,
                            fontFamily = style.bodyFontFamily
                        )
                    },
                    onClick = {
                        onSelect(index)
                        expanded = false
                    }
                )
            }
        }
    }
}
