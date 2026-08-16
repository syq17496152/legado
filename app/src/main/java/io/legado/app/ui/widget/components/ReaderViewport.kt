package io.legado.app.ui.widget.components

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.viewinterop.AndroidView
import io.legado.app.ui.book.read.page.ReadView
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred

/**
 * 阅读器正文尺寸接缝（P2-reader §3 阅读器族，纯桥接零 UI 规格）。
 *
 * [AndroidView] 桥接正文内核 [ReadView]（AD-02 正文零改动，N 不迁移）；
 * 内核原布局，Compose 侧不包 padding；宽/高/密度经 [onViewportSizeChange] 上报
 * （等价 `ChapterProvider::upViewSize`），返回 [Deferred]<[ReadView]> 供宿主
 * `awaitViewport` 挂起等待正文就绪后协调浮层尺寸。
 * 规格：ui-standards §3.4 `ReaderViewport`（task 12.2C，from HapeLee）。
 */
@Composable
fun ReaderViewport(
    factory: (Context) -> ReadView,
    onViewportSizeChange: (width: Int, height: Int, density: Float) -> Unit,
    modifier: Modifier = Modifier,
): Deferred<ReadView> {
    val density = LocalDensity.current.density
    val viewportReady = remember { CompletableDeferred<ReadView>() }
    AndroidView(
        factory = { ctx ->
            factory(ctx).also { view ->
                view.addOnLayoutChangeListener { _, left, top, right, bottom, _, _, _, _ ->
                    onViewportSizeChange(right - left, bottom - top, density)
                    viewportReady.complete(view)
                }
            }
        },
        modifier = modifier,
    )
    return viewportReady
}
