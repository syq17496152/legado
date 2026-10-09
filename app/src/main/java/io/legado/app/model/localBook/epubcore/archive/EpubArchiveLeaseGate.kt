package io.legado.app.model.localBook.epubcore.archive

import java.io.FilterInputStream
import java.io.InputStream
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 压缩包「多会话租约门」：把 [EpubArchive] 包一层引用计数，
 * 任何在途读取（含已交出的 [InputStream]）未结束时，[close] 不会真正关闭底层 zip，
 * 从而避免多会话并发下的句柄泄漏 / use-after-close。
 *
 * 迁移自 archive v15 `EpubArchiveLeaseGate.kt`（纯算法，未改）。
 */
class EpubArchiveLeaseGate(
    private val delegate: EpubArchive
) : EpubArchive {

    private var activeLeases = 0
    private var closing = false
    private var closeClaimed = false
    private var drained = false
    private var closeFailure: Throwable? = null
    private val drainCallbacks = arrayListOf<(Throwable?) -> Unit>()

    override fun exists(path: String): Boolean = withLease { delegate.exists(path) }

    override fun list(): List<String> = withLease { delegate.list() }

    override fun canonicalPath(path: String): String? = withLease { delegate.canonicalPath(path) }

    override fun readBytes(path: String, maxBytes: Long): ByteArray {
        return withLease { delegate.readBytes(path, maxBytes) }
    }

    override fun entrySize(path: String): Long? = withLease { delegate.entrySize(path) }

    override fun openStream(path: String): InputStream {
        acquireLease()
        val stream = try {
            delegate.openStream(path)
        } catch (throwable: Throwable) {
            releaseLease()
            throw throwable
        }
        return object : FilterInputStream(stream) {
            private val released = AtomicBoolean(false)

            override fun close() {
                try {
                    super.close()
                } finally {
                    if (released.compareAndSet(false, true)) releaseLease()
                }
            }
        }
    }

    override fun close() {
        closeWhenDrained {}
    }

    /** 请求关闭；等所有在途租约释放后调用 [onDrained]（回调收到底层 close 的异常，正常为 null）。 */
    fun closeWhenDrained(onDrained: (Throwable?) -> Unit) {
        var invokeImmediately = false
        val closeNow = synchronized(this) {
            if (drained) {
                invokeImmediately = true
                false
            } else {
                drainCallbacks += onDrained
                closing = true
                claimCloseLocked()
            }
        }
        if (invokeImmediately) {
            onDrained(closeFailure)
        } else if (closeNow) {
            closeDelegate()
        }
    }

    private inline fun <T> withLease(block: () -> T): T {
        acquireLease()
        return try {
            block()
        } finally {
            releaseLease()
        }
    }

    private fun acquireLease() {
        synchronized(this) {
            check(!closing) { "EPUB archive is closing" }
            activeLeases++
        }
    }

    private fun releaseLease() {
        val closeNow = synchronized(this) {
            check(activeLeases > 0) { "EPUB archive lease underflow" }
            activeLeases--
            claimCloseLocked()
        }
        if (closeNow) closeDelegate()
    }

    private fun claimCloseLocked(): Boolean {
        if (!closing || activeLeases > 0 || closeClaimed) return false
        closeClaimed = true
        return true
    }

    private fun closeDelegate() {
        val failure = runCatching { delegate.close() }.exceptionOrNull()
        val callbacks = synchronized(this) {
            closeFailure = failure
            drained = true
            drainCallbacks.toList().also { drainCallbacks.clear() }
        }
        callbacks.forEach { callback -> runCatching { callback(failure) } }
    }
}