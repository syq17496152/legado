package io.legado.app.ui.widget.recycler.scroller

import android.content.Context
import android.util.AttributeSet
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.RelativeLayout
import androidx.annotation.ColorInt
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.recyclerview.widget.RecyclerView
import io.legado.app.R

@Suppress("MemberVisibilityCanBePrivate", "unused")
class FastScrollRecyclerView : RecyclerView {

    private lateinit var mFastScroller: FastScroller

    constructor(context: Context) : super(context) {
        layout(context, null)
        layoutParams =
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
    }

    @JvmOverloads
    constructor(
        context: Context,
        attrs: AttributeSet,
        defStyleAttr: Int = 0
    ) : super(context, attrs, defStyleAttr) {
        layout(context, attrs)
    }

    private fun layout(context: Context, attrs: AttributeSet?) {
        // attrs==null（即经 `FastScrollRecyclerView(context)` 程序化构造）必须走无属性构造：
        // FastScroller 的 (context, attrs, defStyleAttr) 会执行 LinearLayout.generateLayoutParams(null)，
        // 因缺 layout_width 抛 UnsupportedOperationException（CE 5.2 全文搜索页程序化装配时实测）。
        // XML 路径（attrs 非空）行为不变。
        mFastScroller = if (attrs == null) FastScroller(context) else FastScroller(context, attrs)
        mFastScroller.id = R.id.fast_scroller
    }

    override fun setAdapter(adapter: Adapter<*>?) {
        super.setAdapter(adapter)
        if (adapter is FastScroller.SectionIndexer) {
            setSectionIndexer(adapter as FastScroller.SectionIndexer?)
        } else if (adapter == null) {
            setSectionIndexer(null)
        }
    }


    override fun setVisibility(visibility: Int) {
        super.setVisibility(visibility)
        mFastScroller.visibility = visibility
    }


    /**
     * Set the [FastScroller.SectionIndexer] for the [FastScroller].
     *
     * @param sectionIndexer The SectionIndexer that provides section text for the FastScroller
     */
    fun setSectionIndexer(sectionIndexer: FastScroller.SectionIndexer?) {
        mFastScroller.setSectionIndexer(sectionIndexer)
    }


    /**
     * Set the enabled state of fast scrolling.
     *
     * @param enabled True to enable fast scrolling, false otherwise
     */
    fun setFastScrollEnabled(enabled: Boolean) {
        mFastScroller.isEnabled = enabled
    }


    /**
     * Hide the scrollbar when not scrolling.
     *
     * @param hideScrollbar True to hide the scrollbar, false to show
     */
    fun setHideScrollbar(hideScrollbar: Boolean) {
        mFastScroller.setFadeScrollbar(hideScrollbar)
    }

    /**
     * Display a scroll track while scrolling.
     *
     * @param visible True to show scroll track, false to hide
     */
    fun setTrackVisible(visible: Boolean) {
        mFastScroller.setTrackVisible(visible)
    }

    /**
     * Set the color of the scroll track.
     *
     * @param color The color for the scroll track
     */
    fun setTrackColor(@ColorInt color: Int) {
        mFastScroller.setTrackColor(color)
    }


    /**
     * Set the color for the scroll handle.
     *
     * @param color The color for the scroll handle
     */
    fun setHandleColor(@ColorInt color: Int) {
        mFastScroller.setHandleColor(color)
    }


    /**
     * Show the section bubble while scrolling.
     *
     * @param visible True to show the bubble, false to hide
     */
    fun setBubbleVisible(visible: Boolean) {
        mFastScroller.setBubbleVisible(visible)
    }


    /**
     * Set the background color of the index bubble.
     *
     * @param color The background color for the index bubble
     */
    fun setBubbleColor(@ColorInt color: Int) {
        mFastScroller.setBubbleColor(color)
    }


    /**
     * Set the text color of the index bubble.
     *
     * @param color The text color for the index bubble
     */
    fun setBubbleTextColor(@ColorInt color: Int) {
        mFastScroller.setBubbleTextColor(color)
    }


    /**
     * Set the fast scroll state change listener.
     *
     * @param fastScrollStateChangeListener The interface that will listen to fastscroll state change events
     */
    fun setFastScrollStateChangeListener(fastScrollStateChangeListener: FastScrollStateChangeListener) {
        mFastScroller.setFastScrollStateChangeListener(fastScrollStateChangeListener)
    }


    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        mFastScroller.attachRecyclerView(this)
        var parent = parent
        while (parent != null) {
            when (parent) {
                is ConstraintLayout, is CoordinatorLayout, is FrameLayout, is RelativeLayout -> break
                else -> parent = parent.parent
            }
        }
        if (parent is ViewGroup && parent.indexOfChild(mFastScroller) == -1) {
            parent.addView(mFastScroller)
            mFastScroller.setLayoutParams(parent)
        }
    }


    override fun onDetachedFromWindow() {
        mFastScroller.detachRecyclerView()
        super.onDetachedFromWindow()
    }

}