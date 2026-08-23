package io.legado.app.help.config

import androidx.annotation.Keep
import io.legado.app.utils.FileUtils
import io.legado.app.utils.compress.ZipUtils
import io.legado.app.utils.externalFiles
import io.legado.app.utils.getFile
import io.legado.app.utils.getPrefString
import io.legado.app.utils.normalizeFileName
import io.legado.app.utils.putPrefString
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import splitties.init.appCtx
import java.io.File

object ShareNoteTemplateManager {

    const val BUILTIN_DIR_NAME = "builtin_default"
    private const val TEMPLATE_FILE_NAME = "template.html"
    private const val PREF_ACTIVE_TEMPLATE = "shareNoteTemplateActive"
    private const val PREF_LAST_TEMPLATE = "shareNoteTemplateLast"
    private const val PREF_STYLE_PALETTE = "shareNoteStylePalette"
    private const val PREF_STYLE_FONT = "shareNoteStyleFont"
    private const val ASSET_ROOT = "share_note_templates/default"
    const val FONT_SYSTEM = "system"
    const val FONT_SERIF = "serif"
    const val FONT_ROUND = "round"
    const val FONT_MONO = "mono"
    val fontFamilies = listOf(FONT_SYSTEM, FONT_SERIF, FONT_ROUND, FONT_MONO)

    data class StylePalette(
        val id: String,
        val name: String,
        val background: String,
        val surface: String,
        val text: String,
        val secondaryText: String,
        val accent: String,
        val divider: String
    )

    data class ShareStyle(
        val paletteId: String = "classic",
        val fontFamily: String = FONT_SYSTEM
    )

    val stylePalettes: List<StylePalette> = listOf(
        StylePalette(
            id = "classic",
            name = "经典",
            background = "#FFF7D8",
            surface = "#FFF7D8",
            text = "#3E3427",
            secondaryText = "#8A7B68",
            accent = "#8C6B2F",
            divider = "#D8C9A2"
        ),
        StylePalette(
            id = "mint",
            name = "青绿",
            background = "#DFF4E8",
            surface = "#F5FFF9",
            text = "#223B31",
            secondaryText = "#648174",
            accent = "#2E8B70",
            divider = "#B7D9C8"
        ),
        StylePalette(
            id = "rose",
            name = "淡粉",
            background = "#F8DDDF",
            surface = "#FFF3F4",
            text = "#453033",
            secondaryText = "#8A6A6E",
            accent = "#B66D78",
            divider = "#E2BFC4"
        ),
        StylePalette(
            id = "coffee",
            name = "咖啡",
            background = "#3A2617",
            surface = "#442C19",
            text = "#F3E2C8",
            secondaryText = "#C1A98E",
            accent = "#D6A15D",
            divider = "#6B4A2F"
        ),
        StylePalette(
            id = "navy",
            name = "深蓝",
            background = "#172234",
            surface = "#1D2A40",
            text = "#EAF0F7",
            secondaryText = "#A7B5C8",
            accent = "#80B7E8",
            divider = "#38506F"
        ),
        StylePalette(
            id = "paper",
            name = "纸白",
            background = "#F8F6EF",
            surface = "#FFFDF7",
            text = "#2F2F2B",
            secondaryText = "#77736A",
            accent = "#7B6F4A",
            divider = "#DDD8C9"
        )
    )

    val rootDir: File
        get() = appCtx.externalFiles.getFile("shareNoteTemplates").apply { mkdirs() }

    val previewDir: File
        get() = rootDir.getFile(".preview").apply { mkdirs() }

    private val tempDir: File
        get() = rootDir.getFile("temp").apply { mkdirs() }

    @Keep
    data class Meta(
        val name: String = "摘录分享模板",
        val type: String = "note",
        val canvas: String = CANVAS_LONG,
        val width: Int = 375,
        val height: Int = 720,
        val radius: Int = 0,
        val output: String = OUTPUT_IMAGE,
        val version: Int = 1,
        val updatedAt: Long = 0L
    ) {
        fun canvasLabel(): String {
            return if (canvas == CANVAS_WIDE) "宽图" else "长图"
        }

        fun sizeLabel(): String {
            return if (canvas == CANVAS_WIDE) {
                "${height}px"
            } else {
                "${width}px"
            }
        }
    }

