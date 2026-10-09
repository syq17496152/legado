package io.legado.app.model.localBook.epubcore.layout

/**
 * 把「原生 Lottie 翻页模式」挡在 Direct EPUB 文档契约之外。
 *
 * 本策略**不改写**已保存的阅读偏好：Direct EPUB 仅把该高级项视为不可用，
 * 普通文字阅读离开 EPUB 后仍可继续使用同一偏好。
 *
 * 迁移自 archive v15（纯算法，未改）。
 */
object EpubReaderChromeModePolicy {

    fun isSupported(
        directEpub: Boolean,
        mode: Int,
        advancedMode: Int
    ): Boolean = !directEpub || mode != advancedMode

    fun <T> selectableModes(
        modes: Map<Int, T>,
        directEpub: Boolean,
        advancedMode: Int
    ): Map<Int, T> {
        if (!directEpub) return modes
        return modes.filterKeys { it != advancedMode }
    }
}