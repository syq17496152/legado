package io.legado.app.model.localBook.epubcore.direct

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeout
import java.io.ByteArrayInputStream
import java.io.Closeable

/**
 * WebView interception must return immediately: source scripts can themselves use
 * WebViews. Only a completed local body is served on that shared callback thread.
 * A small status endpoint starts/coalesces work on the owning book's IO scope.
 */
internal class TextReaderImageResources(
    parent: Job,
    private val isCurrent: () -> Boolean,
    private val load: suspend (String) -> TextReaderImageResource,
    private val onFailure: (String, Throwable) -> Unit = { _, _ -> },
    private val timeoutMs: Long = 20_000,
    private val maxEntries: Int = 128,
    private val maxRetainedBytes: Int = 8 * 1024 * 1024
) : Closeable {
    private val owner = SupervisorJob(parent)
    private val scope = CoroutineScope(owner + Dispatchers.IO)
    private val permits = Semaphore(3)
    private val entries = LinkedHashMap<String, Entry>(16, .75f, true)
    private var retainedBytes = 0
    private var closed = false

    private class Entry(var complete: Boolean = false, var image: TextReaderImageResource? = null,
                      var running: Boolean = false, var readyLeaseUntil: Long = 0)

    fun state(path: String, start: Boolean = true): EpubDirectResource {
        var queued: Entry? = null
        val body = synchronized(this) {
            if (closed || !owner.isActive || !isCurrent()) return@synchronized "{\"state\":\"failed\"}"
            var entry = entries[path]
            if (entry == null && start) {
                trimLocked(makeRoom = true)
                if (entries.size < maxEntries) {
                    entry = Entry().also { entries[path] = it; queued = it }
                }
            }
            when {
                entry?.image != null -> {
                    if (start) entry.readyLeaseUntil = System.nanoTime() + 5_000_000_000L
                    val image = entry.image!!
                    "{\"state\":\"ready\",\"scale\":${image.scale},\"bubble\":${image.isBubble}}"
                }
                entry?.complete == true -> "{\"state\":\"failed\"}"
                else -> "{\"state\":\"pending\",\"queued\":${entry?.running != true}}"
            }
        }
        queued?.let { entry ->
            scope.launch {
                try {
                    val image = permits.withPermit {
                        synchronized(this@TextReaderImageResources) { entry.running = true }
                        withTimeout(timeoutMs) {
                            ensureActive()
                            check(isCurrent()) { "图片所属书籍已切换" }
                            load(path).also { ensureActive() }
                        }
                    }
                    synchronized(this@TextReaderImageResources) {
                        if (!closed && owner.isActive && isCurrent() && entries[path] === entry) {
                            entry.image = image
                            entry.complete = true
                            retainedBytes += image.retainedBytes
                            trimLocked(protected = path)
                        }
                    }
                } catch (error: Exception) {
                    synchronized(this@TextReaderImageResources) {
                        if (entries[path] === entry) entry.complete = true
                    }
                    if (error !is CancellationException) onFailure(path, error)
                }
            }
        }
        return EpubDirectResource("application/json", "UTF-8", 200, "OK",
            mapOf("Cache-Control" to "no-store"), ByteArrayInputStream(body.toByteArray(Charsets.UTF_8)))
    }

    fun resource(path: String, headOnly: Boolean = false): EpubDirectResource? {
        val image = synchronized(this) {
            if (closed || !owner.isActive || !isCurrent()) null else entries[path]?.let { entry ->
                if (!headOnly) entry.readyLeaseUntil = 0
                entry.image
            }
        } ?: return null
        return image.response(headOnly)
    }

    private fun trimLocked(makeRoom: Boolean = false, protected: String? = null) {
        val iterator = entries.iterator()
        while (iterator.hasNext() &&
            (entries.size > maxEntries - (if (makeRoom) 1 else 0) || retainedBytes > maxRetainedBytes)
        ) {
            val (key, entry) = iterator.next()
            if (key == protected || !entry.complete || entry.readyLeaseUntil > System.nanoTime()) continue
            retainedBytes -= entry.image?.retainedBytes ?: 0
            iterator.remove()
        }
    }

    override fun close() {
        synchronized(this) {
            if (closed) return
            closed = true
            entries.clear()
            retainedBytes = 0
        }
        owner.cancel()
    }
}
