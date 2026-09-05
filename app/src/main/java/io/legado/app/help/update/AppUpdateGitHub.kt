package io.legado.app.help.update

import androidx.annotation.Keep
import io.legado.app.constant.AppConst
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.config.AppConfig
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.help.http.newCallResponse
import io.legado.app.help.http.okHttpClient
import io.legado.app.help.http.text
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import kotlinx.coroutines.CoroutineScope

@Keep
@Suppress("unused")
object AppUpdateGitHub : AppUpdate.AppUpdateInterface {

    private val checkVariant: AppVariant
        get() = when (AppConfig.updateToVariant) {
            "official_version" -> AppVariant.OFFICIAL
            "beta_release_version" -> AppVariant.BETA_RELEASE
            "beta_releaseA_version" -> AppVariant.BETA_RELEASEA
            "beta_releaseS_version" -> AppVariant.BETA_RELEASES
            else -> AppConst.appInfo.appVariant
        }

    private suspend fun getLatestRelease(): List<AppReleaseInfo> {
        // 更新源指向本 fork 发布仓（修复硬编码上游原版仓导致应用内更新对 fork 发版失效）
        val lastReleaseUrl = if (checkVariant.isBeta()) {
            "https://api.github.com/repos/syq17496152/legado/releases/tags/beta"
        } else {
            "https://api.github.com/repos/syq17496152/legado/releases/latest"
        }
        val res = okHttpClient.newCallResponse {
            url(lastReleaseUrl)
        }
        if (!res.isSuccessful) {
            throw NoStackTraceException("获取新版本出错(${res.code})")
        }
        val body = res.body.text()
        if (body.isBlank()) {
            throw NoStackTraceException("获取新版本出错")
        }
        return GSON.fromJsonObject<GithubRelease>(body)
            .getOrElse {
                throw NoStackTraceException("获取新版本出错 " + it.localizedMessage)
            }
            .gitReleaseToAppReleaseInfo()
            .sortedByDescending { it.createdAt }
    }

    override fun check(
        scope: CoroutineScope,
    ): Coroutine<AppUpdate.UpdateInfo> {
        return Coroutine.async(scope) {
            checkAwait()
        }.timeout(10000)
    }

    suspend fun checkAwait(): AppUpdate.UpdateInfo {
        return getLatestRelease()
            .filter { it.appVariant == checkVariant }
            .firstOrNull { it.versionName > AppConst.appInfo.versionName }
            ?.let {
                AppUpdate.UpdateInfo(
                    it.versionName,
                    it.note,
                    it.downloadUrl,
                    it.name,
                    it.assetSize,
                    it.createdAt
                )
            }
            ?: throw NoStackTraceException("已是最新版本")
    }
}
