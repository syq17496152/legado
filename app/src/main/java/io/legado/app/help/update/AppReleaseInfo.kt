package io.legado.app.help.update

import androidx.annotation.Keep
import com.google.gson.annotations.SerializedName
import io.legado.app.exception.NoStackTraceException
import java.time.Instant

data class AppReleaseInfo(
    val appVariant: AppVariant,
    val createdAt: Long,
    val note: String,
    val name: String,
    val downloadUrl: String,
    val assetUrl: String,
    val assetSize: Long = 0
) {
    val versionName: String = parseVersionName(name)

    companion object {
        /**
         * 从资产文件名解析版本号（app-update-variant-fix）。
         * 新命名（publish_release.py get_upload_name）：legado_miss_app_debug_{v}.apk /
         * legado_miss_app_{v}.apk / legado_legacy_app_{v}.apk → 版本号是最后一个 "_" 后的段；
         * 旧命名（legado-E 风格）：版本号尾部多 2 位冗余（3.XX.YYMMDDHH）→ 8 位日期时 dropLast(2)
         */
        internal fun parseVersionName(name: String): String {
            val segment = name.removeSuffix(".apk").substringAfterLast('_')
            return if (segment.length == 13 && VERSION_SEGMENT.matches(segment)) {
                segment.dropLast(2)
            } else {
                segment
            }
        }

        private val VERSION_SEGMENT = Regex("""^3\.\d{2}\.\d{6,8}$""")
    }
}

/**
 * 按资产文件名判定包变体（app-update-variant-fix）。
 * 旧逻辑只认 "release/releaseA/releaseS" 子串，而发布脚本实际上传名
 * legado_miss_app_debug_*（测试）/ legado_miss_app_*（正式）/ legado_legacy_app_*（共存）
 * 全部不含 "release"，导致三包全被解析成 OFFICIAL：
 * 测试包过滤永远为空（有新版也不提示），正式包会混入测试/共存包甚至被"更新"成测试包。
 */
internal fun resolveAppVariant(name: String, preRelease: Boolean): AppVariant = when {
    name.contains("releaseA") -> AppVariant.BETA_RELEASEA
    name.contains("releaseS") -> AppVariant.BETA_RELEASES
    name.contains("debug") -> AppVariant.BETA_RELEASE
    name.contains("legacy") -> AppVariant.BETA_RELEASEA
    preRelease && name.contains("release") -> AppVariant.BETA_RELEASE
    else -> AppVariant.OFFICIAL
}

enum class AppVariant {
    OFFICIAL,
    BETA_RELEASEA,
    BETA_RELEASES,
    BETA_RELEASE,
    UNKNOWN;

    fun isBeta(): Boolean {
        return this == BETA_RELEASE || this == BETA_RELEASEA
    }

}

@Keep
data class GithubRelease(
    val assets: List<Asset>?,
    val body: String,
    @SerializedName("prerelease")
    val isPreRelease: Boolean,
) {
    fun gitReleaseToAppReleaseInfo(): List<AppReleaseInfo> {
        assets ?: throw NoStackTraceException("获取新版本出错")
        return assets
            .filter { it.isValid }
            .map { it.assetToAppReleaseInfo(isPreRelease, body) }
    }
}
@Keep
data class GiteeRelease(
    val assets: List<GiteeAsset>?,
    val body: String,
    @SerializedName("prerelease")
    val prerelease: Boolean,
) {
    fun gitReleaseToAppReleaseInfo(): List<AppReleaseInfo> {
        assets ?: throw NoStackTraceException("获取新版本出错")
        return assets
            .filter { it.isValid }
            .map { it.assetToAppReleaseInfo(prerelease, body) }
    }
}

@Keep
data class Asset(
    @SerializedName("browser_download_url")
    val apkUrl: String,
    @SerializedName("content_type")
    val contentType: String,
    @SerializedName("created_at")
    val createdAt: String,
    @SerializedName("download_count")
    val downloadCount: Int,
    val id: Int,
    val name: String,
    val size: Long = 0,
    val state: String,
    val url: String
) {
    val isValid: Boolean
        get() = (contentType == "application/vnd.android.package-archive") && (state == "uploaded")

    fun assetToAppReleaseInfo(preRelease: Boolean, note: String): AppReleaseInfo {
        val instant = Instant.parse(createdAt)
        val timestamp: Long = instant.toEpochMilli()

        val appVariant = resolveAppVariant(name, preRelease)

        return AppReleaseInfo(appVariant, timestamp, note, name, apkUrl, url, size)
    }
}

@Keep
data class GiteeAsset(
    @SerializedName("browser_download_url")
    val apkUrl: String,
    @SerializedName("name")
    val name: String
) {
    val isValid: Boolean
        get() = apkUrl.contains(".apk")

    fun assetToAppReleaseInfo(preRelease: Boolean, note: String): AppReleaseInfo {
        val appVariant = resolveAppVariant(name, preRelease)
        return AppReleaseInfo(appVariant, 0, note, name, apkUrl, "")
    }
}


