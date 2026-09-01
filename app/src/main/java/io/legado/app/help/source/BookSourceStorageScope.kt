package io.legado.app.help.source

import java.security.MessageDigest

/**
 * 书源存储命名空间计算（P0-S1，对齐 NG BookSourceStorageScope 1:1）
 *
 * ns = SHA-256("book\0" + sourceUrl) 的 hex64 小写十六进制串。
 * "\0" 前缀防止 sourceUrl 与任意字符串拼接产生碰撞命名空间。
 * 纯 JVM 计算无 Android 依赖，可直接单测。
 */
internal object BookSourceStorageScope {

    private const val IDENTITY_PREFIX = "book\u0000"

    /**
     * 计算书源对应的存储命名空间标识（64 位 hex 字符串）
     */
    fun namespace(sourceUrl: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest((IDENTITY_PREFIX + sourceUrl).toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
}
