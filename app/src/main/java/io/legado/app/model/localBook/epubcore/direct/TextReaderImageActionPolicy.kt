package io.legado.app.model.localBook.epubcore.direct

import io.legado.app.help.book.ParagraphRuleProcessor
import java.lang.ref.WeakReference

data class TextReaderImageActionRequest(
    val session: EpubDirectSession,
    val chapter: EpubDirectChapter,
    val generation: Long,
    val pageIndex: Int,
    val layoutRevision: Long,
    val imageId: String,
    val action: TextReaderImageAction
)

object TextReaderImageClickPolicy {
    fun mode(preference: String?, images: TextReaderSourceImages?): String = when {
        images == null -> "epub"
        preference == "2" && !images.onlineText -> "3"
        preference in setOf("1", "2", "3", "4") -> preference!!
        else -> "0"
    }

    fun allowsAction(preference: String?, images: TextReaderSourceImages?): Boolean =
        !images?.sourceKey.isNullOrBlank() && mode(preference, images) in setOf("0", "2", "4")
}

/** Reject stale layout events and replayed/rapid actions before entering source JS. */
class TextReaderImageActionGate {
    data class Event(val generation: Long, val page: Int, val revision: Long,
                     val imageId: String, val sequence: Long)
    private var lastChapter: WeakReference<EpubDirectChapter>? = null
    private var lastGeneration = Long.MIN_VALUE
    private var lastSequence = 0L
    private var lastAcceptedAt: Long? = null

    fun accept(chapter: EpubDirectChapter, generation: Long, page: Int, revision: Long,
               event: Event, ready: Boolean, now: Long): TextReaderImageAction? {
        if (!ready || chapter.sourceChapterUrl == null || chapter.sourceImages?.sourceKey.isNullOrBlank() ||
            event.generation != generation || event.page != page || event.revision != revision ||
            event.sequence <= 0L) return null
        val action = chapter.sourceImages?.actions?.get(event.imageId) ?: return null
        if (action.click.isBlank() || ParagraphRuleProcessor.isParagraphClick(action.click.trimStart())) return null
        if (lastChapter?.get() !== chapter || lastGeneration != generation) {
            lastChapter = WeakReference(chapter)
            lastGeneration = generation
            lastSequence = 0L
            lastAcceptedAt = null
        }
        if (event.sequence <= lastSequence) return null
        lastSequence = event.sequence
        if (lastAcceptedAt?.let { now - it < 300L } == true) return null
        lastAcceptedAt = now
        return action
    }
}
