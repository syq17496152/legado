package io.legado.app.ui.widget.keyboard

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.constant.AppLog
import io.legado.app.constant.PreferKey
import io.legado.app.data.appDb
import io.legado.app.data.entities.KeyboardAssist
import io.legado.app.help.config.AppConfig
import io.legado.app.ui.theme.LegadoTheme
import io.legado.app.ui.widget.compose.AppDialogFrame
import io.legado.app.ui.widget.compose.AppDialogSize
import io.legado.app.ui.widget.compose.ComposeDialogFragment
import io.legado.app.ui.widget.compose.LegadoMiuixActionButton
import io.legado.app.ui.widget.compose.LegadoMiuixCard
import io.legado.app.ui.widget.compose.LegadoMiuixPalette
import io.legado.app.ui.widget.compose.rememberAppDialogStyle
import io.legado.app.ui.widget.compose.showComposeTextFormDialog
import io.legado.app.ui.widget.compose.toMiuixPalette
import io.legado.app.ui.widget.number.NumberPickerDialog
import io.legado.app.utils.putPrefInt
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch

/**
 * 辅助按键配置
 *
 * 迁移说明：原 BaseDialogFragment(R.layout.dialog_recycler_view) + RecyclerView
 * （KeyAdapter + ItemTouchHelper 拖拽）迁移为 ComposeDialogFragment + AppDialogFrame + LazyColumn：
 * - 点击条目编辑、删除即时生效，长按拖拽手柄排序（拖拽结束后按序号回写，逻辑与原 onClearView 等价）
 * - 原 Toolbar 副标题（显示行数）迁移为列表上方设置行，点击弹出 NumberPickerDialog
 * - 原菜单「添加」迁移为底部操作按钮
 * - [CallBack] 接口保持不变
 */
class KeyboardAssistsConfig(private val callBack: CallBack) : ComposeDialogFragment() {

    override val dialogSize: AppDialogSize = AppDialogSize.Management

    private var assistItems by mutableStateOf(emptyList<KeyboardAssist>())
    private var isMoved by mutableStateOf(false)
    private var showBoardLine by mutableIntStateOf(AppConfig.showBoardLine)

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
                    LaunchedEffect(Unit) {
                        appDb.keyboardAssistsDao.flowAll.catch {
                            AppLog.put("辅助按键配置获取数据失败\n${it.localizedMessage}", it)
                        }.flowOn(IO).collect {
                            if (!isMoved) assistItems = it
                        }
                    }
                    AppDialogFrame(
                        title = stringResource(R.string.assists_key_config),
                        scrollContent = false,
                        content = {
                            LegadoMiuixCard(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { showLineNumberPicker() },
                                color = style.fieldSurface,
                                contentColor = style.primaryText,
                                cornerRadius = style.actionRadius,
                                insidePadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp)
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = stringResource(R.string.setting_show_line_number),
                                        modifier = Modifier.weight(1f),
                                        color = style.primaryText,
                                        fontSize = MaterialTheme.typography.bodyMedium.fontSize
                                    )
                                    Text(
                                        text = stringResource(R.string.show_line_number, showBoardLine),
                                        color = style.secondaryText,
                                        fontSize = MaterialTheme.typography.bodySmall.fontSize
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            LazyColumn(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 420.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                itemsIndexed(
                                    items = assistItems,
                                    key = { _, item -> "${item.type}:${item.key}" },
                                    contentType = { _, _ -> "keyboardAssist" }
                                ) { _, item ->
                                    AssistKeyRow(
                                        item = item,
                                        palette = palette,
                                        onClick = { editKey(item) },
                                        onDelete = {
                                            lifecycleScope.launch(IO) {
                                                appDb.keyboardAssistsDao.delete(item)
                                            }
                                        },
                                        onMoveBy = { moveItem(item, it) },
                                        onMoveFinished = { moveFinished() }
                                    )
                                }
                            }
                        },
                        actions = {
                            LegadoMiuixActionButton(
                                text = stringResource(R.string.add),
                                palette = palette,
                                onClick = { editKey(null) },
                                primary = true
                            )
                        }
                    )
                }
            }
        }
    }

    /**
     * 原 Toolbar 标题点击：设置阅读界面上辅助按键显示行数
     */
    private fun showLineNumberPicker() {
        NumberPickerDialog(requireContext())
            .setTitle(getString(R.string.setting_show_line_number))
            .setMaxValue(5)
            .setMinValue(1)
            .setValue(showBoardLine)
            .show {
                showBoardLine = it
                putPrefInt(PreferKey.showBoardLine, it)
                callBack.requestLayout()
            }
    }

    /**
     * 以条目为标识移动（拖拽手柄闭包持有条目引用，按主键 type+key 定位，serialNo 在拖拽结束前不变）
     */
    private fun moveItem(item: KeyboardAssist, delta: Int) {
        val from = assistItems.indexOfFirst { it.type == item.type && it.key == item.key }
        if (from < 0) return
        val target = from + delta
        if (target !in assistItems.indices) return
        val list = assistItems.toMutableList()
        list.add(target, list.removeAt(from))
        assistItems = list
        isMoved = true
    }

    /**
     * 拖拽结束：重新编号 serialNo 并回写数据库（等价原 KeyAdapter.onClearView）
     */
    private fun moveFinished() {
        if (isMoved) {
            val list = assistItems.mapIndexed { index, item -> item.apply { serialNo = index + 1 } }
            assistItems = list
            lifecycleScope.launch(IO) {
                appDb.keyboardAssistsDao.update(*list.toTypedArray())
            }
        }
        isMoved = false
    }

    private fun editKey(keyboardAssist: KeyboardAssist?) {
        showComposeTextFormDialog(
            title = "辅助按键",
            labels = listOf("key", "value"),
            initialValues = listOf(keyboardAssist?.key ?: "", keyboardAssist?.value ?: "")
        ) { values ->
            lifecycleScope.launch(IO) {
                val newKeyboardAssist = KeyboardAssist(
                    key = values[0],
                    value = values[1]
                )
                if (keyboardAssist == null) {
                    newKeyboardAssist.serialNo = appDb.keyboardAssistsDao.maxSerialNo + 1
                    appDb.keyboardAssistsDao.insert(newKeyboardAssist)
                } else {
                    newKeyboardAssist.serialNo = keyboardAssist.serialNo
                    appDb.keyboardAssistsDao.delete(keyboardAssist)
                    appDb.keyboardAssistsDao.insert(newKeyboardAssist)
                }
            }
        }
    }

    interface CallBack {
         /**通知布局管理器重新布局*/
        fun requestLayout()
    }
}

