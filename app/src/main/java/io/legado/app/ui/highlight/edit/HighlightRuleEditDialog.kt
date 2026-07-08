package io.legado.app.ui.highlight.edit

import android.graphics.Typeface
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.text.style.UnderlineSpan
import android.view.View
import android.view.ViewGroup
import androidx.lifecycle.lifecycleScope
import com.jaredrummler.android.colorpicker.ColorPickerDialog
import com.jaredrummler.android.colorpicker.ColorPickerDialogListener
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.data.entities.BookHighlight
import io.legado.app.databinding.DialogHighlightRuleEditBinding
import io.legado.app.help.HighlightColors
import io.legado.app.help.HighlightStyle
import io.legado.app.model.ReadBook
import io.legado.app.ui.book.read.HighlightStyleDialog
import io.legado.app.ui.book.read.config.HighlightRule
import io.legado.app.ui.book.read.config.HighlightRuleStore
import io.legado.app.ui.font.FontSelectDialog
import io.legado.app.utils.GSON
import io.legado.app.utils.setLayout
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * F-P1-2 高亮规则系统（借鉴阅读T）
 * 编辑高亮规则(全屏对话框,与书签备注同视觉风格)。
 * 作为 [HighlightStyleDialog] 的 [HighlightStyleDialog.StyleHost]。
 *
 * 适配说明：
 * 1. id: Long → String（SharedPreferences 存储, 用 System.currentTimeMillis().toString()）
 * 2. appDb.highlightRuleDao.* → HighlightRuleStore.*（load/save 整个列表）
 * 3. 移除 scope/order 字段（当前项目用 targetScope:Int 枚举, UI 暂不暴露; order 用列表索引）
 * 4. rule.styleObj() → rule.toHighlightStyle(); rule.applyStyle(s) → rule.styleJson = GSON.toJson(s)
 * 5. rule.isValid() → 内联验证（pattern 非空, isRegex 时正则可编译）
 * 已知上限：UI 不暴露 targetScope 选择 | 升级路径：后续加 Spinner 选择 ALL/TITLE/BODY
 */