    data class Entry(
        val meta: Meta,
        val source: Source,
        val dirName: String,
        val localDir: File? = null
    )

    enum class Source { BUILTIN, LOCAL }

    fun activeDirName(): String {
        return appCtx.getPrefString(PREF_ACTIVE_TEMPLATE, BUILTIN_DIR_NAME)
            ?.ifBlank { BUILTIN_DIR_NAME }
            ?: BUILTIN_DIR_NAME
    }

    fun lastDirName(): String {
        return appCtx.getPrefString(PREF_LAST_TEMPLATE, activeDirName())
            ?.ifBlank { activeDirName() }
            ?: activeDirName()
    }

    fun apply(entry: Entry) {
        appCtx.putPrefString(PREF_ACTIVE_TEMPLATE, entry.dirName)
        appCtx.putPrefString(PREF_LAST_TEMPLATE, entry.dirName)
    }

    fun rememberLast(entry: Entry) {
        appCtx.putPrefString(PREF_LAST_TEMPLATE, entry.dirName)
    }

    fun currentStyle(): ShareStyle {
        val paletteId = appCtx.getPrefString(PREF_STYLE_PALETTE, "classic")
            ?.takeIf { id -> stylePalettes.any { it.id == id } }
            ?: "classic"
        val fontFamily = appCtx.getPrefString(PREF_STYLE_FONT, FONT_SYSTEM)
            ?.takeIf { it in fontFamilies }
            ?: FONT_SYSTEM
        return ShareStyle(paletteId = paletteId, fontFamily = fontFamily)
    }

    fun saveStyle(style: ShareStyle) {
        val paletteId = style.paletteId.takeIf { id -> stylePalettes.any { it.id == id } } ?: "classic"
        val fontFamily = style.fontFamily.takeIf { it in fontFamilies } ?: FONT_SYSTEM
        appCtx.putPrefString(PREF_STYLE_PALETTE, paletteId)
        appCtx.putPrefString(PREF_STYLE_FONT, fontFamily)
    }

    fun palette(id: String): StylePalette {
        return stylePalettes.firstOrNull { it.id == id } ?: stylePalettes.first()
    }

    fun fontLabel(fontFamily: String): String {
        return when (fontFamily) {
            FONT_SERIF -> "衬线字体"
            FONT_ROUND -> "圆体"
            FONT_MONO -> "等宽字体"
            else -> "系统字体"
        }
    }

    suspend fun loadEntries(): List<Entry> = withContext(IO) {
        val local = loadLocal()
        listOf(builtinEntry()) + local.sortedWith(
            compareByDescending<Entry> { it.meta.updatedAt }
                .thenBy { it.meta.name }
                .thenBy { it.dirName }
        )
    }

    fun builtinEntry(): Entry {
        val html = readBuiltinHtml()
        return Entry(
            meta = parseMeta(html).copy(updatedAt = 0L),
            source = Source.BUILTIN,
            dirName = BUILTIN_DIR_NAME,
            localDir = null
        )
    }

    fun readTemplateHtml(entry: Entry): String {
        return if (entry.source == Source.BUILTIN || entry.dirName == BUILTIN_DIR_NAME) {
            readBuiltinHtml()
        } else {
            File(entry.localDir ?: localDir(entry.dirName), TEMPLATE_FILE_NAME).readText()
        }
    }

    fun baseUrl(entry: Entry): String {
        return if (entry.source == Source.BUILTIN || entry.dirName == BUILTIN_DIR_NAME) {
            "file:///android_asset/$ASSET_ROOT/"
        } else {
            (entry.localDir ?: localDir(entry.dirName)).toURI().toString()
        }
    }

