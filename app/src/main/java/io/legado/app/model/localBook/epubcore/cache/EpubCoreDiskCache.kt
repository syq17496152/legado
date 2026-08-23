package io.legado.app.model.localBook.epubcore.cache

import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.help.book.BookHelp
import io.legado.app.help.book.getLocalUri
import io.legado.app.utils.FileUtils
import io.legado.app.utils.GSON
import io.legado.app.utils.MD5Utils
import io.legado.app.utils.isContentScheme
import io.legado.app.utils.fromJsonArray
import splitties.init.appCtx
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

object EpubCoreDiskCache {

    private const val RootDirName = "epub_core"
    private const val StructureDirName = "structure"
    private const val LayoutDirName = "layout"

    fun bookSignature(book: Book): String {
        val uri = book.getLocalUri()
        return if (uri.isContentScheme()) {
            val doc = runCatching { DocumentFile.fromSingleUri(appCtx, uri) }.getOrNull()
            buildString {
                append(book.bookUrl)
                append('|').append(book.originName)
                append('|').append(doc?.length() ?: 0L)
                append('|').append(doc?.lastModified() ?: 0L)
                append("|epubCoreSchema=2")
            }
        } else {
            val file = File(uri.path.orEmpty())
            buildString {
                append(book.bookUrl)
                append('|').append(book.originName)
                append('|').append(file.length())
                append('|').append(file.lastModified())
                append("|epubCoreSchema=2")
            }
        }
    }

    fun readChapterList(bookCacheDir: File, bookSignature: String): List<BookChapter>? {
        val file = structureFile(bookCacheDir, bookSignature)
        if (!file.isFile) return null
        return readGzipText(file)
            ?.let { GSON.fromJsonArray<BookChapter>(it).getOrNull() }
            ?.takeIf { it.isNotEmpty() }
    }

    fun writeChapterList(bookCacheDir: File, bookSignature: String, chapters: List<BookChapter>) {
        if (chapters.isEmpty()) return
        writeGzipText(structureFile(bookCacheDir, bookSignature), GSON.toJson(chapters))
    }

    fun readLayoutRaw(bookCacheDir: File, bookSignature: String, layoutKey: String): String? {
        val file = layoutFile(bookCacheDir, bookSignature, layoutKey)
        return if (file.isFile) readGzipText(file) else null
    }

    fun writeLayoutRaw(bookCacheDir: File, bookSignature: String, layoutKey: String, rawJson: String) {
        if (rawJson.isBlank()) return
        writeGzipText(layoutFile(bookCacheDir, bookSignature, layoutKey), rawJson)
    }

    fun clear(bookCacheDir: File) {
        FileUtils.delete(File(bookCacheDir, RootDirName))
    }

    private fun structureFile(bookCacheDir: File, bookSignature: String): File {
        return File(baseDir(bookCacheDir), "$StructureDirName/${fileHash(bookSignature)}.json.gz")
    }

    private fun layoutFile(bookCacheDir: File, bookSignature: String, layoutKey: String): File {
        return File(baseDir(bookCacheDir), "$LayoutDirName/${fileHash(bookSignature)}/${fileHash(layoutKey)}.json.gz")
    }

    private fun baseDir(bookCacheDir: File): File {
        return File(bookCacheDir, RootDirName).apply { mkdirs() }
    }

    private fun fileHash(value: String): String {
        return MD5Utils.md5Encode16(value)
    }

    private fun readGzipText(file: File): String? {
        return runCatching {
            GZIPInputStream(FileInputStream(file)).use { input ->
                input.readBytes().toString(Charsets.UTF_8)
            }
        }.getOrNull()
    }

    private fun writeGzipText(file: File, content: String) {
        runCatching {
            file.parentFile?.mkdirs()
            val temp = File(file.parentFile, "${file.name}.tmp")
            GZIPOutputStream(FileOutputStream(temp)).use { output ->
                output.write(content.toByteArray(Charsets.UTF_8))
            }
            if (file.exists()) file.delete()
            if (!temp.renameTo(file)) {
                temp.copyTo(file, overwrite = true)
                temp.delete()
            }
        }
    }
}
