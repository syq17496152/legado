package io.legado.app.model.localBook.epubcore.direct

import java.io.Closeable
import java.lang.ref.WeakReference
import java.security.MessageDigest
import java.util.Collections

/**
 * Only registered chapter images may start native work. Paths contain opaque
 * identities rather than source URLs, SVG payloads, or executable source rules.
 *
 * Keep the returned map in the prepared chapter: it owns the request metadata.
 * The session registry deliberately holds only weak references to that metadata.
 */
internal class TextReaderImageRegistry : Closeable {
    private val entries = HashMap<String, WeakReference<TextReaderImageRequest>>()

    @Volatile
    private var closed = false

    fun register(requests: Collection<TextReaderImageRequest>): Map<String, TextReaderImageRequest> {
        check(!closed) { "Image registry is closed" }
        // Hashing a chapter with large SVG/script sources must not block WebView
        // lookups or session close. Validate the complete batch before mutation.
        val candidates = LinkedHashMap<String, TextReaderImageRequest>()
        for (request in requests) {
            val path = pathFor(request)
            val previous = candidates.put(path, request)
            check(previous == null || previous == request) { "Image resource identity collision" }
        }
        return synchronized(this) {
            check(!closed) { "Image registry is closed" }
            // Clean dead chapters once per batch, never once per image.
            val iterator = entries.iterator()
            while (iterator.hasNext()) {
                if (iterator.next().value.get() == null) iterator.remove()
            }
            val registered = LinkedHashMap<String, TextReaderImageRequest>(candidates.size)
            for ((path, request) in candidates) {
                val existing = entries[path]?.get()
                check(existing == null || existing == request) { "Image resource identity collision" }
                // Reuse a live equivalent request so either chapter's returned
                // map keeps it alive if another preparation is discarded.
                registered[path] = existing ?: request
            }
            for ((path, request) in registered) {
                entries[path] = WeakReference(request)
            }
            Collections.unmodifiableMap(registered)
        }
    }

    @Synchronized
    fun lookup(path: String): TextReaderImageRequest? {
        if (closed) return null
        val reference = entries[path] ?: return null
        return reference.get().also { if (it == null) entries.remove(path) }
    }

    @Synchronized
    override fun close() {
        closed = true
        entries.clear()
    }

    private fun pathFor(request: TextReaderImageRequest): String {
        require(request.chapterIndex >= 0) { "Invalid image chapter index" }
        require(segment.matches(request.renderRevision)) { "Invalid image render revision" }
        require(segment.matches(request.image.id)) { "Invalid image identifier" }
        return "text-image/${request.chapterIndex}/${request.renderRevision}/" +
            "${request.image.id}-${fingerprint(request)}"
    }

    private fun fingerprint(request: TextReaderImageRequest): String {
        val digest = MessageDigest.getInstance("SHA-256")
        fun field(value: String?) {
            if (value == null) {
                digest.update(byteArrayOf(-1, -1, -1, -1))
            } else {
                val bytes = value.toByteArray(Charsets.UTF_8)
                // Length prefixes distinguish null, empty and embedded separators.
                digest.update(byteArrayOf(
                    (bytes.size ushr 24).toByte(),
                    (bytes.size ushr 16).toByte(),
                    (bytes.size ushr 8).toByte(),
                    bytes.size.toByte()
                ))
                digest.update(bytes)
            }
        }
        field(request.chapterUrl)
        field(request.managedBubble.toString())
        with(request.image) {
            field(id)
            field(source)
            field(renderSource)
            field(click)
            field(inline.toString())
            field(width)
            field(height)
            field(alignment)
            field(sourceStyle)
        }
        val bytes = digest.digest()
        return buildString(16) {
            for (index in 0 until 8) {
                val value = bytes[index].toInt() and 0xff
                append(hex[value ushr 4])
                append(hex[value and 0x0f])
            }
        }
    }

    private companion object {
        val segment = Regex("[A-Za-z0-9_-]{1,64}")
        const val hex = "0123456789abcdef"
    }
}
