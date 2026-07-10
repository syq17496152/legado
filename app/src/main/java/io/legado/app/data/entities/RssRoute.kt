package io.legado.app.data.entities

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/**
 * 订阅源视频多线路数据类（R3 多线路支持）
 *
 * 用于订阅源 type=2（视频）场景，ruleContent 返回嵌套 JSON 时解析为多线路列表。
 * 与书源 volumes/episodes 范式对称：RssRoute 对应 Volume，RssEpisode 对应 Episode。
 * 字段说明参见 docs/specs/douyin-style-video-player/design.md 阶段8。
 *
 * name: 可选，线路名称，缺省为"线路N"
 * episodes: 必须，该线路下的集数列表
 */
@Parcelize
data class RssRoute(
    var name: String = "",
    var episodes: List<RssEpisode> = emptyList()
) : Parcelable
