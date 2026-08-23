package io.legado.app.service.relay

import android.os.Build
import android.util.Base64
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import java.security.SecureRandom
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import okio.ByteString.Companion.decodeBase64
import okio.ByteString.Companion.toByteString
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.content.Context
import io.legado.app.constant.PreferKey
import io.legado.app.utils.defaultSharedPreferences

internal data class RelayIdentity(val deviceId: String, val secret: ByteArray)

internal class RelaySecretStore(private val context: Context) {
    companion object {
        private const val KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "legado.public_web_relay.v1"
        private const val SECRET_PREFS = "legado_relay_secret"
        private const val SECRET_BLOB = "device_secret_v1"
        private const val BLOB_VERSION: Byte = 1
        private const val IV_BYTES = 12
        private const val SECRET_BYTES = 32
        private const val DEVICE_ID_BYTES = 16
        private val globalLock = Any()

        val isSupported: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
    }

    private val random = SecureRandom()
    fun loadOrCreate(): RelayIdentity = synchronized(globalLock) {
        check(isSupported) { "AndroidKeyStore AES-GCM requires Android 6.0 or newer" }
        val normalPrefs = context.defaultSharedPreferences
        val secretPrefs = context.getSharedPreferences(SECRET_PREFS, Context.MODE_PRIVATE)
        val storedId = normalPrefs.getString(PreferKey.publicWebRelayDeviceId, null)
            ?.takeIf { it.matches(Regex("^[A-Za-z0-9_-]{22}$")) }
        val storedBlob = secretPrefs.getString(SECRET_BLOB, null)
        if (!storedId.isNullOrBlank() && !storedBlob.isNullOrBlank()) {
            runCatching { decrypt(storedBlob) }
                .getOrNull()
                ?.takeIf { it.size == SECRET_BYTES }
                ?.let { return RelayIdentity(storedId, it) }
        }

        // A missing/unreadable secret usually means a restore on another device. Rotate both
        // values and force the relay off so an old device identity is never silently reused.
        val identity = RelayIdentity(randomToken(DEVICE_ID_BYTES), ByteArray(SECRET_BYTES).also(random::nextBytes))
        check(secretPrefs.edit().putString(SECRET_BLOB, encrypt(identity.secret)).commit()) {
            "Unable to persist encrypted relay credential"
        }
        normalPrefs.edit()
            .putString(PreferKey.publicWebRelayDeviceId, identity.deviceId)
            .remove(PreferKey.publicWebRelayDeviceHandle)
            .remove(PreferKey.publicWebRelayPairedWorkerUrl)
            .putBoolean(PreferKey.publicWebRelayEnabled, false)
            .apply()
        identity
    }

    fun clear() = synchronized(globalLock) {
        context.getSharedPreferences(SECRET_PREFS, Context.MODE_PRIVATE).edit().clear().commit()
        context.defaultSharedPreferences.edit()
            .remove(PreferKey.publicWebRelayDeviceId)
            .remove(PreferKey.publicWebRelayDeviceHandle)
            .remove(PreferKey.publicWebRelayPairedWorkerUrl)
            .putBoolean(PreferKey.publicWebRelayEnabled, false)
            .apply()
    }

    private fun encrypt(secret: ByteArray): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        // AndroidKeyStore rejects caller-supplied IVs for encryption when the key requires
        // randomized encryption. Let the provider generate the nonce, then persist it beside
        // the ciphertext for decryption.
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val iv = requireNotNull(cipher.iv).also {
            require(it.size == IV_BYTES) { "Invalid generated secret IV" }
        }
        val encrypted = cipher.doFinal(secret)
        val blob = ByteArray(2 + iv.size + encrypted.size)
        blob[0] = BLOB_VERSION
        blob[1] = iv.size.toByte()
        System.arraycopy(iv, 0, blob, 2, iv.size)
        System.arraycopy(encrypted, 0, blob, 2 + iv.size, encrypted.size)
        return Base64.encodeToString(blob, Base64.NO_WRAP)
    }

    private fun decrypt(encoded: String): ByteArray {
        val blob = Base64.decode(encoded, Base64.NO_WRAP)
        require(blob.size > 2 + IV_BYTES) { "Invalid encrypted secret" }
        require(blob[0] == BLOB_VERSION) { "Unsupported secret version" }
        val ivLength = blob[1].toInt() and 0xff
        require(ivLength == IV_BYTES && blob.size > 2 + ivLength) { "Invalid secret IV" }
        val iv = blob.copyOfRange(2, 2 + ivLength)
        val encrypted = blob.copyOfRange(2 + ivLength, blob.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(128, iv))
        return cipher.doFinal(encrypted)
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).run {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true)
                    .build()
            )
            generateKey()
        }
    }

    private fun randomToken(byteCount: Int): String {
        val bytes = ByteArray(byteCount).also(random::nextBytes)
        return Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }
}

internal object RelayAuthenticator {
    private const val MAX_CLOCK_SKEW_MILLIS = 30_000L
    private const val MAX_CHALLENGE_LIFETIME_MILLIS = 5 * 60_000L

