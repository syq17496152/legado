package io.legado.app.model.localBook.epubcore.direct

import io.legado.app.help.ImageSourceOptions
import io.legado.app.help.ParsedImageSource
import okio.ByteString.Companion.decodeBase64
import okio.ByteString.Companion.toByteString
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.URLEncoder
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Locale

/** A completed resource. Opening its body never starts a network or script request. */
internal class TextReaderImageResource(
    val mimeType: String,
    val length: Long,
    val retainedBytes: Int = 0,
    val scale: Float = 1f,
    val isBubble: Boolean = false,
    private val openStream: () -> InputStream
) {
    fun response(headOnly: Boolean = false) = EpubDirectResource(
        mimeType, null, 200, "OK",
        mapOf("Cache-Control" to "no-store", "Content-Length" to length.toString()),
        if (headOnly) ByteArrayInputStream(ByteArray(0)) else openStream()
    )

    fun contentHash(): String {
        val digest = MessageDigest.getInstance("SHA-256")
        openStream().use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
    }

    /** Export only an already completed small body; never trust a changed file's advertised size. */
    fun dataUri(maxBytes: Int): String? {
        require(maxBytes in 1..MAX_BYTES)
        if (length !in 1..maxBytes.toLong()) return null
        val output = ByteArrayOutputStream(length.toInt())
        openStream().use { input ->
            val buffer = ByteArray(minOf(8192, maxBytes))
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (read > maxBytes - output.size()) return null
                output.write(buffer, 0, read)
            }
        }
        if (output.size().toLong() != length) return null
        return "data:$mimeType;base64," + output.toByteArray().toByteString().base64()
    }

    /** Publish complete files only; a concurrent reader never sees a partial write. */
    fun persist(file: File, replaceExisting: Boolean = false): TextReaderImageResource {
        if (replaceExisting || !file.isFile || file.length() != length) {
            val parent = requireNotNull(file.parentFile)
            check(parent.isDirectory || parent.mkdirs()) { "无法创建图片缓存目录" }
            val temporary = File.createTempFile("text-image-", ".tmp", parent)
            try {
                openStream().use { input -> temporary.outputStream().use { input.copyTo(it) } }
                check(temporary.length() == length) { "图片缓存写入不完整" }
                try {
                    Files.move(temporary.toPath(), file.toPath(),
                        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                } catch (_: AtomicMoveNotSupportedException) {
                    // NIO is desugared on API 21+. Same-directory replacement is
                    // still a complete file if the filesystem lacks atomic moves.
                    Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
                }
            } finally { temporary.delete() }
        }
        return TextReaderImageResource(mimeType, length, scale = scale, isBubble = isBubble) { file.inputStream() }
    }

    companion object {
        const val MAX_BYTES = 32 * 1024 * 1024

        fun bytes(bytes: ByteArray, scale: Float = 1f, isBubble: Boolean = false): TextReaderImageResource {
            require(bytes.isNotEmpty() && bytes.size <= MAX_BYTES) { "图片数据过大或为空" }
            val mime = mimeType(bytes.copyOfRange(0, minOf(bytes.size, 8192)))
            return TextReaderImageResource(mime, bytes.size.toLong(), bytes.size, scale, isBubble) {
                ByteArrayInputStream(bytes)
            }
        }

        fun file(file: File): TextReaderImageResource {
            val length = file.length()
            require(file.isFile && length in 1..MAX_BYTES.toLong()) { "图片文件过大或不存在" }
            val prefix = file.inputStream().use { input ->
                val buffer = ByteArray(minOf(length, 8192).toInt())
                var size = 0
                while (size < buffer.size) {
                    val read = input.read(buffer, size, buffer.size - size)
                    if (read < 0) break
                    size += read
                }
                buffer.copyOf(size)
            }
            // Legacy image caches may call an SVG .jpg. WebView needs the actual MIME.
            return TextReaderImageResource(mimeType(prefix), length) { file.inputStream() }
        }

        private fun mimeType(prefix: ByteArray): String {
            fun at(offset: Int, value: String) = prefix.size >= offset + value.length &&
                value.indices.all { (prefix[offset + it].toInt() and 255) == value[it].code }
            return when {
                at(0, "\u0089PNG\r\n\u001a\n") -> "image/png"
                prefix.size >= 3 && prefix[0] == 0xff.toByte() &&
                    prefix[1] == 0xd8.toByte() && prefix[2] == 0xff.toByte() -> "image/jpeg"
                at(0, "GIF87a") || at(0, "GIF89a") -> "image/gif"
                at(0, "RIFF") && at(8, "WEBP") -> "image/webp"
                at(0, "BM") -> "image/bmp"
                at(4, "ftypavif") || at(4, "ftypavis") -> "image/avif"
                at(0, "\u0000\u0000\u0001\u0000") -> "image/x-icon"
                else -> {
                    val xml = prefix.toString(Charsets.UTF_8).removePrefix("\ufeff").trimStart()
                    val svg = Regex("<(?:[\\w-]+:)?svg(?:\\s|>)", RegexOption.IGNORE_CASE).find(xml)
                    if (svg != null && !xml.take(svg.range.first).contains("<html", true)) {
                        "image/svg+xml"
                    } else throw IOException("无法识别图片数据")
                }
            }
        }
    }
}

