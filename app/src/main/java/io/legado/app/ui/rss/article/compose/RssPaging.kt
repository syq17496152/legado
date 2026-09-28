package io.legado.app.ui.rss.article.compose

import io.legado.app.data.entities.RssArticle

/**
 * 订阅列表**翻页时机**的单源纯函数（CF 6.2 RSS 文章五样式族；2026-09-28 用户报障修复）。
 *
 * 为什么抽成纯函数：换装 Compose 后，翻页判定写在 [RssArticlesComposeList] 内联的 `derivedStateOf` 里，
 * 与样式 5（自由布局，View 路径）的滚动监听口径**各行其是** —— 前者恒 `threshold = 0`（必须滑到真正的
 * 最后一条才请求），后者是 `itemCount - 2`（末项前一条即请求）与 `actualItemCount - 5`（预加载源）。
 * 两条口径散落两处、无法单测，且 Compose 侧明显落后于用户已认可跟手度的自由布局。
 * 本文件把「阈值」与「判定」收敛为不依赖任何 Android API 的纯函数，可被 JVM 单测直接钉死。
 *
 * ⚠️ 本文件**不得**引入 `android.*` 依赖（否则纯函数层不再可单测）。
 */
object RssPagingThresholdResolver {

    /**
     * 预加载源 + 瀑布流（样式 3）的提前量（条）。
     *
     * 沿用原 StaggeredGrid 分支的 `actualItemCount - 5` 口径：瀑布流卡片高度不定、单屏条目少，
     * 提前量需要更大才能盖住「解码 → 回填比例 → 重排」的耗时。
     */
    const val PRELOAD_THRESHOLD = 5

    /**
     * 其余情形的提前量（条）。
     *
     * **必须 ≥ 1，严禁回退为 0**：0 意味着「末个可视项 == 最后一条数据」才发请求，用户在触底后
     * 必然经历「网络 RTT + Room 落库 + Flow 回流 + 重组」的空窗（体感即「卡」）。
     * 取 1 对齐自由布局的 `itemCount - 2`（末项前一条即可见）——该跟手度已获用户真机认可。
     */
    const val DEFAULT_THRESHOLD = 1

    /**
     * 解析提前量（条）：`末个可视项下标 >= itemCount - 1 - 提前量` 即触发翻页。
     *
     * @param style     文章样式（越界取值按默认阈值处理 —— 导入的来源 JSON 可携带任意 `articleStyle`）
     * @param isPreload 订阅源是否开启预加载
     */
    fun resolve(style: Int, isPreload: Boolean): Int =
        if (style == 3 && isPreload) PRELOAD_THRESHOLD else DEFAULT_THRESHOLD
}

/**
 * 翻页判定（纯函数，六要素）。
 *
 * 单源化 `RssArticlesComposeList` 的 `snapshotFlow` 判定体：把「是否该取下一页」从组合作用域
 * 里彻底剥离，既让判定可单测，也让 `snapshotFlow` 只观察 State（见 AD-03）。
 */
object RssPagingDecision {

    /**
     * 是否应当取下一页。
     *
     * 全部条件必须同时成立（任一不成立即不触发）：
     * - [itemCount] > 0：空表不翻页（原 View 路径「首页条目少于半屏永远无法翻页」的死角在此被消除 ——
     *   只要有条目且已进入末段阈值即触发）；
     * - [hasMore]：上一页返回已判定「没有下一页」时不再自动翻页（页脚显示「我是有底线的」）；
     * - !isLoading：**在途闸**，有加载在飞时不得重复请求同一页（宿主 `isLoadingState` 的唯一判据）；
     * - [lastVisibleIndex] >= 0：尚未测量出任何可视项时不判定（避免初始帧误触发）；
     * - [lastVisibleIndex] >= itemCount - 1 - [threshold]：进入末段阈值。
     *
     * @param lastVisibleIndex 末个可视项下标（无可视项时传 -1）
     * @param threshold        提前量（条），由 [RssPagingThresholdResolver.resolve] 单源给出
     */
    fun shouldLoadMore(
        itemCount: Int,
        hasMore: Boolean,
        isLoading: Boolean,
        lastVisibleIndex: Int,
        threshold: Int
    ): Boolean {
        if (itemCount <= 0) return false
        if (!hasMore || isLoading) return false
        if (lastVisibleIndex < 0) return false
        return lastVisibleIndex >= itemCount - 1 - threshold
    }
}

/**
 * 列表条目的**稳定 key**（等价于 `RssArticle` 实体主键 `origin + link + sort`）。
 *
 * 为什么必须预计算且与主键一致：①LazyList 依赖 key 做条目复用与首项锚定，key 不稳定会导致
 * 滚动中条目被整条重建（封面重新取图）；②必须在**数据变化时**算一次，而非每次组合为每个条目
 * 重新拼接字符串（滚动期被反复调用）。
 */
object RssArticleKey {
    fun of(article: RssArticle): String = "${article.origin}|${article.link}|${article.sort}"
}