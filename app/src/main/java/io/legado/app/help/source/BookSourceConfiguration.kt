package io.legado.app.help.source

import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.rule.BookListRule

/**
 * 3.3.1（R 批，J10 硬阻断口径）：判定书源配置是否**全空**。
 *
 * 移植自上游 `help/source/BookSourceConfiguration.kt`（NG 分支），用途：导入时把「没有任何抓取配置」
 * 的空壳源标记为无效并**硬阻断**（不可勾选、不导入），避免导入后书架满面却什么也搜不到。
 *
 * 三条设计约束（照抄上游语义，勿随意放宽）：
 * 1. **只判配置全空**，不把「缺少某个入口」或「联网失败」当作无效 —— 源站临时不可达 ≠ 源无效；
 * 2. **不执行脚本**、不调用会创建默认规则对象的 getter、**不修改书源**（保持纯函数）；
 * 3. `eventListener` / `customButton` / `canReName` / `imageStyle` 等**显示/行为开关**不算抓取配置，
 *    但 `eventListener`/`customButton` 一旦开启说明源有自定义行为 ⇒ 不算全空。
 */
fun BookSource.isEmptyConfiguration(): Boolean {
    if (eventListener || customButton || !blank(
            searchUrl, exploreUrl, jsLib, loginUrl, loginUi, loginCheckJs,
            bookUrlPattern, coverDecodeJs, header, exploreScreen,
        )
    ) return false
    if (!ruleSearch.emptyListRule() || !ruleSearch?.checkKeyWord.isNullOrBlank()
        || !ruleExplore.emptyListRule()
    ) return false
    ruleBookInfo?.let {
        if (!blank(
                it.init, it.name, it.author, it.intro, it.kind, it.lastChapter,
                it.updateTime, it.coverUrl, it.tocUrl, it.wordCount, it.downloadUrls
            )
        ) return false
    }
    ruleToc?.let {
        if (!blank(
                it.preUpdateJs, it.chapterList, it.chapterName, it.chapterUrl,
                it.formatJs, it.isVolume, it.isVip, it.isPay, it.updateTime, it.nextTocUrl
            )
        ) return false
    }
    ruleContent?.let {
        if (!blank(
                it.content, it.subContent, it.title, it.nextContentUrl, it.webJs,
                it.sourceRegex, it.replaceRegex, it.imageDecode, it.payAction, it.callBackJs
            )
        ) return false
    }
    ruleReview?.let {
        if (!blank(
                it.reviewUrl, it.avatarRule, it.contentRule, it.postTimeRule,
                it.reviewQuoteUrl, it.voteUpUrl, it.voteDownUrl, it.postReviewUrl,
                it.postQuoteUrl, it.deleteUrl
            )
        ) return false
    }
    return true
}

private fun BookListRule?.emptyListRule(): Boolean = this == null || blank(
    bookList, name, author, intro, kind, lastChapter, updateTime, bookUrl, coverUrl, wordCount,
)

private fun blank(vararg values: String?): Boolean = values.all { it.isNullOrBlank() }