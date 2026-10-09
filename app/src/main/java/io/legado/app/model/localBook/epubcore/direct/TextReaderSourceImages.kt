package io.legado.app.model.localBook.epubcore.direct

/**
 * 源图片「动作 / 资源」容器。
 *
 * 说明：archive v15 把这两个类型放在 `TextReaderImage.kt` 内；本仓按职责拆分独立文件，
 * 使 `EpubDirectChapter`（阶段 1 内容管线）不必依赖整个图片渲染管线（阶段 2/3）。
 * 源动作始终保留在原生文档中，**绝不写入 HTML 事件处理器**。
 */
data class TextReaderImageAction(val source: String, val click: String)

data class TextReaderSourceImages(
    val sourceKey: String?,
    val onlineText: Boolean,
    val actions: Map<String, TextReaderImageAction>,
    val resources: Map<String, TextReaderImageRequest> = emptyMap()
)