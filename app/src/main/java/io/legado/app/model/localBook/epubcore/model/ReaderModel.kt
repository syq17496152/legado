package io.legado.app.model.localBook.epubcore.model

data class SourceAnchor(
    val chapterHref: String,
    val nodePath: String,
    val blockIndex: Int,
    val textOffset: Int
)

data class SourceRange(
    val start: SourceAnchor,
    val end: SourceAnchor
) {
    constructor(
        chapterHref: String,
        blockIndex: Int,
        startOffset: Int,
        endOffset: Int,
        nodePath: String = "body/$blockIndex"
    ) : this(
        start = SourceAnchor(chapterHref, nodePath, blockIndex, startOffset),
        end = SourceAnchor(chapterHref, nodePath, blockIndex, endOffset)
    )

    val chapterHref: String get() = start.chapterHref
    val blockIndex: Int get() = start.blockIndex
    val startOffset: Int get() = start.textOffset
    val endOffset: Int get() = end.textOffset
}
