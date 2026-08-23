package io.legado.app.model.localBook.epubcore.font

import android.content.Context
import android.graphics.Typeface
import io.legado.app.model.localBook.epubcore.archive.EpubArchive
import io.legado.app.model.localBook.epubcore.archive.EpubPath
import java.io.File
import java.security.MessageDigest
import java.util.Locale

class EpubTypefaceResolver(
    private val context: Context,
    private val archive: EpubArchive,
    fontFaces: List<EpubFontFace>
) {

    private val faces = fontFaces.filter { it.family.isNotBlank() && it.sources.isNotEmpty() }
    private val typefaceCache = hashMapOf<String, Typeface?>()
    private val fileCache = hashMapOf<String, File?>()
    private val fontDir: File by lazy {
        File(context.cacheDir, "epub-fonts").apply { mkdirs() }
    }

    @Synchronized
    fun resolve(fontFamily: String?, typefaceStyle: Int): Typeface? {
        return resolve(
            fontFamily = fontFamily,
            bold = typefaceStyle == Typeface.BOLD || typefaceStyle == Typeface.BOLD_ITALIC,
            italic = typefaceStyle == Typeface.ITALIC || typefaceStyle == Typeface.BOLD_ITALIC
        )
    }

    @Synchronized
    fun resolve(fontFamily: String?, bold: Boolean, italic: Boolean): Typeface? {
        val candidates = fontFamily.toFamilyCandidates()
        if (candidates.isEmpty()) return null
        val targetWeight = if (bold) 700 else 400
        val targetStyle = if (italic) EpubFontStyle.Italic else EpubFontStyle.Normal
        candidates.forEach { family ->
            val face = findBestFace(family, targetWeight, targetStyle) ?: return@forEach
            loadTypeface(face)?.let { typeface ->
                val style = when {
                    bold && italic -> Typeface.BOLD_ITALIC
                    bold -> Typeface.BOLD
                    italic -> Typeface.ITALIC
                    else -> Typeface.NORMAL
                }
                return Typeface.create(typeface, style)
            }
        }
        return null
    }

    @Synchronized
    fun clear() {
        typefaceCache.clear()
        fileCache.values.filterNotNull().forEach { file ->
            runCatching { file.delete() }
        }
        fileCache.clear()
    }

    private fun findBestFace(family: String, targetWeight: Int, targetStyle: EpubFontStyle): EpubFontFace? {
        return faces
            .filter { it.family.normalizeFamily() == family.normalizeFamily() }
            .minWithOrNull(
                compareBy<EpubFontFace> {
                    if (it.style == targetStyle || targetStyle == EpubFontStyle.Italic && it.style == EpubFontStyle.Oblique) 0 else 1
                }.thenBy {
                    val range = it.weight
                    when {
                        targetWeight in range -> 0
                        targetWeight < range.first -> range.first - targetWeight
                        else -> targetWeight - range.last
                    }
                }
            )
    }

    private fun loadTypeface(face: EpubFontFace): Typeface? {
        face.sources.forEach { source ->
            val key = source.href.toArchivePath()
            if (typefaceCache.containsKey(key)) {
                typefaceCache[key]?.let { return it }
                return@forEach
            }
            val file = materializeFont(source)
            val typeface = file?.let { runCatching { Typeface.createFromFile(it) }.getOrNull() }
            typefaceCache[key] = typeface
            if (typeface != null) return typeface
        }
        return null
    }

    private fun materializeFont(source: EpubFontSource): File? {
        val href = source.href.toArchivePath()
        if (fileCache.containsKey(href)) return fileCache[href]
        val bytes = runCatching { archive.readBytes(href) }.getOrNull()
        if (bytes == null || bytes.isEmpty()) {
            fileCache[href] = null
            return null
        }
        val extension = href.substringAfterLast('.', "font").substringBefore('?').takeIf { it.isNotBlank() } ?: "font"
        val file = File(fontDir, "${bytes.sha1()}.$extension")
        val ready = runCatching {
            if (!file.exists() || file.length() != bytes.size.toLong()) {
                file.writeBytes(bytes)
            }
            file
        }.getOrNull()
        fileCache[href] = ready
        return ready
    }

    private fun String.toArchivePath(): String {
        val end = indexOfAny(charArrayOf('?', '#')).takeIf { it >= 0 } ?: length
        return EpubPath.normalize(substring(0, end))
    }

    private fun String?.toFamilyCandidates(): List<String> {
        if (isNullOrBlank()) return emptyList()
        return splitCommaList()
            .map { it.trim().trim('\'', '"') }
            .filter { it.isNotBlank() && it.lowercase(Locale.ROOT) !in genericFamilies }
    }

    private fun String.normalizeFamily(): String {
        return trim().trim('\'', '"').lowercase(Locale.ROOT)
    }

    private fun ByteArray.sha1(): String {
        val digest = MessageDigest.getInstance("SHA-1").digest(this)
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun String.splitCommaList(): List<String> {
        val result = arrayListOf<String>()
        var quote: Char? = null
        var parenDepth = 0
        var start = 0
        for (index in indices) {
            val char = this[index]
            if (quote != null) {
                if (char == quote && getOrNull(index - 1) != '\\') quote = null
                continue
            }
            when (char) {
                '\'', '"' -> quote = char
                '(' -> parenDepth++
                ')' -> if (parenDepth > 0) parenDepth--
                ',' -> if (parenDepth == 0) {
                    substring(start, index).trim().takeIf { it.isNotBlank() }?.let(result::add)
                    start = index + 1
                }
            }
        }
        substring(start).trim().takeIf { it.isNotBlank() }?.let(result::add)
        return result
    }

    private companion object {
        val genericFamilies = setOf(
            "serif",
            "sans-serif",
            "monospace",
            "cursive",
            "fantasy",
            "system-ui"
        )
    }
}
