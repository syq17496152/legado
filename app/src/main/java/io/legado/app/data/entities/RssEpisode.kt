package io.legado.app.data.entities

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/**
 * 订阅源视频多集数据类（R1 多集选择播放）
 *
 * 用于订阅源 type=2（视频）场景，ruleContent 返回 JSON 数组或多行 URL 时解析为多集列表。
 * 字段说明参见 docs/specs/rss-video-player-enhancement/design.md 1.5 节。
 *
 * url: 必须，播放地址（m3u8/mp4/mpd 等），相对路径自动拼接 baseUrl
 * title: 可选，集数标题，缺省为"第N集"
 * duration: 预留，时长（毫秒），未来扩充
 * cover: 预留，封面 URL，未来扩充
 */
@Parcelize
data class RssEpisode(
    var title: String = "",
    var url: String = "",
    var duration: Long = 0,
    var cover: String = ""
) : Parcelable
