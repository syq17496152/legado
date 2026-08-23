package io.legado.app.ui.widget.compose

import android.graphics.drawable.Animatable
import android.widget.ImageView
import com.bumptech.glide.Glide

/**
 * 统一的 Compose AndroidView 图片释放逻辑。
 *
 * Compose 中用 [androidx.compose.ui.viewinterop.AndroidView] 承载 ImageView 加载图片时，
 * Glide 请求绑定的是宿主 Activity/Fragment 生命周期而非 view 的 attach 状态；离开 composition
 * 时若不主动清理，gif/动画会持续解码、bitmap 不释放，列表滚动或主题频繁切换会累积导致 OOM。
 *
 * 在这些 AndroidView 上加 `onRelease = { it.releaseComposeImage() }` 即可统一释放，避免逐处重复。
 */
fun ImageView.releaseComposeImage() {
    (drawable as? Animatable)?.stop()
    // view 离开 composition 时其 context 可能正在销毁，Glide.with 可能抛异常，需吞掉
    val appContext = context.applicationContext ?: context
    runCatching { Glide.with(appContext).clear(this) }
    setImageDrawable(null)
}
