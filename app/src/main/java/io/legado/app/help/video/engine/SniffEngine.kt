package io.legado.app.help.video.engine

import io.legado.app.constant.AppLog
import io.legado.app.data.entities.BaseSource
import io.legado.app.help.exoplayer.ExoPlayerHelper
import io.legado.app.help.video.SniffCandidate
import io.legado.app.help.video.VideoUrlExtractor
import io.legado.app.model.analyzeRule.RuleDataInterface
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import java.util.concurrent.ConcurrentHashMap

/**
 * SniffEngine 引擎门面（video-sniff-403-and-rss-classic-fix Phase 3 / 4.1-4.2 / AD-06）
 *
 * 统一播放/下载/预检三调用方的嗅探入口（design §3.0-3.1）：三意图共享四层发现流水线
 * （L1 静态解析 MacCMS/DOM → L2 WebView 抓包带上下文 → L3 直连探测 Phase 4 深化）
 * 与去重缓存，仅评分与预检策略按意图差异化。
 *
 * 实施策略（design §3.6 回滚锚点）：引擎为**纯新增包**——发现层委托既有验证充分的
 * VideoUrlExtractor 流水线（L1/L2 资产原样复用，不重复造轮子，见 §3.5 资产清单），
 * 本对象只新增：意图语义化入口 + 页面级去重缓存泛化 + 统一 SniffResult 模型。
 * 调用方逐个接入，任一调用方异常可单独回退到 Phase 1 直连路径。
 *
 * 对外接口（design §3.1）：
 * - [execute] 主入口（含去重缓存）
 * - [play]/[download]/[probe] 三意图语义化封装
 * - [invalidate] 页面切换时清缓存
 */
object SniffEngine {

    /**
     * 页面级去重缓存（playerPageCache+r5InProgress 并发去重模式泛化，VideoUrlExtractor L65-79 资产复用）：
     * 同页多调用方（播放+下载+预检）共享一次嗅探结果，intent 不同仅评分与预检策略不同。
     * key = "intent:targetUrl"，value = 进行中的嗅探 Deferred。
     */
    private val inFlight = ConcurrentHashMap<String, Deferred<SniffCandidate?>>()

    /** 缓存容量上限（防极端页面泄漏，LRU 语义由 clearAll 兜底——页面切换即 invalidate） */
    private const val MAX_IN_FLIGHT = 16

    /** 主入口：执行嗅探（含去重缓存） */
    suspend fun execute(request: SniffRequest): SniffResult = coroutineScope {
        val dedupKey = "${request.intent}:${request.targetUrl}"
        // 并发去重：同 key 已有进行中的嗅探 → 共享同一 Deferred（复用 playerPageCache 模式）
        val existing = inFlight[dedupKey]
        if (existing != null) {
            AppLog.put("SniffEngine: dedup hit ($dedupKey), share in-flight sniff")
            val candidate = existing.await()
            return@coroutineScope SniffResult(
                candidates = listOfNotNull(candidate),
                selected = candidate,
                fromCache = true
            )
        }
        if (inFlight.size >= MAX_IN_FLIGHT) {
            AppLog.put("SniffEngine: in-flight cache full (${inFlight.size}), clearAll")
            inFlight.clear()
        }
        val deferred = async {
            dispatch(request)
        }
        inFlight[dedupKey] = deferred
        try {
            val candidate = deferred.await()
            val candidates = listOfNotNull(candidate)
            // video-sniff-403-and-rss-classic-fix Phase 4 (5.3)：候选>1 时评分选优
            // （类型权重+时序新近度+URL 启发），=1 维持 Phase 3 "首命中"现状（零破坏）
            val selected = if (candidates.size > 1) score(candidates) else candidates.firstOrNull()
            SniffResult(candidates = candidates, selected = selected)
        } finally {
            inFlight.remove(dedupKey, deferred)
        }
    }

    /** 三意图语义化封装（内部统一走 execute） */
    suspend fun play(request: SniffRequest): SniffResult =
        execute(request.copy(intent = SniffIntent.PLAY))

    suspend fun download(request: SniffRequest): SniffResult =
        execute(request.copy(intent = SniffIntent.DOWNLOAD))

    suspend fun probe(request: SniffRequest): SniffResult =
        execute(request.copy(intent = SniffIntent.PROBE))

    /** 页面切换时清缓存（VideoPlay.switchToArticle/playRssEpisode 时序点调用） */
    fun invalidate(pageUrl: String? = null) {
        if (pageUrl == null) {
            if (inFlight.isNotEmpty()) inFlight.clear()
        } else {
            inFlight.keys.removeAll { it.endsWith(":$pageUrl") }
        }
    }

    /**
     * 意图分发：发现层委托 VideoUrlExtractor（L1 静态解析+L2 WebView 抓包流水线资产复用）。
     *
     * - PLAY/DOWNLOAD：extractVideoUrlForEpisode 四层流水线（fast/MacCMS/DOM 静态 + WebView 抓包）
     * - PROBE：M3u8PreCheck 预检语义（4.7 接入引擎时扩展；当前 PROBE 与 PLAY 同发现路径，
     *   预检 auth-retry+Rejected 已由 Phase 1 在 ExoPlayerHelper.sniffVideoType 内实现）
     */
    private suspend fun dispatch(request: SniffRequest): SniffCandidate? {
        if (request.targetUrl.isBlank()) return null
        AppLog.put(
            "SniffEngine: execute intent=${request.intent}, " +
                "urlPath=${ExoPlayerHelper.sanitizeUrl(request.targetUrl)}"
        )
        val source = request.source as? BaseSource
        val ruleData = request.ruleData as? RuleDataInterface
        val candidate = VideoUrlExtractor.extractVideoUrlForEpisode(
            url = request.targetUrl,
            source = source,
            ruleData = ruleData
        )
        if (candidate == null) {
            AppLog.put(
                "SniffEngine: no candidate, intent=${request.intent}, " +
                    "urlPath=${ExoPlayerHelper.sanitizeUrl(request.targetUrl)}"
            )
        }
        return candidate
    }

