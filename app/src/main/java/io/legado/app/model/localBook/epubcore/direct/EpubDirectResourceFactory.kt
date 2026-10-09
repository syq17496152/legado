package io.legado.app.model.localBook.epubcore.direct

import io.legado.app.model.localBook.epubcore.archive.EpubArchive
import java.io.FilterInputStream
import java.io.InputStream
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.net.URLConnection
import java.util.Locale

object EpubDirectResourceFactory {

    fun open(
        archive: EpubArchive,
        path: String,
        declaredMimeType: String?,
        rangeHeader: String?,
        cachedBytes: ByteArray? = null,
        cachedFile: File? = null,
        openBody: Boolean = true
    ): EpubDirectResource? {
        if (!archive.exists(path)) return null
        val size = cachedBytes?.size?.toLong() ?: cachedFile?.length() ?: archive.entrySize(path)
        val mimeType = resolvedMimeType(path, declaredMimeType)
        val encoding = responseEncoding(path, mimeType)
        val range = size?.let { EpubDirectRangePolicy.parse(rangeHeader, it) }
        if (!rangeHeader.isNullOrBlank() && size != null && range == null) {
            return EpubDirectResource(
                mimeType = mimeType,
                encoding = encoding,
                statusCode = 416,
                reasonPhrase = "Range Not Satisfiable",
                headers = failureResponseHeaders() + mapOf(
                    "Content-Range" to "bytes */$size",
                    "Accept-Ranges" to "bytes"
                ),
                stream = emptyInputStream()
            )
        }
        fun openSource(): InputStream {
            return cachedBytes?.let(::ByteArrayInputStream)
                ?: cachedFile?.takeIf(File::isFile)?.let(::FileInputStream)
                ?: archive.openStream(path)
        }
        if (range != null) {
            val headers = responseHeaders() + mapOf(
                "Accept-Ranges" to "bytes",
                "Content-Length" to range.length.toString(),
                "Content-Range" to "bytes ${range.start}-${range.endInclusive}/$size"
            )
            if (!openBody) {
                return EpubDirectResource(
                    mimeType = mimeType,
                    encoding = encoding,
                    statusCode = 206,
                    reasonPhrase = "Partial Content",
                    headers = headers,
                    stream = emptyInputStream()
                )
            }
            val source = openSource()
            return runCatching {
                if (source is FileInputStream) {
                    source.channel.position(range.start)
                } else {
                    source.skipFully(range.start)
                }
                EpubDirectResource(
                    mimeType = mimeType,
                    encoding = encoding,
                    statusCode = 206,
                    reasonPhrase = "Partial Content",
                    headers = headers,
                    stream = BoundedInputStream(source, range.length)
                )
            }.getOrElse {
                source.close()
                throw it
            }
        }
        val source = if (openBody) {
            openSource()
        } else {
            emptyInputStream()
        }
        val headers = LinkedHashMap(responseHeaders())
        size?.let {
            headers["Content-Length"] = it.toString()
            headers["Accept-Ranges"] = "bytes"
        }
        return EpubDirectResource(
            mimeType = mimeType,
            encoding = encoding,
            statusCode = 200,
            reasonPhrase = "OK",
            headers = headers,
            stream = source
        )
    }

    private fun responseHeaders() = mapOf(
        "Cache-Control" to "public, max-age=31536000, immutable",
        "Access-Control-Allow-Origin" to "*"
    )

    private fun failureResponseHeaders() = mapOf(
        "Cache-Control" to "no-store, max-age=0",
        "Pragma" to "no-cache",
        "Access-Control-Allow-Origin" to "*"
    )

    private fun responseEncoding(path: String, mimeType: String): String? {
        // Let Chromium honor a stylesheet BOM or @charset declaration.
        if (path.substringAfterLast('.', "").equals("css", true)) return null
        return if (isText(mimeType)) Charsets.UTF_8.name() else null
    }

    private fun isText(mimeType: String): Boolean {
        return mimeType.startsWith("text/") || mimeType in setOf(
            "application/xhtml+xml",
            "application/xml",
            "application/javascript",
            "application/json",
            "image/svg+xml"
        )
    }

    internal fun resolvedMimeType(path: String, declaredMimeType: String?): String {
        val known = when (path.substringAfterLast('.', "").lowercase(Locale.ROOT)) {
            "htm", "html" -> "text/html"
            "xhtml", "xht" -> "application/xhtml+xml"
            "css" -> "text/css"
            "js" -> "application/javascript"
            "svg", "svgz" -> "image/svg+xml"
            "gif" -> "image/gif"
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "webp" -> "image/webp"
            "avif" -> "image/avif"
            "woff" -> "font/woff"
            "woff2" -> "font/woff2"
            "ttf" -> "font/ttf"
            "otf" -> "font/otf"
            "eot" -> "application/vnd.ms-fontobject"
            "aac" -> "audio/aac"
            "mp3" -> "audio/mpeg"
            "m4a" -> "audio/mp4"
            "mp4", "m4v" -> "video/mp4"
            "mov" -> "video/mp4"
            "ogg", "oga" -> "audio/ogg"
            "ogv" -> "video/ogg"
            "wav" -> "audio/wav"
            "flac" -> "audio/flac"
            "webm" -> "video/webm"
            "vtt" -> "text/vtt"
            "smil" -> "application/smil+xml"
            "ncx" -> "application/x-dtbncx+xml"
            "xml", "opf" -> "application/xml"
            else -> null
        }
        if (known != null) return known
        val declared = declaredMimeType
            ?.substringBefore(';')
            ?.trim()
            ?.takeIf { it.isNotBlank() }
        if (declared != null && declared.lowercase(Locale.ROOT) !in GENERIC_MIME_TYPES) {
            return declared
        }
        return URLConnection.guessContentTypeFromName(path)
            ?.takeIf { it.isNotBlank() }
            ?: declared
            ?: "application/octet-stream"
    }

    private val GENERIC_MIME_TYPES = setOf(
        "application/octet-stream",
        "binary/octet-stream",
        "application/unknown"
    )

    private class BoundedInputStream(source: InputStream, private var remaining: Long) :
        FilterInputStream(source) {

        override fun read(): Int {
            if (remaining <= 0L) return -1
            val value = super.read()
            if (value >= 0) remaining--
            return value
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (remaining <= 0L) return -1
            val count = super.read(buffer, offset, minOf(length.toLong(), remaining).toInt())
            if (count > 0) remaining -= count
            return count
        }

        override fun skip(byteCount: Long): Long {
            val skipped = super.skip(minOf(byteCount, remaining))
            remaining -= skipped
            return skipped
        }
    }

    private fun InputStream.skipFully(byteCount: Long) {
        var remaining = byteCount
        while (remaining > 0L) {
            val skipped = skip(remaining)
            if (skipped > 0L) {
                remaining -= skipped
            } else if (read() >= 0) {
                remaining--
            } else {
                error("Unexpected end of EPUB resource")
            }
        }
    }

    private fun emptyInputStream(): InputStream {
        return object : InputStream() {
            override fun read(): Int = -1
        }
    }
}
