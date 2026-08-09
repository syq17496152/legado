package io.legado.app.help.source

import io.legado.app.constant.AppLog
import io.legado.app.data.entities.BaseSource
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.RssSource
import io.legado.app.help.config.AppConfig

/**
 * M5 SourceWebViewController — 统一 WebView JS 注入控制
 *
 * 机制互补：抽取 RssSource 的 injectJs 字段为共享组件，
 * BookSource 视频源 WebView 通过 AppConfig.bookSourceInjectJs 获得同样的 JS 注入能力。
 *
 * 设计说明：
 * - 仅统一 injectJs 注入脚本（核心价值），不统一 enableJs
 * - 原因：BookSource 视频源 WebView 从 WebViewPool 获取时默认 javaScriptEnabled=true，
 *   强行通过 AppConfig 覆盖会引入回归风险
 *
 * 注入策略：
 * - RssSource 读自身 injectJs 字段（onPageFinished 时注入）
 * - BookSource 读 AppConfig.bookSourceInjectJs 全局配置（默认空=不注入=沿用现有行为）
 */
object SourceWebViewController {

    /**
     * 获取源需要注入的 JS 脚本
     * @param source 源（RssSource 读自身字段，BookSource 读 AppConfig 全局配置）
     * @return JS 脚本（null 或空表示不注入）
     */
    fun getInjectJs(source: BaseSource): String? {
        val js = when (source) {
            is RssSource -> source.injectJs
            is BookSource -> AppConfig.bookSourceInjectJs?.takeIf { it.isNotBlank() }
            else -> null
        }
        if (!js.isNullOrBlank()) {
            AppLog.putDebugWithTag(
                AppLog.TAG_SOURCE_MECHANISM,
                "注入JS sourceType=${source::class.simpleName} jsLen=${js.length}",
                level = AppLog.Level.INFO
            )
        }
        return js
    }
}
