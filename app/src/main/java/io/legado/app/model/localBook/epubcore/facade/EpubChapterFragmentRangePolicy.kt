package io.legado.app.model.localBook.epubcore.facade

import io.legado.app.model.localBook.epubcore.archive.EpubPath

internal object EpubChapterFragmentRangePolicy {

    fun endFragmentId(
        chapterUrl: String,
        nextChapterUrl: String?,
        nextStartFragmentId: String?
    ): String? {
        if (nextStartFragmentId.isNullOrBlank() || nextChapterUrl.isNullOrBlank()) return null
        return nextStartFragmentId.takeIf {
            EpubPath.stripFragment(chapterUrl) == EpubPath.stripFragment(nextChapterUrl)
        }
    }
}
