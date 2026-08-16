package io.legado.app.ui.widget.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.legado.app.R

/**
 * Confirm/Select 统一弹窗入口 DSL（公共组件库三期 Dialog 族）。
 *
 * 设计说明：把「确认弹窗」与「单选/列表弹窗」统一为一个 builder 入口。
 * - 链式 DSL：`body`/`confirm`/`dismiss` 配置「确认形态」（复用 [AppConfirmDialog]）；
 *   `items` 切换为「列表/单选形态」（M3 [AlertDialog] 包裹 [LazyColumn]）。
 * - 渲染时依据是否有 items 决定形态：有 items → 列表弹窗；否则 → 确认弹窗。
 * - 按钮文案空则回退现有资源 `R.string.ok`/`R.string.cancel`，不新增字符串、不硬编码中文。
 *
 * 规格：ui-standards §3.4 `AlertBuilder<D>` DSL（task 12.37，from 325506/legado-with-MD3-DIY）。
 */
class AppDialogBuilder(
    private val title: String,
    private val onDismiss: () -> Unit,
) {
    /** 正文文案（确认形态）。 */
    private var bodyText: String? by mutableStateOf(null)

    /** 确认按钮文案，空则回退 R.string.ok。 */
    private var confirmText: String by mutableStateOf("")

    /** 确认动作，空则仅关闭弹窗。 */
    private var confirmAction: (() -> Unit)? by mutableStateOf(null)

    /** 是否破坏性确认（确认钮 error 色）。 */
    private var confirmDestructive: Boolean by mutableStateOf(false)

    /** 取消按钮文案，空则回退 R.string.cancel。 */
    private var dismissText: String by mutableStateOf("")

    /** 取消动作，空则仅关闭弹窗。 */
    private var dismissAction: (() -> Unit)? by mutableStateOf(null)

    /** 列表项（非空时渲染「列表/单选形态」）。 */
    private var itemList: List<Pair<String, () -> Unit>>? by mutableStateOf(null)

    /** 设置正文文案（仅确认形态生效）。 */
    fun body(text: String?): AppDialogBuilder {
        bodyText = text
        return this
    }

    /**
     * 配置确认按钮；[text] 空时渲染回退 R.string.ok。
     * 点击后执行 [action] 并自动关闭弹窗。
     */
    fun confirm(
        text: String,
        destructive: Boolean = false,
        action: () -> Unit,
    ): AppDialogBuilder {
        confirmText = text
        confirmDestructive = destructive
        confirmAction = action
        return this
    }

    /**
     * 配置取消按钮；[text] 空时渲染回退 R.string.cancel。
     * 点击后执行 [action] 并自动关闭弹窗。
     */
    fun dismiss(
        text: String,
        action: () -> Unit,
    ): AppDialogBuilder {
        dismissText = text
        dismissAction = action
        return this
    }

    /** 触发「列表/单选」形态；每项执行动作后自动关闭弹窗。 */
    fun items(list: List<Pair<String, () -> Unit>>): AppDialogBuilder {
        itemList = list
        return this
    }

    /**
     * @Composable 渲染入口：有 items 渲染列表弹窗，否则渲染确认弹窗。
     */
    @Composable
    fun Show(modifier: Modifier = Modifier) {
        val list = itemList
        if (list != null) {
            ListDialog(list, modifier)
        } else {
            ConfirmDialog(modifier)
        }
    }

    /** 确认形态：复用 [AppConfirmDialog]，不重复实现 AlertDialog。 */
    @Composable
    private fun ConfirmDialog(modifier: Modifier) {
        val okText = stringResource(R.string.ok)
        val cancelText = stringResource(R.string.cancel)
        AppConfirmDialog(
            title = title,
            body = bodyText,
            confirmText = confirmText.ifBlank { okText },
            dismissText = dismissText.ifBlank { cancelText },
            onConfirm = {
                confirmAction?.invoke()
                onDismiss()
            },
            onDismiss = {
                dismissAction?.invoke()
                onDismiss()
            },
            destructive = confirmDestructive,
            modifier = modifier,
        )
    }

    /** 列表/单选形态：M3 AlertDialog（卡 18dp 圆角）包裹 LazyColumn。 */
    @Composable
    private fun ListDialog(
        list: List<Pair<String, () -> Unit>>,
        modifier: Modifier,
    ) {
        val maxHeight = LocalConfiguration.current.screenHeightDp.dp * 0.7f
        AlertDialog(
            onDismissRequest = onDismiss,
            modifier = modifier,
            shape = AppShapes.Card,
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            title = {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                )
            },
            text = {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = maxHeight)
                ) {
                    itemsIndexed(list) { _, (label, action) ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp)
                                .clickable {
                                    action()
                                    onDismiss()
                                }
                                .padding(horizontal = 16.dp, vertical = 12.dp)
                        ) {
                            Text(
                                text = label,
                                style = MaterialTheme.typography.bodyLarge,
                            )
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = onDismiss) {
                    Text(text = stringResource(R.string.cancel))
                }
            },
        )
    }
}

/**
 * Confirm/Select 统一弹窗 DSL 顶层入口。
 *
 * 用法：
 * ```
 * var showDialog by remember { mutableStateOf(false) }
 * if (showDialog) {
 *     AppDialogBuilder(title = "确认", onDismiss = { showDialog = false }) {
 *         body("操作不可恢复")
 *         confirm(destructive = true) { doDelete() }
 *         dismiss { }
 *     }
 * }
 * // 列表/单选形态
 * AppDialogBuilder(title = "选择分组", onDismiss = { showDialog = false }) {
 *     items(listOf("全部" to { pick("") }, "默认分组" to { pick("default") }))
 * }
 * ```
 *
 * 规格：ui-standards §3.4 `AlertBuilder<D>` DSL（task 12.37，from 325506/legado-with-MD3-DIY）。
 */
@Composable
fun AppDialogBuilder(
    title: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    build: AppDialogBuilder.() -> Unit,
) {
    val builder = remember(title) { AppDialogBuilder(title, onDismiss) }
    builder.apply(build)
    builder.Show(modifier)
}