    fun addOrUpdate(html: String, oldEntry: Entry? = null): Entry {
        val meta = parseMeta(html)
        require(meta.type.equals("note", ignoreCase = true)) { "只支持摘录分享模板" }
        val editableOld = oldEntry?.takeIf {
            it.source == Source.LOCAL && it.dirName.isNotBlank() && it.dirName != BUILTIN_DIR_NAME
        }
        val dirName = editableOld?.dirName ?: uniqueDirName(
            meta.name.normalizeFileName().ifBlank { "share_note_${System.currentTimeMillis()}" }
        )
        val dir = localDir(dirName).apply { mkdirs() }
        File(dir, TEMPLATE_FILE_NAME).writeText(html)
        clearPreview(dirName)
        return Entry(
            meta = meta.copy(updatedAt = System.currentTimeMillis()),
            source = Source.LOCAL,
            dirName = dirName,
            localDir = dir
        )
    }

    fun copyToLocal(entry: Entry): Entry {
        val html = readTemplateHtml(entry)
        val meta = parseMeta(html)
        val nameMetaRegex = Regex(
            """(<meta\s+name=["']reeden-template-name["']\s+content=["'])([^"']*)(["'][^>]*>)""",
            RegexOption.IGNORE_CASE
        )
        val copied = nameMetaRegex.find(html)?.let { matchResult ->
            html.replaceRange(
                matchResult.range,
                "${matchResult.groupValues[1]}${meta.name} 副本${matchResult.groupValues[3]}"
            )
        } ?: html
        return addOrUpdate(copied)
    }

    fun deleteLocal(entry: Entry) {
        if (entry.source != Source.LOCAL || entry.dirName == BUILTIN_DIR_NAME) return
        FileUtils.delete(entry.localDir ?: localDir(entry.dirName), deleteRootDir = true)
        clearPreview(entry.dirName)
        if (activeDirName() == entry.dirName) {
            appCtx.putPrefString(PREF_ACTIVE_TEMPLATE, BUILTIN_DIR_NAME)
        }
        if (lastDirName() == entry.dirName) {
            appCtx.putPrefString(PREF_LAST_TEMPLATE, BUILTIN_DIR_NAME)
        }
    }

    suspend fun importHtml(html: String): Entry = withContext(IO) {
        addOrUpdate(html)
    }

    suspend fun importZip(zipFile: File): Entry = withContext(IO) {
        val unzipDir = tempDir.getFile("import_${System.currentTimeMillis()}").apply {
            if (exists()) FileUtils.delete(this, deleteRootDir = true)
            mkdirs()
        }
        try {
            ZipUtils.unZipToPath(zipFile, unzipDir)
            val templateFile = unzipDir.walkTopDown().firstOrNull {
                it.isFile && it.name.equals(TEMPLATE_FILE_NAME, ignoreCase = true)
            } ?: throw IllegalArgumentException("模板包内没有 template.html")
            val html = templateFile.readText()
            val meta = parseMeta(html)
            require(meta.type.equals("note", ignoreCase = true)) { "只支持摘录分享模板" }
            val dirName = uniqueDirName(
                meta.name.normalizeFileName().ifBlank { "share_note_${System.currentTimeMillis()}" }
            )
            val target = localDir(dirName)
            if (target.exists()) FileUtils.delete(target, deleteRootDir = true)
            target.mkdirs()
            templateFile.parentFile?.copyRecursively(target, overwrite = true)
            File(target, TEMPLATE_FILE_NAME).writeText(html)
            Entry(meta.copy(updatedAt = System.currentTimeMillis()), Source.LOCAL, dirName, target)
        } finally {
            FileUtils.delete(unzipDir, deleteRootDir = true)
        }
    }

    suspend fun exportZip(entry: Entry): File = withContext(IO) {
        if (entry.source == Source.BUILTIN) {
            val dir = tempDir.getFile(BUILTIN_DIR_NAME).apply {
                if (exists()) FileUtils.delete(this, deleteRootDir = true)
                mkdirs()
            }
            File(dir, TEMPLATE_FILE_NAME).writeText(readBuiltinHtml())
            zipDir(dir, "${entry.dirName}.zip")
        } else {
            zipDir(entry.localDir ?: localDir(entry.dirName), "${entry.dirName}.zip")
        }
    }