    // ==================== 多候选评分器（video-sniff-403-and-rss-classic-fix Phase 4 / 5.3）====================

    /** 类型权重分档：m3u8/mpd 清单 > 单视频直链 > 音频直链 > 分片(.ts) > 未知 */
    private const val TYPE_SCORE_MANIFEST = 65
    private const val TYPE_SCORE_DIRECT_VIDEO = 50
    private const val TYPE_SCORE_DIRECT_AUDIO = 40
    private const val TYPE_SCORE_SEGMENT = 25
    private const val TYPE_SCORE_UNKNOWN = 10

    /** 时序新近度加成上限（新者优先，规避广告先命中；上限低于清单与直链的分档差，类型证据优先） */
    private const val RECENCY_MAX_BONUS = 10

    /** URL 启发加权（design AD-03/Phase 4 5.3）：/index.m3u8 主清单特征 / master 字样 */
    private const val URL_BONUS_INDEX_M3U8 = 8
    private const val URL_BONUS_MASTER = 5

    /**
     * 多候选评分选优（design §3.1/AD-03/Phase 4 5.3）：类型权重 + 时序新近度 + URL 启发，
     * 代替 Phase 3 "首命中"策略；广告分片靠"类型+时序"双重降权（design §七 Phase 4）。
     *
     * - 类型权重：m3u8/mpd 清单(65) > 单视频直链(50) > 音频直链(40) > 分片 .ts(25) > 未知(10)，
     *   判定优先用 mimeType/contentType（响应证据），缺失退化为 URL 后缀启发
     * - 时序新近度：timestamp 新者加分（区间归一化，上限 [RECENCY_MAX_BONUS]）——
     *   广告分片通常先命中，同类型中新者更可信；上限低于类型分档差，类型证据永远优先
     * - URL 启发：含 /index.m3u8 或 master 字样加权（主清单特征）
     *
     * 评分日志以 "SniffEngine: score" 关键字输出（AppLog 观测调参，design §七 Phase 4），
     * URL 仅保留路径段（脱敏，防 query/token 入日志）。纯 JVM 可测（不触 CookieManager/网络）。
     *
     * @return 得分最高候选（同分新者优先）；空列表返回 null
     */
    fun score(candidates: List<SniffCandidate>): SniffCandidate? {
        val distinct = candidates.distinctBy { it.url }
        if (distinct.isEmpty()) return null
        val minTs = distinct.minOf { it.timestamp }
        val tsRange = (distinct.maxOf { it.timestamp } - minTs).coerceAtLeast(1L)
        val scored = distinct.map { candidate ->
            val total = typeScoreOf(candidate) +
                (((candidate.timestamp - minTs).toDouble() / tsRange) * RECENCY_MAX_BONUS).toInt() +
                urlBonusOf(candidate.url)
            candidate to total
        }
        // 同分时新者优先（时序 tie-break）
        val best = scored.maxWith(compareBy({ it.second }, { it.first.timestamp }))
        scored.forEach {
            AppLog.putInfo(
                "SniffEngine: score src=${it.first.source} total=${it.second} " +
                    "urlPath=${pathOnly(it.first.url)}"
            )
        }
        AppLog.putInfo(
            "SniffEngine: score selected total=${best.second} urlPath=${pathOnly(best.first.url)}"
        )
        return best.first
    }

    /** 类型分档判定：mimeType/contentType 响应证据优先，缺失退化 URL 后缀 */
    private fun typeScoreOf(candidate: SniffCandidate): Int {
        val ct = (candidate.mimeType ?: candidate.contentType)
            ?.lowercase()?.substringBefore(';')?.trim()
        if (!ct.isNullOrBlank()) {
            return when {
                ct.contains("mpegurl") || ct.contains("dash+xml") -> TYPE_SCORE_MANIFEST
                ct.startsWith("video/mp2t") -> TYPE_SCORE_SEGMENT
                ct.startsWith("video/") -> TYPE_SCORE_DIRECT_VIDEO
                ct.startsWith("audio/") -> TYPE_SCORE_DIRECT_AUDIO
                else -> suffixScoreOf(candidate.url)
            }
        }
        return suffixScoreOf(candidate.url)
    }

    private fun suffixScoreOf(url: String): Int {
        val path = url.substringBefore('?').lowercase()
        return when {
            path.endsWith(".m3u8") || path.endsWith(".mpd") -> TYPE_SCORE_MANIFEST
            path.endsWith(".mp4") || path.endsWith(".mkv") || path.endsWith(".flv") ||
                path.endsWith(".webm") || path.endsWith(".mov") -> TYPE_SCORE_DIRECT_VIDEO
            path.endsWith(".mp3") || path.endsWith(".m4a") || path.endsWith(".aac") ||
                path.endsWith(".flac") || path.endsWith(".ogg") -> TYPE_SCORE_DIRECT_AUDIO
            path.endsWith(".ts") -> TYPE_SCORE_SEGMENT
            else -> TYPE_SCORE_UNKNOWN
        }
    }

    private fun urlBonusOf(url: String): Int {
        val lower = url.lowercase()
        var bonus = 0
        if (lower.contains("/index.m3u8")) bonus += URL_BONUS_INDEX_M3U8
        if (lower.contains("master")) bonus += URL_BONUS_MASTER
        return bonus
    }

    /** 日志脱敏：仅保留路径段（去 query/token），超长截尾 */
    private fun pathOnly(url: String): String = url.substringBefore('?').takeLast(80)
}
