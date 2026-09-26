package io.legado.app.utils

import android.util.Base64

const val MAX_DATA_URL_BYTES = 32 * 1024 * 1024

fun String.decodeBase64DataUrlBytes(maxBytes: Long = MAX_DATA_URL_BYTES.toLong()): ByteArray? {
    val clean = trim()
        .trimMatchingDataUrlWrapper()
        .replace("&amp;", "&")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
    val rawPayload = when {
        clean.startsWith("data:", ignoreCase = true) -> {
            val commaIndex = clean.indexOf(',')
            if (commaIndex < 0) return null
            val meta = clean.substring(0, commaIndex).lowercase()
            if (!meta.contains(";base64")) return null
            clean.substring(commaIndex + 1).substringBefore(",{")
        }
        clean.startsWith("data64:", ignoreCase = true) -> {
            clean.substringAfter(':').substringBefore(",{")
        }
        else -> return null
    }
    val payload = rawPayload
        .trimMatchingDataUrlWrapper()
        .let { it.decodePercentEscapesPreservingPlus() }
        .filterNot { it.isWhitespace() }
    return decodeTolerantBase64(payload, maxBytes)
}

/**
 * 宽容 Base64 解码（3.3.3 单源）——**外部来源**的 base64 载荷（正文/网页 data URI 等）一律走这里。
 *
 * 为什么必须宽容：`android.util.Base64.decode` 遇到非法字符/缺填充**直接抛异常**，正文里一个畸形
 * data URI 就能让「保存图片/加载正文」整段失败（甚至崩溃）；而外部数据本就常带 URL-safe 变体、
 * 换行、HTML 实体或百分号转义。处理链：百分号转义 → 去空白 → 补 `=` → DEFAULT 解码 →
 * URL_SAFE 变体 → 畸形字符清洗后再试一次；均失败或超 [maxBytes] 返回 null（调用方回落）。
 */
fun decodeTolerantBase64(payload: String, maxBytes: Long = MAX_DATA_URL_BYTES.toLong()): ByteArray? {
    val clean = payload
        .trim()
        .let { it.decodePercentEscapesPreservingPlus() }
        .filterNot { it.isWhitespace() }
    if (clean.isBlank()) return null
    if (clean.estimatedBase64Bytes() > maxBytes) return null

    fun decode(value: String): ByteArray? = runCatching {
        Base64.decode(value, Base64.DEFAULT)
    }.getOrElse {
        runCatching { Base64.decode(value, Base64.URL_SAFE) }.getOrNull()
    }

    decode(clean.padBase64())?.takeIf { it.size.toLong() <= maxBytes }?.let { return it }
    val sanitized = clean
        .replace(Regex("[^A-Za-z0-9+/=_-]"), "")
        .padBase64()
    if (sanitized.isBlank()) return null
    if (sanitized.estimatedBase64Bytes() > maxBytes) return null
    return decode(sanitized)?.takeIf { it.size.toLong() <= maxBytes }
}

fun String.estimateBase64DataUrlBytes(): Long? {
    val clean = trim().trimMatchingDataUrlWrapper()
    val payload = when {
        clean.startsWith("data:", ignoreCase = true) -> {
            val commaIndex = clean.indexOf(',')
            if (commaIndex < 0 || !clean.substring(0, commaIndex).contains(";base64", true)) return null
            clean.substring(commaIndex + 1).substringBefore(",{")
        }
        clean.startsWith("data64:", ignoreCase = true) -> clean.substringAfter(':').substringBefore(",{")
        else -> return null
    }.filterNot { it.isWhitespace() }
    if (payload.isBlank()) return null
    return payload.estimatedBase64Bytes()
}

private fun String.decodePercentEscapesPreservingPlus(): String {
    if (!contains('%')) return this
    val bytes = ByteArray(length)
    var write = 0
    var index = 0
    while (index < length) {
        val ch = this[index]
        if (ch == '%' && index + 2 < length) {
            val hi = this[index + 1].digitToIntOrNull(16)
            val lo = this[index + 2].digitToIntOrNull(16)
            if (hi != null && lo != null) {
                bytes[write++] = ((hi shl 4) or lo).toByte()
                index += 3
                continue
            }
        }
        if (ch.code <= 0x7F) {
            bytes[write++] = ch.code.toByte()
        } else {
            val encoded = ch.toString().toByteArray(Charsets.UTF_8)
            encoded.forEach { b -> bytes[write++] = b }
        }
        index++
    }
    return bytes.copyOf(write).toString(Charsets.UTF_8)
}

private fun String.trimMatchingDataUrlWrapper(): String {
    var value = trim()
    while (value.isNotEmpty() && value.first() in charArrayOf('\'', '"')) {
        value = value.drop(1).trimStart()
    }
    while (value.isNotEmpty() && value.last() in charArrayOf('\'', '"', ')', ';')) {
        value = value.dropLast(1).trimEnd()
    }
    return value
}

private fun String.padBase64(): String {
    val remainder = length % 4
    return if (remainder == 0) this else this + "=".repeat(4 - remainder)
}

private fun String.estimatedBase64Bytes(): Long {
    val padding = takeLastWhile { it == '=' }.length.coerceAtMost(2)
    return (length.toLong() * 3L / 4L - padding).coerceAtLeast(0L)
}