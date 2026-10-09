package io.legado.app.model.localBook.epubcore.direct

import java.util.concurrent.CountDownLatch

/**
 * 内存级资源缓存（字节数组 LRU + 并发去重加载）。
 * 同一 path 的并发请求合并为一次 [loader]（后到者等待前者结果）。
 *
 * 迁移自 archive v15（纯算法，未改）。
 */
internal class EpubDirectResourceCache(
    private val maxEntryBytes: Long = 8L * 1024L * 1024L,
    private val maxTotalBytes: Long = 18L * 1024L * 1024L
) {
    private val entries = LinkedHashMap<String, ByteArray>(12, 0.75f, true)
    private val inFlight = HashMap<String, PendingLoad>()
    private var totalBytes = 0L

    fun getOrLoad(path: String, size: Long?, loader: () -> ByteArray): ByteArray? {
        if (size == null || size < 0L || size > maxEntryBytes) return null
        val pending: PendingLoad
        val ownsLoad: Boolean
        synchronized(this) {
            entries[path]?.let { return it }
            val existing = inFlight[path]
            if (existing != null) {
                pending = existing
                ownsLoad = false
            } else {
                pending = PendingLoad()
                inFlight[path] = pending
                ownsLoad = true
            }
        }
        if (!ownsLoad) return pending.await()

        return try {
            val loaded = loader().takeIf { it.size.toLong() <= maxEntryBytes }
            val result = synchronized(this) {
                entries[path] ?: loaded?.also { putLocked(path, it) }
            }
            pending.complete(result)
            result
        } catch (throwable: Throwable) {
            pending.fail(throwable)
            throw throwable
        } finally {
            synchronized(this) {
                if (inFlight[path] === pending) inFlight.remove(path)
            }
        }
    }

    @Synchronized
    fun clear() {
        entries.clear()
        totalBytes = 0L
    }

    private fun putLocked(path: String, bytes: ByteArray) {
        while (entries.isNotEmpty() && totalBytes + bytes.size > maxTotalBytes) {
            val eldest = entries.entries.first()
            totalBytes -= eldest.value.size
            entries.remove(eldest.key)
        }
        entries[path] = bytes
        totalBytes += bytes.size
    }

    private class PendingLoad {
        private val latch = CountDownLatch(1)
        @Volatile
        private var result: ByteArray? = null
        @Volatile
        private var failure: Throwable? = null

        fun complete(value: ByteArray?) {
            result = value
            latch.countDown()
        }

        fun fail(throwable: Throwable) {
            failure = throwable
            latch.countDown()
        }

        fun await(): ByteArray? {
            try {
                latch.await()
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
                throw interrupted
            }
            failure?.let { throw it }
            return result
        }
    }
}