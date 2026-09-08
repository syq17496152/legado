package io.legado.app.model

import android.content.Context
import android.content.Intent
import android.os.Bundle
import io.legado.app.constant.AppLog
import io.legado.app.constant.EventBus
import io.legado.app.constant.IntentAction
import io.legado.app.help.config.AppConfig
import io.legado.app.help.readaloud.speech.SpeechRoute
import io.legado.app.service.BaseReadAloudService
import io.legado.app.service.HttpReadAloudService
import io.legado.app.service.TTSReadAloudService
import io.legado.app.utils.LogUtils
import io.legado.app.utils.postEvent
import io.legado.app.utils.startForegroundServiceCompat
import io.legado.app.utils.toastOnUi
import splitties.init.appCtx

object ReadAloud {
    // 当前生效路由（AD-01）：经 resolveSpeechRoute 纯同步解析，服务内装配以此为准
    var currentRoute: SpeechRoute = SpeechRoute.resolveSpeechRoute(ttsEngine)
        private set
    private var aloudClass: Class<*> = routeToClass(currentRoute)
    val ttsEngine get() = ReadBook.book?.getTtsEngine() ?: AppConfig.ttsEngine

    private fun routeToClass(route: SpeechRoute): Class<*> {
        return if (route.engineType == SpeechRoute.ENGINE_HTTP) {
            HttpReadAloudService::class.java
        } else {
            TTSReadAloudService::class.java
        }
    }

    private fun getReadAloudClass(): Class<*> {
        return routeToClass(SpeechRoute.resolveSpeechRoute(ttsEngine))
    }

    /**
     * 续播意图（AD-02）：切换期间上收数据层，面板 STOP 分支经 consumePendingSwitch 取用后重建续播
     */
    data class PendingSwitch(
        val wasPlaying: Boolean,
        val pageIndex: Int,
        val startPos: Int
    )

    @Volatile
    private var pendingSwitch: PendingSwitch? = null

    fun consumePendingSwitch(): PendingSwitch? {
        val pending = pendingSwitch
        pendingSwitch = null
        return pending
    }

    fun upReadAloudClass() {
        val oldClass = aloudClass
        val newRoute = SpeechRoute.resolveSpeechRoute(ttsEngine)
        val newClass = routeToClass(newRoute)
        if (BaseReadAloudService.isRun) {
            if (newClass == oldClass) {
                // 同服务类型：引擎内重建（reInitTts），不整服务重启（AD-02）
                val intent = Intent(appCtx, oldClass)
                intent.action = IntentAction.reInitTts
                runCatching {
                    appCtx.startForegroundServiceCompat(intent)
                }
            } else {
                // 跨类型：捕获续播意图 → 用重算前旧 Class 引用 stop（重算后字段已变，禁用字段发 Intent）→ 面板 STOP 消费 PendingSwitch 重建续播
                pendingSwitch = PendingSwitch(
                    wasPlaying = BaseReadAloudService.isPlay(),
                    pageIndex = ReadBook.durPageIndex,
                    startPos = ReadBook.durChapterPos
                )
                val intent = Intent(appCtx, oldClass)
                intent.action = IntentAction.stop
                runCatching {
                    appCtx.startForegroundServiceCompat(intent)
                }
            }
        }
        currentRoute = newRoute
        aloudClass = newClass
    }

    fun play(
        context: Context,
        play: Boolean = true,
        pageIndex: Int = ReadBook.durPageIndex,
        startPos: Int = 0
    ) {
        val intent = Intent(context, aloudClass)
        intent.action = IntentAction.play
        intent.putExtra("play", play)
        intent.putExtra("pageIndex", pageIndex)
        intent.putExtra("startPos", startPos)
        LogUtils.d("ReadAloud", intent.toString())
        try {
            context.startForegroundServiceCompat(intent)
        } catch (e: Exception) {
            val msg = "启动朗读服务出错\n${e.localizedMessage}"
            AppLog.put(msg, e)
            context.toastOnUi(msg)
        }
    }

    fun playByEventBus(
        play: Boolean = true,
        pageIndex: Int = ReadBook.durPageIndex,
        startPos: Int = 0
    ) {
        val bundle = Bundle().apply {
            putBoolean("play", play)
            putInt("pageIndex", pageIndex)
            putInt("startPos", startPos)
        }
        postEvent(EventBus.READ_ALOUD_PLAY, bundle)
    }

    fun refreshReadAloudClass(): Class<*> {
        aloudClass = getReadAloudClass()
        return aloudClass
    }