internal object TextReaderImageSource {
    // Android's ICU rejects unescaped closing braces even though desktop Java accepts them.
    private val scriptPattern = Regex("<js>[\\s\\S]*?</js>|@js:[\\s\\S]*|\\{\\{[\\s\\S]*?\\}\\}", RegexOption.IGNORE_CASE)
    private val presentation = setOf("click", "onclick", "pclick", "style", "width", "height")

    fun needsScript(source: ParsedImageSource): Boolean = source.option("js") != null ||
        (!source.source.startsWith("data:image/", true) && scriptPattern.containsMatchIn(source.source)) ||
        source.options.any { (key, value) -> key.lowercase(Locale.ROOT) !in presentation && scriptPattern.containsMatchIn(value) }

    fun browserDataImage(raw: String): String? = ImageSourceOptions.parse(raw)?.let {
        it.source.takeIf { value -> value.startsWith("data:image/", true) && !needsScript(it) }
    }

    fun isBubble(raw: String): Boolean = raw.startsWith("dp:", true) ||
        raw.startsWith("bubble://paragraph", true)

    fun isLocal(raw: String): Boolean = raw.startsWith("file:", true) ||
        raw.startsWith("content:", true) || raw.startsWith("ai-image://", true)

    fun bubbleSource(parsed: ParsedImageSource): String? {
        if (parsed.source.startsWith("bubble://paragraph", true)) {
            return "bubble://paragraph" + parsed.source.substring("bubble://paragraph".length)
        }
        if (!parsed.source.startsWith("dp:", true)) return null
        fun encoded(value: String) = URLEncoder.encode(value, "UTF-8").replace("+", "%20")
        val text = listOf("displayText", "num", "\$num", "\${num}", "{{num}}", "count", "text", "label")
            .firstNotNullOfOrNull(parsed::option) ?: parsed.source.substring(3).trim()
        val status = parsed.option("status") ?: "normal"
        val color = parsed.option("displayColor") ?: parsed.option("color")
        return "bubble://paragraph?displayText=${encoded(text)}&num=${encoded(text)}&status=${encoded(status)}" +
            (color?.let { "&displayColor=${encoded(it)}" } ?: "")
    }

    fun dataImage(raw: String): TextReaderImageResource? {
        if (!raw.startsWith("data:image/", true)) return null
        val comma = raw.indexOf(',')
        require(comma > 0) { "图片 data URI 不完整" }
        val metadata = raw.substring(0, comma)
        val payload = raw.substring(comma + 1)
        require(payload.length.toLong() <= TextReaderImageResource.MAX_BYTES * 4L) { "图片数据过大" }
        val decoded = percentBytes(payload)
        val bytes = if (metadata.contains(";base64", true)) {
            decoded.toString(Charsets.US_ASCII).decodeBase64()?.toByteArray()
                ?: throw IOException("图片 base64 数据无效")
        } else decoded
        return TextReaderImageResource.bytes(bytes)
    }

    private fun percentBytes(value: String): ByteArray {
        val output = ByteArrayOutputStream(minOf(value.length, 8192))
        var index = 0
        while (index < value.length) {
            if (value[index] == '%' && index + 2 < value.length) {
                val hi = value[index + 1].digitToIntOrNull(16)
                val lo = value[index + 2].digitToIntOrNull(16)
                if (hi != null && lo != null) {
                    output.write((hi shl 4) or lo)
                    index += 3
                    continue
                }
            }
            val end = value.indexOf('%', index + 1).takeIf { it >= 0 } ?: value.length
            output.write(value.substring(index, end).toByteArray(Charsets.UTF_8))
            index = end
        }
        return output.toByteArray()
    }
}

/** Shares the same routing for native requests and the lifecycle regression tests. */
internal class TextReaderImageResolver(
    private val analyze: suspend (String) -> Request,
    private val local: suspend (String) -> TextReaderImageResource,
    private val bubble: suspend (String) -> TextReaderImageResource,
    private val containerImage: (suspend (String) -> TextReaderImageResource)? = null,
    private val managedBubble: ((String) -> String?)? = null,
    private val isLocalFile: (String) -> Boolean = { false }
) {
    class Request(val url: String, val fetch: suspend () -> TextReaderImageResource)

    suspend fun load(raw: String): TextReaderImageResource {
        val parsed = requireNotNull(ImageSourceOptions.parse(raw))
        if (!TextReaderImageSource.needsScript(parsed)) {
            special(parsed)?.let { return it }
            containerImage?.let { return it(parsed.source) }
        }
        val request = analyze(raw)
        val resolved = requireNotNull(ImageSourceOptions.parse(request.url))
        special(resolved)?.let { return it }
        containerImage?.let { return it(resolved.source) }
        return request.fetch()
    }

    private suspend fun special(parsed: ParsedImageSource): TextReaderImageResource? {
        TextReaderImageSource.bubbleSource(parsed)?.let { return bubble(it) }
        managedBubble?.invoke(parsed.source)?.let { return bubble(it) }
        TextReaderImageSource.dataImage(parsed.source)?.let { return it }
        if (TextReaderImageSource.isLocal(parsed.source) || isLocalFile(parsed.source)) return local(parsed.source)
        return null
    }
}