    fun createProof(
        identity: RelayIdentity,
        nonce: String,
        expiresAt: Long,
        epoch: Long,
        now: Long = System.currentTimeMillis()
    ): String {
        require(epoch > 0) { "Invalid connection epoch" }
        require(expiresAt >= now - MAX_CLOCK_SKEW_MILLIS) { "Expired challenge" }
        require(expiresAt <= now + MAX_CHALLENGE_LIFETIME_MILLIS) { "Challenge lifetime is too long" }
        val nonceBytes = nonce.decodeBase64()?.toByteArray()
            ?: throw IllegalArgumentException("Invalid challenge nonce")
        require(nonceBytes.size in 16..64) { "Invalid challenge nonce" }
        val canonical = "v1\n${identity.deviceId}\n$epoch\n$expiresAt\n$nonce"
            .toByteArray(StandardCharsets.UTF_8)
        val authKey = MessageDigest.getInstance("SHA-256").digest(identity.secret)
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(authKey, "HmacSHA256"))
        authKey.fill(0)
        return mac.doFinal(canonical).toByteString().base64Url().trimEnd('=')
    }

    data class ConnectProof(val timestamp: String, val nonce: String, val signature: String)

    fun createConnectProof(
        identity: RelayIdentity,
        timestampSeconds: Long = System.currentTimeMillis() / 1000L,
        nonceBytes: ByteArray = ByteArray(16).also(SecureRandom()::nextBytes)
    ): ConnectProof {
        require(timestampSeconds in 1_000_000_000L..9_999_999_999L) { "Invalid timestamp" }
        require(nonceBytes.size in 16..64) { "Invalid connect nonce" }
        return createControlProof(identity, "GET", "/v1/device/connect", ByteArray(0), timestampSeconds, nonceBytes)
    }

    fun deviceVerifier(identity: RelayIdentity): String {
        return MessageDigest.getInstance("SHA-256").digest(identity.secret)
            .toByteString().base64Url().trimEnd('=')
    }

    fun createControlProof(
        identity: RelayIdentity,
        method: String,
        path: String,
        body: ByteArray,
        timestampSeconds: Long = System.currentTimeMillis() / 1000L,
        nonceBytes: ByteArray = ByteArray(16).also(SecureRandom()::nextBytes)
    ): ConnectProof {
        require(method in setOf("GET", "POST", "DELETE")) { "Invalid control method" }
        require(path.startsWith("/v1/device/") || path == "/v1/device/connect") { "Invalid control path" }
        require(body.size <= RelayProtocol.MAX_CONTROL_BYTES) { "Control body is too large" }
        require(timestampSeconds in 1_000_000_000L..9_999_999_999L) { "Invalid timestamp" }
        require(nonceBytes.size in 16..64) { "Invalid control nonce" }
        val nonce = nonceBytes.toByteString().base64Url().trimEnd('=')
        val bodyHash = MessageDigest.getInstance("SHA-256").digest(body)
            .toByteString().base64Url().trimEnd('=')
        val canonical = listOf(
            "LEGADO-RELAY-CONTROL-V1",
            method,
            path,
            timestampSeconds.toString(),
            nonce,
            bodyHash
        ).joinToString("\n").toByteArray(StandardCharsets.UTF_8)
        val authKey = MessageDigest.getInstance("SHA-256").digest(identity.secret)
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(authKey, "HmacSHA256"))
        authKey.fill(0)
        return ConnectProof(
            timestampSeconds.toString(),
            nonce,
            mac.doFinal(canonical).toByteString().base64Url().trimEnd('=')
        )
    }
}

internal object RelayReadAllowlist {
    private val paths = setOf(
        "/getBookshelf",
        "/getChapterList",
        "/getBookContent",
        "/getBookContentEx",
        "/getReadConfig"
        , "/getBookCover"
    )
    private const val PROGRESS_PATH = "/saveBookProgress"
    private val allowedHeaders = setOf(
        "accept", "accept-language", "if-modified-since", "if-none-match", "range", "user-agent"
    )

    fun validate(message: RelayControlMessage): String? {
        val rawPath = message.path ?: return "invalid_path"
        if (rawPath.length !in 1..2048 || rawPath.any { it.code < 0x20 || it.code == 0x7f }) {
            return "invalid_path"
        }
        val pathOnly = rawPath.substringBefore('?')
        val lowerPath = pathOnly.lowercase()
        if (!rawPath.startsWith('/') || '#' in rawPath || '\\' in pathOnly || "//" in pathOnly ||
            "%2e" in lowerPath || "%2f" in lowerPath || "%5c" in lowerPath
        ) return "invalid_path"
        val method = message.method
        if (method != "GET" && !(method == "POST" && pathOnly == PROGRESS_PATH)) {
            return if (method == "POST") "method_not_allowed" else "path_not_allowed"
        }
        val isRead = method == "GET" && pathOnly in paths
        val isProgressWrite = method == "POST" && pathOnly == PROGRESS_PATH && rawPath == PROGRESS_PATH
        if (!isRead && !isProgressWrite) return "path_not_allowed"
        val body = message.bodyBase64?.decodeBase64()?.toByteArray() ?: ByteArray(0)
        val declaredLength = message.contentLength ?: 0L
        if (isRead && (declaredLength != 0L || body.isNotEmpty())) return "body_not_allowed"
        if (isProgressWrite && (body.isEmpty() || body.size > 16 * 1024 || declaredLength != body.size.toLong())) {
            return "invalid_body"
        }
        val headers = message.headers.orEmpty()
        if (headers.size > 32) return "headers_too_large"
        for ((name, value) in headers) {
            if (name.length !in 1..64 || value.length > 4096) return "headers_too_large"
            val normalizedName = name.lowercase()
            if (normalizedName !in allowedHeaders && !(isProgressWrite && normalizedName == "content-type")) {
                return "forbidden_header"
            }
            if (normalizedName == "content-type" && value.substringBefore(';').trim() != "application/json") {
                return "invalid_content_type"
            }
            if (name.any { it.code <= 0x20 || it.code >= 0x7f } ||
                value.any { it == '\r' || it == '\n' || it.code == 0 }
            ) return "invalid_header"
        }
        return null
    }
}
