package io.legado.app.model.localBook.epubcore.direct

import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * 磁盘级资源缓存：把 EPUB 内大资源（字体/图片/媒体）按需落盘，
 * 以「路径 + 声明字节数」为键，单后台线程串行抽取，容量超限按 LRU 回收。
 *
 * 迁移自 archive v15（纯算法，未改）。写入采用「临时文件 → rename」原子落盘，杜绝半成品。
 */
internal class EpubDirectDiskResourceCache(
    private val directory: File,
    private val maxEntryBytes: Long = 256L * 1024L * 1024L,
    private val maxTotalBytes: Long = 512L * 1024L * 1024L
) : Closeable {

    private val inFlight = HashSet<String>()
    private val executor = ThreadPoolExecutor(
        1,
        1,
        0L,
        TimeUnit.MILLISECONDS,
        ArrayBlockingQueue(2),
        { runnable -> Thread(runnable, "epub-direct-resource").apply { isDaemon = true } },
        ThreadPoolExecutor.AbortPolicy()
    )
    @Volatile
    private var closed = false

    /** 命中且大小一致才返回（顺带刷新 LRU 时间戳）。 */
    fun get(path: String, size: Long?): File? {
        if (!isCacheable(size)) return null
        val file = cacheFile(path, size!!)
        if (!file.isFile || file.length() != size) {
            if (file.exists()) runCatching { file.delete() }
            return null
        }
        runCatching { file.setLastModified(System.currentTimeMillis()) }
        return file
    }

    /** 异步落盘；已在缓存或已在途则直接返回。 */
    fun prepare(path: String, size: Long?, loader: () -> InputStream) {
        if (closed || !isCacheable(size) || get(path, size) != null) return
        val key = cacheKey(path, size!!)
        synchronized(this) {
            if (closed || !inFlight.add(key)) return
        }
        try {
            executor.execute {
                try {
                    extract(path, size, loader)
                } finally {
                    synchronized(this) { inFlight.remove(key) }
                }
            }
        } catch (_: RejectedExecutionException) {
            synchronized(this) { inFlight.remove(key) }
        }
    }

    override fun close() {
        closeWhenDrained {}
    }

    /** 请求关闭；后台抽取线程退出后回调 [onDrained]。 */
    fun closeWhenDrained(onDrained: () -> Unit) {
        val startDrain = synchronized(this) {
            if (closed) false else {
                closed = true
                inFlight.clear()
                true
            }
        }
        if (!startDrain) return
        executor.shutdownNow()
        if (executor.isTerminated) {
            onDrained()
            return
        }
        Thread({
            var interrupted = false
            while (!executor.isTerminated) {
                try {
                    executor.awaitTermination(1, TimeUnit.DAYS)
                } catch (_: InterruptedException) {
                    interrupted = true
                }
            }
            if (interrupted) Thread.currentThread().interrupt()
            onDrained()
        }, "epub-direct-resource-close").apply {
            isDaemon = true
            start()
        }
    }

    private fun extract(path: String, size: Long, loader: () -> InputStream) {
        if (closed || get(path, size) != null) return
        directory.mkdirs()
        val target = cacheFile(path, size)
        val temp = File(directory, ".${target.name}.${System.nanoTime()}.tmp")
        try {
            loader().use { input ->
                FileOutputStream(temp).use { output ->
                    copyExactly(input, output, size)
                    output.fd.sync()
                }
            }
            check(temp.length() == size) { "EPUB resource cache size mismatch" }
            if (closed) return
            if (target.exists() && target.length() != size) target.delete()
            if (!target.exists()) {
                if (!reserveCapacity(size)) return
                if (!temp.renameTo(target)) {
                    FileOutputStream(target).use { output ->
                        temp.inputStream().use { input -> input.copyTo(output) }
                        output.fd.sync()
                    }
                }
            }
            runCatching { target.setLastModified(System.currentTimeMillis()) }
        } catch (_: Throwable) {
            runCatching { target.takeIf { it.length() != size }?.delete() }
        } finally {
            runCatching { temp.delete() }
        }
    }

    private fun copyExactly(input: InputStream, output: FileOutputStream, size: Long) {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var remaining = size
        while (remaining > 0L) {
            if (Thread.currentThread().isInterrupted) throw InterruptedException()
            val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
            if (read < 0) error("Unexpected end of EPUB resource")
            output.write(buffer, 0, read)
            remaining -= read
        }
        if (input.read() >= 0) error("EPUB resource is larger than its declared size")
    }

    private fun reserveCapacity(requiredBytes: Long): Boolean {
        val files = directory.listFiles { file -> file.isFile && !file.name.endsWith(".tmp") }
            ?.sortedByDescending(File::lastModified)
            .orEmpty()
        var total = files.sumOf(File::length)
        files.asReversed().forEach { file ->
            if (total + requiredBytes <= maxTotalBytes) return true
            val length = file.length()
            if (file.delete()) total -= length
        }
        return total + requiredBytes <= maxTotalBytes
    }

    private fun isCacheable(size: Long?): Boolean {
        return size != null && size > 0L && size <= maxEntryBytes && size <= maxTotalBytes
    }

    private fun cacheFile(path: String, size: Long): File {
        return File(directory, "${cacheKey(path, size)}.bin")
    }

    private fun cacheKey(path: String, size: Long): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("$path|$size".toByteArray(Charsets.UTF_8))
        return digest.take(16).joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}