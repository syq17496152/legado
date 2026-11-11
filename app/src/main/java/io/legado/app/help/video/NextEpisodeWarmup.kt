package io.legado.app.help.video

import io.legado.app.constant.AppLog
import io.legado.app.help.exoplayer.VideoSegmentPrefetcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * 播完倒计时「下一集增量预热」（R36 / AD-24）。
 *
 * 背景：3 秒倒计时原先只有 `delay` + UI 秒数，期间不做任何网络动作；
 * 而下一集的 `triggerPreload` 要等新集起播后才触发，白白丢掉 3 秒带宽窗口。
 *
 * 设计约束：
 * - **独立 scope**：不挂在倒计时 job 下 ⇒ 用户点「取消续播」后任务继续（用户裁定）；
 * - **增量**：直接调 [VideoSegmentPrefetcher.prefillSegments]，已缓存分片跳过、不计入 count，
 *   因此能绕过 `VideoPrefiller.prefillRecords` 的去重拦住增量的问题（AD-27）；
 * - **可注入 resolve**：解析逻辑（查 `NextEpisodeResolver` → 预嗅探）由调用方传入，
 *   本对象不读 `VideoPlay` 全局状态，便于 JVM 单测；
 * - **幂等**：同一 key 在途时复用，不重复启动。
 */
object NextEpisodeWarmup {

    /** 解析结果：可直接预取的分片清单地址 + 请求头 */
    data class Resolved(val finalUrl: String, val headers: Map<String, String>)

    /**
     * 预热上下文。
     *
     * @param key 目标唯一键（倒计时目标集/文章），用于在途去重与取消
     * @param targetUrl 目标原始 URL（日志脱敏用）
     * @param isLive 直播不预热
     * @param count 目标片数（含已缓存跳过后的增量片数）
     * @param limitBytesPerSec 档位预算（不再落默认 1.5MB/s）
     * @param resolve 解析器：命中解析缓存直接返回；未命中时预嗅探并写缓存
     */
    data class Context(
        val key: String,
        val targetUrl: String,
        val isLive: Boolean = false,
        val count: Int,
        val limitBytesPerSec: Long,
        val resolve: suspend () -> Resolved?,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = ConcurrentHashMap<String, Job>()

    /**
     * 启动预热。
     *
     * @return true=已启动或已在途；false=参数无效/直播
     */
    fun start(ctx: Context): Boolean {
        if (ctx.isLive || ctx.count <= 0 || ctx.targetUrl.isBlank()) return false
        if (jobs.containsKey(ctx.key)) return true
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                val resolved = ctx.resolve()
                if (resolved == null || resolved.finalUrl.isBlank()) {
                    AppLog.put("[Warmup] resolve_null key=${ctx.key}")
                    return@launch
                }
                val bytes = VideoSegmentPrefetcher.prefillSegments(
                    playlistUri = resolved.finalUrl,
                    headers = resolved.headers,
                    count = ctx.count,
                    limitBytesPerSec = ctx.limitBytesPerSec,
                )
                AppLog.put(
                    "[Warmup] done key=${ctx.key} count=${ctx.count} bytes=$bytes " +
                        "budgetBps=${ctx.limitBytesPerSec}"
                )
            } catch (e: CancellationException) {
                AppLog.put("[Warmup] cancelled key=${ctx.key}")
                throw e
            } catch (e: Exception) {
                AppLog.put("[Warmup] failed key=${ctx.key} error=${e.javaClass.simpleName}")
            } finally {
                jobs.remove(ctx.key)
            }
        }
        jobs[ctx.key] = job
        job.start()
        AppLog.put(
            "[Warmup] start key=${ctx.key} count=${ctx.count} budgetBps=${ctx.limitBytesPerSec}"
        )
        return true
    }

    /** 取消指定目标（手动切集时用） */
    fun cancel(key: String, reason: String) {
        val job = jobs.remove(key) ?: return
        job.cancel()
        AppLog.put("[Warmup] cancel key=$key reason=$reason")
    }

    /** 取消全部（退出播放器 / 新会话） */
    fun cancelAll(reason: String) {
        if (jobs.isEmpty()) return
        jobs.values.forEach { it.cancel() }
        jobs.clear()
        AppLog.put("[Warmup] cancel_all reason=$reason")
    }

    fun isRunning(): Boolean = jobs.isNotEmpty()

    fun isRunning(key: String): Boolean = jobs.containsKey(key)

    internal fun resetForTest() {
        jobs.values.forEach { it.cancel() }
        jobs.clear()
    }
}
