package io.legado.app.ui.main.bookshelf.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import io.legado.app.data.dao.BookShelfDisplay
import io.legado.app.data.entities.BookGroup
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.CoverCollectionManager
import io.legado.app.help.config.CoverCollectionManager.isRealCoverPath
import io.legado.app.ui.widget.compose.BookCoverImage
import io.legado.app.ui.widget.image.CoverImageView

@Composable
fun BookshelfComposeCover(
    item: BookshelfItemUi,
    modifier: Modifier = Modifier,
    fragment: Fragment? = null,
    lifecycle: Lifecycle? = null,
    fillBounds: Boolean = false,
    style: CoverImageView.CoverStyle = CoverImageView.CoverStyle.GRID
) {
    val coverRequest = remember(item.coverIdentityKey()) {
        item.toCoverRequest()
    }
    BookCoverImage(
        path = coverRequest.path,
        name = coverRequest.name,
        author = coverRequest.author,
        sourceOrigin = coverRequest.sourceOrigin,
        modifier = modifier,
        style = style,
        fragment = fragment,
        lifecycle = lifecycle,
        preferThumb = coverRequest.preferThumb,
        forcePath = coverRequest.forcePath,
        allowNameOverlay = coverRequest.allowNameOverlay,
        fillBounds = fillBounds
    )
}

private data class BookshelfCoverRequest(
    val path: String?,
    val name: String?,
    val author: String?,
    val sourceOrigin: String?,
    val preferThumb: Boolean,
    val forcePath: Boolean,
    val allowNameOverlay: Boolean?
)

private fun BookshelfItemUi.toCoverRequest(): BookshelfCoverRequest {
    return when (this) {
        is BookshelfBookItemUi -> display.toCoverRequest()
        is BookshelfFolderItemUi -> group.toCoverRequest()
    }
}

private fun BookShelfDisplay.toCoverRequest(): BookshelfCoverRequest {
    val originalCover = getDisplayCover()
    val collectionCover = CoverCollectionManager.selectedCollectionCover(
        bookKey = bookUrl.ifBlank { "$origin|$name|$author" },
        coverPath = originalCover
    )
    val usingCollectionCover = collectionCover != null
    val forceOriginalCover = collectionCover == null &&
        CoverCollectionManager.isMixedMode() &&
        originalCover.isRealCoverPath()
    val forcePath = usingCollectionCover || forceOriginalCover
    val allowNameOverlay = usingCollectionCover || !originalCover.isRealCoverPath()
    return buildCoverRequest(
        path = collectionCover ?: originalCover,
        name = name,
        author = author,
        sourceOrigin = origin,
        preferThumb = true,
        forcePath = forcePath,
        allowNameOverlay = allowNameOverlay
    )
}

private fun BookGroup.toCoverRequest(): BookshelfCoverRequest {
    return buildCoverRequest(
        path = cover,
        name = groupName,
        author = null,
        sourceOrigin = null,
        preferThumb = true,
        forcePath = false,
        allowNameOverlay = true,
    )
}

private fun buildCoverRequest(
    path: String?,
    name: String?,
    author: String?,
    sourceOrigin: String?,
    preferThumb: Boolean,
    forcePath: Boolean,
    allowNameOverlay: Boolean?
): BookshelfCoverRequest {
    return BookshelfCoverRequest(
        path = path,
        name = name,
        author = author,
        sourceOrigin = sourceOrigin,
        preferThumb = preferThumb,
        forcePath = forcePath,
        allowNameOverlay = allowNameOverlay
    )
}

private fun BookshelfItemUi.coverIdentityKey(): String {
    val configKey = listOf(
        AppConfig.useDefaultCover.toString(),
        AppConfig.loadCoverHighQuality.toString(),
        CoverCollectionManager.selectionKey()
    ).joinToString("|")
    return when (this) {
        is BookshelfBookItemUi -> "$configKey|book|${display.bookUrl}|${display.getDisplayCover()}|${display.name}|${display.author}"
        is BookshelfFolderItemUi -> "$configKey|folder|${group.groupId}|${group.cover}|${group.groupName}"
    }
}
