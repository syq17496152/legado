package io.legado.app.utils

/**
 * 时长格式化：天/小时/分钟/秒 组合（全部为 0 时返回「0秒」）。
 */
fun formatDuring(mss: Long): String {
    val days = mss / (1000 * 60 * 60 * 24)
    val hours = mss % (1000 * 60 * 60 * 24) / (1000 * 60 * 60)
    val minutes = mss % (1000 * 60 * 60) / (1000 * 60)
    val seconds = mss % (1000 * 60) / 1000
    val d = if (days > 0) "${days}天" else ""
    val h = if (hours > 0) "${hours}小时" else ""
    val m = if (minutes > 0) "${minutes}分钟" else ""
    val s = if (seconds > 0) "${seconds}秒" else ""
    var time = "$d$h$m$s"
    if (time.isBlank()) {
        time = "0秒"
    }
    return time
}
