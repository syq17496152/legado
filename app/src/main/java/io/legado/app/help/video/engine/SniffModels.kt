package io.legado.app.help.video.engine

import io.legado.app.help.video.SniffCandidate

/**
 * SniffEngine 数据模型（video-sniff-403-and-rss-classic-fix Phase 3 / 4.1 / AD-06）
 *
 * 引擎三意图共享四层发现流水线与去重缓存，仅评分与预检策略按意图差异化（design §3.1）。
 */

/** 嗅探意图：决定评分权重与是否强制预检（design §3.1 / AD-06） */
enum class SniffIntent {
    /** 播放：消费候选直连播放，头落 currentPlayHeaders */
    PLAY,
    /** 下载：独立嗅探（不依赖播放现场状态），头持久化 headersJson */
    DOWNLOAD,
    /** 预检：M3u8PreCheck 清单结构感知验活（auth-retry + Rejected 语义） */
    PROBE
}

/**
 * 引擎统一请求模型
 *
 * @param targetUrl 目标页/文章/播放页链接（非视频直链也可，引擎负责四层发现）
 * @param source 书源/订阅源（RuleDataInterface 已抽象 resolveReferer 先例，供源配置头兜底）
 * @param ruleData 页面上下文（用于 Referer 兜底与规则头，通常为 rssArticle/book）
 * @param intent 嗅探意图（PLAY/DOWNLOAD/PROBE）
 * @param delayTime WebView 层预埋等待（对齐 R5_DELAY_TIME 语义；null=引擎按意图取默认）
 * @param timeout WebView 层总超时（对齐 R5_TIMEOUT 语义；null=引擎按意图取默认）
 */
data class SniffRequest(
    val targetUrl: String,
    val source: Any? = null,
    val ruleData: Any? = null,
    val intent: SniffIntent = SniffIntent.PLAY,
    val delayTime: Long? = null,
    val timeout: Long? = null
)

/**
 * 引擎统一结果
 *
 * @param candidates 发现的全部候选（时序排列；Phase 3 仅首个有效，Phase 4 完整评分器消费）
 * @param selected 评分选优后的唯一候选（=candidates 首个，Phase 3 "首命中+合法性校验"策略）
 * @param rejected 预检被源站确定性拒绝（auth-retry 后仍 403/410/451；区别于瞬时 Fail，驱动上游确定性提示）
 * @param rejectedStatus 拒绝状态码（rejected=true 时有效）
 * @param fromCache 本次结果是否命中引擎去重缓存（观测用）
 */
data class SniffResult(
    val candidates: List<SniffCandidate> = emptyList(),
    val selected: SniffCandidate? = null,
    val rejected: Boolean = false,
    val rejectedStatus: Int? = null,
    val fromCache: Boolean = false
) {
    /** 便捷取值：选中候选 URL（null=未命中） */
    val url: String? get() = selected?.url
}
