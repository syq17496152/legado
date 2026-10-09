package io.legado.app.model.localBook.epubcore.facade

import io.legado.app.data.entities.BookChapter
import io.legado.app.model.localBook.epubcore.archive.EpubPath
import kotlin.math.abs

internal object EpubChapterIdentityPolicy {

    fun fragment(chapter: BookChapter): String? {
        return chapter.startFragmentId?.takeIf { it.isNotBlank() }
            ?: EpubPath.decodedFragment(chapter.url)
    }

    fun select(
        candidates: List<BookChapter>,
        fragmentId: String?,
        requestedIndex: Int,
        ownerIndex: Int? = null
    ): BookChapter? {
        if (candidates.isEmpty()) return null
        candidates.firstOrNull {
            fragmentId != null && fragment(it) == fragmentId
        }?.let { return it }
        ownerIndex?.let { owner ->
            candidates.firstOrNull { it.index == owner }?.let { return it }
        }
        if (candidates.size == 1) return candidates.single()
        return candidates.firstOrNull { it.index == requestedIndex }
            ?: candidates.minByOrNull { abs(it.index - requestedIndex) }
    }
}
