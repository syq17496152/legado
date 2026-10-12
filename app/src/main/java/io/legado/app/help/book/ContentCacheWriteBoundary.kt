package io.legado.app.help.book

import io.legado.app.R
import io.legado.app.constant.AppLog
import io.legado.app.model.reader.render.ContentCacheWriteGuard
import io.legado.app.utils.toastOnUi
import splitties.init.appCtx

/**
 * 内容缓存写入的**边界动作**层（epub-md-rich-rendering 补充任务 S12；AD-28⑥ / NFR-07①）。
 *
 * ### 为什么单独成文件（而不是塞进 [BookHelp]）
 * ① [BookHelp] 已贴近单文件行数门禁（G-38，阈值 800 行）—— 边界处理属"新能力"，按"取能力不取结构"
 * 拆分；② **判据**（纯函数，可 JVM 测）属 `model/reader/render/ContentCacheWriteGuard`，而**副作用**
 * （留痕 + 用户提示 + 去重）是宿主侧动作 ⇒ 分离后判据可测、动作层只做接线（两侧都由测试钉住）。
 *
 * ### 语义（对齐「磁盘满：禁写并提示，不阻断阅读」）
 * - 磁盘将满 ⇒ [blockWriteIfNoSpace] 返回 `true`，调用方**跳过本次写入**；
 * - 写失败若属磁盘满/无权限 ⇒ [onWriteFailure] 提示用户（异常**不吞**，仍由调用方按原语义上抛）；
 * - 提示**同因去重**（批量缓存同一原因只提示一次，避免刷屏）；两条路径都**留痕**——
 *   只弹提示不留日志会让真机**无从取证"提示到底有没有触发"**（本轮据此补）。
 */
object ContentCacheWriteBoundary {

    /** 最近一次「写入被拒」提示时间戳（去重窗口见 [ContentCacheWriteGuard.NOTIFY_DEDUPE_MS]）。 */
    @Volatile
    private var lastNotifiedAt = 0L

    /**
     * 写入前把关（磁盘将满 ⇒ 禁写 + 提示）。
     *
     * @return `true` 表示**应跳过本次写入**；调用方应立即 return，不进入写盘。
     */
    fun blockWriteIfNoSpace(usableBytes: Long, contentChars: Int, chapterIndex: Int): Boolean {
        if (ContentCacheWriteGuard.hasRoomForCache(usableBytes, contentChars.toLong())) return false
        AppLog.putDebugWithTag(
            AppLog.TAG_CONTENT,
            "磁盘空间不足，跳过正文写入 chapterIndex=$chapterIndex len=$contentChars",
            level = AppLog.Level.WARN
        )
        notifyBlocked()
        return true
    }

    /**
     * 写入失败后的边界动作：只有**磁盘满 / 无权限**才提示（其它异常不误报）。
     *
     * 不吞异常（调用方按原语义上抛），只负责留痕与提示 —— 保持既有错误语义零变化。
     */
    fun onWriteFailure(error: Throwable, chapterIndex: Int) {
        if (ContentCacheWriteGuard.classifyWriteFailure(error) == null) return
        AppLog.putDebugWithTag(
            AppLog.TAG_CONTENT,
            "正文写入被拒（磁盘满/无权限）⇒ 已提示用户 chapterIndex=$chapterIndex",
            level = AppLog.Level.WARN
        )
        notifyBlocked()
    }

    private fun notifyBlocked() {
        val now = System.currentTimeMillis()
        if (!ContentCacheWriteGuard.shouldNotify(lastNotifiedAt, now)) return
        lastNotifiedAt = now
        appCtx.toastOnUi(R.string.content_write_blocked_no_space)
    }
}