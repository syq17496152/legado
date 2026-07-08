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
        if (toast) {
            appCtx.toastOnUi(message)
        }
        if (mLogs.size > 100) {
            mLogs.removeLastOrNull()
        }
        mLogs.add(0, LogEntry(System.currentTimeMillis(), message, throwable, Level.ERROR))
        if (BuildConfig.DEBUG) {
            val stackTrace = Thread.currentThread().stackTrace
            Log.e(stackTrace[3].className, message, throwable)
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
        if (toast) {
            appCtx.toastOnUi(message)
        }
        if (mLogs.size > 100) {
            mLogs.removeLastOrNull()
        }
        if (throwable == null) {
            LogUtils.d("AppLog", message)
        } else {
            LogUtils.d("AppLog", "$message\n${throwable.stackTraceToString()}")
        }
        mLogs.add(0, LogEntry(System.currentTimeMillis(), message, throwable, level))
        if (BuildConfig.DEBUG) {
            val stackTrace = Thread.currentThread().stackTrace
            Log.e(stackTrace[3].className, message, throwable)
        }
    }

}
