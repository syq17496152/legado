package io.legado.app.help.update

import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.coroutine.Coroutine
import kotlinx.coroutines.CoroutineScope

object AppUpdate {

    val gitHubUpdate: AppUpdateInterface? by lazy {
        AppUpdateGitHub
    }
    val giteeUpdate: AppUpdateInterface by lazy {
        AppUpdateGitee
    }
    val preferredUpdate: AppUpdateInterface by lazy {
        PreferredAppUpdate
    }

    fun isLatestVersionError(error: Throwable): Boolean {
        val message = error.message ?: return false
        return error is NoStackTraceException &&
            (message.contains("最新版本") || message.contains("鏈€鏂扮増鏈"))
    }

    private object PreferredAppUpdate : AppUpdateInterface {
        // 简化说明: 目标项目无 AppUpdateConfig/AppUpdateInternal,Archive 的多源择优逻辑无法直移,
        // 这里以 GitHub 源作为首选更新检查源。
        override fun check(scope: CoroutineScope): Coroutine<UpdateInfo> {
            return AppUpdateGitHub.check(scope)
        }
    }


    data class UpdateInfo(
        val tagName: String,
        val updateLog: String,
        val downloadUrl: String,
        val fileName: String,
        val assetSize: Long = 0,
        val publishDate: Long = 0
    )

    interface AppUpdateInterface {

        fun check(scope: CoroutineScope): Coroutine<UpdateInfo>

    }

}
