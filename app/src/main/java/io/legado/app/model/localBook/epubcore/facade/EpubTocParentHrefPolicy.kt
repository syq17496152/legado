package io.legado.app.model.localBook.epubcore.facade

import io.legado.app.model.localBook.epubcore.archive.EpubPath

internal object EpubTocParentHrefPolicy {

    fun sharesResource(firstUrl: String, secondUrl: String?): Boolean {
        if (secondUrl.isNullOrBlank()) return false
        if (firstUrl.startsWith("skip:") || secondUrl.startsWith("skip:")) return false
        val first = EpubPath.stripFragment(firstUrl)
        val second = EpubPath.stripFragment(secondUrl)
        return first.isNotBlank() && first == second
    }

    fun shouldBecomeStructural(
        isVolume: Boolean,
        parentUrl: String,
        nextChapterUrl: String?
    ): Boolean {
        return isVolume &&
            sharesResource(parentUrl, nextChapterUrl)
    }
}
