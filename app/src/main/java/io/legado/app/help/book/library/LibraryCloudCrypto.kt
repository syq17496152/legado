package io.legado.app.help.book.library

import android.util.Base64
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.SecureRandom
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

object LibraryCloudCrypto {

    private const val VERSION = 1
    private const val PREFIX = "LEGADO_LIBRARY_AES_GCM:"
    private const val ITERATIONS = 120_000
    private const val KEY_BITS = 256
    private const val TAG_BITS = 128
    private const val MAX_DECOMPRESSED_BYTES = 64 * 1024 * 1024
    private val random = SecureRandom()

    data class EncryptedPayload(
        val version: Int = VERSION,
        val salt: String = "",
        val nonce: String = "",
        val cipherText: String = ""
    )

    fun encodeJson(value: Any, password: String?, gzip: Boolean = false): ByteArray {
        val jsonBytes = GSON.toJson(value).toByteArray(Charsets.UTF_8)
        val payload = if (gzip) gzip(jsonBytes) else jsonBytes
        if (password.isNullOrBlank()) return payload
        return (PREFIX + GSON.toJson(encrypt(payload, password))).toByteArray(Charsets.UTF_8)
    }

    fun decodeString(bytes: ByteArray, password: String?): String {
        val payload = decodeBytes(bytes, password)
        return String(payload, Charsets.UTF_8)
    }

    private fun decodeBytes(bytes: ByteArray, password: String?): ByteArray {
        val raw = String(bytes, Charsets.UTF_8)
        val payload = if (!raw.startsWith(PREFIX)) {
            bytes
        } else {
            require(!password.isNullOrBlank()) { "云端内容已加密，请先设置密码" }
            val encrypted = GSON.fromJsonObject<EncryptedPayload>(raw.removePrefix(PREFIX))
                .getOrThrow()
            decrypt(encrypted, password)
        }
        return if (isGzip(payload)) gunzip(payload) else payload
    }

    private fun isGzip(bytes: ByteArray): Boolean {
        return bytes.size >= 2 && bytes[0] == 0x1f.toByte() && bytes[1] == 0x8b.toByte()
    }

    private fun gzip(bytes: ByteArray): ByteArray {
        val output = ByteArrayOutputStream()
        GZIPOutputStream(output).use { it.write(bytes) }
        return output.toByteArray()
    }

    private fun gunzip(bytes: ByteArray): ByteArray {
        return GZIPInputStream(ByteArrayInputStream(bytes)).use { input ->
            val output = ByteArrayOutputStream(minOf(bytes.size * 2, 1024 * 1024))
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var total = 0
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                total += read
                require(total <= MAX_DECOMPRESSED_BYTES) { "云端书库数据解压后过大" }
                output.write(buffer, 0, read)
            }
            output.toByteArray()
        }
    }

    private fun encrypt(bytes: ByteArray, password: String): EncryptedPayload {
        val salt = ByteArray(16).also(random::nextBytes)
        val nonce = ByteArray(12).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key(password, salt), GCMParameterSpec(TAG_BITS, nonce))
        return EncryptedPayload(
            salt = salt.b64(),
            nonce = nonce.b64(),
            cipherText = cipher.doFinal(bytes).b64()
        )
    }

    private fun decrypt(payload: EncryptedPayload, password: String): ByteArray {
        val salt = payload.salt.fromB64()
        val nonce = payload.nonce.fromB64()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(password, salt), GCMParameterSpec(TAG_BITS, nonce))
        return cipher.doFinal(payload.cipherText.fromB64())
    }

    private fun key(password: String, salt: ByteArray): SecretKeySpec {
        val spec = PBEKeySpec(password.toCharArray(), salt, ITERATIONS, KEY_BITS)
        val bytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        return SecretKeySpec(bytes, "AES")
    }

    private fun ByteArray.b64(): String = Base64.encodeToString(this, Base64.NO_WRAP)

    private fun String.fromB64(): ByteArray = Base64.decode(this, Base64.NO_WRAP)
}