    fun moveToCue(
        context: Context,
        cueIndex: Int,
        chapterPosition: Int,
        expectedChapterIndex: Int = ReadBook.durChapterIndex,
        play: Boolean = BaseReadAloudService.isPlay()
    ) {
        if (!BaseReadAloudService.isRun) return
        val intent = Intent(context, aloudClass)
        intent.action = IntentAction.moveTo
        intent.putExtra("cueIndex", cueIndex)
        intent.putExtra("chapterPosition", chapterPosition)
        intent.putExtra("expectedChapterIndex", expectedChapterIndex)
        intent.putExtra("play", play)
        context.startForegroundServiceCompat(intent)
    }

    fun playFromPosition(
        context: Context,
        bookUrl: String,
        chapterIndex: Int,
        chapterUrl: String,
        chapterPosition: Int
    ) {
        val intent = Intent(context, aloudClass)
        intent.action = IntentAction.playFromPosition
        intent.putExtra("bookUrl", bookUrl)
        intent.putExtra("chapterIndex", chapterIndex)
        intent.putExtra("chapterUrl", chapterUrl)
        intent.putExtra("chapterPosition", chapterPosition)
        try {
            context.startForegroundServiceCompat(intent)
        } catch (e: Exception) {
            val msg = "启动选句朗读出错\n${e.localizedMessage}"
            AppLog.put(msg, e)
            context.toastOnUi(msg)
        }
    }

    fun prevChapter(context: Context, continuePlayback: Boolean = BaseReadAloudService.isPlay()) {
        if (BaseReadAloudService.isRun) {
            val intent = Intent(context, aloudClass)
            intent.action = IntentAction.prev
            intent.putExtra("continuePlayback", continuePlayback)
            context.startForegroundServiceCompat(intent)
        }
    }

    fun nextChapter(context: Context, continuePlayback: Boolean = BaseReadAloudService.isPlay()) {
        if (BaseReadAloudService.isRun) {
            val intent = Intent(context, aloudClass)
            intent.action = IntentAction.next
            intent.putExtra("continuePlayback", continuePlayback)
            context.startForegroundServiceCompat(intent)
        }
    }

    fun selectChapter(
        context: Context,
        chapterIndex: Int,
        continuePlayback: Boolean = BaseReadAloudService.isPlay()
    ) {
        if (BaseReadAloudService.isRun) {
            val intent = Intent(context, aloudClass)
            intent.action = IntentAction.selectChapter
            intent.putExtra("chapterIndex", chapterIndex)
            intent.putExtra("continuePlayback", continuePlayback)
            context.startForegroundServiceCompat(intent)
        }
    }

    fun pause(context: Context) {
        if (BaseReadAloudService.isRun) {
            val intent = Intent(context, aloudClass)
            intent.action = IntentAction.pause
            context.startForegroundServiceCompat(intent)
        }
    }

    fun resume(context: Context) {
        if (BaseReadAloudService.isRun) {
            val intent = Intent(context, aloudClass)
            intent.action = IntentAction.resume
            context.startForegroundServiceCompat(intent)
        }
    }

    fun stop(context: Context) {
        if (BaseReadAloudService.isRun) {
            val intent = Intent(context, aloudClass)
            intent.action = IntentAction.stop
            context.startForegroundServiceCompat(intent)
        }
    }

    // 切换书籍时停止朗读（archive-ui P1-B）：与 stop 等价，供 ReadBook.stopReadAloudForBookSwitch 联动 UI 状态
    fun stopForBookSwitch(context: Context) {
        stop(context)
    }

    fun prevParagraph(context: Context) {
        if (BaseReadAloudService.isRun) {
            val intent = Intent(context, aloudClass)
            intent.action = IntentAction.prevParagraph
            context.startForegroundServiceCompat(intent)
        }
    }

    fun nextParagraph(context: Context) {
        if (BaseReadAloudService.isRun) {
            val intent = Intent(context, aloudClass)
            intent.action = IntentAction.nextParagraph
            context.startForegroundServiceCompat(intent)
        }
    }

    fun upTtsSpeechRate(context: Context) {
        if (BaseReadAloudService.isRun) {
            val intent = Intent(context, aloudClass)
            intent.action = IntentAction.upTtsSpeechRate
            context.startForegroundServiceCompat(intent)
        }
    }

    fun setTimer(context: Context, minute: Int) {
        if (BaseReadAloudService.isRun) {
            val intent = Intent(context, aloudClass)
            intent.action = IntentAction.setTimer
            intent.putExtra("minute", minute)
            context.startForegroundServiceCompat(intent)
        }
    }

    // 定时朗读模式入口: mode 1=读完本章 2=剩余 chapters 章 (R7.2)
    fun setTimerMode(context: Context, mode: Int, chapters: Int = 0) {
        if (BaseReadAloudService.isRun) {
            val intent = Intent(context, aloudClass)
            intent.action = IntentAction.setTimer
            intent.putExtra("mode", mode)
            intent.putExtra("minute", 0)
            intent.putExtra("chapters", chapters)
            context.startForegroundServiceCompat(intent)
        }
    }

}