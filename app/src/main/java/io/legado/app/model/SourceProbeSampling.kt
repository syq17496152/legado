package io.legado.app.model

import io.legado.app.data.entities.BookChapter

/**
 * 目录取样（**巡检服务与质量体检的唯一权威口径**，纯函数，JVM 可测）。
 *
 * 背景：同一套「过滤卷标题占位 → 取前 2 条（第 1 条试读正文，第 2 条供 `nextChapterUrl` 推导）」
 * 曾在两处各写一份：
 *  · `CheckSourceService.checkBook`（用户侧「校验书源」链路）——**取样后直接 `first()`**，
 *    当目录条目**全是卷标题占位**（`isVolume && url.startsWith(title)`，无真实链接）时取样为空
 *    ⇒ 抛 `NoSuchElementException`；该异常不是 `NoStackTraceException`，会被上游 `when`
 *    归因为「网站失效」并把可用源写成死站（用户据此误删源）；
 *  · `SourceQualityChecker.probeBookDetail`（导入体检链路）——对同一序列**已有**空守卫。
 * 两处漂移的根因是"规则写在两处"：本函数把它们收敛为同一实现，并明确**取样可能为空**的契约。
 *
 * 契约（调用方必须遵守）：
 *  · 返回**空列表** ⇒ 判定「目录不可用」（目录为空 / 全为卷标题占位），**禁止**对其调用
 *    `first()` / `last()` 等无守卫取值；
 *  · 返回非空 ⇒ 第 1 条用于正文试读，`getOrNull(1)?.url ?: first().url` 用于下一章地址。
 */
internal fun sampleProbeChapters(chapters: List<BookChapter>): List<BookChapter> {
    return chapters.asSequence()
        // 卷标题占位（url 就是标题本身）不带真实链接，作试读起点会必然失败
        .filter { !(it.isVolume && it.url.startsWith(it.title)) }
        .take(2)
        .toList()
}
