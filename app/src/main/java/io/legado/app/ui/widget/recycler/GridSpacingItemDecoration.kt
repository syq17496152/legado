package io.legado.app.ui.widget.recycler

import android.graphics.Rect
import android.view.View
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView

/**
 * Grid 布局等间距装饰器。仅在 GridLayoutManager 下生效，四边等距。
 * spacing 单位为 px。
 */
class GridSpacingItemDecoration(var spacing: Int = 0) : RecyclerView.ItemDecoration() {
    override fun getItemOffsets(
        outRect: Rect,
        view: View,
        parent: RecyclerView,
        state: RecyclerView.State
    ) {
        if (parent.layoutManager is GridLayoutManager) {
            outRect.set(spacing, spacing, spacing, spacing)
        }
    }
}
