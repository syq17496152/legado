package io.legado.app.model.localBook.epubcore.style

sealed interface EpubSizeValue {
    data object Auto : EpubSizeValue
    data class Px(val value: Float) : EpubSizeValue
    data class Percent(val value: Float) : EpubSizeValue
}

data class EpubBorderRadius(
    val topLeftPx: Float = 0f,
    val topRightPx: Float = 0f,
    val bottomRightPx: Float = 0f,
    val bottomLeftPx: Float = 0f
) {
    val maxPx: Float
        get() = maxOf(topLeftPx, topRightPx, bottomRightPx, bottomLeftPx)

    companion object {
        val Zero = EpubBorderRadius()
    }
}

data class EpubBackground(
    val imageHref: String? = null,
    val repeat: EpubBackgroundRepeat = EpubBackgroundRepeat.Repeat,
    val size: EpubBackgroundSize = EpubBackgroundSize.Auto,
    val position: EpubBackgroundPosition = EpubBackgroundPosition.Center
) {
    companion object {
        val None = EpubBackground()
    }
}

enum class EpubBackgroundRepeat {
    Repeat,
    NoRepeat,
    RepeatX,
    RepeatY
}

sealed interface EpubBackgroundSize {
    data object Auto : EpubBackgroundSize
    data object Cover : EpubBackgroundSize
    data object Contain : EpubBackgroundSize
    data class Explicit(val width: EpubSizeValue, val height: EpubSizeValue = EpubSizeValue.Auto) : EpubBackgroundSize
}

data class EpubBackgroundPosition(
    val xPercent: Float,
    val yPercent: Float
) {
    companion object {
        val Center = EpubBackgroundPosition(0.5f, 0.5f)
    }
}
