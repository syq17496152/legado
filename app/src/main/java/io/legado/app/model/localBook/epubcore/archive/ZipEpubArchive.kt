package io.legado.app.model.localBook.epubcore.archive

import io.legado.app.utils.readBytesLimited
import java.io.File
import java.io.IOException
import java.util.zip.ZipFile

class ZipEpubArchive(file: File) : EpubArchive {

    private val zipFile = ZipFile(file)
    private val entries: Map<String, String> = zipFile.entries().asSequence()
        .filterNot { it.isDirectory }
        .associate { EpubPath.normalize(it.name) to it.name }

    override fun exists(path: String): Boolean = entries.containsKey(EpubPath.normalize(path))

    override fun list(): List<String> = entries.keys.toList()

    override fun readBytes(path: String, maxBytes: Long): ByteArray {
        val normalized = EpubPath.normalize(path)
        val entryName = entries[normalized] ?: error("EPUB entry not found: $path")
        val entry = zipFile.getEntry(entryName) ?: error("EPUB entry not found: $path")
        if (entry.size > maxBytes) {
            throw IOException("EPUB entry is too large: $path (${entry.size} bytes)")
        }
        return zipFile.getInputStream(entry).use { it.readBytesLimited(maxBytes) }
    }

    override fun close() {
        zipFile.close()
    }
}
