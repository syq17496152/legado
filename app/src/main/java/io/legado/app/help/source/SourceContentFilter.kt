package io.legado.app.help.source

import io.legado.app.constant.AppLog
import io.legado.app.data.entities.BaseSource
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.RssSource
import io.legado.app.help.config.AppConfig
import io.legado.app.utils.splitNotBlank
import java.util.regex.PatternSyntaxException

/**
 * M2 SourceContentFilter — 统一 WebView 资源 URL 过滤
 *
 * 机制互补：抽取 RssSource 的 contentWhitelist/contentBlacklist 过滤逻辑为共享组件，
 * BookSource 视频源 WebView 通过 AppConfig 获得同样的过滤能力。
 *
 * 过滤规则（与 ReadRssActivity.kt 原有逻辑一致）：
 * - 黑名单非空时：URL 命中黑名单（startsWith/regex）→ 过滤（return false）
 * - 黑名单为空时检查白名单：白名单非空时，URL 命中白名单 → 放行（return true）；未命中 → 过滤（return false）
 * - 黑白名单都为空 → 放行（return true）
 */
object SourceContentFilter {

    /**
     * 判断 URL 是否允许加载
     * @param url 待检查的 URL
     * @param source 源（RssSource 读自身字段，BookSource 读 AppConfig 全局配置）
     * @return true=允许加载，false=应拦截
     */
    fun filterUrl(url: String, source: BaseSource): Boolean {
        val (blacklist, whitelist) = when (source) {
            is RssSource -> source.contentBlacklist to source.contentWhitelist
            is BookSource -> AppConfig.bookSourceContentBlacklist to AppConfig.bookSourceContentWhitelist
            else -> return true
        }
        // 黑名单优先
        if (!blacklist.isNullOrBlank()) {
            val patterns = blacklist.splitNotBlank(",")
            for (pattern in patterns) {
                try {
                    if (url.startsWith(pattern) || url.matches(pattern.toRegex())) {
                        AppLog.putDebugWithTag(
                            AppLog.TAG_SOURCE_MECHANISM,
                            "黑名单命中 过滤URL patternLen=${pattern.length}",
                            level = AppLog.Level.INFO
                        )
                        return false
                    }
                } catch (e: PatternSyntaxException) {
                    AppLog.putDebugWithTag(
                        AppLog.TAG_SOURCE_MECHANISM,
                        "黑名单规则正则语法错误 patternLen=${pattern.length}",
                        e,
                        AppLog.Level.WARN
                    )
                }
            }
        } else if (!whitelist.isNullOrBlank()) {
            val patterns = whitelist.splitNotBlank(",")
            for (pattern in patterns) {
                try {
                    if (url.startsWith(pattern) || url.matches(pattern.toRegex())) {
                        return true
                    }
                } catch (e: PatternSyntaxException) {
                    AppLog.putDebugWithTag(
                        AppLog.TAG_SOURCE_MECHANISM,
                        "白名单规则正则语法错误 patternLen=${pattern.length}",
                        e,
                        AppLog.Level.WARN
                    )
                }
            }
            // 白名单非空但未命中，过滤
            AppLog.putDebugWithTag(
                AppLog.TAG_SOURCE_MECHANISM,
                "白名单未命中 过滤URL urlLen=${url.length}",
                level = AppLog.Level.INFO
            )
            return false
        }
        return true
    }
}
