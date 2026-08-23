package io.legado.app.help.book.library

import android.util.Base64
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

object LibraryContainerExportCrypto {

    // 这里的加密只用于分享导出时避免容器配置明文暴露。
    // 开源客户端不适合伪装成不可破解的 DRM，也没必要为了正常分享场景去做对抗式隐藏。
    private const val TYPE = "legado.library.container.encrypted"
    private const val VERSION = 1
    private const val KEY_PREFIX = "LBK1"
    private const val KEY_BYTES = 32
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128
    private const val AAD = "legado.library.container.export.v1"

    fun isEncrypted(text: String): Boolean {
        return GSON.fromJsonObject<EncryptedPayload>(text.trim()).getOrNull()?.type == TYPE
    }

    fun encrypt(json: String): EncryptedResult {
        val key = ByteArray(KEY_BYTES)
        val iv = ByteArray(IV_BYTES)
        SecureRandom().nextBytes(key)
        SecureRandom().nextBytes(iv)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, iv))
        cipher.updateAAD(AAD.toByteArray())
        val encrypted = cipher.doFinal(json.toByteArray(Charsets.UTF_8))
        val payload = EncryptedPayload(
            iv = encode(iv),
            keyHint = checksum(key),
            ciphertext = encode(encrypted)
        )
        return EncryptedResult(payload, formatKey(key))
    }

    fun decrypt(text: String, decryptKey: String): String {
        val payload = GSON.fromJsonObject<EncryptedPayload>(text.trim()).getOrThrow()
        require(payload.type == TYPE && payload.version == VERSION) { "不支持的加密格式" }
        val key = parseKey(decryptKey)
        require(payload.keyHint == checksum(key)) { "解密密钥不正确" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(key, "AES"),
            GCMParameterSpec(TAG_BITS, decode(payload.iv))
        )
        cipher.updateAAD(AAD.toByteArray())
        return cipher.doFinal(decode(payload.ciphertext)).toString(Charsets.UTF_8)
    }

    private fun formatKey(key: ByteArray): String {
        return "$KEY_PREFIX.${encode(key)}.${checksum(key)}"
    }

    private fun parseKey(value: String): ByteArray {
        val parts = value.trim().split('.')
        val body = if (parts.size == 3 && parts[0] == KEY_PREFIX) parts[1] else value.trim()
        val key = decode(body)
        require(key.size == KEY_BYTES) { "解密密钥格式不正确" }
        if (parts.size == 3 && parts[2] != checksum(key)) {
            throw IllegalArgumentException("解密密钥校验失败")
        }
        return key
    }

    private fun checksum(key: ByteArray): String {
        return encode(MessageDigest.getInstance("SHA-256").digest(key).copyOfRange(0, 4))
    }

    private fun encode(bytes: ByteArray): String {
        return Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    }

    private fun decode(value: String): ByteArray {
        return Base64.decode(value, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    }

    data class EncryptedResult(
        val payload: EncryptedPayload,
        val decryptKey: String
    )

    data class EncryptedPayload(
        val type: String = TYPE,
        val version: Int = VERSION,
        val alg: String = "AES-256-GCM",
        val iv: String = "",
        val keyHint: String = "",
        val ciphertext: String = ""
    )
}
