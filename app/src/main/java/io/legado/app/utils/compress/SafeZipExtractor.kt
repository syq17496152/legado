package io.legado.app.utils.compress

import io.legado.app.utils.isSameOrSubFileOf
import io.legado.app.utils.readBytesLimited
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.Locale
import java.util.zip.ZipFile

data class SafeZipLimits(
    val maxEntries: Int,
    val maxEntryBytes: Long,
    val maxTotalBytes: Long,
    val maxCompressionRatio: Long
) {
    init {
        require(maxEntries > 0)
        require(maxEntryBytes > 0L)
        require(maxTotalBytes >= maxEntryBytes)
        require(maxCompressionRatio > 0L)
    }
}

object SafeZipExtractor {

    fun extract(
        zipFile: File,
        destination: File,
        limits: SafeZipLimits,
        filter: ((String) -> Boolean)? = null
    ): List<File> {
        require(zipFile.isFile) { "ZIP file does not exist" }
        check(destination.mkdirs() || destination.isDirectory) { "Unable to create ZIP destination" }
        val root = destination.canonicalFile
        val extracted = arrayListOf<File>()
        val paths = hashSetOf<String>()
        var entryCount = 0
        var totalBytes = 0L
        ZipFile(zipFile).use { zip ->
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                if (++entryCount > limits.maxEntries) throw IOException("ZIP contains too many entries")
                val relativePath = validateEntryName(entry.name)
                if (!paths.add(relativePath.lowercase(Locale.ROOT))) {
                    throw IOException("ZIP contains duplicate paths")
                }
                val target = File(root, relativePath).canonicalFile
                if (!target.isSameOrSubFileOf(root)) throw IOException("ZIP entry escapes destination")
                if (entry.isDirectory) {
                    check(target.mkdirs() || target.isDirectory) { "Unable to create ZIP directory" }
                    continue
                }
                if (filter != null && !filter(entry.name)) continue
                val declaredSize = entry.size
                if (declaredSize > limits.maxEntryBytes) throw IOException("ZIP entry is too large")
                target.parentFile?.let { parent ->
                    check(parent.mkdirs() || parent.isDirectory) { "Unable to create ZIP directory" }
                }
                var entryBytes = 0L
                zip.getInputStream(entry).use { input ->
                    FileOutputStream(target).use { output ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            if (count == 0) continue
                            entryBytes += count.toLong()
                            totalBytes += count.toLong()
                            if (entryBytes > limits.maxEntryBytes) throw IOException("ZIP entry is too large")
                            if (totalBytes > limits.maxTotalBytes) throw IOException("ZIP expands beyond allowed size")
                            output.write(buffer, 0, count)
                        }
                    }
                }
                val compressedSize = entry.compressedSize
                if (entryBytes > 0L && compressedSize >= 0L &&
                    entryBytes / compressedSize.coerceAtLeast(1L) > limits.maxCompressionRatio
                ) {
                    throw IOException("ZIP entry has an unsafe compression ratio")
                }
                extracted += target
            }
        }
        return extracted
    }

    private fun validateEntryName(rawName: String): String {
        if (rawName.isBlank() || '\u0000' in rawName || '\\' in rawName) {
            throw IOException("ZIP contains an invalid path")
        }
        if (rawName.startsWith('/') || DRIVE_PREFIX.matches(rawName)) {
            throw IOException("ZIP contains an absolute path")
        }
        val isDirectory = rawName.endsWith('/')
        val normalized = rawName.trimEnd('/')
        val segments = normalized.split('/')
        if (segments.any { it.isEmpty() || it == "." || it == ".." }) {
            throw IOException("ZIP contains an unsafe path")
        }
        return if (isDirectory) "$normalized/" else normalized
    }

    private val DRIVE_PREFIX = Regex("^[A-Za-z]:.*")
}

fun File.readTextLimited(maxBytes: Long): String {
    require(isFile) { "File does not exist" }
    return inputStream().use { input ->
        String(input.readBytesLimited(maxBytes), Charsets.UTF_8)
    }
}