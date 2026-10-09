package io.legado.app.model.localBook.epubcore.facade

/**
 * A cover image selected from the package metadata and read through the same
 * archive used by the Direct EPUB renderer.
 */
data class EpubCoreCoverResource(
    val path: String,
    val mediaType: String?,
    val bytes: ByteArray
)
