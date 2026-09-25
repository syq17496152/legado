package io.legado.app.ui.book.source.edit

import android.content.Context
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import androidx.appcompat.widget.AppCompatSpinner
import androidx.appcompat.widget.AppCompatTextView
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.tabs.TabLayout
import io.legado.app.R
import io.legado.app.lib.theme.view.ThemeCheckBox
import io.legado.app.utils.dpToPx

/**
 * 「书源编辑」页的程序化等价物（CE-a，2026-09-26）。
 *
 * 原 `activity_book_source_edit.xml` 已退役 ⇒ 本类是该布局的**唯一装配来源**（单宿主，但装配仍下沉，
 * 与同批的 `ParagraphRuleEditShellViews` / 既有 `RssSourceEditActivity` 换装口径保持一致）。
 *
 * 装配口径（三不影响；与同类页 `RssSourceEditActivity` 逐项同构）：
 *  · 原 XML 六段结构逐项复刻：ComposeView（规则帮助引导条）→ 多选框行 →
 *    参数行（书源类型标签 + Spinner + 三个勾选）→ 事件/自定义按键行 → `TabLayout`(36dp + 3dp 阴影) →
 *    `RecyclerView`（`clipToPadding=false`；布局管理器与 adapter 由宿主 `initView` 装配）。
 *  · `RecyclerView` / `TabLayout` 在宿主 `onActivityCreated` 就要被配置（adapter / tab 监听 / insets）
 *    ⇒ 必须由本类**提前建好**、宿主以字段持有，`AndroidView` 工厂只返回实例（组合晚于 `onActivityCreated`）。
 *  · 原 `android:entries="@array/book_type"` + `android:theme="@style/Spinner"` ⇒ 程序化走
 *    `ContextThemeWrapper(context, R.style.Spinner)` + `ArrayAdapter`（同 `RssSourceEditActivity` 口径）。
 *  · `android:visibility="gone"` 的 `cb_is_enable_review` 逐字保留（既有语义：暂不展示的评分配置）。
 */
class BookSourceEditShellViews(private val context: Context) {

    // ==================== 顶栏后的 Compose 槽（F：一次性规则帮助引导条） ====================

    /** 原 `cv_rule_help_guide`：引导条显隐由宿主状态驱动（恒在场 ⇒ 未消费时零高度，同 XML 语义）。 */
    val cvRuleHelpGuide: ComposeView = ComposeView(context).apply {
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        // 合成策略与迁移前 XML 内 ComposeView 口径一致；宿主只负责 setContent {}
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
    }

    // ==================== 基础信息行（原 HorizontalScrollView#1） ====================

    val spType: AppCompatSpinner = AppCompatSpinner(ContextThemeWrapper(context, R.style.Spinner)).apply {
        adapter = ArrayAdapter(
            context,
            android.R.layout.simple_spinner_item,
            context.resources.getStringArray(R.array.book_type)
        ).apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
    }

    /** 原 `cb_is_enable`（默认勾选）。 */
    val cbIsEnable: ThemeCheckBox = checkBox(R.string.is_enable, checked = true)

    /** 原 `cb_is_enable_explore`（默认勾选）。 */
    val cbIsEnableExplore: ThemeCheckBox = checkBox(R.string.discovery, checked = true)

    /** 原 `cb_is_enable_cookie`（默认勾选）。 */
    val cbIsEnableCookie: ThemeCheckBox = checkBox(R.string.auto_save_cookie, checked = true)

    /** 原 `cb_is_enable_review`（默认不勾 + `visibility=gone`，逐字保留）。 */
    val cbIsEnableReview: ThemeCheckBox = checkBox(R.string.review, checked = false).apply {
        visibility = android.view.View.GONE
    }

    // ==================== 开关行（原 HorizontalScrollView#2） ====================

    /** 原 `cb_is_event_listener`。 */
    val cbIsEventListener: ThemeCheckBox = checkBox(R.string.is_event_listener, checked = false)

    /** 原 `cb_is_custom_button`。 */
    val cbIsCustomButton: ThemeCheckBox = checkBox(R.string.custom_button, checked = false)

    // ==================== 容器与后半段 ====================

    /** 原 `HorizontalScrollView#1`（`scrollbars=none`）：书源类型标签 + Spinner + 三个勾选 + 隐藏勾选。 */
    val basicRow: HorizontalScrollView = horizontalRow { row ->
        row.addView(label(R.string.book_type))
        row.addView(spType)
        row.addView(cbIsEnable)
        row.addView(cbIsEnableExplore)
        row.addView(cbIsEnableCookie)
        row.addView(cbIsEnableReview)
    }

    /** 原 `HorizontalScrollView#2`：事件监听 / 自定义按键两个勾选。 */
    val switchRow: HorizontalScrollView = horizontalRow { row ->
        row.addView(cbIsEventListener)
        row.addView(cbIsCustomButton)
    }

    /** 原 `tab_layout`（36dp 高 + elevation 3dp；底色/指示器色由宿主按主题覆写）。 */
    val tabLayout: TabLayout = TabLayout(context).apply {
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 36.dpToPx()
        )
        elevation = 3.dpToPx().toFloat()
    }

    /** 原 `recycler_view`（`clipToPadding=false`；布局管理器与 adapter 由宿主装配）。 */
    val recyclerView: RecyclerView = RecyclerView(context).apply {
        clipToPadding = false
    }

    // ==================== 构造助手 ====================

    /** 原参数行标签（`match_parent` 高 + `gravity=center` ⇒ 随行高垂直居中）。 */
    private fun label(textRes: Int): AppCompatTextView = AppCompatTextView(context).apply {
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.MATCH_PARENT
        )
        gravity = Gravity.CENTER
        setText(textRes)
    }

    /** 原 `ThemeCheckBox`（文案/勾选态由代码给，其余取主题默认 ⇒ 与 XML 同口径）。 */
    private fun checkBox(textRes: Int, checked: Boolean): ThemeCheckBox =
        ThemeCheckBox(context).apply {
            setText(textRes)
            isChecked = checked
        }

    /** 原 `HorizontalScrollView`（`scrollbars=none` + 内层 `LinearLayout(paddingHorizontal=8dp)`）。 */
    private fun horizontalRow(block: (LinearLayout) -> Unit): HorizontalScrollView {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(8.dpToPx(), 0, 8.dpToPx(), 0)
        }
        block(row)
        return HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            addView(row)
        }
    }
}