package io.legado.app.ui.book.read.config

import android.content.Context
import android.graphics.Typeface
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.StringRes
import androidx.appcompat.widget.AppCompatTextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import io.legado.app.R
import io.legado.app.utils.dpToPx

/**
 * 「管理族共用布局」`activity_theme_manage.xml` 的**程序化等价物**（CE-a #8，2026-09-26）。
 *
 * 该布局原由 **14 个管理页共用**：其中 10 页已完成 Compose 换装（内容整体迁入 Compose、不再消费
 * 该布局）；剩余 4 页是**真消费者**——在 `onActivityCreated` 内直接配置布局内的
 * `tab_bar` / `btn_day` / `btn_night` / `tv_summary` / `recycler_view` / `btn_add`，并在运行时
 * 向根布局 `addView` 动态插入批量栏 / 预览条 / 分组快捷 ⇒ 无法「内容整体迁 Compose」，
 * 故以本类提供程序化等价物（单源装配）。
 *
 * 装配口径（三不影响）：
 *  · 子节点**顺序 / 尺寸 / 内边距 / 外边距 / 默认文案逐项复刻原 XML**——顺序即插入语义
 *    （`AddView` 追加在末尾 / 以 `tv_summary` 或 `btn_add` 为锚点插入，位置改动会改变动态栏落点）；
 *  · 根必须保持 `LinearLayout(vertical)`：4 个宿主都依赖 `root as? LinearLayout` 做动态插入，
 *    `AiReadAloudUsageRecordActivity` 还以 `tvSummary.parent` 作为插入容器、`btnAdd` 作为插入锚点；
 *  · `RecyclerView` / `TextView` 在宿主 `onActivityCreated` 就要被配置（adapter / layoutManager /
 *    点击监听 / ItemTouchHelper）⇒ 必须由本类**提前建好**、宿主以字段持有，
 *    `AndroidView` 工厂只返回实例（组合晚于 `onActivityCreated`，与 `ParagraphRuleEditShellViews` 同口径）。
 *  · 顶栏（原 `title_bar`）**不再装配**：改由宿主 `GlassTopAppBar` 页内渲染（CE 顶栏单源契约）。
 */
class ThemeManageShellViews(private val context: Context) {

    // ==================== 原 tab_bar（42dp + 16dp 左右外边距 + 10dp 上边距 + 4dp 内边距） ====================

    val tabBar: LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 42.dpToPx()
        ).apply {
            marginStart = 16.dpToPx()
            topMargin = 10.dpToPx()
            marginEnd = 16.dpToPx()
        }
        // 原 XML `android:padding="4dp"`
        val pad = 4.dpToPx()
        setPadding(pad, pad, pad, pad)
    }

    /** 原 `btn_day`（0dp + weight=1 + 居中 + 14sp）。 */
    val btnDay: TextView = tabButton(R.string.theme_day)

    /** 原 `btn_night`（同上）。 */
    val btnNight: TextView = tabButton(R.string.theme_night)

    // ==================== 原 tv_summary / recycler_view / btn_add ====================

    /** 原 `tv_summary`（18dp 左右外边距 + 10dp 上边距 + minHeight 18dp + 13sp）。 */
    val tvSummary: TextView = AppCompatTextView(context).apply {
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            marginStart = 18.dpToPx()
            topMargin = 10.dpToPx()
            marginEnd = 18.dpToPx()
        }
        minHeight = 18.dpToPx()
        textSize = 13f
        setText(R.string.theme_package_summary_default)
    }

    /** 原 `recycler_view`（0dp + weight=1 + `clipToPadding=false` + 16dp 左右/底内边距 + 8dp 上边距）。 */
    val recyclerView: RecyclerView = RecyclerView(context).apply {
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ).apply { topMargin = 8.dpToPx() }
        clipToPadding = false
        val pad = 16.dpToPx()
        setPadding(pad, 0, pad, pad)
    }

    /** 原 `btn_add`（48dp 高 + 16dp 外边距 + 居中加粗 15sp；default 文案/底色逐项复刻）。 */
    val btnAdd: TextView = AppCompatTextView(context).apply {
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 48.dpToPx()
        ).apply {
            marginStart = 16.dpToPx()
            marginEnd = 16.dpToPx()
            bottomMargin = 16.dpToPx()
        }
        gravity = Gravity.CENTER
        setText(R.string.theme_add)
        setTextColor(ContextCompat.getColor(context, R.color.primaryText))
        textSize = 15f
        setTypeface(typeface, Typeface.BOLD)
        background = ContextCompat.getDrawable(context, R.drawable.bg_book_info_action_secondary)
    }

    // ==================== 根容器（原 XML 根 LinearLayout；顺序即插入语义） ====================

    val root: LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        // ⚠️ `btn_day` / `btn_night` 的父节点必须是 `tab_bar`（原 XML 即如此）：
        // 它们的 params 是「宽 0dp + weight=1 + 高 match_parent」，这是**横向**等宽两段的写法；
        // 若误挂到竖向 `root` 上，weight 会作用在**纵向** ⇒ 两个按钮吃掉全部高度、宽度为 0（不可见），
        // 并把 `tv_summary` / `recycler_view` / `btn_add` 挤成 0（真机截图实证：只剩空 tab 条 + 空白页）。
        addView(tabBar)
        tabBar.addView(btnDay)
        tabBar.addView(btnNight)
        addView(tvSummary)
        addView(recyclerView)
        addView(btnAdd)
    }

    // ==================== 构造助手 ====================

    /** 原 `btn_day` / `btn_night`（等宽两段：0dp + weight=1 + 居中 + 14sp）。 */
    private fun tabButton(@StringRes textRes: Int): TextView = AppCompatTextView(context).apply {
        layoutParams = LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.MATCH_PARENT, 1f
        )
        gravity = Gravity.CENTER
        setText(textRes)
        textSize = 14f
    }
}
