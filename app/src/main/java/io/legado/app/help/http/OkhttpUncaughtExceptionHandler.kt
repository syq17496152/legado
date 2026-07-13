package io.legado.app.help.http

import io.legado.app.constant.AppLog

object OkhttpUncaughtExceptionHandler : Thread.UncaughtExceptionHandler {

    override fun uncaughtException(t: Thread, e: Throwable) {
        // P2-B 修复：记录完整异常类名，便于定位问题
        AppLog.put("Okhttp Dispatcher中的线程执行出错\nclass=${e.javaClass.name}, msg=${e.localizedMessage}", e)
    }

}
