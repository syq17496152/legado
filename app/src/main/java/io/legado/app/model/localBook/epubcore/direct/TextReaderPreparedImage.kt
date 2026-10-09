package io.legado.app.model.localBook.epubcore.direct

/** 文档展示前的最终局部像素与呈现元数据。 */
data class TextReaderPreparedImage(
    val dataUri: String,
    val scale: Float = 1f,
    val isBubble: Boolean = true
)