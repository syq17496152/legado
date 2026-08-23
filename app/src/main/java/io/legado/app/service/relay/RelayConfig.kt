package io.legado.app.service.relay

import android.content.Context
import android.os.Build
import io.legado.app.constant.PreferKey
import io.legado.app.utils.defaultSharedPreferences
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

internal data class RelayConfig(
    val workerUrl: HttpUrl,
    val deviceName: String,
    val identity: RelayIdentity,
    val deviceHandle: String?
) {
    /** OkHttp performs a secure WebSocket upgrade for an HTTPS request URL. */
    val socketUrl: HttpUrl
        get() = workerUrl.newBuilder()
            .addPathSegments("v1/device/connect")
            .addQueryParameter("deviceHandle", requireDeviceHandle())
            .build()

    companion object {
        const val DEFAULT_WORKER_URL = "https://"

        fun load(context: Context): Result<RelayConfig> = runCatching {
            check(RelaySecretStore.isSupported) {
                "Android 6.0 or newer is required for secure credential storage"
            }
            val prefs = context.defaultSharedPreferences
            val rawUrl = prefs.getString(PreferKey.publicWebRelayWorkerUrl, "").orEmpty()
            val url = normalizeWorkerUrl(rawUrl)
            val deviceName = prefs.getString(PreferKey.publicWebRelayDeviceName, null)
                .orEmpty()
                .trim()
                .ifBlank { Build.MODEL?.trim().orEmpty().ifBlank { "Android device" } }
                .take(64)
            val identity = RelaySecretStore(context).loadOrCreate()
            val pairedWorkerUrl = prefs.getString(PreferKey.publicWebRelayPairedWorkerUrl, null)
            val deviceHandle = prefs.getString(PreferKey.publicWebRelayDeviceHandle, null)
                ?.trim()
                ?.takeIf { it == "${identity.deviceId}.${it.substringAfter('.', "")}" }
                ?.takeIf { it.matches(Regex("^[A-Za-z0-9_-]{22}\\.[A-Za-z0-9_-]{22}$")) }
                ?.takeIf { pairedWorkerUrl == url.toString().trimEnd('/') }
            RelayConfig(url, deviceName, identity, deviceHandle)
        }

        fun normalizeWorkerUrl(raw: String): HttpUrl {
            val trimmed = raw.trim().trimEnd('/')
            val url = trimmed.toHttpUrlOrNull() ?: throw IllegalArgumentException("Invalid Worker URL")
            require(url.scheme == "https") { "Worker URL must use HTTPS" }
            require(url.username.isEmpty() && url.password.isEmpty()) { "Worker URL must not contain credentials" }
            require(url.query == null && url.fragment == null) { "Worker URL must not contain a query or fragment" }
            require(url.encodedPath == "/") { "Worker URL must not contain a path" }
            return url
        }
    }

    fun requireDeviceHandle(): String = requireNotNull(deviceHandle) {
        "Device must be paired with this Worker first"
    }
}
