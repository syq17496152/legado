package io.legado.app.utils

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import kotlin.random.Random

/**
 * Codec for the clipboard import codes used by Luoyacheng/legado-E.
 *
 * Keep the token table and envelope compatible with the upstream implementation so codes can be
 * exchanged between the two apps. Parsing is intentionally stricter than upstream: malformed
 * metadata returns a failure instead of throwing from a substring operation in the UI thread.
 */
object ShibbolethCodec {

    const val MAX_CODE_CHARS = 16 * 1024

    const val BOOK_SOURCE = "sy"
    const val RSS_SOURCE = "dy"
    const val DICT_RULE = "zd"
    const val REPLACE_RULE = "jh"
    const val TOC_RULE = "ml"
    const val TTS_RULE = "ld"

    private const val URL_MARKER = "#L:"
    private const val MILLIS_PREFIX_SCALE = 1_000_000L

    private val mappings = linkedMapOf(
        "https://" to listOf(URL_MARKER),
        "." to listOf("电", "店", "垫", "殿", "。"),
        "%" to listOf("白", "百", "拜", "摆", "💯"),
        "/" to listOf("杠", "刚", "钢", "岗", "🎹"),
        "zip" to listOf("压", "亚", "呀", "牙", "🦆"),
        "json" to listOf("串", "穿", "船", "传", "🚢"),
        "4" to listOf("四", "是", "时", "丝", "🕓"),
        "5" to listOf("五", "武", "误", "勿", "🕔"),
        "6" to listOf("六", "刘", "留", "陆", "🕕"),
        "0" to listOf("零", "另", "玲", "灵", "⏰"),
        "com" to listOf("🛜1", "🌐1", "🌏1"),
        "cn" to listOf("🛜2", "🌐2", "🌏2"),
        "net" to listOf("🛜3", "🌐3", "🌏3"),
        "org" to listOf("🛜7", "🌐7", "🌏7"),
        "xyz" to listOf("🛜8", "🌐8", "🌏8"),
        "me" to listOf("🛜9", "🌐9", "🌏9")
    )

    private val sortedMappingKeys = mappings.keys.sortedByDescending(String::length)

    private val reverseMappings = mappings.flatMap { (original, replacements) ->
        replacements.map { replacement -> replacement to original }
    }.sortedByDescending { (replacement, _) -> replacement.length }

    data class Payload(
        val url: String,
        val type: String,
        val customWord: String,
        val expiresAtMillis: Long?
    ) {
        fun isExpired(nowMillis: Long = System.currentTimeMillis()): Boolean {
            return expiresAtMillis?.let { it < nowMillis } == true
        }
    }

    fun looksLikeCode(text: String?): Boolean {
        return text != null && text.length <= MAX_CODE_CHARS && text.contains(URL_MARKER)
    }

    fun canEncodeUrl(url: String): Boolean = validatedHttpsUrl(url) != null

    fun encode(
        url: String,
        type: String,
        timeMillis: Long = System.currentTimeMillis(),
        expiryDays: Int = 0
    ): Result<String> = runCatching {
        require(canEncodeUrl(url)) {
            "Only HTTPS direct links can be converted to an import code"
        }
        require(type in supportedTypes) { "Unsupported import code type: $type" }
        require(expiryDays >= 0) { "Expiry days must not be negative" }

        val normalizedUrl = requireNotNull(validatedHttpsUrl(url))
        val random = Random(timeMillis)
        val encodedUrl = buildString(normalizedUrl.length) {
            var index = 0
            while (index < normalizedUrl.length) {
                val key = sortedMappingKeys.firstOrNull { normalizedUrl.startsWith(it, index) }
                if (key == null) {
                    append(normalizedUrl[index])
                    index++
                } else {
                    val replacements = requireNotNull(mappings[key])
                    append(replacements[random.nextInt(replacements.size)])
                    index += key.length
                }
            }
        }
        val expiresAt = if (expiryDays == 0) {
            0L
        } else {
            Math.addExact(
                timeMillis,
                Math.multiplyExact(expiryDays.toLong(), 24L * 60L * 60L * 1000L)
            )
        }
        val expiryPrefix = expiresAt.toString().take(7)
        "复制口令到阅读导入$encodedUrl！$type©$expiryPrefix¥Sigma^"
    }

    fun decode(text: String): Result<Payload> = runCatching {
        require(text.length <= MAX_CODE_CHARS) { "Import code is too long" }
        val markerIndex = text.indexOf(URL_MARKER)
        require(markerIndex >= 0) { "Import code marker is missing" }

        val urlEnd = text.indexOf('！', markerIndex).let { if (it >= 0) it else text.length }
        require(urlEnd > markerIndex) { "Import code URL is missing" }
        var url = text.substring(markerIndex, urlEnd)
        reverseMappings.forEach { (replacement, original) ->
            url = url.replace(replacement, original)
        }
        require(url.none { it.isWhitespace() || it.isISOControl() }) { "Import code URL is invalid" }
        val parsedUrl = url.toHttpUrlOrNull()
            ?: throw IllegalArgumentException("Import code URL is invalid")
        require(parsedUrl.isHttps && parsedUrl.username.isEmpty() && parsedUrl.password.isEmpty()) {
            "Import code URL is invalid"
        }
        url = parsedUrl.toString()

        if (urlEnd == text.length) {
            return@runCatching Payload(url, "", "", null)
        }

        val typeStart = urlEnd + 1
        val expirySeparator = text.indexOf('©', typeStart)
        val type = if (expirySeparator >= typeStart) {
            text.substring(typeStart, expirySeparator)
        } else {
            text.substring(typeStart).trim()
        }

        var customWord = ""
        var expiresAtMillis: Long? = null
        if (expirySeparator >= 0) {
            val customSeparator = text.indexOf('¥', expirySeparator + 1)
            require(customSeparator > expirySeparator) { "Import code expiry metadata is invalid" }
            val expiryPrefix = text.substring(expirySeparator + 1, customSeparator)
            val prefixValue = expiryPrefix.toLongOrNull()
                ?: throw IllegalArgumentException("Import code expiry metadata is invalid")
            if (prefixValue > 0) {
                expiresAtMillis = Math.multiplyExact(prefixValue, MILLIS_PREFIX_SCALE)
            }
            val suffixEnd = text.indexOf('^', customSeparator + 1)
                .let { if (it >= 0) it else text.length }
            customWord = text.substring(customSeparator + 1, suffixEnd)
        }

        Payload(url, type, customWord, expiresAtMillis)
    }

    private fun validatedHttpsUrl(value: String): String? {
        if (value.any { it.isWhitespace() || it.isISOControl() }) return null
        val parsed = value.toHttpUrlOrNull() ?: return null
        if (!parsed.isHttps || parsed.username.isNotEmpty() || parsed.password.isNotEmpty()) return null
        return parsed.toString()
    }

    val supportedTypes = setOf(
        BOOK_SOURCE,
        RSS_SOURCE,
        DICT_RULE,
        REPLACE_RULE,
        TOC_RULE,
        TTS_RULE
    )
}
