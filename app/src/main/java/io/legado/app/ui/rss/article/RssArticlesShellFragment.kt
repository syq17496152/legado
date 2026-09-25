package io.legado.app.ui.rss.article

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.ViewModel
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import io.legado.app.R
import io.legado.app.base.VMBaseFragment
import io.legado.app.base.attachComposeContent
import io.legado.app.ui.widget.recycler.RecyclerViewAtPager2

/**
 * 订阅文章列表的**共享合成壳基类**（CE-b）：原 `fragment_rss_articles.xml` 由两个 Fragment 共用
 * （[RssArticlesFragment] 文章列表 / [io.legado.app.ui.rss.favorites.RssFavoritesFragment] 收藏列表）
 * ⇒ 把「无 XML 合成壳 + 两个 View 内核」收敛到基类**单源**，避免两处重复装配与漂移。
 *
 * 与原 XML 的逐一对应关系：
 *  · 根 `SwipeRefreshLayout@refresh_layout` → 程序化 `SwipeRefreshLayout`（显式赋 id）
 *  · 内层 `RecyclerViewAtPager2@recycler_view`（`clipToPadding=false`）→ 程序化等价物（显式赋 id）
 *
 * **为什么 `VMBaseFragment<VM>(0)`**：布局 id 传 0 表示「无 XML」；`Fragment(0)` 时 `onViewCreated`
 * 只在 view 非空时回调 ⇒ **必须 override `onCreateView`** 提供合成壳，否则 `onFragmentCreated` 永不执行。
 *
 * **为什么两个 View 是字段而非工厂内创建**：宿主 `onFragmentCreated`（`initView()`）里就要
 * `refreshLayout.isEnabled = …` / `recyclerView.layoutManager = …` / `adapter = …`，而组合挂载晚于该时点
 * （见交接文档 §8-㉕）；工厂只回传字段。
 */
abstract class RssArticlesShellFragment<VM : ViewModel> : VMBaseFragment<VM>(0) {

    /** 原 XML `recycler_view`（`clipToPadding=false`；显式赋 id 保持与原布局一致） */
    protected val recyclerView: RecyclerViewAtPager2 by lazy {
        RecyclerViewAtPager2(requireContext()).apply {
            id = R.id.recycler_view
            clipToPadding = false
        }
    }

    /** 原 XML `refresh_layout`（唯一子视图为上面的 recycler） */
    protected val refreshLayout: SwipeRefreshLayout by lazy {
        SwipeRefreshLayout(requireContext()).apply {
            id = R.id.refresh_layout
            addView(recyclerView)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val root = FrameLayout(requireContext()).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        root.attachComposeContent {
            AndroidView(modifier = Modifier.fillMaxSize(), factory = { refreshLayout })
        }
        return root
    }
}