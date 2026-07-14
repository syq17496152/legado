package io.legado.app.constant

import android.util.Log
import io.legado.app.BuildConfig
import io.legado.app.help.config.AppConfig
import io.legado.app.utils.LogUtils
import io.legado.app.utils.toastOnUi
import splitties.init.appCtx

object AppLog {

    enum class Level { ERROR, WARN, INFO, DEBUG }

    data class LogEntry(
        val time: Long,
        val message: String,
        val throwable: Throwable? = null,
        val level: Level = Level.ERROR
    )

    private val mLogs = arrayListOf<LogEntry>()

    val logs get() = mLogs.toList()

    /**
     * P0 截断保护：防止 data URI(MB级base64图片) / 超长文本 导致 OOM/ANR/TransactionTooLargeException
     * - data URI 专项截断：startsWith("data:image/") 时截断为前80字符 + 长度提示
     * - 通用截断：>maxLen 字符按代码点截断（UTF-8安全，避免切断中文）+ 长度提示
     * 供 Debug.log / AppLog.putEntry / AppLog.putNotSave 复用，单点定义避免重复
     */
    fun truncateSafely(msg: String, maxLen: Int = 2000): String {
        if (msg.length <= maxLen) return msg
        // data URI 专项截断（base64 图片可达数 MB，撑爆 logcat + Binder 崩溃）
        if (msg.startsWith("data:image/")) {
            val prefixLen = minOf(80, msg.length)
            return "${msg.substring(0, prefixLen)}...(data URI 共${msg.length}字符，已截断)"
        }
        // 通用截断：按代码点截断，避免切断多字节中文字符
        val codePointCount = msg.codePointCount(0, msg.length)
        val safeEnd = if (codePointCount <= maxLen) msg.length
        else msg.offsetByCodePoints(0, maxLen)
        return "${msg.substring(0, safeEnd)}...(已截断，总长${msg.length}字符)"
    }

    @Synchronized
    fun put(message: String?, throwable: Throwable? = null, toast: Boolean = false) {
        putEntry(message, throwable, toast, Level.ERROR)
    }

    @Synchronized
    fun putError(message: String?, throwable: Throwable? = null, toast: Boolean = false) {
        putEntry(message, throwable, toast, Level.ERROR)
    }

    @Synchronized
    fun putWarn(message: String?, throwable: Throwable? = null, toast: Boolean = false) {
        putEntry(message, throwable, toast, Level.WARN)
    }

    @Synchronized
    fun putInfo(message: String?, throwable: Throwable? = null, toast: Boolean = false) {
        putEntry(message, throwable, toast, Level.INFO)
    }

    @Synchronized
    fun putNotSave(message: String?, throwable: Throwable? = null, toast: Boolean = false) {
        message ?: return
        // P0 截断保护：防止 data URI / 超长文本撑爆 logcat + mLogs
        val safeMsg = truncateSafely(message)
        if (toast) {
            appCtx.toastOnUi(safeMsg)
        }
        if (mLogs.size > 100) {
            mLogs.removeLastOrNull()
        }
        mLogs.add(0, LogEntry(System.currentTimeMillis(), safeMsg, throwable, Level.ERROR))
        if (BuildConfig.DEBUG) {
            val stackTrace = Thread.currentThread().stackTrace
            Log.e(stackTrace[3].className, safeMsg, throwable)
        }
    }

    @Synchronized
    fun clear() {
        mLogs.clear()
    }

    fun putDebug(message: String?, throwable: Throwable? = null) {
        if (AppConfig.recordLog) {
            putEntry(message, throwable, false, Level.DEBUG)
        }
    }

    @Synchronized
    private fun putEntry(
        message: String?,
        throwable: Throwable?,
        toast: Boolean,
        level: Level
    ) {
        message ?: return
        // P0 截断保护：防止 data URI / 超长文本撑爆 logcat + mLogs
        val safeMsg = truncateSafely(message)
        if (toast) {
            appCtx.toastOnUi(safeMsg)
        }
        if (mLogs.size > 100) {
            mLogs.removeLastOrNull()
        }
        if (throwable == null) {
            LogUtils.d("AppLog", safeMsg)
        } else {
            LogUtils.d("AppLog", "$safeMsg\n${throwable.stackTraceToString()}")
        }
        mLogs.add(0, LogEntry(System.currentTimeMillis(), safeMsg, throwable, level))
        if (BuildConfig.DEBUG) {
            val stackTrace = Thread.currentThread().stackTrace
            Log.e(stackTrace[3].className, safeMsg, throwable)
        }
    }

}
