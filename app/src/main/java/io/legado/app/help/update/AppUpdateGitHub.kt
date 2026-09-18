package io.legado.app.help.update

import androidx.annotation.Keep
import io.legado.app.constant.AppConst
import io.legado.app.constant.AppLog
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.config.AppConfig
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.help.http.newCallResponse
import io.legado.app.help.http.okHttpClient
import io.legado.app.help.http.text
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.withTimeoutOrNull

@Keep
@Suppress("unused")
object AppUpdateGitHub : AppUpdate.AppUpdateInterface {

    /** 发布仓 Releases API（本 fork 发布仓，公开可匿名访问；实测直连可用） */
    private const val RELEASE_URL =
        "https://api.github.com/repos/syq17496152/legado/releases/latest"

    /** 单次候选尝试限时 */
    private const val ATTEMPT_TIMEOUT_MS = 8_000L

    /** 候选上限 = 直连 + 前 3 个代理模板，防止叠加大量模板把总超时耗尽（design §5.2/§5.4） */
    private const val MAX_QUERY_CANDIDATES = 4

    private val checkVariant: AppVariant
        get() = when (AppConfig.updateToVariant) {
            "official_version" -> AppVariant.OFFICIAL
            "beta_release_version" -> AppVariant.BETA_RELEASE
            "beta_releaseA_version" -> AppVariant.BETA_RELEASEA
            "beta_releaseS_version" -> AppVariant.BETA_RELEASES
            else -> AppConst.appInfo.appVariant
        }

    /**
     * 查询候选序列：直连优先，失败后按代理模板逐个兜底（app-update-github-channel AD-02）
     *
     * 直连优先的依据（实测）：`api.github.com` 直连可用且最快（200/0.94s），而公共加速站多为
     * 文件下载代理，对 API 的支持参差（实测仅部分站点返回完整 JSON）→ 代理只作失败重试路径，
     * 避免选中一个不支持 API 的模板就导致"检查更新"整体失效。
     */
    private fun buildQueryCandidates(): List<String> {
        val proxied = AppUpdateConfig.githubProxyTemplates
            .map { AppUpdateConfig.applyTemplate(it, RELEASE_URL) }
        return (listOf(RELEASE_URL) + proxied).distinct().take(MAX_QUERY_CANDIDATES)
    }

    private suspend fun getLatestRelease(): List<AppReleaseInfo> {
        val candidates = buildQueryCandidates()
        val proxyLabel = AppUpdateConfig.selectedGithubProxyTemplate
            ?.let { AppUpdateConfig.proxyLabel(it) }
            ?: "none"
        AppLog.put("AppUpdate 检查开始: 候选数=${candidates.size}, 选中代理=$proxyLabel")
        var lastError: Throwable? = null
        candidates.forEachIndexed { index, candidateUrl ->
            // 取消必须透传：超时取消由 withTimeoutOrNull 转成 null（继续下一候选），
            // 而父协程取消要能真正中断整段（项目铁律：runCatching 会吞 CancellationException）
            val result = withTimeoutOrNull(ATTEMPT_TIMEOUT_MS) {
                try {
                    Result.success(fetchRelease(candidateUrl))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    Result.failure(e)
                }
            }
            val releases = result?.getOrNull()
            if (releases != null) {
                AppLog.put("AppUpdate 候选命中: 序号=$index, 数量=${releases.size}")
                return releases.sortedByDescending { it.createdAt }
            }
            lastError = result?.exceptionOrNull() ?: lastError
            val reason = lastError?.let { "${it.javaClass.simpleName}:${it.message}" } ?: "超时"
            AppLog.put("AppUpdate 候选失败: 序号=$index, 原因=$reason")
        }
        AppLog.put("AppUpdate 全部候选失败: 候选数=${candidates.size}")
        val message = lastError?.localizedMessage ?: lastError?.message
        throw NoStackTraceException(
            if (message.isNullOrBlank()) "获取新版本出错" else "获取新版本出错 $message"
        )
    }

    private suspend fun fetchRelease(url: String): List<AppReleaseInfo> {
        val res = okHttpClient.newCallResponse {
            url(url)
        }
        if (!res.isSuccessful) {
            throw NoStackTraceException("(${res.code})")
        }
        val body = res.body.text()
        if (body.isBlank()) {
            throw NoStackTraceException("空响应")
        }
        return GSON.fromJsonObject<GithubRelease>(body)
            .getOrElse { throw NoStackTraceException(it.localizedMessage ?: "解析失败") }
            .gitReleaseToAppReleaseInfo()
    }

    override fun check(
        scope: CoroutineScope,
    ): Coroutine<AppUpdate.UpdateInfo> {
        // 20s 覆盖"多候选逐个重试"的最坏情况（单次 8s × 候选上限，受外层总超时约束）
        return Coroutine.async(scope) {
            checkAwait()
        }.timeout(20000)
    }

    suspend fun checkAwait(): AppUpdate.UpdateInfo {
        val release = getLatestRelease()
            .filter { it.appVariant == checkVariant }
            .maxByOrNull { it.createdAt }
            ?: throw AppUpdate.latestVersionError()
        // 下载地址按当前选中的代理模板改写（未选中则原样）
        val updateInfo = AppUpdate.UpdateInfo(
            tagName = release.versionName,
            updateLog = release.note,
            downloadUrl = AppUpdateConfig.applyGithubProxy(release.downloadUrl),
            fileName = release.name,
            assetSize = release.assetSize,
            publishDate = release.createdAt,
            versionCode = release.versionCode
        )
        if (!AppUpdate.isNewerThanCurrent(updateInfo)) {
            throw AppUpdate.latestVersionError()
        }
        AppLog.put(
            "AppUpdate 组装更新信息: 版本=${updateInfo.tagName}, " +
                "versionCode=${updateInfo.versionCode}, 代理=${AppUpdateConfig.selectedGithubProxyTemplate != null}"
        )
        return updateInfo
    }
}