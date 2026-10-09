package io.legado.app.model.localBook.epubcore.direct

import kotlinx.coroutines.CancellationException
import org.jsoup.parser.Parser
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Applies replacement rules to source text, before image parsing or generated reader markup. */
class TextReaderReplacementProcessor(
    private val maxEntries: Int = 4,
    private val maxCachedChars: Long = 4L * 1024 * 1024
) {
    data class Rule(
        val id: Long,
        val name: String,
        val pattern: String,
        val replacement: String,
        val isRegex: Boolean,
        val timeoutMillis: Long = 3000L,
        val enabled: Boolean = true
    ) {
        val regex: Regex by lazy { pattern.toRegex() }
    }

    data class Rules(val title: List<Rule> = emptyList(), val content: List<Rule> = emptyList())

    data class Source(
        val bookUrl: String,
        val chapterUrl: String,
        val chapterIndex: Int,
        val title: String,
        val raw: String
    )

    data class Prepared(
        val content: TextReaderDocument.Content,
        val effectiveContentRuleIds: Set<Long>
    )

    private data class Key(val source: Source, val rules: Rules, val enabled: Boolean)
    private data class Entry(val prepared: Prepared, val chars: Long)
    private class Pending(val generation: Long) {
        val ready = CountDownLatch(1)
        @Volatile var outcome: Result<Prepared>? = null
    }

    private val lock = Any()
    private val entries = LinkedHashMap<Key, Entry>(4, 0.75f, true)
    private val pending = HashMap<Key, Pending>()
    private var cachedChars = 0L
    private var generation = 0L

    init {
        require(maxEntries > 0)
        require(maxCachedChars > 0)
    }

    /** Old computations may finish for their callers, but cannot repopulate a newer cache. */
    fun invalidate() = synchronized(lock) {
        generation++
        entries.clear()
        pending.clear()
        cachedChars = 0L
    }

    fun prepare(
        source: Source,
        rules: Rules,
        enabled: Boolean,
        replaceRegex: (Rule, String) -> String,
        onRuleError: (Rule, Exception) -> Unit = { _, _ -> },
        checkActive: () -> Unit = {}
    ): Prepared {
        check(source.raw.length <= TextReaderDocument.MAX_SOURCE_CHARS) { "章节正文过大，无法安全排版" }
        // Full input equality avoids hash collisions and never feeds an earlier result back in.
        val key = Key(source, rules, enabled)
        while (true) {
            ensureActive(checkActive)
            var cached: Prepared? = null
            var ownsLoad = false
            val load = synchronized(lock) {
                cached = entries[key]?.prepared
                if (cached != null) null else pending[key] ?: Pending(generation).also {
                    pending[key] = it
                    ownsLoad = true
                }
            }
            cached?.let {
                ensureActive(checkActive)
                return it
            }
            checkNotNull(load)
            if (!ownsLoad) {
                try {
                    while (!load.ready.await(100L, TimeUnit.MILLISECONDS)) ensureActive(checkActive)
                } catch (error: InterruptedException) {
                    throw interrupted(error)
                }
                ensureActive(checkActive)
                val outcome = checkNotNull(load.outcome)
                // Cancellation of a preload must not cancel another live caller sharing its input.
                if (outcome.exceptionOrNull() is CancellationException) continue
                return outcome.getOrThrow()
            }
            try {
                val (prepared, chars) = process(source, rules, enabled, replaceRegex, onRuleError, checkActive)
                ensureActive(checkActive)
                synchronized(lock) {
                    if (load.generation == generation && chars <= maxCachedChars) {
                        entries.put(key, Entry(prepared, chars))?.let { cachedChars -= it.chars }
                        cachedChars += chars
                        while (entries.size > maxEntries || cachedChars > maxCachedChars) {
                            val iterator = entries.entries.iterator()
                            val oldest = iterator.next()
                            cachedChars -= oldest.value.chars
                            iterator.remove()
                        }
                    }
                }
                load.outcome = Result.success(prepared)
                return prepared
            } catch (error: Throwable) {
                load.outcome = Result.failure(error)
                throw error
            } finally {
                synchronized(lock) {
                    if (pending[key] === load) pending.remove(key)
                }
                load.ready.countDown()
            }
        }
    }

    private fun process(
        source: Source,
        rules: Rules,
        enabled: Boolean,
        replaceRegex: (Rule, String) -> String,
        onRuleError: (Rule, Exception) -> Unit,
        checkActive: () -> Unit
    ): Pair<Prepared, Long> {
        val effective = linkedSetOf<Long>()
        fun apply(input: String, selected: List<Rule>, title: Boolean): String {
            var value = input
            if (!enabled) return value
            selected.forEach { rule ->
                ensureActive(checkActive)
                if (!rule.enabled || rule.pattern.isEmpty()) return@forEach
                try {
                    val next = if (rule.isRegex) replaceRegex(rule, value)
                    else value.replace(rule.pattern, rule.replacement)
                    ensureActive(checkActive)
                    check(next.length <= TextReaderDocument.MAX_SOURCE_CHARS) { "替换结果过大，已保留替换前正文" }
                    if (next != value && (!title || next.isNotBlank())) {
                        value = next
                        if (!title) effective.add(rule.id)
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: InterruptedException) {
                    throw interrupted(error)
                } catch (error: Exception) {
                    onRuleError(rule, error)
                }
            }
            return value
        }

        val (sourceBody, sourceTitleRemoved) = removeSourceTitle(source.title, source.raw)
        val raw = apply(sourceBody, rules.content, title = false)
        val displayTitle = apply(source.title.trim(), rules.title, title = true).trim()
        ensureActive(checkActive)
        // Parse once, then remove at most one leading title. Parsing with an empty title avoids
        // dropping a second matching paragraph after the document's normal duplicate removal.
        val parsed = TextReaderDocument.prepare("", raw)
        val first = parsed.blocks.firstOrNull()
        val duplicateTitle = !sourceTitleRemoved && first != null && first.image == null && first.inlineImages.isEmpty() &&
            (first.text == source.title.trim() || first.text == displayTitle)
        val content = parsed.copy(
            title = displayTitle,
            blocks = if (duplicateTitle) parsed.blocks.drop(1) else parsed.blocks
        )
        ensureActive(checkActive)
        val chars = source.raw.length.toLong() + raw.length + source.title.length + displayTitle.length +
            source.bookUrl.length + source.chapterUrl.length
        return Prepared(content, effective) to chars
    }

    private fun removeSourceTitle(title: String, raw: String): Pair<String, Boolean> {
        val original = title.trim()
        if (original.isEmpty()) return raw to false
        var start = 0
        while (start < raw.length && (raw[start].isWhitespace() || raw[start] == '\uFEFF')) start++
        if (start == raw.length) return raw to false
        val lineEnd = raw.indexOfAny(charArrayOf('\r', '\n'), start).takeIf { it >= 0 } ?: raw.length
        if (raw.substring(start, lineEnd).trim() == original) {
            var end = lineEnd
            if (end < raw.length && raw[end] == '\r') end++
            if (end < raw.length && raw[end] == '\n') end++
            return raw.substring(end) to true
        }
        // Only simple, complete source blocks are removed. Inline tags/images and ambiguous
        // containers stay untouched; guessing their transformed first block could delete body text.
        if (raw[start] != '<') return raw to false
        val element = leadingTitleElement.matchAt(raw, start)
        if (element != null && Parser.unescapeEntities(element.groupValues[2], false).trim() == original) {
            return raw.substring(element.range.last + 1) to true
        }
        return raw to false
    }

    private fun ensureActive(checkActive: () -> Unit) {
        if (Thread.currentThread().isInterrupted) throw CancellationException("正文净化已取消")
        checkActive()
    }

    private fun interrupted(error: InterruptedException): CancellationException {
        Thread.currentThread().interrupt()
        return CancellationException("正文净化已取消").apply { initCause(error) }
    }

    private companion object {
        val leadingTitleElement by lazy {
            Regex("""<(p|h[1-6]|div)\b(?:[^<>"']|"[^"]*"|'[^']*')*>([^<]*)</\1\s*>""", RegexOption.IGNORE_CASE)
        }
    }
}
