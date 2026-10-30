package io.legado.app.help.tts.builtin

import java.security.MessageDigest

/** 纯 JVM MD5（不依赖 hutool，便于单测与内核可抽离） */
object BuiltinHash {

    fun md5Hex(value: String): String = md5Hex(value.toByteArray())

    fun md5Hex(value: ByteArray): String {
        val digest = MessageDigest.getInstance("MD5").digest(value)
        val builder = StringBuilder(digest.size * 2)
        digest.forEach { b ->
            val v = b.toInt() and 0xFF
            if (v < 0x10) builder.append('0')
            builder.append(Integer.toHexString(v))
        }
        return builder.toString()
    }
}