    fun exportHtmlBytes(entry: Entry): ByteArray {
        return readTemplateHtml(entry).toByteArray()
    }

    fun previewFile(
        entry: Entry,
        style: ShareStyle = currentStyle()
    ): File {
        return previewDir.getFile("${entry.dirName}_${previewStyleKey(style)}.png")
    }

    fun clearPreview(dirName: String) {
        previewDir.getFile("$dirName.png").delete()
        previewDir.listFiles()
            ?.filter { it.name.startsWith("${dirName}_") && it.name.endsWith(".png") }
            ?.forEach { it.delete() }
    }

    private fun previewStyleKey(style: ShareStyle): String {
        val paletteId = style.paletteId.takeIf { id -> stylePalettes.any { it.id == id } } ?: "classic"
        val fontFamily = style.fontFamily.takeIf { it in fontFamilies } ?: FONT_SYSTEM
        return "${paletteId}_${fontFamily}"
    }

    fun parseMeta(html: String): Meta {
        val doc = Jsoup.parse(html)
        fun meta(name: String): String {
            return doc.selectFirst("""meta[name=$name]""")?.attr("content").orEmpty().trim()
        }
        val canvas = meta("reeden-template-canvas").lowercase().ifBlank { CANVAS_LONG }
            .let { if (it == CANVAS_WIDE) CANVAS_WIDE else CANVAS_LONG }
        return Meta(
            name = meta("reeden-template-name").ifBlank { "摘录分享模板" },
            type = meta("reeden-template-type").ifBlank { "note" },
            canvas = canvas,
            width = meta("reeden-template-width").toIntOrNull()?.coerceIn(240, 1440) ?: 375,
            height = meta("reeden-template-height").toIntOrNull()?.coerceIn(240, 2400) ?: 720,
            radius = meta("reeden-template-radius").toIntOrNull()?.coerceIn(0, 120) ?: 0,
            output = meta("reeden-template-output").lowercase().ifBlank { OUTPUT_IMAGE },
            version = meta("reeden-template-version").toIntOrNull()?.coerceAtLeast(1) ?: 1
        )
    }

    private fun zipDir(dir: File, fileName: String): File {
        val zipFile = tempDir.getFile(fileName)
        if (zipFile.exists()) zipFile.delete()
        if (!ZipUtils.zipFile(dir, zipFile) || !zipFile.exists() || zipFile.length() <= 0L) {
            throw IllegalStateException("模板导出失败")
        }
        return zipFile
    }

    private fun loadLocal(): List<Entry> {
        if (!rootDir.exists()) return emptyList()
        return rootDir.listFiles()
            ?.filter { it.isDirectory && it.name !in setOf("temp", ".preview", BUILTIN_DIR_NAME) }
            ?.mapNotNull(::readLocalEntry)
            .orEmpty()
    }

    private fun readLocalEntry(dir: File): Entry? {
        val template = File(dir, TEMPLATE_FILE_NAME)
        if (!template.exists()) return null
        val meta = runCatching { parseMeta(template.readText()) }.getOrNull() ?: return null
        return Entry(
            meta = meta.copy(updatedAt = template.lastModified()),
            source = Source.LOCAL,
            dirName = dir.name,
            localDir = dir
        )
    }

    private fun localDir(dirName: String): File {
        return rootDir.getFile(dirName)
    }

    private fun uniqueDirName(base: String): String {
        val normalized = base.normalizeFileName().ifBlank { "share_note" }
        var candidate = normalized
        var index = 1
        while (localDir(candidate).exists() || candidate == BUILTIN_DIR_NAME) {
            candidate = "${normalized}_${index++}"
        }
        return candidate
    }

    private fun readBuiltinHtml(): String {
        return appCtx.assets.open("$ASSET_ROOT/$TEMPLATE_FILE_NAME").bufferedReader().use { it.readText() }
    }

    const val CANVAS_LONG = "long"
    const val CANVAS_WIDE = "wide"
    const val OUTPUT_IMAGE = "image"
}