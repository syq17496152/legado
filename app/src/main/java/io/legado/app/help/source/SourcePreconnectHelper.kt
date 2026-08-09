package io.legado.app.help.source

import io.legado.app.constant.AppLog
import io.legado.app.help.http.warmUpConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/**
 * M4 SourcePreconnectHelper — 统一预连接组件（source-arch-mutual-borrow spec design.md AD-04）
 *
 * 机制层互补：抽取 Rss.kt getArticlesAwait 中的 F-P1-F 预连接实现，
 * 让 BookSource（BookChapterList）也获得目录加载后预连接前 N 章的能力。
 *
 * 数据流：
 *   1. 调用方在列表/目录加载完成后，传入 URL 列表和预连接数量 N
 *   2. 本组件并行对前 N 个 URL 发起 HEAD 预连接（warmUpConnection）
 *   3. kotlin.runCatching 包裹，失败不影响主流程
 *   4. 预连接完成后，后续点击章节/文章时减少 300-1000ms 连接建立时间
 *
 * 行为等同 Rss.kt 原内联实现（coroutineScope + async + awaitAll + runCatching）。
 */
object SourcePreconnectHelper {

    /**
     * 预连接前 N 个 URL（F-P1-F 机制）
     *
     * @param urls URL 列表（取前 N 个非空 URL）
     * @param n 预连接数量（默认 3）
     */
    suspend fun preconnectTopN(urls: List<String>, n: Int = 3) {
        kotlin.runCatching {
            coroutineScope {
                urls.take(n).mapIndexed { index, url ->
                    async(Dispatchers.IO) {
                        if (url.isNotBlank()) {
                            AppLog.putDebugWithTag(
                                AppLog.TAG_SOURCE_MECHANISM,
                                "预连接: 第${index + 1}个",
                                level = AppLog.Level.INFO
                            )
                            warmUpConnection(url)
                        }
                    }
                }.awaitAll()
            }
        }
    }
}
