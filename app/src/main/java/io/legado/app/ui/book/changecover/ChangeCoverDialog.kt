package io.legado.app.ui.book.changecover

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle.State.STARTED
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.data.entities.SearchBook
import io.legado.app.ui.theme.LegadoTheme
import io.legado.app.ui.widget.compose.AppDialogFrame
import io.legado.app.ui.widget.compose.AppDialogSize
import io.legado.app.ui.widget.compose.AppDialogStyle
import io.legado.app.ui.widget.compose.ComposeDialogFragment
import io.legado.app.ui.widget.compose.LegadoMiuixActionButton
import io.legado.app.ui.widget.compose.LegadoMiuixCard
import io.legado.app.ui.widget.compose.rememberAppDialogStyle
import io.legado.app.ui.widget.compose.toMiuixPalette
import io.legado.app.ui.widget.image.CoverImageView
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 换封面（迁移：原 BaseDialogFragment(R.layout.dialog_change_cover) 的
 * Toolbar 菜单（开始/停止）+ RefreshProgressBar + RecyclerView(CoverAdapter 3 列网格)
 * 迁移为 AppDialogFrame + LinearProgressIndicator + LazyVerticalGrid（3 列），
 * 封面项用 AndroidView 包裹 [CoverImageView] 保留原有加载逻辑（含默认封面/书名绘制）。
 * 构造器（name/author）、CallBack 接口、ViewModel 数据流行为保持不变。）
 */
class ChangeCoverDialog() : ComposeDialogFragment() {

    override val dialogSize: AppDialogSize = AppDialogSize.Wide

    constructor(name: String, author: String) : this() {
        arguments = Bundle().apply {
            putString("name", name)
            putString("author", author)
        }
    }

    private val callBack: CallBack? get() = activity as? CallBack
    private val viewModel: ChangeCoverViewModel by viewModels()

    private var covers by mutableStateOf<List<SearchBook>>(emptyList())
    private var searchState by mutableStateOf(0)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        viewModel.initData(arguments)
        viewModel.searchStateData.observe(viewLifecycleOwner) {
            searchState = it ?: 0
        }
        initData()
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
                    AppDialogFrame(
                        title = stringResource(R.string.change_cover_source),
                        scrollContent = false,
                        content = {
                            if (searchState == 1) {
                                LinearProgressIndicator(
                                    modifier = Modifier.fillMaxWidth(),
                                    color = style.accent,
                                    trackColor = style.fieldSurface
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                            }
                            LazyVerticalGrid(
                                columns = GridCells.Fixed(3),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 460.dp),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                items(covers) { item ->
                                    CoverItemCell(
                                        item = item,
                                        style = style,
                                        onClick = { changeTo(item.coverUrl ?: "") }
                                    )
                                }
                            }
                        },
                        actions = {
                            LegadoMiuixActionButton(
                                text = when (searchState) {
                                    1 -> stringResource(R.string.stop)
                                    2 -> stringResource(R.string.resume)
                                    else -> stringResource(R.string.refresh)
                                },
                                palette = palette,
                                onClick = { viewModel.startOrStopSearch() },
                                cornerRadius = style.actionRadius
                            )
                        }
                    )
                }
            }
        }
    }

    private fun initData() {
        lifecycleScope.launch {
            lifecycle.currentStateFlow.first { it.isAtLeast(STARTED) }
            viewModel.dataFlow.conflate().collect {
                covers = it
                delay(1000)
            }
        }
    }

    private fun changeTo(coverUrl: String) {
        callBack?.coverChangeTo(coverUrl)
        dismissAllowingStateLoss()
    }

    interface CallBack {
        fun coverChangeTo(coverUrl: String)
    }
}

@Composable
private fun CoverItemCell(
    item: SearchBook,
    style: AppDialogStyle,
    onClick: () -> Unit
) {
    LegadoMiuixCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        color = style.fieldSurface,
        contentColor = style.primaryText,
        cornerRadius = style.actionRadius,
        insidePadding = PaddingValues(horizontal = 8.dp, vertical = 10.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            AndroidView(
                factory = { context ->
                    CoverImageView(context).apply {
                        scaleType = ImageView.ScaleType.CENTER_CROP
                        contentDescription = context.getString(R.string.img_cover)
                    }
                },
                update = { it.load(item, false) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(100.dp)
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = item.originName,
                color = style.primaryText,
                fontSize = MaterialTheme.typography.bodySmall.fontSize,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center
            )
        }
    }
}
