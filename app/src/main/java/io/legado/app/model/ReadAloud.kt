package io.legado.app.model

import android.content.Context
import android.content.Intent
import android.os.Bundle
import io.legado.app.constant.AppLog
import io.legado.app.constant.EventBus
import io.legado.app.constant.IntentAction
import io.legado.app.data.appDb
import io.legado.app.data.entities.HttpTTS
import io.legado.app.help.config.AppConfig
import io.legado.app.service.BaseReadAloudService
import io.legado.app.service.HttpReadAloudService
import io.legado.app.service.TTSReadAloudService
import io.legado.app.utils.LogUtils
import io.legado.app.utils.StringUtils
import io.legado.app.utils.postEvent
import io.legado.app.utils.startForegroundServiceCompat
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.runBlocking
import splitties.init.appCtx

object ReadAloud {
    private var aloudClass: Class<*> = getReadAloudClass()
    val ttsEngine get() = ReadBook.book?.getTtsEngine() ?: AppConfig.ttsEngine
    var httpTTS: HttpTTS? = null

    private fun getReadAloudClass(): Class<*> {
        val ttsEngine = ttsEngine
        if (ttsEngine.isNullOrBlank()) {
            return TTSReadAloudService::class.java
        }
        if (StringUtils.isNumeric(ttsEngine)) {
            httpTTS = runBlocking(IO) { appDb.httpTTSDao.get(ttsEngine.toLong()) }
            if (httpTTS != null) {
                return HttpReadAloudService::class.java
            }
        }
        return TTSReadAloudService::class.java
    }

    fun upReadAloudClass() {
        stop(appCtx)
        aloudClass = getReadAloudClass()
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