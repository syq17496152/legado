package io.legado.app.help.source

import io.legado.app.data.entities.BookSourcePart
import io.legado.app.data.entities.RssSource
import io.legado.app.utils.NetworkUtils

/**
 * Issue-6 书源/订阅源布局重构扩展函数
 *
 * 设计文档：docs/specs/source-layout-bookshelf-style/design.md
 * - ADR-4/ADR-12: sourceInitial() 复用 Grid Adapter 已有的简单实现
 *   避免中英文/emoji/符号等边界情况的处理复杂度
 * - ADR-11: sourceUrlHost() 复用 lastHost 字段（优先于源URL截取）
 *   BookSourcePart 是独立 data class 不继承 BaseSource，需分别定义扩展函数
 *
 * 注意：本文件只提供 UI 显示用的便捷方法，不修改数据源。
 * lastHost 由 SourceLastHostHelper.kt 在 AnalyzeUrl 解析后回填。
 */

/**
 * 书源首字（参考 BookSourceAdapterGrid L79 已有实现）
 * 空名称返回空字符串（不显示"?"，与 Grid 模式一致）
 */
fun BookSourcePart.sourceInitial(): String {
    return bookSourceName.firstOrNull()?.toString() ?: ""
}

/**
 * 订阅源首字（参考 RssSourceAdapterGrid L79 已有实现）
 * 空名称返回空字符串（不显示"?"，与 Grid 模式一致）
 */
fun RssSource.sourceInitial(): String {
    return sourceName.firstOrNull()?.toString() ?: ""
}

/**
 * 书源 host 显示字符串（ADR-11: 优先用 lastHost，回退 bookSourceUrl）
 *
 * 数据流：
 *   1. SourceLastHostHelper.fillBack 在 AnalyzeUrl 解析后回填 lastHost
 *   2. UI 显示时调用 sourceUrlHost() 提取 host
 *   3. 异常输入（空/纯协议名/"http:///"/"https:///"）返回 "#" 分组
 *
 * 注意：BookSourcePart 不继承 BaseSource，无法用 getKey()，直接用 bookSourceUrl 字段
 */
fun BookSourcePart.sourceUrlHost(): String {
    val origin = lastHost ?: bookSourceUrl
    return extractHost(origin)
}

/**
 * 订阅源 host 显示字符串（ADR-11: 优先用 lastHost，回退 sourceUrl）
 *
 * 数据流：同 BookSourcePart.sourceUrlHost()
 *
 * 注意：RssSource 继承 BaseSource，可用 sourceUrl 字段
 */
fun RssSource.sourceUrlHost(): String {
    val origin = lastHost ?: sourceUrl
    return extractHost(origin)
}

/**
 * 通用 host 提取逻辑
 *
 * 参考实现：BookSourceActivity.getSourceHost (L916-934)
 * 异常输入处理：空/纯协议名"http"/"https"/"http:///"/"https:///" 返回 "#"
 * 正常输入走 NetworkUtils.getSubDomainOrNull
 *
 * 注意：与 RssSourceActivity.getSourceHost (L597-609) 不一致——后者无异常输入处理，
 *       else 分支 fallback 是 `?: origin`（返回原值），本函数统一对齐 BookSourceActivity。
 *       ADR-15 修复：RssSourceActivity.getSourceHost 已对齐本函数逻辑。
 */
private fun extractHost(origin: String): String {
    val trimmed = origin.trim()
    if (trimmed.isEmpty() || trimmed.equals("http", true) || trimmed.equals("https", true)
        || trimmed.startsWith("http:///", true) || trimmed.startsWith("https:///", true)
    ) return "#"
    return if (trimmed.startsWith("http", ignoreCase = true)) {
        NetworkUtils.getSubDomainOrNull(trimmed) ?: "#"
    } else {
        // host 补 http:// 前缀再提取子域名，支持 "www.example.com" → "example.com" 归并
        NetworkUtils.getSubDomainOrNull("http://$trimmed") ?: "#"
    }
}
