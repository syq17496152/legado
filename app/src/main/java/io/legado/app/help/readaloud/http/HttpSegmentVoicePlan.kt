package io.legado.app.help.readaloud.http

/**
 * T8.6 段级换声的媒体文件名标记（纯函数，JVM 可单测）。
 *
 * 为什么要标记：多角色路径下一段会变成多个媒体项，播放侧必须能区分
 * 「同段内非末段（l0）」与「末段（l1）」——前者不能推进段落指针，否则进度账按子段乱走。
 */
object HttpSegmentVoicePlan {

    private const val PREFIX = "sv_"
    private const val NON_LAST = "l0"
    private const val LAST = "l1"

    /** 段级媒体文件名 = `sv_{段落下标}_{l0|l1}_{原缓存键}` */
    fun fileName(paragraphIndex: Int, last: Boolean, baseName: String): String =
        "$PREFIX${paragraphIndex}_${if (last) LAST else NON_LAST}_$baseName"

    /** 是否"同段内非末段"（播放完不推进段落指针） */
    fun isNonLastSegment(fileName: String?): Boolean =
        fileName != null && fileName.startsWith(PREFIX) && fileName.contains("_$NON_LAST" + "_")

    /** 从段级文件名解析段落下标（非段级文件返回 null） */
    fun paragraphIndexOf(fileName: String?): Int? {
        if (fileName == null || !fileName.startsWith(PREFIX)) return null
        return fileName.removePrefix(PREFIX).substringBefore('_').toIntOrNull()
    }
}
