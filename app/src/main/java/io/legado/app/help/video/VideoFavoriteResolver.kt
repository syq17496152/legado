package io.legado.app.help.video

import io.legado.app.data.entities.RssArticle
import io.legado.app.data.entities.RssReadRecord
import io.legado.app.data.entities.RssStar

/**
 * 视频播放器「收藏」判定与派生的**纯函数**单源（video-live-favorite-fix AD-01）。
 *
 * 为什么单独抽纯函数（而非直接写在 VideoPlay 里）：
 * - `VideoPlay` 是依赖 `appCtx` 的全局 `object`，其逻辑无法直接跑 JVM 单测；
 * - 收藏的「能不能收藏」曾散落三处（Fragment / Activity / ViewModel）且口径互不相同，
 *   历史上已发生「顶栏星标与悬浮星标显示不一致」的漂移 ⇒ 必须收口到同一函数。
 *
 * 本文件**禁止**引入任何 Android 依赖（不做 IO、不持有 Context、不访问数据库），
 * 只做「输入 → 输出」的纯映射，保证 100% 可 JVM 单测（工程级测试强制配对）。
 */
object VideoFavoriteResolver {

    /**
     * 当前可收藏条目的解析优先级（与既有 `startPlay` 的解析链保持一致）：
     * ①收藏实体 ②阅读记录 ③文章列表当前项
     *
     * 三级兜底的意义：收藏/记录存在时字段更全（含 description/image）；都不存在时
     * 退到「文章列表当前项」——这正是直播/视频源「首次点入无记录」场景的救命兜底。
     */
    fun pickArticle(
        star: RssStar?,
        record: RssReadRecord?,
        articles: List<RssArticle>?,
        index: Int
    ): RssArticle? =
        star?.toRssArticle()
            ?: record?.toRssArticle()
            ?: articles?.getOrNull(index)

    /**
     * 收藏按钮可见性判定（单源）：
     * - 单 URL 直连（无订阅源身份）⇒ 不可收藏
     * - 书源视频（`book != null`，收藏语义走「书架」）⇒ 不可收藏
     * - 非订阅源 ⇒ 不可收藏
     * - 无任何可收藏条目 ⇒ 不可收藏
     *
     * 注意：**不再**要求「已有阅读记录或收藏」——订阅源视频模式下只要拿到当前条目即恒可收藏。
     */
    fun canFavorite(
        singleUrl: Boolean,
        hasBook: Boolean,
        isRssSource: Boolean,
        article: RssArticle?
    ): Boolean = !singleUrl && !hasBook && isRssSource && article != null

    /**
     * 由当前条目构造收藏实体：标题=文章标题、分组=默认分组、type 透传（复用 `RssArticle.toStar()`）。
     * 条目为空（无上下文）时返回 null，由调用方给出提示（不静默）。
     */
    fun buildStar(article: RssArticle?): RssStar? = article?.toStar()

    /**
     * 由当前条目构造阅读记录：主键 `record = link`、`origin` 透传（复用 `RssArticle.toRecord()`）。
     * 供播放器侧「先查后插」兜底写入使用（video-live-favorite-fix AD-04）。
     */
    fun buildRecord(article: RssArticle?): RssReadRecord? = article?.toRecord()
}