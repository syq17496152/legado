package io.legado.app.ui.urlrecord

import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.R
import io.legado.app.ui.widget.compose.AppDialogFrame
import io.legado.app.ui.widget.compose.AppDialogSize
import io.legado.app.ui.widget.compose.ComposeDialogFragment
import io.legado.app.ui.widget.compose.LegadoMiuixActionButton
import io.legado.app.ui.widget.compose.LegadoMiuixChoiceRow
import io.legado.app.ui.widget.compose.rememberAppDialogStyle
import io.legado.app.ui.widget.compose.toMiuixPalette
import androidx.compose.material3.MaterialTheme

/**
 * 访问记录四维过滤弹框（Compose 底部弹框，两级合一，替代原 6 级嵌套 selector）。
 *
 * 类别级：域名 / 来源 / 方法 / 状态 / 清除过滤；选类别后进入值级。
 * 值列表仍由宿主 Activity 协程从 DAO 拉取后经回调回填（`onCategorySelected` 的 emit），
 * 选值后把 filter 状态写回并触发 loadData（`onValueSelected`）。
 * 深度审查补充（blank）：当前值列表为空时显示「无可用值」占位提示。
 */
class UrlRecordFilterSheet : ComposeDialogFragment() {

    override val dialogSize: AppDialogSize = AppDialogSize.Form
    override val dialogGravity: Int = Gravity.BOTTOM

    private var onCategoryListener: ((Int, (List<String>) -> Unit) -> Unit)? = null
    private var onValueListener: ((Int, String) -> Unit)? = null
    private var onClearListener: (() -> Unit)? = null

    // 类别态 null / 值态非 null（属性级 Compose 状态，参照 UrlRecordActivity 桥接模式）
    private var currentValues by mutableStateOf<List<String>?>(null)
    private var selectedCategoryIndex by mutableStateOf(0)

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val args = arguments ?: Bundle()
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                LaunchedEffect(onCategoryListener == null) {
                    if (onCategoryListener == null) {
                        dismissAllowingStateLoss()
                    }
                }
                val style = rememberAppDialogStyle()
                val title = remember { args.getString(ARG_TITLE).orEmpty() }
                val categories = remember { args.getStringArrayList(ARG_CATEGORIES)?.toList().orEmpty() }
                val clearIndex = remember { args.getInt(ARG_CLEAR_INDEX, -1) }
                val values = currentValues
                AppDialogFrame(
                    title = if (values == null) {
                        title
                    } else {
                        categories.getOrNull(selectedCategoryIndex).orEmpty()
                    },
                    content = {
                        val palette = style.toMiuixPalette()
                        when {
                            values == null -> LazyColumn(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 420.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                itemsIndexed(categories) { index, label ->
                                    LegadoMiuixChoiceRow(
                                        text = label,
                                        selected = false,
                                        palette = palette,
                                        onClick = {
                                            if (index == clearIndex) {
                                                onClearListener?.invoke()
                                                dismissAllowingStateLoss()
                                            } else {
                                                selectedCategoryIndex = index
                                                onCategoryListener?.invoke(index) { list ->
                                                    currentValues = list.toList()
                                                }
                                            }
                                        },
                                        minHeight = 42.dp
                                    )
                                }
                            }

                            values.isEmpty() -> Text(
                                text = stringResource(R.string.url_record_no_values),
                                color = style.secondaryText,
                                fontSize = MaterialTheme.typography.bodyMedium.fontSize,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 120.dp),
                                // 简化说明: 顶部留白交给 AppDialogFrame 内部 Spacer，这里仅保证占位高度
                            )

                            else -> LazyColumn(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 420.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                itemsIndexed(values) { index, value ->
                                    LegadoMiuixChoiceRow(
                                        text = value,
                                        selected = false,
                                        palette = palette,
                                        onClick = {
                                            onValueListener?.invoke(selectedCategoryIndex, value)
                                            dismissAllowingStateLoss()
                                        },
                                        minHeight = 42.dp
                                    )
                                }
                            }
                        }
                    },
                    actions = {
                        val palette = style.toMiuixPalette()
                        if (values != null) {
                            LegadoMiuixActionButton(
                                text = stringResource(R.string.back),
                                palette = palette,
                                onClick = { currentValues = null },
                                cornerRadius = style.actionRadius
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                        }
                        LegadoMiuixActionButton(
                            text = stringResource(R.string.cancel),
                            palette = palette,
                            onClick = { dismissAllowingStateLoss() },
                            cornerRadius = style.actionRadius
                        )
                    }
                )
            }
        }
    }

    companion object {
        /**
         * Lambda callbacks 与 ComposeChoiceListDialog 一致为瞬时持有；重建时缺失回调则自动关闭。
         */
        fun create(
            title: String,
            categories: List<String>,
            clearIndex: Int = -1,
            onCategorySelected: (Int, (List<String>) -> Unit) -> Unit,
            onValueSelected: (Int, String) -> Unit,
            onClearFilter: () -> Unit
        ): UrlRecordFilterSheet {
            return UrlRecordFilterSheet().apply {
                arguments = Bundle().apply {
                    putString(ARG_TITLE, title)
                    putStringArrayList(ARG_CATEGORIES, ArrayList(categories.toList()))
                    putInt(ARG_CLEAR_INDEX, clearIndex)
                }
                this.onCategoryListener = onCategorySelected
                this.onValueListener = onValueSelected
                this.onClearListener = onClearFilter
            }
        }

        private const val ARG_TITLE = "title"
        private const val ARG_CATEGORIES = "categories"
        private const val ARG_CLEAR_INDEX = "clearIndex"
    }
}
