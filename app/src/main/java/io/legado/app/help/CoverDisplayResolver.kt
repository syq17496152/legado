package io.legado.app.help

import io.legado.app.data.entities.Book
import io.legado.app.data.entities.SearchBook
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.CoverCollectionManager
import io.legado.app.help.config.CoverCollectionManager.isRealCoverPath

data class CoverDisplayRequest(
    val path: String?,
    val name: String?,
    val author: String?,
    val sourceOrigin: String?,
    val forcePath: Boolean,
    val allowNameOverlay: Boolean
) {
    val loadKey: String
        get() = listOf(
            path.orEmpty(),
            name.orEmpty(),
            author.orEmpty(),
            sourceOrigin.orEmpty(),
            AppConfig.useDefaultCover.toString(),
            CoverCollectionManager.selectionKey(),
            forcePath.toString(),
            allowNameOverlay.toString()
        ).joinToString("|")
}

object CoverDisplayResolver {

    fun resolve(book: Book): CoverDisplayRequest {
        val originalCover = book.getDisplayCover()
        val collectionCover = CoverCollectionManager.selectedCollectionCover(book)
        val usingCollectionCover = collectionCover != null
        val forceOriginalCover = collectionCover == null &&
            CoverCollectionManager.isMixedMode() &&
            originalCover.isRealCoverPath()
        return CoverDisplayRequest(
            path = collectionCover ?: originalCover,
            name = book.name,
            author = book.author,
            sourceOrigin = book.origin,
            forcePath = usingCollectionCover || forceOriginalCover,
            allowNameOverlay = usingCollectionCover || !originalCover.isRealCoverPath()
        )
    }

    fun resolve(searchBook: SearchBook): CoverDisplayRequest {
        val originalCover = searchBook.coverUrl
        val collectionCover = CoverCollectionManager.selectedCollectionCover(searchBook)
        val usingCollectionCover = collectionCover != null
        val forceOriginalCover = collectionCover == null &&
            CoverCollectionManager.isMixedMode() &&
            originalCover.isRealCoverPath()
        return CoverDisplayRequest(
            path = collectionCover ?: originalCover,
            name = searchBook.name,
            author = searchBook.author,
            sourceOrigin = searchBook.origin,
            forcePath = usingCollectionCover || forceOriginalCover,
            allowNameOverlay = usingCollectionCover || !originalCover.isRealCoverPath()
        )
    }
}
