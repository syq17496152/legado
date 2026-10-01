package io.legado.app.ui.rss.article

/**
 * 翻页「是否还有下一页」的**纯函数**判定（可 JVM 单测，不依赖任何 `android.*`）。
 *
 * 为什么抽成纯函数（2026-10-01 用户报障修复 · 缺陷①）：
 * `RssArticlesViewModel.loadMoreSuccess()` 此前把三态判定散落在 DB 副作用之间，且
 * **「追加成功」出口忘记发完成信号**（只有「空结果」「首末条均已入库」两处 `postValue(false)`）。
 * 宿主 `RssArticlesFragment` 的在途闸 `isLoadingState` 只由 `loadFinallyLiveData` /
 * `loadErrorLiveData` 复位 ⇒ 第一次成功翻页后闸门永为 `true` ⇒ `scrollToBottom()` 首行守卫恒真
 * （后续翻页全被拦），同时 `loadMoreView.stopLoad()` 不被调用（页脚永久转圈）。
 * 用户观感即「最多只能翻两页 + 一直刷新翻不动」。
 *
 * 本函数把三态判定收敛为单源纯函数，调用方只需按其结果 `postValue` 一次，
 * 并从结构上杜绝「某分支漏发信号」——该不变式由 `RssPagingSignalContractTest` 以源码契约钉死
 * （`loadMoreSuccess` 内 `postValue` 恰 1 处且函数体无提前 `return`）。
 */
object RssLoadMoreOutcome {

    /**
     * 本次翻页后是否仍存在下一页。
     *
     * - [articlesEmpty] = `true`：该页没有返回任何条目（源已到底 / 该页无数据）⇒ 判定到底；
     * - [firstItemInDb] 与 [lastItemInDb] **同时**为 `true`：该页首末条均已在库 ⇒ 视为重复页
     *   （源站忽略了分页参数或该页内容已被抓过）⇒ 判定到底，避免对同一 URL 无限重复请求；
     * - 其余情形（有新增条目）⇒ 仍有下一页，调用方应把条目追加入库。
     *
     * 注意：本函数**不产生副作用**，「是否 append」由调用方依据返回值决定（`true` 才 append）。
     */
    fun hasMore(articlesEmpty: Boolean, firstItemInDb: Boolean, lastItemInDb: Boolean): Boolean =
        !articlesEmpty && !(firstItemInDb && lastItemInDb)
}