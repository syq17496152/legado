package io.legado.app.lib.theme.view

import android.content.Context
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatCheckBox
import io.legado.app.lib.theme.accentColor
import io.legado.app.utils.applyTint

/**
 * 主题强调色的多选框（accent tint 由本组件在 init 内施加）。
 *
 * CE 5.2（2026-09-25）：构造签名放宽为 `attrs: AttributeSet? = null`（加 `@JvmOverloads`），
 * 使**程序化构造**成为可能——原签名 `(context, attrs: AttributeSet)` 为非空，XML 退役后
 * 页面只能靠「伪造 AttributeSet」来建视图（行为不等价）；XML 膨胀路径（attrs 非空）**行为一字不变**。
 */
class ThemeCheckBox @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : AppCompatCheckBox(context, attrs) {

    private var isUserAction = false

    init {
        if (!isInEditMode) {
            applyTint(context.accentColor)
        }
    }

    override fun performClick(): Boolean {
        isUserAction = true
        val result = super.performClick()
        isUserAction = false
        return result
    }

    fun setOnUserCheckedChangeListener(listener: ((Boolean) -> Unit)?) {
        if (listener == null) {
            return super.setOnCheckedChangeListener(null)
        }
        super.setOnCheckedChangeListener { _, isChecked ->
            if (isUserAction) {
                listener.invoke(isChecked)
            }
        }
    }

}
