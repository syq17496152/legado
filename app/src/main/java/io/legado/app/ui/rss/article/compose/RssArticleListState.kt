package io.legado.app.ui.rss.article.compose

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.staggeredgrid.LazyStaggeredGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import io.legado.app.data.entities.RssArticle

/**
 * D4 收敛后的列表 state holder（design-b3-d4-flagship §2.2）。
 * UI 侧数据（articles/topOverlaySpacePx/pending）为可回放运行时态，宿主收集 Room Flow 后回填，
 * 组件只读；滚动定位/回顶为一次性语义，无需 saveable。
 */
@Stable
class RssArticleListState(
    val lazyListState: LazyListState,
    val lazyGridState: LazyGridState,
    val staggeredGridState: LazyStaggeredGridState,
    /** 待定位的已读恢复目标初始值（VideoPlay/ImagePlay 返回一次性定位），null=无 */
    initialPendingLink: String?,
    /** 手动滚顶请求计数（RssFragment.gotoTop 联动），自增触发 LaunchedEffect */
    internal val scrollToTopRequest: MutableState<Int>,
) {
    /**
     * modern 嵌入：宿主顶栏覆盖占位（RssFragment.setTopOverlaySpace 同源数据）；
     * mutableStateOf backing，setTopOverlaySpace 写入即驱动重组。
     */
    var topOverlaySpacePx: Int by mutableStateOf(0)
        internal set

    /** UI 侧可见数据（宿主收集 Room Flow 后回填；组件只读） */
    var articles: List<RssArticle> by mutableStateOf(emptyList())
        internal set

    /** pending 定位目标：mutableStateOf backing，供 ScrollRestoreEffect 的 snapshotFlow 观察 */
    private var pendingScrollToLink by mutableStateOf(initialPendingLink)

    /** 播放器/图片浏览器返回时一次性滚动到离开位置（对齐原 onResume 逻辑） */
    fun requestScrollToLink(link: String) {
        pendingScrollToLink = link
    }

    /** 一次性消费：取值并置空（ScrollRestoreEffect 专用） */
    fun consumePendingLink(): String? = pendingScrollToLink.also { pendingScrollToLink = null }

    fun requestScrollToTop() {
        scrollToTopRequest.value += 1
    }
}

/**
 * 默认工厂（design-b3-d4-flagship §2.2）：仅用于预览/无恢复诉求场景。
 * 进程恢复（I5）：lazyListState/lazyGridState/staggeredGridState 需进程恢复的场景由壳以
 * rememberSaveable(saver = LazyListState.Saver) 创建后传入（§4 边界 9，批 2 宿主接线落地）；
 * topOverlaySpacePx/articles/pending 为可回放运行时态，无需 saveable。
 */
@Composable
fun rememberRssArticleListState(
    topOverlaySpacePx: Int = 0,
): RssArticleListState {
    val state = remember {
        RssArticleListState(
            lazyListState = LazyListState(),
            lazyGridState = LazyGridState(),
            staggeredGridState = LazyStaggeredGridState(),
            initialPendingLink = null,
            scrollToTopRequest = mutableStateOf(0),
        )
    }
    state.topOverlaySpacePx = topOverlaySpacePx
    return state
}
