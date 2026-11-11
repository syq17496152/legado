package io.legado.app.help.exoplayer

import java.net.URLDecoder

/**
 * 判定 URL 是否为流清单（HLS / DASH）。
 *
 * 背景（fix-live-hls-manifest-cache，2026-11-08）：
 * 直播 HLS 的媒体清单是滑动窗口（本项目目标站为 3 段 × 2 秒）。若清单被 SimpleCache 缓存，
 * 每次约 1 秒的清单重载都会命中同一份 3 段窗口 ⇒ `#EXT-X-MEDIA-SEQUENCE` 永不推进
 * ⇒ Media3 在 `3.5 × targetDuration`（约 7 秒）后抛 `PlaylistStuckException` ⇒ 播放 6 秒即停。
 *
 * 因此清单请求必须绕过缓存；分片（.ts/.mp4/.m4s）仍走缓存。
 *
 * 判据：
 * 1. 去 query/fragment 后的路径以 `.m3u8` / `.m3u` / `.mpd` 结尾（大小写不敏感）
 * 2. `format` / `type` 参数值为 `m3u8` / `mpd`
 * 3. 某个 query 参数值是**相对路径**且以 `.m3u8` / `.mpd` 结尾（代理端点，如 `?path=xxx.m3u8`）
 *    —— 刻意排除绝对 URL（历史铁证：`Play.php?uu=https%3A%2F%2F…%2Findex.m3u8` 被过宽匹配
 *    误判为 HLS，HLS 解析器读非清单内容抛 3002）；这类交给内容预检，不在此处绕过缓存。
 * 4. 路径含 `/m3u8/` 段的**分片**不判定（如 `/m3u8/segment/1.mp4` 仍走缓存）
 *
 * 纯函数、无 Android 依赖 ⇒ 可 JVM 单测。
 */
internal fun isManifestUrl(url: String): Boolean {
    if (url.isBlank()) return false
    val noFragment = url.substringBefore('#')
    val path = noFragment.substringBefore('?').lowercase()
    if (path.endsWith(".m3u8") || path.endsWith(".m3u") || path.endsWith(".mpd")) {
        return true
    }
    val query = noFragment.substringAfter('?', "")
    if (query.isEmpty()) return false
    return query.split('&').any { param ->
        val key = param.substringBefore('=', "").lowercase()
        val rawValue = param.substringAfter('=', "")
        val value = runCatching { URLDecoder.decode(rawValue, "UTF-8") }
            .getOrDefault(rawValue).lowercase()
        if ((key == "format" || key == "type") && (value == "m3u8" || value == "mpd")) {
            return@any true
        }
        (value.endsWith(".m3u8") || value.endsWith(".mpd")) && !value.contains("://")
    }
}
