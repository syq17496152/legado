package io.legado.app.ui.code

import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Space
import androidx.appcompat.content.res.AppCompatResources
import androidx.appcompat.widget.AppCompatButton
import androidx.appcompat.widget.AppCompatImageView
import androidx.appcompat.widget.AppCompatTextView
import androidx.appcompat.widget.SwitchCompat
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import io.legado.app.R
import io.legado.app.lib.theme.themeCardColorOrDefault
import io.legado.app.utils.dpToPx

/**
 * 代码编辑器**搜索/替换面板**的视图构造（epub-md-rich-rendering 阶段 4.8c 拆分副产品）。
 *
 * 拆分理由（`audit_file_size.py` 单文件 800 行上限 + "按职责拆分"）：本面板原是
 * `CodeEditActivity` 内约 190 行的**程序化 View 构造**（CE 5.2 换装时把 XML 节点逐项复刻进代码），
 * 与宿主的编辑/生命周期逻辑无耦合 —— 视图构造下沉到本类，宿主只留行为（搜索/替换流程）。
 *
 * 语义**逐行不变**（控件类型、内边距、可见性、背景取色全部照搬）：
 * - 控件类与被 AppCompat 替换后的实际类型一致（`TextView`/`ImageView`/`Button` → `AppCompat*`，`Switch` → `SwitchCompat`）；
 * - 面板底原为 XML 静态 `@color/background_card`（R30 技术债，`theme_token_allowlist.json` 已登记）
 *   ⇒ 仍走运行时面 token [themeCardColorOrDefault]（卡片面，`color.md` §六），与 Compose 侧同语义；
 * - 显隐仍由宿主按 View 语义切换（`visibility`）⇒ 监听器与搜索订阅语义与原实现一致。
 */
internal class CodeEditSearchPanelViews(private val context: Context) {

    /** 面板内文本节点（原 `@dimen/text_14sp`；[colorRes] 非 0 时对齐原 `android:textColor`）。 */
    private fun panelText(colorRes: Int = 0): AppCompatTextView =
        AppCompatTextView(context).apply {
            textSize = 14f
            if (colorRes != 0) {
                setTextColor(AppCompatResources.getColorStateList(context, colorRes))
            }
        }

    /** 原 `style="?android:attr/buttonBarButtonStyle"` 的等价值（以该属性为默认样式属性构造）。 */
    private fun panelButton(textRes: Int, colorRes: Int): AppCompatButton =
        AppCompatButton(context, null, android.R.attr.buttonBarButtonStyle).apply {
            setText(textRes)
            textSize = 14f
            setTextColor(AppCompatResources.getColorStateList(context, colorRes))
        }

    /** 原 `TextInputLayout(boxBackgroundMode=none)` + 子 `TextInputEditText` 成对结构。 */
    private fun inputField(child: EditText): TextInputLayout =
        TextInputLayout(context, null).apply {
            boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_NONE
            addView(
                child, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            )
        }

    /** 原面板内的关闭图标（`8dp` 内边距 + `ic_baseline_close`）。 */
    private fun panelCloseIcon(): AppCompatImageView = AppCompatImageView(context).apply {
        contentDescription = context.getString(R.string.close)
        scaleType = ImageView.ScaleType.CENTER
        setImageResource(R.drawable.ic_baseline_close)
        val pad = 8.dpToPx()
        setPadding(pad, pad, pad, pad)
    }

    /** `wrap_content × wrap_content` 的 LinearLayout 子节点布局参数。 */
    private fun wrap() = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.WRAP_CONTENT,
        LinearLayout.LayoutParams.WRAP_CONTENT
    )

    private fun row(): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
    }

    val tvSearchResultLabel by lazy { panelText().apply { setText(R.string.search_result) } }
    val tvSearchResult by lazy { panelText().apply { text = "0" } }
    val switchRegex by lazy {
        SwitchCompat(context).apply {
            isChecked = true
            setText(R.string.regex)
        }
    }
    val tvFindLabel by lazy { panelText(R.color.primaryText).apply { setText(R.string.find) } }
    val etFind by lazy { TextInputEditText(context) }
    val btnCloseFind by lazy { panelCloseIcon() }
    val tvReplaceLabel by lazy {
        panelText(R.color.primaryText).apply { setText(R.string.replace) }
    }
    val etReplace by lazy { TextInputEditText(context) }
    val btnCloseReplace by lazy { panelCloseIcon() }
    val btnPrevious by lazy { panelButton(R.string.btn_previous, R.color.primaryText) }
    val btnNext by lazy { panelButton(R.string.btn_next, R.color.primaryText) }
    val btnReplace by lazy { panelButton(R.string.replace, R.color.primaryText) }
    val btnReplaceAll by lazy {
        panelButton(R.string.replace_all, R.color.selector_btn_text_color).apply {
            isEnabled = false
        }
    }

    /** 原 `replace_group`（默认 `gone`，点击「替换」后才展开）。 */
    val replaceGroup by lazy {
        LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            addView(tvReplaceLabel, wrap())
            addView(
                inputField(etReplace), LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                )
            )
            addView(btnCloseReplace, wrap())
        }
    }

    /** 原 `search_group`（`12dp` 左右内边距 / 默认 `gone`）。 */
    val searchPanel: LinearLayout by lazy {
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            // 面 token 取色需 Context 接收者（本类的 receiver 是 LinearLayout）
            setBackgroundColor(context.themeCardColorOrDefault())
            val pad = 12.dpToPx()
            setPadding(pad, 0, pad, 0)
            // 行 1：命中计数 + 正则开关
            addView(
                row().apply {
                    addView(tvSearchResultLabel, wrap())
                    addView(tvSearchResult, wrap().apply { marginStart = 8.dpToPx() })
                    addView(Space(context), LinearLayout.LayoutParams(0, 0, 1f))
                    addView(switchRegex, wrap())
                }
            )
            // 行 2：查找
            addView(
                row().apply {
                    clipChildren = false
                    clipToPadding = false
                    addView(tvFindLabel, wrap())
                    addView(
                        inputField(etFind), LinearLayout.LayoutParams(
                            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                        )
                    )
                    addView(btnCloseFind, wrap())
                }
            )
            // 行 3：替换（默认收起）
            addView(replaceGroup)
            // 行 4：操作按钮条（原 `style="?android:attr/buttonBarStyle"`）
            addView(
                LinearLayout(context, null, android.R.attr.buttonBarStyle).apply {
                    orientation = LinearLayout.HORIZONTAL
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                    addView(
                        btnPrevious, LinearLayout.LayoutParams(
                            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                        )
                    )
                    addView(
                        btnNext, LinearLayout.LayoutParams(
                            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                        )
                    )
                    addView(
                        btnReplace, LinearLayout.LayoutParams(
                            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                        )
                    )
                    addView(
                        btnReplaceAll, LinearLayout.LayoutParams(
                            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                        )
                    )
                }
            )
        }
    }
}