@Composable
private fun AssistKeyRow(
    item: KeyboardAssist,
    palette: LegadoMiuixPalette,
    onClick: () -> Unit,
    onDelete: () -> Unit,
    onMoveBy: (Int) -> Unit,
    onMoveFinished: () -> Unit
) {
    LegadoMiuixCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        color = palette.surfaceVariant,
        contentColor = palette.primaryText,
        cornerRadius = palette.actionRadius ?: 9.dp,
        insidePadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AssistKeyDragHandle(
                tint = palette.secondaryText,
                onMoveBy = onMoveBy,
                onMoveFinished = onMoveFinished
            )
            Text(
                text = item.key,
                modifier = Modifier
                    .weight(1f)
                    .padding(vertical = 12.dp),
                color = palette.primaryText,
                fontSize = MaterialTheme.typography.bodyMedium.fontSize,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Icon(
                painter = painterResource(R.drawable.ic_clear_all),
                contentDescription = stringResource(R.string.delete),
                tint = palette.secondaryText,
                modifier = Modifier
                    .size(36.dp)
                    .clickable(onClick = onDelete)
                    .padding(8.dp)
            )
        }
    }
}

/**
 * 拖拽排序手柄（长按拖动，按行高阈值逐位移动，参照 RuleSubScreen 既有实现）
 */
@Composable
private fun AssistKeyDragHandle(
    tint: Color,
    onMoveBy: (Int) -> Unit,
    onMoveFinished: () -> Unit
) {
    val density = LocalDensity.current
    val thresholdPx = with(density) { 52.dp.toPx() }
    var accumulatedY by remember { mutableFloatStateOf(0f) }
    Icon(
        painter = painterResource(R.drawable.ic_menu),
        contentDescription = null,
        tint = tint,
        modifier = Modifier
            .size(36.dp)
            .padding(8.dp)
            .pointerInput(Unit) {
                detectDragGesturesAfterLongPress(
                    onDragEnd = {
                        accumulatedY = 0f
                        onMoveFinished()
                    },
                    onDragCancel = {
                        accumulatedY = 0f
                        onMoveFinished()
                    },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        accumulatedY += dragAmount.y
                        while (accumulatedY >= thresholdPx) {
                            onMoveBy(1)
                            accumulatedY -= thresholdPx
                        }
                        while (accumulatedY <= -thresholdPx) {
                            onMoveBy(-1)
                            accumulatedY += thresholdPx
                        }
                    }
                )
            }
    )
}
