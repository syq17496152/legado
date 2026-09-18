package io.legado.app.help.update

import io.legado.app.constant.AppConst
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.coroutine.Coroutine
import kotlinx.coroutines.CoroutineScope

object AppUpdate {

    val githubUpdate: AppUpdateInterface by lazy {
        AppUpdateGitHub
    }

    /**
     * 检查更新的统一入口（app-update-github-channel AD-01）
     *
     * 通道已收敛为 GitHub 单通道：历史上需要在 Gitee/GitHub 之间"择优 + 降级"，
     * 单通道后该编排不再需要。保留此入口名，使调用方无需感知通道变化。
     */
    val preferredUpdate: AppUpdateInterface by lazy {
        AppUpdateGitHub
    }

    data class UpdateInfo(
        val tagName: String,
        val updateLog: String,
        val downloadUrl: String,
        val fileName: String,
        val assetSize: Long = 0,
        val publishDate: Long = 0,
        /** 资产名解析出的 versionCode；0 表示资产名未携带，改用 versionName 数值化比较 */
        val versionCode: Long = 0
    )

    interface AppUpdateInterface {

        fun check(scope: CoroutineScope): Coroutine<UpdateInfo>

    }

    fun isLatestVersionError(error: Throwable): Boolean {
        val message = error.message ?: return false
        return error is NoStackTraceException &&
            (message.contains("最新版本") || message.contains("鏈€鏂扮増鏈"))
    }

    fun latestVersionError(): NoStackTraceException = NoStackTraceException("已是最新版本")

    private val VERSION_PATTERN = Regex("""^(\d+)\.(\d+)\.(\d+)""")

    /**
     * 是否有新版：优先 versionCode 数值比较，缺失时按规范化 versionName 比较。
     *
     * 不再用裸字符串比较：旧口径 `versionName > appInfo.versionName` 在版本名格式不严格一致时
     * 会误判；数值化后取值序与时间序一致（见 design AD-03）。
     */
    fun isNewerThanCurrent(updateInfo: UpdateInfo): Boolean {
        if (updateInfo.versionCode > 0L) {
            return updateInfo.versionCode > AppConst.appInfo.versionCode
        }
        val candidate = versionValueOf(updateInfo.tagName)
        if (candidate <= 0L) {
            // 版本名格式不符：退回裸字符串比较（与修复前行为等价，不比现状更差）
            return updateInfo.tagName > AppConst.appInfo.versionName
        }
        return candidate > versionValueOf(AppConst.appInfo.versionName)
    }

    /**
     * versionName 数值化：`major.minor.<定宽数字>` → major*1e9 + minor*1e7 + 时间段
     *
     * 例：`3.26.091720` → 3260091720、`3.26.100815` → 3260100815
     * （定宽 6 位保证数值序 = 时间序）。尾部字母后缀（测试包 `3.26.091817c`）被正则自然忽略；
     * 8 位时间段（旧命名尾部 2 位冗余）按基线 `parseVersionName` 口径先对齐为 6 位。
     * 格式不符返回 0（表示无法比较，调用方退回字符串比较）。
     */
    fun versionValueOf(versionName: String): Long {
        val match = VERSION_PATTERN.find(versionName) ?: return 0L
        val major = match.groupValues[1].toLongOrNull() ?: return 0L
        val minor = match.groupValues[2].toLongOrNull() ?: return 0L
        var segment = match.groupValues[3]
        if (segment.length == 8) segment = segment.dropLast(2)
        val time = segment.toLongOrNull() ?: return 0L
        return major * 1_000_000_000L + minor * 10_000_000L + time
    }
}