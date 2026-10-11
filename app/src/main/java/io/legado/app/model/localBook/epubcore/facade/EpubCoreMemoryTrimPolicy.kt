package io.legado.app.model.localBook.epubcore.facade

import android.content.ComponentCallbacks2

/**
 * EPUB 阅读面在**系统内存压力**下的释放判据（S10 配对，纯函数 ⇒ JVM 可测）。
 *
 * 背景：阅读面的离屏 WebView 是**整个阅读链路最大的内存头**（单实例约 20~50MB）。
 * 它们只服务"预取后续章节"，属**可重建**资源 ⇒ 内存吃紧时宁可释放重建，
 * 也不该让系统把整个进程杀掉（用户重进 App 的代价远大于重建一个 WebView）。
 *
 * 判据：从 [ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE]（系统提示"内存尚可但要留意"）
 * 起即释放；[ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN]（阅读页已不可见）同样释放。
 * 更低的 level（系统未给出压力信号）不动作，避免无谓的 WebView 反复重建。
 */
internal object EpubCoreMemoryTrimPolicy {

    fun shouldRelease(level: Int): Boolean =
        level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE
}