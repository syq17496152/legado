package io.legado.app.ui.book.read.config

import android.content.Context
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import androidx.annotation.StringRes
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import io.legado.app.R
import io.legado.app.lib.theme.view.ThemeCheckBox
import io.legado.app.lib.theme.view.ThemeEditText
import io.legado.app.ui.widget.NoChildScrollNestedScrollView
import io.legado.app.ui.widget.code.CodeView
import io.legado.app.ui.widget.text.TextInputLayout
import io.legado.app.utils.dpToPx

/**
 * 「段落规则编辑」共用布局的**程序化等价物**（CE 5.2，2026-09-26）。
 *
 * 原 `activity_paragraph_rule_edit.xml` 已退役 ⇒ 本类是该布局的**唯一装配来源**，
 * 由 **2 个宿主共用**：`ParagraphRuleEditActivity`（段落规则编辑）与
 * `ReadMenuCustomButtonEditActivity`（自定义按键编辑，运行时再重排一次子视图顺序）。
 *
 * 装配口径（三不影响）：
 *  · 子节点**顺序 / 尺寸 / 内边距 / id 逐项复刻原 XML**——顺序即字段栈语义
 *    （`ReadMenuCustomButtonEditActivity.applyFormOrder()` 会按自己的分组再重排，
 *     它依赖这 10 个节点全部在场，故顺序改动会破坏该页分组）；
 *  · 6 个 `et_*` 节点必须赋原 `R.id.et_*`（宿主 `hintFor(view)` 按 `view.id` 取提示文案
 *    ⇒ id 随 XML 退役迁入 `values/ids.xml`）；
 *  · 3 个 Compose 槽（模板行 / 高级组头 / 测试运行）在**原 XML 里就夹在 View 字段之间**，
 *    位置不可上移下移（上移会改变字段分组与滚动归属）⇒ 仍以 `ComposeView` 原地承载，
 *    且构造点收敛到本类的唯一工厂 [composeSlot]（宿主不再自行 new ComposeView / 自设策略）。
 *  · `nested_scroll` 在原 XML 为 `0dp + weight=1` ⇒ 由宿主的 Compose `Modifier.weight(1f)` 表达，
 *    本类内按 `MATCH_PARENT` 装配（滚动与指针/焦点语义由 `NoChildScrollNestedScrollView` 提供）。
 */
class ParagraphRuleEditShellViews(context: Context) {

    // ==================== 原 XML 字段节点（顺序即字段栈） ====================

    val etName: ThemeEditText = ThemeEditText(context).apply {
        id = R.id.et_name
        setSingleLine(true)
    }
    val tilName: TextInputLayout = field(context, R.string.name, etName)

    val etLoginUrl: CodeView = CodeView(context).apply {
        id = R.id.et_login_url
        maxLines = 6
    }
    val tilLoginUrl: TextInputLayout = field(context, R.string.login_url, etLoginUrl)

    val etLoginUi: CodeView = CodeView(context).apply {
        id = R.id.et_login_ui
        maxLines = 8
    }
    val tilLoginUi: TextInputLayout = field(context, R.string.login_ui, etLoginUi)

    val cbIsEnableCookie: ThemeCheckBox = ThemeCheckBox(context).apply {
        setText(R.string.auto_save_cookie)
    }

    val etTimeout: ThemeEditText = ThemeEditText(context).apply {
        id = R.id.et_timeout
        inputType = InputType.TYPE_CLASS_NUMBER
    }
    val tilTimeout: TextInputLayout = field(context, R.string.timeout_millisecond, etTimeout)

    /** F63：空脚本模板 chip 行槽（仅段落规则编辑页设内容；自定义按键页不设 ⇒ 零占位）。 */
    val cvScriptTemplates: ComposeView = composeSlot(context)

    val etScript: CodeView = CodeView(context).apply {
        id = R.id.et_script
        maxLines = 14
    }
    val tilScript: TextInputLayout = field(context, R.string.paragraph_rule_script, etScript)

    val etJsLib: CodeView = CodeView(context).apply {
        id = R.id.et_js_lib
        maxLines = 12
    }

    /** 原 XML 的 hint 为字面量 `jsLib`（非字符串资源）⇒ 逐字保留。 */
    val tilJsLib: TextInputLayout = field(context, "jsLib", etJsLib)

    /** F345：登录与高级组头槽（仅自定义按键页置为可见；段落规则页恒 `gone` = 零占位）。 */
    val cvLoginAdvanced: ComposeView = composeSlot(context).apply { visibility = View.GONE }

    /** F343：页内测试运行槽（同上，仅自定义按键页接线）。 */
    val cvScriptTest: ComposeView = composeSlot(context).apply { visibility = View.GONE }

    // ==================== 容器（原 nested_scroll > ll_content） ====================

    val llContent: LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        // 原 XML `android:padding="10dp"`
        val pad = 10.dpToPx()
        setPadding(pad, pad, pad, pad)
        addView(tilName)
        addView(tilLoginUrl)
        addView(tilLoginUi)
        addView(cbIsEnableCookie)
        addView(tilTimeout)
        addView(cvScriptTemplates)
        addView(tilScript)
        addView(tilJsLib)
        addView(cvLoginAdvanced)
        addView(cvScriptTest)
    }

    val nestedScroll: NoChildScrollNestedScrollView = NoChildScrollNestedScrollView(context).apply {
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        addView(
            llContent, ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
    }

    // ==================== 构造助手（与 activity_replace_edit 换装同口径） ====================

    /** 原 `TextInputLayout` + 子编辑框成对结构（match_parent 宽 / wrap_content 高）。 */
    private fun field(context: Context, @StringRes hintRes: Int, child: EditText): TextInputLayout =
        field(context, context.getString(hintRes), child)

    private fun field(context: Context, hint: CharSequence, child: EditText): TextInputLayout =
        // attrs 本身可空 ⇒ 程序化构造与 XML 膨胀同走 `R.attr.textInputStyle`（先例 ReplaceEditActivity）
        TextInputLayout(context, null).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            this.hint = hint
            addView(
                child, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            )
        }

    /**
     * 字段栈内 Compose 槽位的唯一构造工厂。
     * 合成策略与迁移前 XML 内的 `ComposeView` 口径一致（`DisposeOnViewTreeLifecycleDestroyed`），
     * 宿主只负责 `setContent {}`，**不得**自行创建 ComposeView 或改策略。
     */
    private fun composeSlot(context: Context): ComposeView = ComposeView(context).apply {
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
    }
}