class HighlightRuleEditDialog : BaseDialogFragment(R.layout.dialog_highlight_rule_edit, true),
    HighlightStyleDialog.StyleHost,
    FontSelectDialog.CallBack,
    ColorPickerDialogListener {

    companion object {
        private const val COLOR_PICKER_TAG = "highlight-rule-color-picker"

        /**
         * 新建规则(预填 pattern/isRegex/style)。
         * [sourceHighlightTime] > 0 表示由「批量」从某手动划线发起,保存成功后删除该划线(转化为规则);
         * 0 表示规则管理页直接新增,不影响任何划线。
         */
        fun create(
            pattern: String,
            isRegex: Boolean = false,
            style: String? = null,
            sourceHighlightTime: Long = 0L
        ): HighlightRuleEditDialog = HighlightRuleEditDialog().apply {
            arguments = Bundle().apply {
                putString("pattern", pattern)
                putBoolean("isRegex", isRegex)
                putString("style", style)
                putLong("sourceHighlightTime", sourceHighlightTime)
            }
        }

        /** 编辑已有规则（适配：id 为 String） */
        fun edit(id: String): HighlightRuleEditDialog = HighlightRuleEditDialog().apply {
            arguments = Bundle().apply { putString("id", id) }
        }

        /** 「批量」保存成功后应删除的来源划线; sourceTime<=0(规则管理页新增)时返回 null */
        fun highlightToRemove(highlights: List<BookHighlight>, sourceTime: Long): BookHighlight? =
            if (sourceTime > 0) highlights.firstOrNull { it.time == sourceTime } else null

        data class ColorPickerConfig(
            val dialogId: Int,
            val color: Int,
            val withAlpha: Boolean,
            val presets: IntArray
        )

        fun colorPickerConfig(dialogId: Int, initial: Int, withAlpha: Boolean): ColorPickerConfig {
            val seed = if (initial != 0) initial else HighlightColors.bg.first()
            return ColorPickerConfig(
                dialogId = dialogId,
                color = seed,
                withAlpha = withAlpha,
                presets = if (withAlpha) HighlightColors.bg else HighlightColors.text
            )
        }

        fun bindColorPickerListener(
            dialog: ColorPickerDialog,
            listener: ColorPickerDialogListener
        ): ColorPickerDialog = dialog.also { it.setColorPickerDialogListener(listener) }

        fun createColorPickerDialog(
            dialogId: Int,
            initial: Int,
            withAlpha: Boolean,
            listener: ColorPickerDialogListener
        ): ColorPickerDialog {
            val config = colorPickerConfig(dialogId, initial, withAlpha)
            return bindColorPickerListener(
                ColorPickerDialog.newBuilder()
                    .setColor(config.color)
                    .setShowAlphaSlider(config.withAlpha)
                    .setDialogType(ColorPickerDialog.TYPE_PRESETS)
                    .setPresets(config.presets)
                    .setDialogId(config.dialogId)
                    .create(),
                listener
            )
        }
    }

    private val binding by viewBinding(DialogHighlightRuleEditBinding::bind)
    private var editingStyle = HighlightStyle()
    private var styleDialog: HighlightStyleDialog? = null
    private var rule: HighlightRule? = null

    override fun onStart() {
        super.onStart()
        setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        binding.btnStyle.setOnClickListener {
            val d = HighlightStyleDialog()
            styleDialog = d
            showDialogFragment(d)
        }
        binding.tvCancel.setOnClickListener { dismiss() }
        binding.tvOk.setOnClickListener { save() }
        childFragmentManager.findFragmentByTag(COLOR_PICKER_TAG)
            ?.let { it as? ColorPickerDialog }
            ?.setColorPickerDialogListener(this)

        val id = arguments?.getString("id")
        if (!id.isNullOrBlank()) {
            loadById(id)
        } else {
            fromArgs()
        }
    }

    /** 适配：从 Store.load 整个列表后按 id 查找 */
    private fun loadById(id: String) {
        lifecycleScope.launch {
            val r = withContext(Dispatchers.IO) {
                HighlightRuleStore.load(requireContext()).firstOrNull { it.id == id }
            }
            if (r != null) {
                rule = r
                upView(r)
            } else {
                requireActivity().toastOnUi("规则不存在")
                dismiss()
            }
        }
    }

    private fun fromArgs() {
        val a = arguments ?: return
        val r = HighlightRule(
            name = a.getString("pattern") ?: "",
            pattern = a.getString("pattern") ?: "",
            isRegex = a.getBoolean("isRegex", false),
            styleJson = a.getString("style") ?: ""
        )
        rule = r
        upView(r)
    }

    private fun upView(r: HighlightRule) = binding.run {
        etName.setText(r.name)
        etPattern.setText(r.pattern)
        cbUseRegex.isChecked = r.isRegex
        editingStyle = r.toHighlightStyle()
        upPreview()
    }

    private fun getRule(): HighlightRule = binding.run {
        val r = rule ?: HighlightRule()
        r.name = etName.text.toString()
        r.pattern = etPattern.text.toString()
        r.isRegex = cbUseRegex.isChecked
        r.styleJson = GSON.toJson(editingStyle)
        r
    }

    /** 适配：内联验证（pattern 非空, isRegex 时正则可编译） */
    private fun isValidRule(r: HighlightRule): Boolean {
        if (r.pattern.isBlank()) return false
        if (r.isRegex) {
            return runCatching { Regex(r.pattern) }.isSuccess
        }
        return true
    }

    /** 适配：load 整个列表 → 替换或追加 → save 整个列表 */
    private fun save() {
        val r = getRule()
        if (!isValidRule(r)) {
            requireActivity().toastOnUi("规则无效: ${r.pattern}")
            return
        }
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                val rules = HighlightRuleStore.load(requireContext())
                val idx = rules.indexOfFirst { it.id == r.id }
                if (idx >= 0) {
                    rules[idx] = r
                } else {
                    rules.add(r)
                }
                HighlightRuleStore.save(requireContext(), rules)
            }
            ReadBook.upHighlightRules()
            // 「批量」转化: 规则已接管该处文字, 删除发起的那条手动划线, 避免同段文字双份高亮
            val srcTime = arguments?.getLong("sourceHighlightTime", 0L) ?: 0L
            highlightToRemove(ReadBook.highlights, srcTime)?.let { ReadBook.removeHighlight(it) }
            dismiss()
        }
    }

    private fun upPreview() = binding.run {
        // F-P1-2 Phase 8 23.48: 用 Span 渲染所有样式通道, 提升预览真实度
        // 简化说明：underline 不分 kind（波浪/虚线/点线/双线都用实线 UnderlineSpan）; box/emphasis/fontPath 暂不支持
        // 已知上限：不显示波浪/虚线/点线/双线/方框/着重号/自定义字体
        // 升级路径：移植自定义 Span 类后可恢复完整预览
        val sampleText = "预览文字 Preview"
        val spannable = SpannableStringBuilder(sampleText)
        val len = sampleText.length
        if (editingStyle.fill != 0) {
            spannable.setSpan(BackgroundColorSpan(editingStyle.fill), 0, len, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        if (editingStyle.textColor != 0) {
            spannable.setSpan(ForegroundColorSpan(editingStyle.textColor), 0, len, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        if (editingStyle.bold) {
            spannable.setSpan(StyleSpan(Typeface.BOLD), 0, len, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        if (editingStyle.italic) {
            spannable.setSpan(StyleSpan(Typeface.ITALIC), 0, len, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        if (editingStyle.underline != null) {
            spannable.setSpan(UnderlineSpan(), 0, len, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        if (editingStyle.strike != null) {
            spannable.setSpan(StrikethroughSpan(), 0, len, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        tvStylePreview.text = spannable
    }

    // --- HighlightStyleDialog.StyleHost ---
    override fun currentHighlightStyle(): HighlightStyle = editingStyle

    override fun onHighlightStyleChanged(style: HighlightStyle) {
        editingStyle = style
        upPreview()
    }

    override fun pickHighlightColor(dialogId: Int, initial: Int, withAlpha: Boolean) {
        createColorPickerDialog(
            dialogId = dialogId,
            initial = initial,
            withAlpha = withAlpha,
            listener = this@HighlightRuleEditDialog
        ).show(childFragmentManager, COLOR_PICKER_TAG)
    }

    override fun pickHighlightFont(current: String) {
        showDialogFragment(FontSelectDialog())
    }

    // --- FontSelectDialog.CallBack ---
    override val curFontPath: String get() = editingStyle.fontPath

    override fun selectFont(path: String) {
        editingStyle = editingStyle.copy(fontPath = path)
        styleDialog?.refresh()
        upPreview()
    }

    // --- ColorPickerDialogListener ---
    override fun onColorSelected(dialogId: Int, color: Int) {
        editingStyle = HighlightStyleDialog.applyChannelColor(editingStyle, dialogId, color)
        styleDialog?.refresh()
        upPreview()
    }

    override fun onDialogDismissed(dialogId: Int) {}
}
