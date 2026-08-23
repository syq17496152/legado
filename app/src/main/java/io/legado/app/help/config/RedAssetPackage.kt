package io.legado.app.help.config

import io.legado.app.utils.FileUtils
import io.legado.app.utils.getFile
import io.legado.app.utils.isSameOrSubFileOf
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipFile

internal object RedAssetPackage {

    enum class Kind {
        ThemeZip,
        NavigationBar,
        CoverCollection,
        Unknown
    }

    private val imageExtensions = setOf("jpg", "jpeg", "png", "webp", "bmp", "gif")
    private val navigationIconExtensions = imageExtensions + setOf("svg", "ico")
    private val navigationKeys = setOf(
        "home", "bookshelf", "bookcase", "book", "books",
        "feature", "discovery", "explore", "find",
        "notes", "rss", "subscribe", "subscription", "feed",
        "statistics", "stats", "readrecord", "record", "history",
        "settings", "my", "mine", "profile", "user", "config",
        "search", "findsearch"
    )

    fun zipPayload(file: File, tempDir: File): File? {
        val header = file.inputStream().use { input ->
            ByteArray(8).also { bytes ->
                val count = input.read(bytes)
                if (count < 8) return null
            }
        }
        val hasRedHeader = header[0] == 'R'.code.toByte() &&
            header[1] == 'E'.code.toByte() &&
            header[2] == 'D'.code.toByte()
        val zipOffset = if (hasRedHeader && header[4] == 'P'.code.toByte() && header[5] == 'K'.code.toByte()) {
            4L
        } else if (header[0] == 'P'.code.toByte() && header[1] == 'K'.code.toByte()) {
            0L
        } else {
            return null
        }
        val target = tempDir.getFile("red_asset_${System.currentTimeMillis()}.zip")
        file.inputStream().use { input ->
            if (zipOffset > 0L) input.skip(zipOffset)
            FileOutputStream(target).use { output -> input.copyTo(output) }
        }
        return target.takeIf { it.isFile && it.length() > 0L }
    }

    fun classifyZip(zipFile: File): Kind {
        return runCatching {
            ZipFile(zipFile).use { zip ->
                val names = zip.entries().asSequence()
                    .filterNot { it.isDirectory }
                    .map { it.name.substringAfterLast('/').lowercase() }
                    .toList()
                when {
                    names.any { it == "theme.json" } -> Kind.ThemeZip
                    names.any { it == "reeden_theme.json" } -> Kind.ThemeZip
                    names.any { it == "navigation.json" } -> Kind.NavigationBar
                    names.any { it == "collection.json" } -> Kind.CoverCollection
                    hasNavigationIcons(names) -> Kind.NavigationBar
                    names.isNotEmpty() && names.all(::isImageName) -> Kind.CoverCollection
                    else -> Kind.Unknown
                }
            }
        }.getOrDefault(Kind.Unknown)
    }

    fun unzipSecure(zipFile: File, targetDir: File) {
        if (targetDir.exists()) {
            FileUtils.delete(targetDir, deleteRootDir = true)
        }
        targetDir.mkdirs()
        ZipFile(zipFile).use { zip ->
            zip.entries().asSequence().forEach { entry ->
                val target = File(targetDir, entry.name)
                if (!target.isSameOrSubFileOf(targetDir)) {
                    throw IllegalArgumentException("Invalid RED package")
                }
                if (entry.isDirectory) {
                    target.mkdirs()
                } else {
                    target.parentFile?.mkdirs()
                    zip.getInputStream(entry).use { input ->
                        FileOutputStream(target).use { output -> input.copyTo(output) }
                    }
                }
            }
        }
    }

    fun isImageName(name: String): Boolean {
        return name.substringAfterLast('.', "").lowercase() in imageExtensions
    }

    private fun hasNavigationIcons(names: List<String>): Boolean {
        return navigationKeys.count { key ->
            names.any { name ->
                val extension = name.substringAfterLast('.', "")
                if (extension !in navigationIconExtensions) return@any false
                val stem = name.substringBeforeLast('.', name)
                stem == key || stem == "${key}_normal" || stem == "${key}_selected"
            }
        } >= 3
    }
}