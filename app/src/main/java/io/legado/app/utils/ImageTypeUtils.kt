package io.legado.app.utils

import java.io.File

object ImageTypeUtils {

    fun isGif(file: File?): Boolean {
        if (file == null || !file.isFile) return false
        return runCatching {
            file.inputStream().use { input ->
                val header = ByteArray(6)
                if (input.read(header) != header.size) return@use false
                header.contentEquals(gif87Header) || header.contentEquals(gif89Header)
            }
        }.getOrDefault(false)
    }

    fun isWebp(file: File?): Boolean {
        if (file == null || !file.isFile) return false
        return runCatching {
            file.inputStream().use { input ->
                val header = ByteArray(12)
                if (input.read(header) != header.size) return@use false
                header.startsWith(riffHeader) && header.sliceArray(8 until 12).contentEquals(webpHeader)
            }
        }.getOrDefault(false)
    }

    fun isAnimatedWebp(file: File?): Boolean {
        if (file == null || !file.isFile) return false
        return runCatching {
            file.inputStream().buffered().use { input ->
                val header = ByteArray(12)
                if (input.read(header) != header.size) return@use false
                if (!header.startsWith(riffHeader) || !header.sliceArray(8 until 12).contentEquals(webpHeader)) {
                    return@use false
                }
                val chunkHeader = ByteArray(8)
                var scanned = 12L
                val maxScan = minOf(file.length(), 512 * 1024L)
                while (scanned + chunkHeader.size <= maxScan && input.read(chunkHeader) == chunkHeader.size) {
                    scanned += chunkHeader.size
                    val chunkType = chunkHeader.sliceArray(0 until 4)
                    val chunkSize = chunkHeader.readLittleEndianInt(4).coerceAtLeast(0)
                    when {
                        chunkType.contentEquals(animHeader) -> return@use true
                        chunkType.contentEquals(vp8xHeader) -> {
                            val flags = input.read()
                            if (flags < 0) return@use false
                            scanned += 1
                            if ((flags and 0x02) != 0) return@use true
                            val remaining = chunkSize - 1
                            if (remaining > 0) {
                                input.skipFully(remaining + chunkSize.paddingByte())
                                scanned += remaining + chunkSize.paddingByte()
                            }
                        }
                        else -> {
                            val skip = chunkSize + chunkSize.paddingByte()
                            input.skipFully(skip)
                            scanned += skip
                        }
                    }
                }
                false
            }
        }.getOrDefault(false)
    }

    fun isAnimatedImage(file: File?): Boolean {
        return isGif(file) || isAnimatedWebp(file)
    }

    fun preferredRasterExtension(file: File?, fallback: String = "jpg"): String {
        return when {
            isGif(file) -> "gif"
            isWebp(file) -> "webp"
            file?.extension?.isNotBlank() == true -> file.extension
            else -> fallback
        }
    }

    private fun ByteArray.startsWith(prefix: ByteArray): Boolean {
        if (size < prefix.size) return false
        return prefix.indices.all { this[it] == prefix[it] }
    }

    private fun ByteArray.readLittleEndianInt(offset: Int): Int {
        return (this[offset].toInt() and 0xFF) or
            ((this[offset + 1].toInt() and 0xFF) shl 8) or
            ((this[offset + 2].toInt() and 0xFF) shl 16) or
            ((this[offset + 3].toInt() and 0xFF) shl 24)
    }

    private fun Int.paddingByte(): Int = if (this % 2 == 0) 0 else 1

    private fun java.io.InputStream.skipFully(byteCount: Int) {
        var remaining = byteCount.toLong()
        while (remaining > 0) {
            val skipped = skip(remaining)
            if (skipped <= 0L) {
                if (read() == -1) return
                remaining--
            } else {
                remaining -= skipped
            }
        }
    }

    private val gif87Header = byteArrayOf(0x47, 0x49, 0x46, 0x38, 0x37, 0x61)
    private val gif89Header = byteArrayOf(0x47, 0x49, 0x46, 0x38, 0x39, 0x61)
    private val riffHeader = byteArrayOf(0x52, 0x49, 0x46, 0x46)
    private val webpHeader = byteArrayOf(0x57, 0x45, 0x42, 0x50)
    private val vp8xHeader = byteArrayOf(0x56, 0x50, 0x38, 0x58)
    private val animHeader = byteArrayOf(0x41, 0x4E, 0x49, 0x4D)
}