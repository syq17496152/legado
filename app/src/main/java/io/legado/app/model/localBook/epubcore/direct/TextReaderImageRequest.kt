package io.legado.app.model.localBook.epubcore.direct

/** 不可变图片元数据：由所属「已准备章节」强引用，而非按 URL 持有。 */
data class TextReaderImageRequest(
    val chapterIndex: Int,
    val chapterUrl: String,
    val image: TextReaderImage,
    val renderRevision: String,
    val managedBubble: Boolean
)