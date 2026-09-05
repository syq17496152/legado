package io.legado.app.help.update

import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.coroutine.Coroutine
import kotlinx.coroutines.CancellationException
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
        // 多源择优：Gitee 为主源（国内网络稳定，指向本 fork 发布仓），
        // Gitee 请求失败或未检出更新（发布滞后）时降级 GitHub 源兜底，
        // 两源均无更新时透传"已是最新版本"；CancellationException 必须透传防吞取消
        override fun check(scope: CoroutineScope): Coroutine<AppUpdate.UpdateInfo> {
            return Coroutine.async(scope) {
                val gitee = try {
                    AppUpdateGitee.checkAwait()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    null
                }
                gitee ?: AppUpdateGitHub.checkAwait()
            }.timeout(20000)
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
