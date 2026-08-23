package io.legado.app.service.relay

import io.legado.app.utils.GSON
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okio.Buffer
import java.io.IOException
import java.util.concurrent.TimeUnit

internal data class RelayShareResult(
    val id: String,
    val shareUrl: String,
    val expiresAt: Long,
    val allowProgress: Boolean = false,
    val permanent: Boolean = false
)

internal class RelayControlClient(
    private val config: RelayConfig,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .build()
) {
    companion object {
        private const val MAX_RESPONSE_BYTES = 64 * 1024L
        private val JSON = "application/json; charset=utf-8".toMediaType()
    }

    fun provision(adminToken: CharArray): String {
        require(adminToken.size >= 32) { "Deployment token must contain at least 32 characters" }
        val token = String(adminToken)
        try {
            val bodyBytes = GSON.toJson(
                mapOf("deviceVerifier" to RelayAuthenticator.deviceVerifier(config.identity))
            ).toByteArray(Charsets.UTF_8)
            val url = config.workerUrl.newBuilder()
                .addPathSegments("v1/admin/devices")
                .addPathSegment(config.identity.deviceId)
                .build()
            val request = Request.Builder()
                .url(url)
                .put(bodyBytes.toRequestBody(JSON))
                .header("Authorization", "Bearer $token")
                .build()
            val response = GSON.fromJson(execute(request), ProvisionResult::class.java)
            require(response.deviceId == config.identity.deviceId)
            val parts = response.deviceHandle.split('.')
            return response.deviceHandle.takeIf {
                parts.size == 2 && parts[0] == config.identity.deviceId &&
                    parts[1].matches(Regex("^[A-Za-z0-9_-]{22}$"))
            } ?: throw IOException("Worker returned an invalid device handle")
        } finally {
            adminToken.fill('\u0000')
        }
    }

    fun createReadShare(
        expiresInSeconds: Int = 7 * 24 * 60 * 60,
        permanent: Boolean = false,
        allowProgress: Boolean = false
    ): RelayShareResult {
        require(permanent || expiresInSeconds in 60..2_592_000)
        val path = "/v1/device/share"
        val bodyBytes = GSON.toJson(
            mapOf(
                "scope" to "read",
                "expiresInSeconds" to expiresInSeconds,
                "permanent" to permanent,
                "allowProgress" to allowProgress
            )
        ).toByteArray(Charsets.UTF_8)
        val proof = RelayAuthenticator.createControlProof(config.identity, "POST", path, bodyBytes)
        val request = Request.Builder()
            .url(config.workerUrl.newBuilder().addPathSegments("v1/device/share").build())
            .post(bodyBytes.toRequestBody(JSON))
            .signed(proof)
            .build()
        val response = execute(request)
        val parsed = GSON.fromJson(response, RelayShareResult::class.java)
        val shareUrl = parsed.shareUrl.toHttpUrlOrNull()
        val expectedPath = "/d/${config.requireDeviceHandle()}/"
        val token = shareUrl?.fragment?.removePrefix("token=")
        require(
            parsed.id.matches(Regex("^[A-Za-z0-9_-]{16,64}$")) &&
                shareUrl != null && shareUrl.scheme == "https" &&
                shareUrl.host == config.workerUrl.host && shareUrl.port == config.workerUrl.port &&
                shareUrl.encodedPath == expectedPath && shareUrl.query == null &&
                shareUrl.username.isEmpty() && shareUrl.password.isEmpty() &&
                shareUrl.fragment?.startsWith("token=") == true &&
                token?.matches(Regex("^[A-Za-z0-9_-]{16,64}\\.[A-Za-z0-9_-]{24,64}$")) == true &&
                token?.substringBefore('.') == parsed.id &&
                parsed.expiresAt > System.currentTimeMillis() / 1000L &&
                parsed.permanent == permanent && parsed.allowProgress == allowProgress
        ) {
            "Worker returned an invalid share link"
        }
        return parsed
    }

    fun revokeShare(id: String) {
        require(id.matches(Regex("^[A-Za-z0-9_-]{16,64}$"))) { "Invalid share id" }
        val path = "/v1/device/share/$id"
        val proof = RelayAuthenticator.createControlProof(config.identity, "DELETE", path, ByteArray(0))
        val request = Request.Builder()
            .url(config.workerUrl.newBuilder().addPathSegments("v1/device/share").addPathSegment(id).build())
            .delete()
            .signed(proof)
            .build()
        execute(request)
    }

    fun close() {
        config.identity.secret.fill(0)
    }

    private fun Request.Builder.signed(proof: RelayAuthenticator.ConnectProof): Request.Builder {
        return header("x-legado-device-id", config.identity.deviceId)
            .header("x-legado-device-handle", config.requireDeviceHandle())
            .header("x-legado-timestamp", proof.timestamp)
            .header("x-legado-nonce", proof.nonce)
            .header("x-legado-signature", proof.signature)
    }

    private data class ProvisionResult(val deviceId: String = "", val deviceHandle: String = "")

    private fun execute(request: Request): String {
        client.newCall(request).execute().use { response ->
            val text = response.body?.let(::readBounded).orEmpty()
            if (!response.isSuccessful) {
                throw IOException("Worker request failed (${response.code})")
            }
            return text
        }
    }

    private fun readBounded(body: okhttp3.ResponseBody): String {
        if (body.contentLength() > MAX_RESPONSE_BYTES) throw IOException("Worker response is too large")
        val buffer = Buffer()
        val source = body.source()
        var total = 0L
        while (true) {
            val read = source.read(buffer, minOf(8 * 1024L, MAX_RESPONSE_BYTES + 1 - total))
            if (read < 0) break
            total += read
            if (total > MAX_RESPONSE_BYTES) throw IOException("Worker response is too large")
        }
        return buffer.readString(Charsets.UTF_8)
    }
}
