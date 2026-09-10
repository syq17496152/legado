package io.legado.app.help.readaloud.prebuild

import io.legado.app.utils.MD5Utils

/**
 * TTS 缓存键单一权威源（§3.7.1，上游 §1.8-D-1 契约 1/2/4/8）：
 * - 播放端与批量端同函数（禁第二套键算法），键维度与引擎实际生效参数同源解析
 * - KEY_VERSION 参与哈希：首升=有意失配（存量期1 缓存一次性重合成，updateLog 已告知）
 * - engineKey=引擎 id（httpTTS 表 id，空 url type=2 引擎防跨引擎碰撞）
 * - 键 stem 显式含章节 index（防同章名跨章碰撞）
 * - 全输入可枚举可持久化（禁含运行时句柄），为未来任务持久化/迁移留前提
 */
object TtsCacheKeys {

    /** 缓存键代际（参与哈希）：升级键算法/维度时 +1，旧代缓存整体失配。
     *  v3：speedKey 对齐播放端 speechRatePlay+5（P0-4）/切分口径去 trim（P1-1）/标题因子走 getDisplayTitle（P1-2） */
    const val KEY_VERSION = "v3"

    fun ttsSpeakFileName(
        engineKey: String,
        speedKey: String,
        voiceKey: String,
        chapterIndex: Int,
        chapterTitle: String,
        unitText: String
    ): String {
        val stem = MD5Utils.md5Encode16(chapterTitle)
        val unit = MD5Utils.md5Encode16(
            "$KEY_VERSION-|-${engineKey}-|-$speedKey-|-$voiceKey-|-$chapterIndex-|-$unitText"
        )
        // 双段结构保持与旧格式同构（stem_unit），stem 与正文缓存主名同源
        return "${stem}_${unit}"
    }
}
