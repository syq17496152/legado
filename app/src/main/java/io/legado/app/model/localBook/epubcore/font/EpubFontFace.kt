package io.legado.app.model.localBook.epubcore.font

data class EpubFontFace(
    val family: String,
    val sources: List<EpubFontSource>,
    val weight: IntRange = 400..400,
    val style: EpubFontStyle = EpubFontStyle.Normal
)

data class EpubFontSource(
    val href: String,
    val format: String? = null
)

enum class EpubFontStyle {
    Normal,
    Italic,
    Oblique
}
