package io.legado.app.model.localBook.epubcore.archive

import java.io.Closeable

interface EpubArchive : Closeable {
    fun exists(path: String): Boolean
    fun list(): List<String>
    fun readBytes(path: String, maxBytes: Long = DEFAULT_MAX_ENTRY_BYTES): ByteArray

    fun readText(path: String): String = readBytes(path).toString(Charsets.UTF_8)

    companion object {
        const val DEFAULT_MAX_ENTRY_BYTES = 32L * 1024L * 1024L
    }
}
