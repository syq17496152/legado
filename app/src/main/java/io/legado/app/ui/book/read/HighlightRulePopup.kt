package io.legado.app.ui.book.read

import android.content.Context
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.PopupWindow
import io.legado.app.databinding.PopupHighlightRuleActionBinding

/**
 * F-P1-2 高亮规则系统（借鉴阅读T）
 * 点击规则自动高亮时弹出: 编辑规则 / 停用规则
 *
 * 适配说明：原阅读T 使用 skin_textColor 皮肤属性,当前项目无此属性,改用 android:textColor
 */
class HighlightRulePopup(context: Context, private val callBack: CallBack) :
    PopupWindow(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT) {

    private val binding = PopupHighlightRuleActionBinding.inflate(LayoutInflater.from(context))

    init {
        contentView = binding.root
        isTouchable = true
        isOutsideTouchable = true
        isFocusable = false
        binding.tvEdit.setOnClickListener { callBack.onRuleEdit(); dismiss() }
        binding.tvDisable.setOnClickListener { callBack.onRuleDisable(); dismiss() }
    }

    fun show(anchor: View, x: Int, topY: Int) {
        contentView.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        val h = contentView.measuredHeight
        val y = if (topY > h + 100) topY - h else topY + 80
        showAtLocation(anchor, Gravity.TOP or Gravity.START, x.coerceAtLeast(0), y.coerceAtLeast(0))
    }

    interface CallBack {
        fun onRuleEdit()
        fun onRuleDisable()
    }
}
