package io.legado.app.help.video

import io.legado.app.constant.AppLog
import io.legado.app.data.entities.SearchBook

/**
 * 书源播放列表单例注入（video-playlist-continuity AD-04）
 *
 * 书源视频入口（搜索/发现/全局搜索）点入播放器时，把"当前列表+所点索引"注入此处，
 * 播放器经 [VideoPlay] 消费后用于跨影片续播（末集下滑接列表下一个视频）。
 *
 * 生命周期三铁律（红队 R5-1）：
 * 1. **consume 一次性**：openVideo→initSource 链消费后置 consumed，同影片二次进入不重复消费
 * 2. **bookUrl 校验**：滑动追加时校验当前 book.bookUrl ∈ books（书架/历史重进陌生影片视为无列表）
 * 3. **onDestroy 清理**：VideoPlayerActivity destroy 后清空，防残留列表导致后续单影片错误续播
 *
 * 仿 RssSearchSourceHolder 先例（订阅源列表注入）。放 help/video 供 model 层引用（不引 ui 包）。
 *
 * 诊断埋点（2026-09-15 日志盲区审计 P0：本类原为 0 埋点，"下一部切换失败"排障时
 * 队列成员判定/邻居查找全黑盒）：统一前缀 VideoPlaylistDiag，只记队列规模/索引/成员判定
 * 结果，不输出影片名等业务内容。
 */
object VideoPlaylistHolder {

    var books: List<SearchBook>? = null
        private set

    var index: Int = 0
        private set

    private var consumed: Boolean = false

    /**
     * 入口注入（SearchActivity / ExploreShowActivity / ExploreFragment 点击时调用）
     * @param list 当前呈现的视频列表（含所点影片及之后的所有项）
     * @param clickedIndex 所点影片在 list 中的索引
     */
    fun set(list: List<SearchBook>, clickedIndex: Int) {
        books = list
        index = clickedIndex
        consumed = false
        AppLog.put("VideoPlaylistDiag set: size=${list.size}, clickedIndex=$clickedIndex")
    }

    /**
     * 消费（initSource 链调用，一次性）
     * @return books/实际索引（按 currentBookUrl 匹配校正）；已消费或不合法返回 null
     */
    fun consume(currentBookUrl: String?): Pair<List<SearchBook>, Int>? {
        if (consumed) return null
        val list = books ?: return null
        if (list.isEmpty()) return null
        // 铁律2：当前 bookUrl 必须在列表中（防止列表与实际点击影片错位）
        val idx = if (currentBookUrl != null) {
            val matched = list.indexOfFirst { it.bookUrl == currentBookUrl }
            if (matched < 0) {
                // 失败原因必须留痕：列表与实际影片错位是"无队列"类问题的唯一黑盒点
                AppLog.put("VideoPlaylistDiag consume: matched<0 拒绝, size=${list.size}, urlHash=${currentBookUrl.hashCode()}")
                return null
            }
            matched
        } else {
            index
        }
        consumed = true
        AppLog.put("VideoPlaylistDiag consume: ok, size=${list.size}, idx=$idx")
        return list to idx
    }

    /** 铁律2：bookUrl 是否在注入列表中（滑动追加时校验） */
    fun containsBookUrl(bookUrl: String?): Boolean {
        if (bookUrl.isNullOrBlank()) return false
        val contained = books?.any { it.bookUrl == bookUrl } == true
        // 只在"不在队列"结果留痕（防上下滑排障黑盒）；命中时由调用方 switchToBookFromList 的
        // switchToBook 埋点覆盖，避免同链路日志翻倍
        if (!contained) {
            AppLog.put("VideoPlaylistDiag containsBookUrl: false, size=${books?.size ?: -1}, urlHash=${bookUrl.hashCode()}")
        }
        return contained
    }

    /** 取指定影片在列表中的邻居（direction: +1 下一部 / -1 上一部） */
    fun neighborOf(bookUrl: String?, direction: Int): SearchBook? {
        val list = books ?: run {
            AppLog.put("VideoPlaylistDiag neighborOf: books=null, dir=$direction")
            return null
        }
        if (bookUrl.isNullOrBlank()) return null
        val idx = list.indexOfFirst { it.bookUrl == bookUrl }
        if (idx < 0) {
            AppLog.put("VideoPlaylistDiag neighborOf: idx<0 拒绝, size=${list.size}, dir=$direction, urlHash=${bookUrl.hashCode()}")
            return null
        }
        val neighbor = list.getOrNull(idx + direction)
        if (neighbor == null) {
            // 边界（第一部的上一部/最后一部的下一部）属正常流程，DEBUG 级不干扰 App 日志页
            AppLog.putDebugWithTag(
                "VideoPlaylist",
                "VideoPlaylistDiag neighborOf: 边界, idx=$idx, dir=$direction, size=${list.size}"
            )
        }
        return neighbor
    }

    /** 铁律3：清理 */
    fun clear() {
        if (books != null) {
            AppLog.put("VideoPlaylistDiag clear: size=${books?.size}")
        }
        books = null
        index = 0
        consumed = false
    }
}
