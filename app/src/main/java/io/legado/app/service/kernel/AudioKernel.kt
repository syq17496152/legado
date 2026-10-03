package io.legado.app.service.kernel

import io.legado.app.model.AudioPlay
import io.legado.app.model.ReadAloud
import io.legado.app.service.AudioPlayService
import io.legado.app.service.BaseReadAloudService
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.withContext
import splitties.init.appCtx

/**
 * ⑦ 听书/有声书**播放控制**内核（web-mcp-productization 二期 · tasks 2.13 / 2.28 · 亦供 §5.9 内腿复用）。
 *
 * 契约同 [BookKernel]：只返回结构化 Map、全链挂起、零 `runBlocking`、失败抛异常。
 *
 * 两套播放器**语义区分**（spec §4.2⑦）：
 * - **听书**（`audio_control` / `audio_progress_get`）= `ReadAloud`（TTS 朗读章节正文，前台服务驱动）；
 * - **有声书**（`audiobook_control` / `audiobook_progress_get`）= `AudioPlay`（播放音频文件）。
 *
 * **端侧指令口径（重要）**：这些方法都是「**下发指令 + 回读播放态**」——
 * 控制方法本身**不阻塞等待播放结果**（`ReadAloud`/`AudioPlay` 经前台服务 Intent 异步生效），
 * 因此返回值里如实回告「指令已下发」+ 当前运行标志；调用方（AI）需要确认效果时**再调一次**
 * `*_progress_get` 回读。这是刻意设计（同步等待会让工具调用撞上 15s 超时）。
 */
object AudioKernel {

    // ============================================================ 听书（ReadAloud）

    /**
     * `audio_control`：听书播放控制。
     *
     * action：`play` / `pause` / `resume` / `stop` / `prev_chapter` / `next_chapter` /
     * `prev_paragraph` / `next_paragraph` / `speed_up` / `set_timer`（需 `minute`）/
     * `set_timer_mode`（需 `mode`，1=本章 2=剩余章；可选 `chapters`）/
     * `select_chapter`（需 `chapterIndex`）。
     */
    suspend fun audioControl(
        action: String,
        minute: Int = 0,
        mode: Int = 0,
        chapters: Int = 0,
        chapterIndex: Int = 0,
    ): Map<String, Any?> = withContext(IO) {
        when (action) {
            "play" -> ReadAloud.play(appCtx)
            "pause" -> ReadAloud.pause(appCtx)
            "resume" -> ReadAloud.resume(appCtx)
            "stop" -> ReadAloud.stop(appCtx)
            "prev_chapter" -> ReadAloud.prevChapter(appCtx)
            "next_chapter" -> ReadAloud.nextChapter(appCtx, continuePlayback = BaseReadAloudService.isPlay())
            "prev_paragraph" -> ReadAloud.prevParagraph(appCtx)
            "next_paragraph" -> ReadAloud.nextParagraph(appCtx)
            "speed_up" -> ReadAloud.upTtsSpeechRate(appCtx)
            "set_timer" -> ReadAloud.setTimer(appCtx, minute)
            "set_timer_mode" -> ReadAloud.setTimerMode(appCtx, mode, chapters)
            "select_chapter" -> ReadAloud.selectChapter(
                appCtx, chapterIndex, continuePlayback = BaseReadAloudService.isPlay()
            )
            else -> throw IllegalArgumentException(
                "未知 action：$action（支持 play/pause/resume/stop/prev_chapter/next_chapter/" +
                    "prev_paragraph/next_paragraph/speed_up/set_timer/set_timer_mode/select_chapter）"
            )
        }
        mapOf("action" to action, "dispatched" to true) + audioProgressSnapshot()
    }

    /** `audio_progress_get`：听书进度与当前引擎路由（只读）。 */
    suspend fun audioProgress(): Map<String, Any?> = withContext(IO) { audioProgressSnapshot() }

    private fun audioProgressSnapshot(): Map<String, Any?> {
        val route = ReadAloud.currentRoute
        return mapOf(
            "running" to BaseReadAloudService.isRun,
            "paused" to BaseReadAloudService.pause,
            "playing" to BaseReadAloudService.isPlay(),
            "timerMinutes" to BaseReadAloudService.timeMinute,
            "timerMode" to BaseReadAloudService.ttsTimerMode,
            "remainChapters" to BaseReadAloudService.remainChapters,
            // 当前语音路由（技术标识；token/密钥不属于该模型）
            "engineType" to route.engineType,
            "engineValue" to route.engineValue,
            "speakerName" to route.speakerName,
        )
    }

    // ============================================================ 有声书（AudioPlay）

    /**
     * `audiobook_control`：有声书播放控制（端侧指令）。
     *
     * action：`play` / `pause` / `resume` / `stop` / `prev` / `next` /
     * `set_speed`（需 `speed`，0.5~3.0，内部自动夹取）/ `skip_to`（需 `chapterIndex`）/
     * `set_timer`（需 `minute`）。
     */
    suspend fun audiobookControl(
        action: String,
        speed: Float = 1.0f,
        chapterIndex: Int = 0,
        minute: Int = 0,
    ): Map<String, Any?> = withContext(IO) {
        when (action) {
            "play" -> AudioPlay.play()
            "pause" -> AudioPlay.pause(appCtx)
            "resume" -> AudioPlay.resume(appCtx)
            "stop" -> AudioPlay.stop()
            "prev" -> AudioPlay.prev()
            "next" -> AudioPlay.next()
            "set_speed" -> AudioPlay.setSpeed(speed)
            "skip_to" -> AudioPlay.skipTo(chapterIndex)
            "set_timer" -> AudioPlay.setTimer(minute)
            else -> throw IllegalArgumentException(
                "未知 action：$action（支持 play/pause/resume/stop/prev/next/set_speed/skip_to/set_timer）"
            )
        }
        mapOf("action" to action, "dispatched" to true) + audiobookProgressSnapshot()
    }

    /** `audiobook_progress_get`：有声书播放进度（只读；播放 URL 已打码）。 */
    suspend fun audiobookProgress(): Map<String, Any?> = withContext(IO) { audiobookProgressSnapshot() }

    private fun audiobookProgressSnapshot(): Map<String, Any?> = mapOf(
        "running" to AudioPlayService.isRun,
        "paused" to AudioPlayService.pause,
        "speed" to AudioPlayService.playSpeed,
        "timerMinutes" to AudioPlayService.timeMinute,
        // AudioPlay.status 是 Int（constant/Status 的 STOP=0/PLAY=1/PAUSE=3 常量），非枚举
        "status" to AudioPlay.status,
        "playMode" to AudioPlay.playMode.name,
        "hasBook" to (AudioPlay.book != null),
        "chapterIndex" to AudioPlay.durChapterIndex,
        "chapterPos" to AudioPlay.durChapterPos,
        // URL 敏感 query 打码（复用诊断域同一实现，避免两套脱敏口径）
        "playUrl" to DiagKernel.maskUrl(AudioPlay.durPlayUrl),
    )
}