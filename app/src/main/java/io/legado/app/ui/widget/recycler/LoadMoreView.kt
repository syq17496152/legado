package io.legado.app.ui.widget.recycler

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import androidx.annotation.ColorRes
import androidx.compose.ui.graphics.toArgb
import io.legado.app.R
import io.legado.app.databinding.ViewLoadMoreBinding
import io.legado.app.lib.theme.accentColor
import io.legado.app.ui.widget.compose.AppSemanticColors
import io.legado.app.ui.widget.compose.showComposeConfirmDialog
import io.legado.app.utils.ColorUtils
import io.legado.app.utils.activity
import io.legado.app.utils.dpToPx
import io.legado.app.utils.getCompatColor
import io.legado.app.utils.invisible
import io.legado.app.utils.visible

@Suppress("unused")
class LoadMoreView(context: Context, attrs: AttributeSet? = null) : FrameLayout(context, attrs) {
    private val binding = ViewLoadMoreBinding.inflate(LayoutInflater.from(context), this)
    private var errorMsg = ""

    private var onClickListener: OnClickListener? = null

    var isLoading = false
        private set

    var hasMore = true
        private set

    init {
        super.setOnClickListener {
            if (!showErrorDialog(it)) {
                onClickListener?.onClick(it)
            }
        }
        initErrorView()
    }

    /** F142：错误态语义色与描边（danger 圆点 + 描边胶囊），色值单源取 AppSemanticColors */
    private fun initErrorView() {
        val danger = AppSemanticColors.Danger.toArgb()
        binding.errorDot.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(danger)
        }
        binding.errorContainer.background = GradientDrawable().apply {
            cornerRadius = 12.dpToPx().toFloat()
            setStroke(1.dpToPx(), ColorUtils.adjustAlpha(danger, ERROR_STROKE_ALPHA))
        }
        binding.tvErrorHint.setTextColor(context.accentColor)
    }

    override fun setOnClickListener(l: OnClickListener?) {
        this.onClickListener = l
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        layoutParams.width = LayoutParams.MATCH_PARENT
    }

    fun startLoad() {
        isLoading = true
        binding.tvText.invisible()
        binding.errorContainer.invisible()
        binding.rotateLoading.visible()
    }

    fun stopLoad() {
        isLoading = false
        binding.rotateLoading.inVisible()
    }

    fun hasMore() {
        errorMsg = ""
        hasMore = true
        startLoad()
    }

    fun noMore(msg: String? = null) {
        stopLoad()
        errorMsg = ""
        hasMore = false
        binding.errorContainer.invisible()
        if (msg != null) {
            binding.tvText.text = msg
        } else {
            binding.tvText.setText(R.string.bottom_line)
        }
        binding.tvText.visible()
    }

    /**
     * 加载失败态（F142）。
     *
     * [msg] 为错误详情：非空时点击页脚会弹出详情弹窗（弹窗内提供重试），故提示文案为「点击查看详情」；
     * 为空时点击页脚直接重试，提示文案为「点击重试」。摘要优先用调用方文案 [text]，否则取错误详情首行
     * （错误详情是完整堆栈，单行省略展示首行，全量可在详情弹窗中查看/复制）。
     */
    fun error(msg: String?, text: String = "") {
        stopLoad()
        hasMore = false
        errorMsg = msg ?: ""
        binding.tvErrorSummary.text = text.ifEmpty {
            context.getString(R.string.error_load_msg, errorSummaryLine())
        }
        binding.tvErrorHint.setText(
            if (errorMsg.isBlank()) R.string.dynamic_click_retry else R.string.error_view_detail
        )
        binding.tvText.invisible()
        binding.errorContainer.visible()
    }

    /** 错误详情首行（跳过空行），供页脚摘要单行展示 */
    private fun errorSummaryLine(): String =
        errorMsg.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()

    fun setLoadingColor(@ColorRes color: Int) {
        binding.rotateLoading.loadingColor = context.getCompatColor(color)
    }

    fun setLoadingTextColor(@ColorRes color: Int) {
        binding.tvText.setTextColor(context.getCompatColor(color))
    }

    private fun showErrorDialog(view: View): Boolean {
        if (errorMsg.isBlank()) {
            return false
        }
        activity?.showComposeConfirmDialog(
            title = context.getString(R.string.error),
            message = errorMsg,
            positiveText = context.getString(R.string.retry),
            showNegative = false,
            // 错误详情是**完整堆栈**（实测 40+ 行）：必须放进高度受限、可滚动的正文区，
            // 否则正文把操作行挤出屏幕 ⇒ 「重试」不可见也不可点，恢复路径等于断了
            messageInContent = true,
            onPositive = {
                onClickListener?.onClick(view)
            }
        )
        return true
    }

    companion object {
        /** 错误态胶囊描边透明度（danger 淡化描边，避免页脚视觉过重） */
        private const val ERROR_STROKE_ALPHA = 0.35f
    }

}
