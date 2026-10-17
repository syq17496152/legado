package io.legado.app.ui.config

import android.content.Context
import android.graphics.Canvas
import android.view.View
import io.legado.app.help.config.EpubLoadingTemplate
import io.legado.app.ui.book.read.epub.loading.EpubLoadingOverlay

/**
 * 首屏加载模板预览承载 View（Phase 3）。
 *
 * 用**真实绘制器** [EpubLoadingOverlay] 画全屏预览（与开书首屏同一实现 ⇒ 预览所见即开书所见），
 * 而非另画示意图。属性 setter 触发 [invalidate]，由承载它的 Compose `AndroidView` 的 `update` 驱动
 * （昼夜切换 ⇒ 重新赋值 `night` ⇒ 同屏重绘）。
 *
 * insets 取 `null`：预览面在弹窗内、非全屏，系统栏 insets 已被弹窗消费；
 * `null` 会被绘制器按「零 insets」处理（见 [EpubLoadingOverlay.draw]），不影响构图。
 */
internal class EpubLoadingPreviewView(context: Context) : View(context) {

    private val overlay = EpubLoadingOverlay(context.resources)

    var template: EpubLoadingTemplate? = null
        set(value) {
            field = value
            invalidate()
        }

    var night: Boolean = false
        set(value) {
            field = value
            invalidate()
        }

    override fun onDraw(canvas: Canvas) {
        val current = template ?: return
        overlay.draw(
            canvas = canvas,
            template = current,
            night = night,
            message = PreviewMessage,
            failed = false,
            insets = null
        )
    }

    private companion object {
        const val PreviewMessage = "正在加载…"
    }
}
