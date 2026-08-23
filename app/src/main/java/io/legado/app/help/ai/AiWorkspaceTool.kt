package io.legado.app.help.ai

import io.legado.app.data.appDb
import io.legado.app.data.entities.BookSource
import io.legado.app.model.Debug
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import splitties.init.appCtx
import java.io.File
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.regex.Matcher
import java.util.regex.Pattern

object AiWorkspaceTool {

    private const val TOOL_LIST_FILES = "workspace_list_files"
    private const val TOOL_READ_FILE = "workspace_read_file"
    private const val TOOL_READ_LINES = "workspace_read_lines"
    private const val TOOL_READ_MATCHES = "workspace_read_matches"
    private const val TOOL_SEARCH_FILES = "workspace_search_files"
    private const val TOOL_SAVE_INPUT_FILE = "workspace_save_input_file"
    private const val TOOL_WRITE_FILE = "workspace_write_file"
    private const val TOOL_EDIT_FILE = "workspace_edit_file"
    private const val TOOL_REPLACE_TEXT = "workspace_replace_text"
    private const val TOOL_REPLACE_REGEX = "workspace_replace_regex"
    private const val TOOL_EDIT_LINES = "workspace_edit_lines"
    private const val TOOL_INSERT_TEXT = "workspace_insert_text"
    private const val TOOL_DIFF_FILE = "workspace_diff_file"
    private const val TOOL_DELETE_FILE = "workspace_delete_file"
    private const val TOOL_LIST_BACKUPS = "workspace_list_backups"
    private const val TOOL_CREATE_BACKUP = "workspace_create_backup"
    private const val TOOL_RESTORE_BACKUP = "workspace_restore_backup"
    private const val TOOL_IMPORT_BOOK_SOURCE = "workspace_import_book_source"
    private const val TOOL_CREATE_BOOK_SOURCE_FILE = "workspace_create_book_source_file"
    private const val TOOL_DEBUG_BOOK_SOURCE = "workspace_debug_book_source"
    private const val TOOL_APPLY_BOOK_SOURCE = "workspace_apply_book_source"

    private const val DEFAULT_SESSION_ID = "global"
    private const val MAX_FILE_BYTES = 1_048_576L
    private const val MAX_READ_CHARS = 80_000
    private const val MAX_SEARCH_FILE_BYTES = 1_048_576L
    private const val MAX_SEARCH_MATCHES = 200
    private val allowedExtensions = setOf(
        "json", "txt", "html", "htm", "log", "md", "patch", "diff",
        "kt", "kts", "java", "xml", "gradle", "properties", "toml",
        "js", "ts", "jsx", "tsx", "css", "scss", "sass", "less",
        "py", "rb", "go", "rs", "c", "cc", "cpp", "h", "hpp",
        "yml", "yaml", "csv", "ini", "conf", "sh", "bat", "ps1"
    )
    private val allowedFileNames = setOf("readme", "license", "notice", "dockerfile", "makefile")

    private data class LiteralReplacement(
        val oldText: String,
        val newText: String,
        val usedAutoUnescape: Boolean,
        val note: String?
    )

    fun resolvedTools(): List<AiResolvedTool> {
        return listOf(
            AiResolvedTool(TOOL_LIST_FILES, listFilesDefinition()) { args -> listFiles(args) },
            AiResolvedTool(TOOL_READ_FILE, readFileDefinition()) { args -> readFile(args) },
            AiResolvedTool(TOOL_READ_LINES, readLinesDefinition()) { args -> readLines(args) },
            AiResolvedTool(TOOL_READ_MATCHES, readMatchesDefinition()) { args -> readMatches(args) },
            AiResolvedTool(TOOL_SEARCH_FILES, searchFilesDefinition()) { args -> searchFiles(args) },
            AiResolvedTool(TOOL_SAVE_INPUT_FILE, saveInputFileDefinition()) { args -> saveInputFile(args) },
            AiResolvedTool(TOOL_WRITE_FILE, writeFileDefinition()) { args -> writeFile(args) },
            AiResolvedTool(TOOL_EDIT_FILE, editFileDefinition()) { args -> editFile(args) },
            AiResolvedTool(TOOL_REPLACE_TEXT, replaceTextDefinition()) { args -> replaceText(args) },
            AiResolvedTool(TOOL_REPLACE_REGEX, replaceRegexDefinition()) { args -> replaceRegex(args) },
            AiResolvedTool(TOOL_EDIT_LINES, editLinesDefinition()) { args -> editLines(args) },
            AiResolvedTool(TOOL_INSERT_TEXT, insertTextDefinition()) { args -> insertText(args) },
            AiResolvedTool(TOOL_DIFF_FILE, diffFileDefinition()) { args -> diffFile(args) },
            AiResolvedTool(TOOL_DELETE_FILE, deleteFileDefinition()) { args -> deleteFile(args) },
            AiResolvedTool(TOOL_LIST_BACKUPS, listBackupsDefinition()) { args -> listBackups(args) },
            AiResolvedTool(TOOL_CREATE_BACKUP, createBackupDefinition()) { args -> createBackup(args) },
            AiResolvedTool(TOOL_RESTORE_BACKUP, restoreBackupDefinition()) { args -> restoreBackup(args) },
            AiResolvedTool(TOOL_IMPORT_BOOK_SOURCE, importBookSourceDefinition()) { args -> importBookSource(args) },
            AiResolvedTool(TOOL_CREATE_BOOK_SOURCE_FILE, createBookSourceFileDefinition()) { args -> createBookSourceFile(args) },
            AiResolvedTool(TOOL_DEBUG_BOOK_SOURCE, debugBookSourceDefinition()) { args -> debugBookSource(args) },
            AiResolvedTool(TOOL_APPLY_BOOK_SOURCE, applyBookSourceDefinition()) { args -> applyBookSource(args) }
        )
    }

    private fun listFiles(args: JSONObject?): String {
        val sessionId = sessionId(args)
        val dir = resolvePath(sessionId, args?.optString("path").orEmpty(), allowMissing = true)
            .getOrElse { return error(it.message ?: "invalid path") }
        if (!dir.exists()) return ok().put("sessionId", sessionId).put("files", JSONArray()).toString()
        if (!dir.isDirectory) return error("path is not a directory")
        val recursive = args?.optBoolean("recursive", false) == true
        val maxFiles = (args?.optInt("maxFiles", 500) ?: 500).coerceIn(1, 5_000)
        val files = if (recursive) {
            dir.walkTopDown()
                .filter { it != dir && !it.toPath().startsWith(backupRoot(sessionId).toPath()) }
                .take(maxFiles)
                .toList()
        } else {
            dir.listFiles()
                ?.filterNot { it.name == ".backups" }
                ?.sortedWith(compareBy<File> { !it.isDirectory }.thenBy { it.name.lowercase() })
                ?.take(maxFiles)
                .orEmpty()
        }
        return ok()
            .put("sessionId", sessionId)
            .put("path", relativePath(sessionId, dir))
            .put("recursive", recursive)
            .put("files", JSONArray().apply {
                files.forEach { file ->
                    put(JSONObject().apply {
                        put("path", relativePath(sessionId, file))
                        put("name", file.name)
                        put("directory", file.isDirectory)
                        put("size", if (file.isFile) file.length() else 0L)
                        put("updatedAt", file.lastModified())
                    })
                }
            })
            .put("truncated", files.size >= maxFiles)
            .toString()
    }

    private fun readFile(args: JSONObject?): String {
        val sessionId = sessionId(args)
        val file = resolvePath(sessionId, args?.optString("path").orEmpty())
            .getOrElse { return error(it.message ?: "invalid path") }
        if (!file.isFile) return error("file not found")
        val text = readWorkspaceText(file).getOrElse { return error(it.message ?: "file is too large") }
        val offset = (args?.optInt("offset", 0) ?: 0).coerceAtLeast(0)
        val maxChars = (args?.optInt("maxChars", 20_000) ?: 20_000).coerceIn(1, MAX_READ_CHARS)
        val chunk = text.drop(offset).take(maxChars)
        return ok()
            .put("sessionId", sessionId)
            .put("path", relativePath(sessionId, file))
            .put("size", file.length())
            .put("chars", text.length)
            .put("offset", offset)
            .put("nextOffset", (offset + chunk.length).takeIf { it < text.length } ?: JSONObject.NULL)
            .put("truncated", offset + chunk.length < text.length)
            .put("sha256", sha256(text))
            .put("content", chunk)
            .toString()
    }

    private fun readLines(args: JSONObject?): String {
        args ?: return error("missing arguments")
        val sessionId = sessionId(args)
        val file = resolvePath(sessionId, args.optString("path"))
            .getOrElse { return error(it.message ?: "invalid path") }
        if (!file.isFile) return error("file not found")
        val text = readWorkspaceText(file).getOrElse { return error(it.message ?: "file is too large") }
        val lines = text.lines()
        val startLine = args.optInt("startLine", 1).coerceAtLeast(1)
        val maxLines = args.optInt("maxLines", 120).coerceIn(1, 1_000)
        val requestedEnd = args.optInt("endLine", startLine + maxLines - 1)
        val endLine = requestedEnd.coerceAtLeast(startLine).coerceAtMost(lines.size)
        val actualStart = startLine.coerceAtMost((lines.size + 1).coerceAtLeast(1))
        val selected = if (actualStart > lines.size) {
            emptyList()
        } else {
            lines.subList(actualStart - 1, endLine)
        }
        return ok()
            .put("sessionId", sessionId)
            .put("path", relativePath(sessionId, file))
            .put("size", file.length())
            .put("sha256", sha256(text))
            .put("lineCount", lines.size)
            .put("startLine", actualStart)
            .put("endLine", if (selected.isEmpty()) JSONObject.NULL else endLine)
            .put("truncated", endLine < lines.size)
            .put("lines", JSONArray().apply {
                selected.forEachIndexed { index, line ->
                    put(JSONObject().apply {
                        put("line", actualStart + index)
                        put("text", line)
                    })
                }
            })
            .put(
                "content",
                selected.mapIndexed { index, line -> "${actualStart + index}: $line" }
                    .joinToString("\n")
                    .limitForJson(MAX_READ_CHARS)
            )
            .toString()
    }

    private fun readMatches(args: JSONObject?): String {
        args ?: return error("missing arguments")
        val sessionId = sessionId(args)
        val file = resolvePath(sessionId, args.optString("path"))
            .getOrElse { return error(it.message ?: "invalid path") }
        if (!file.isFile) return error("file not found")
        if (file.length() > MAX_SEARCH_FILE_BYTES) return error("file is too large to regex read")
        val pattern = compileSearchPattern(args.optString("pattern"), args.optString("flags"))
            .getOrElse { return error(it.message ?: "invalid regex") }
        val maxMatches = args.optInt("maxMatches", 50).coerceIn(1, MAX_SEARCH_MATCHES)
        val contextLines = args.optInt("contextLines", 2).coerceIn(0, 10)
        val text = readWorkspaceText(file).getOrElse { return error(it.message ?: "file is too large") }
        val matches = collectRegexMatches(
            sessionId = sessionId,
            file = file,
            text = text,
            pattern = pattern,
            maxMatches = maxMatches,
            contextLines = contextLines
        )
        return ok()
            .put("sessionId", sessionId)
            .put("path", relativePath(sessionId, file))
            .put("matches", JSONArray(matches))
            .put("truncated", matches.size >= maxMatches)
            .put("sha256", sha256(text))
            .toString()
    }

    private fun searchFiles(args: JSONObject?): String {
        args ?: return error("missing arguments")
        val sessionId = sessionId(args)
        val dir = resolvePath(sessionId, args.optString("path").ifBlank { "." }, allowMissing = true)
            .getOrElse { return error(it.message ?: "invalid path") }
        if (!dir.exists()) return error("path not found")
        if (!dir.isDirectory) return error("path is not a directory")
        val pattern = compileSearchPattern(args.optString("pattern"), args.optString("flags"))
            .getOrElse { return error(it.message ?: "invalid regex") }
        val pathPattern = args.optString("pathPattern").takeIf { it.isNotBlank() }?.let { raw ->
            compileSearchPattern(raw, "i").getOrElse { return error("invalid pathPattern: ${it.message}") }
        }
        val maxFiles = args.optInt("maxFiles", 200).coerceIn(1, 2_000)
        val maxMatches = args.optInt("maxMatches", 100).coerceIn(1, MAX_SEARCH_MATCHES)
        val contextLines = args.optInt("contextLines", 1).coerceIn(0, 10)
        val backupsPath = backupRoot(sessionId).canonicalFile.toPath()
        var scannedFiles = 0
        var skippedFiles = 0
        val matches = mutableListOf<JSONObject>()
        dir.walkTopDown()
            .filter { it.isFile }
            .filterNot { it.canonicalFile.toPath().startsWith(backupsPath) }
            .filter { file ->
                val relative = relativePath(sessionId, file)
                pathPattern == null || pathPattern.matcher(relative).find()
            }
            .take(maxFiles)
            .forEach { file ->
                if (matches.size >= maxMatches) return@forEach
                if (!isTextWorkspaceFile(file) || file.length() > MAX_SEARCH_FILE_BYTES) {
                    skippedFiles++
                    return@forEach
                }
                scannedFiles++
                val text = readWorkspaceText(file).getOrNull() ?: run {
                    skippedFiles++
                    return@forEach
                }
                val remaining = maxMatches - matches.size
                matches += collectRegexMatches(
                    sessionId = sessionId,
                    file = file,
                    text = text,
                    pattern = pattern,
                    maxMatches = remaining,
                    contextLines = contextLines
                )
            }
        return ok()
            .put("sessionId", sessionId)
            .put("path", relativePath(sessionId, dir))
            .put("scannedFiles", scannedFiles)
            .put("skippedFiles", skippedFiles)
            .put("matches", JSONArray(matches))
            .put("truncated", matches.size >= maxMatches)
            .toString()
    }

    private fun saveInputFile(args: JSONObject?): String {
        args ?: return error("missing arguments")
        val sessionId = sessionId(args)
        val content = args.optString("content")
        if (content.toByteArray().size > MAX_FILE_BYTES) return error("input is too large")
        val rawPath = args.optString("path").ifBlank {
            val name = args.optString("name").ifBlank { "input" }
            val extension = args.optString("extension").trim('.').ifBlank { "txt" }
            val stamp = SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US).format(Date())
            "inputs/${stamp}_${safeFileName(name)}.$extension"
        }
        val file = resolvePath(sessionId, rawPath, allowMissing = true)
            .getOrElse { return error(it.message ?: "invalid path") }
        validateWritableFile(file).getOrElse { return error(it.message ?: "invalid file") }
        val existed = file.exists()
        val before = if (existed && file.isFile) {
            readWorkspaceText(file).getOrElse { return error(it.message ?: "file is too large") }
        } else {
            ""
        }
        val backupId = backupIfNeeded(sessionId, file, args.optString("backup", "auto"), existed)
            .getOrElse { return error(it.message ?: "backup failed") }
        file.parentFile?.mkdirs()
        file.writeText(content)
        return mutationResult(sessionId, file, before, content, backupId)
            .let { JSONObject(it).put("inputFile", true).toString() }
    }

    private fun writeFile(args: JSONObject?): String {
        args ?: return error("missing arguments")
        val sessionId = sessionId(args)
        val mode = args.optString("mode", "create").ifBlank { "create" }
        val content = args.optString("content")
        if (content.toByteArray().size > MAX_FILE_BYTES) return error("file is too large")
        val file = resolvePath(sessionId, args.optString("path"), allowMissing = true)
            .getOrElse { return error(it.message ?: "invalid path") }
        validateWritableFile(file).getOrElse { return error(it.message ?: "invalid file") }
        val existed = file.exists()
        if (mode == "create" && existed) return error("file already exists")
        if (mode !in setOf("create", "overwrite", "append")) return error("unsupported mode")
        val before = if (existed && file.isFile) {
            readWorkspaceText(file).getOrElse { return error(it.message ?: "file is too large") }
        } else {
            ""
        }
        val backupId = backupIfNeeded(sessionId, file, args.optString("backup", "auto"), existed)
            .getOrElse { return error(it.message ?: "backup failed") }
        file.parentFile?.mkdirs()
        when (mode) {
            "append" -> file.appendText(content)
            else -> file.writeText(content)
        }
        val after = if (mode == "append") before + content else content
        return mutationResult(sessionId, file, before, after, backupId)
    }

    private fun editFile(args: JSONObject?): String {
        args ?: return error("missing arguments")
        val sessionId = sessionId(args)
        val file = resolvePath(sessionId, args.optString("path"))
            .getOrElse { return error(it.message ?: "invalid path") }
        validateWritableFile(file).getOrElse { return error(it.message ?: "invalid file") }
        if (!file.isFile) return error("file not found")
        val replacements = args.optJSONArray("replacements") ?: return error("missing replacements")
        var text = readWorkspaceText(file).getOrElse { return error(it.message ?: "file is too large") }
        val before = text
        for (index in 0 until replacements.length()) {
            val item = replacements.optJSONObject(index) ?: return error("invalid replacement at $index")
            val result = applyWorkspaceReplacement(text, item, index)
                .getOrElse { return error(it.message ?: "invalid replacement at $index") }
            text = result
        }
        if (text.toByteArray().size > MAX_FILE_BYTES) return error("file is too large")
        val backupId = backupIfNeeded(sessionId, file, args.optString("backup", "auto"), true)
            .getOrElse { return error(it.message ?: "backup failed") }
        file.writeText(text)
        return mutationResult(sessionId, file, before, text, backupId)
    }

    private fun replaceText(args: JSONObject?): String {
        args ?: return error("missing arguments")
        val sessionId = sessionId(args)
        val file = resolveEditableFile(sessionId, args.optString("path"))
            .getOrElse { return error(it.message ?: "invalid path") }
        val oldText = args.optString("oldText")
        if (oldText.isEmpty()) return error("oldText is empty")
        val newText = args.optString("newText")
        val expectMatches = args.optInt("expectMatches", 1).coerceAtLeast(0)
        val before = readWorkspaceText(file).getOrElse { return error(it.message ?: "file is too large") }
        val replacement = resolveLiteralReplacement(
            text = before,
            oldText = oldText,
            newText = newText,
            expectMatches = expectMatches,
            autoUnescape = args.optBoolean("autoUnescape", true)
        ).getOrElse { return error(it.message ?: "literal replacement failed") }
        val after = before.replace(replacement.oldText, replacement.newText)
        if (after.toByteArray().size > MAX_FILE_BYTES) return error("file is too large")
        val backupId = backupIfNeeded(sessionId, file, args.optString("backup", "auto"), true)
            .getOrElse { return error(it.message ?: "backup failed") }
        file.writeText(after)
        return JSONObject(mutationResult(sessionId, file, before, after, backupId))
            .put("editMethod", "replace_text")
            .put("matchedText", replacement.oldText.limitForJson(1_000))
            .put("usedAutoUnescape", replacement.usedAutoUnescape)
            .put("note", replacement.note ?: JSONObject.NULL)
            .toString()
    }

    private fun replaceRegex(args: JSONObject?): String {
        args ?: return error("missing arguments")
        val sessionId = sessionId(args)
        val file = resolveEditableFile(sessionId, args.optString("path"))
            .getOrElse { return error(it.message ?: "invalid path") }
        val before = readWorkspaceText(file).getOrElse { return error(it.message ?: "file is too large") }
        val item = JSONObject().apply {
            put("pattern", args.optString("pattern"))
            put("replacement", args.optString("replacement"))
            put("flags", args.optString("flags"))
            put("expectMatches", args.optInt("expectMatches", 1).coerceAtLeast(0))
            put("maxMatches", args.optInt("maxMatches", args.optInt("expectMatches", 1).coerceAtLeast(0)).coerceAtLeast(0))
            put("replacementSyntax", args.optString("replacementSyntax", "python").ifBlank { "python" })
        }
        val after = applyRegexReplacement(before, item, 0)
            .getOrElse { return error(it.message ?: "regex replacement failed") }
        if (after.toByteArray().size > MAX_FILE_BYTES) return error("file is too large")
        val backupId = backupIfNeeded(sessionId, file, args.optString("backup", "auto"), true)
            .getOrElse { return error(it.message ?: "backup failed") }
        file.writeText(after)
        return JSONObject(mutationResult(sessionId, file, before, after, backupId))
            .put("editMethod", "replace_regex")
            .toString()
    }

    private fun editLines(args: JSONObject?): String {
        args ?: return error("missing arguments")
        val sessionId = sessionId(args)
        val file = resolveEditableFile(sessionId, args.optString("path"))
            .getOrElse { return error(it.message ?: "invalid path") }
        val startLine = args.optInt("startLine", 0)
        val endLine = args.optInt("endLine", startLine)
        if (startLine <= 0 || endLine < startLine) return error("invalid line range")
        val before = readWorkspaceText(file).getOrElse { return error(it.message ?: "file is too large") }
        val lines = before.split('\n').toMutableList()
        if (startLine > lines.size + 1 || endLine > lines.size) {
            return error("line range outside file, file has ${lines.size} line(s)")
        }
        val oldLines = lines.subList(startLine - 1, endLine).joinToString("\n")
        val expectedText = args.optString("expectedText")
        if (expectedText.isNotEmpty() && oldLines != expectedText) {
            return error("expectedText did not match the selected line range")
        }
        val replacement = args.optString("replacement")
        val replacementLines = if (replacement.isEmpty()) {
            emptyList()
        } else {
            replacement.split('\n')
        }
        val fromIndex = startLine - 1
        val toIndexExclusive = endLine
        repeat(toIndexExclusive - fromIndex) {
            lines.removeAt(fromIndex)
        }
        lines.addAll(fromIndex, replacementLines)
        val after = lines.joinToString("\n")
        if (after.toByteArray().size > MAX_FILE_BYTES) return error("file is too large")
        val backupId = backupIfNeeded(sessionId, file, args.optString("backup", "auto"), true)
            .getOrElse { return error(it.message ?: "backup failed") }
        file.writeText(after)
        return JSONObject(mutationResult(sessionId, file, before, after, backupId))
            .put("editMethod", "edit_lines")
            .put("startLine", startLine)
            .put("endLine", endLine)
            .put("matchedText", oldLines.limitForJson(1_000))
            .toString()
    }

    private fun insertText(args: JSONObject?): String {
        args ?: return error("missing arguments")
        val sessionId = sessionId(args)
        val file = resolveEditableFile(sessionId, args.optString("path"))
            .getOrElse { return error(it.message ?: "invalid path") }
        val textToInsert = args.optString("text")
        val before = readWorkspaceText(file).getOrElse { return error(it.message ?: "file is too large") }
        val mode = args.optString("position", "after").ifBlank { "after" }
        val anchor = args.optString("anchor")
        val after = if (anchor.isNotEmpty()) {
            val occurrences = countOccurrences(before, anchor)
            val expectMatches = args.optInt("expectMatches", 1).coerceAtLeast(0)
            if (occurrences != expectMatches) {
                return error("anchor must match exactly $expectMatches time(s), matched $occurrences")
            }
            when (mode) {
                "before" -> before.replace(anchor, textToInsert + anchor)
                "after" -> before.replace(anchor, anchor + textToInsert)
                else -> return error("position must be before, after, start, or end")
            }
        } else {
            when (mode) {
                "start" -> textToInsert + before
                "end" -> before + textToInsert
                else -> return error("anchor is required for before/after insert")
            }
        }
        if (after.toByteArray().size > MAX_FILE_BYTES) return error("file is too large")
        val backupId = backupIfNeeded(sessionId, file, args.optString("backup", "auto"), true)
            .getOrElse { return error(it.message ?: "backup failed") }
        file.writeText(after)
        return JSONObject(mutationResult(sessionId, file, before, after, backupId))
            .put("editMethod", "insert_text")
            .toString()
    }

    private fun diffFile(args: JSONObject?): String {
        args ?: return error("missing arguments")
        val sessionId = sessionId(args)
        val file = resolvePath(sessionId, args.optString("path"))
            .getOrElse { return error(it.message ?: "invalid path") }
        if (!file.isFile) return error("file not found")
        val after = readWorkspaceText(file).getOrElse { return error(it.message ?: "file is too large") }
        val backupIdArg = args.optString("backupId").trim()
        val beforeSource: String
        val before: String
        val backupId: String?
        if (backupIdArg.isNotBlank()) {
            val backupFile = resolveBackupFile(sessionId, backupIdArg)
                .getOrElse { return error(it.message ?: "invalid backupId") }
            beforeSource = "backup"
            before = readWorkspaceText(backupFile).getOrElse { return error(it.message ?: "backup is too large") }
            backupId = backupIdArg
        } else {
            val beforeContent = args.optString("beforeContent")
            if (beforeContent.isNotEmpty()) {
                beforeSource = "beforeContent"
                before = beforeContent
                backupId = null
            } else {
                val latest = latestBackupForPath(sessionId, relativePath(sessionId, file))
                    ?: return error("backupId or beforeContent is required, and no backup was found for this path")
                beforeSource = "latestBackup"
                before = readWorkspaceText(latest.second).getOrElse { return error(it.message ?: "backup is too large") }
                backupId = latest.first
            }
        }
        val contextLines = args.optInt("contextLines", 3).coerceIn(0, 20)
        val maxChars = args.optInt("maxChars", 12_000).coerceIn(1_000, 80_000)
        val expectedContains = args.optString("expectedContains").takeIf { it.isNotEmpty() }
        val expectedNotContains = args.optString("expectedNotContains").takeIf { it.isNotEmpty() }
        val expectedRegex = args.optString("expectedRegex").takeIf { it.isNotEmpty() }
        val regexOk = expectedRegex?.let { raw ->
            compileSearchPattern(raw, args.optString("expectedRegexFlags"))
                .map { it.matcher(after).find() }
                .getOrElse { return error("invalid expectedRegex: ${it.message}") }
        }
        return ok()
            .put("sessionId", sessionId)
            .put("path", relativePath(sessionId, file))
            .put("beforeSource", beforeSource)
            .put("backupId", backupId ?: JSONObject.NULL)
            .put("changed", before != after)
            .put("beforeHash", sha256(before))
            .put("afterHash", sha256(after))
            .put("expectedContainsOk", expectedContains?.let { after.contains(it) } ?: JSONObject.NULL)
            .put("expectedNotContainsOk", expectedNotContains?.let { !after.contains(it) } ?: JSONObject.NULL)
            .put("expectedRegexOk", regexOk ?: JSONObject.NULL)
            .put("diff", unifiedDiff(before, after, contextLines, maxChars))
            .toString()
    }

    private fun deleteFile(args: JSONObject?): String {
        args ?: return error("missing arguments")
        val sessionId = sessionId(args)
        val file = resolvePath(sessionId, args.optString("path"))
            .getOrElse { return error(it.message ?: "invalid path") }
        if (!file.exists()) return error("file not found")
        if (file.isDirectory && file.listFiles()?.isNotEmpty() == true) {
            return error("directory is not empty")
        }
        val backupId = backupIfNeeded(sessionId, file, args.optString("backup", "auto"), file.isFile)
            .getOrElse { return error(it.message ?: "backup failed") }
        val deleted = file.delete()
        return ok()
            .put("sessionId", sessionId)
            .put("path", args.optString("path"))
            .put("deleted", deleted)
            .put("backupId", backupId ?: JSONObject.NULL)
            .toString()
    }

    private fun listBackups(args: JSONObject?): String {
        val sessionId = sessionId(args)
        val backupsRoot = backupRoot(sessionId)
        val pathFilter = args?.optString("path").orEmpty().trim().replace('\\', '/')
        val files = backupsRoot.walkTopDown()
            .filter { it.isFile && it.name != "manifest.json" }
            .map { file ->
                val backupId = backupsRoot.toPath().relativize(file.toPath()).toString().replace('\\', '/')
                JSONObject().apply {
                    put("backupId", backupId)
                    put("path", backupId.substringAfter('/', backupId))
                    put("size", file.length())
                    put("createdAt", file.lastModified())
                }
            }
            .filter { item ->
                pathFilter.isBlank() || item.optString("path").endsWith(pathFilter)
            }
            .toList()
        return ok().put("sessionId", sessionId).put("backups", JSONArray(files)).toString()
    }

    private fun createBackup(args: JSONObject?): String {
        args ?: return error("missing arguments")
        val sessionId = sessionId(args)
        val file = resolvePath(sessionId, args.optString("path"))
            .getOrElse { return error(it.message ?: "invalid path") }
        if (!file.isFile) return error("file not found")
        val backupId = backupIfNeeded(sessionId, file, "true", true)
            .getOrElse { return error(it.message ?: "backup failed") }
        return ok()
            .put("sessionId", sessionId)
            .put("path", relativePath(sessionId, file))
            .put("backupId", backupId ?: JSONObject.NULL)
            .put("sha256", sha256(readWorkspaceText(file).getOrElse { return error(it.message ?: "file is too large") }))
            .toString()
    }

    private fun restoreBackup(args: JSONObject?): String {
        args ?: return error("missing arguments")
        val sessionId = sessionId(args)
        val backupId = args.optString("backupId").trim()
        if (backupId.isBlank() || backupId.contains("..")) return error("invalid backupId")
        val backupRoot = backupRoot(sessionId).canonicalFile
        val source = File(backupRoot, backupId).canonicalFile
        if (!source.toPath().startsWith(backupRoot.toPath()) || source == backupRoot || !source.isFile) {
            return error("backup not found")
        }
        val targetPath = args.optString("path").ifBlank { backupId.substringAfter('/') }
        val target = resolvePath(sessionId, targetPath, allowMissing = true)
            .getOrElse { return error(it.message ?: "invalid path") }
        validateWritableFile(target).getOrElse { return error(it.message ?: "invalid file") }
        val before = if (target.isFile) {
            readWorkspaceText(target).getOrElse { return error(it.message ?: "file is too large") }
        } else {
            ""
        }
        val after = readWorkspaceText(source).getOrElse { return error(it.message ?: "backup is too large") }
        val backupBeforeRestore = backupIfNeeded(sessionId, target, "auto", target.exists())
            .getOrElse { return error(it.message ?: "backup failed") }
        target.parentFile?.mkdirs()
        source.copyTo(target, overwrite = true)
        return mutationResult(sessionId, target, before, after, backupBeforeRestore)
    }

    private fun importBookSource(args: JSONObject?): String {
        args ?: return error("missing arguments")
        val sessionId = sessionId(args)
        val source = findBookSource(args) ?: return error("book source not found")
        val path = args.optString("path").ifBlank {
            "book_sources/${safeFileName(source.bookSourceName.ifBlank { source.bookSourceUrl })}.json"
        }
        val file = resolvePath(sessionId, path, allowMissing = true)
            .getOrElse { return error(it.message ?: "invalid path") }
        val json = JSONObject(GSON.toJson(source)).toString(2)
        val existed = file.exists()
        val backupId = backupIfNeeded(sessionId, file, "auto", existed)
            .getOrElse { return error(it.message ?: "backup failed") }
        file.parentFile?.mkdirs()
        file.writeText(json)
        return ok()
            .put("sessionId", sessionId)
            .put("path", relativePath(sessionId, file))
            .put("bookSourceUrl", source.bookSourceUrl)
            .put("bookSourceName", source.bookSourceName)
            .put("backupId", backupId ?: JSONObject.NULL)
            .put("source", JSONObject(json))
            .toString()
    }

    private fun createBookSourceFile(args: JSONObject?): String {
        args ?: return error("missing arguments")
        val sessionId = sessionId(args)
        val source = readBookSourceFromArgs(args) ?: return error("invalid book source")
        val path = args.optString("path").ifBlank {
            "book_sources/${safeFileName(source.bookSourceName.ifBlank { source.bookSourceUrl })}.json"
        }
        val file = resolvePath(sessionId, path, allowMissing = true)
            .getOrElse { return error(it.message ?: "invalid path") }
        val json = JSONObject(GSON.toJson(source)).toString(2)
        val existed = file.exists()
        val backupId = backupIfNeeded(sessionId, file, args.optString("backup", "auto"), existed)
            .getOrElse { return error(it.message ?: "backup failed") }
        file.parentFile?.mkdirs()
        file.writeText(json)
        return ok()
            .put("sessionId", sessionId)
            .put("path", relativePath(sessionId, file))
            .put("backupId", backupId ?: JSONObject.NULL)
            .put("source", JSONObject(json))
            .toString()
    }

    private suspend fun debugBookSource(args: JSONObject?): String = coroutineScope {
        args ?: return@coroutineScope error("missing arguments")
        val sessionId = sessionId(args)
        val source = sourceFromWorkspace(sessionId, args.optString("path"))
            ?: return@coroutineScope error("invalid book source file")
        val key = args.optString("key")
            .ifBlank { source.ruleSearch?.checkKeyWord.orEmpty() }
            .ifBlank { "我的" }
        val timeoutMs = args.optLong("timeoutMs", 45_000L).coerceIn(10_000L, 90_000L)
        val logs = arrayListOf<String>()
        val finished = CompletableDeferred<Int>()
        val callback = object : Debug.Callback {
            override fun printLog(state: Int, msg: String) {
                logs += msg
                if ((state == -1 || state == 1000) && !finished.isCompleted) {
                    finished.complete(state)
                }
            }
        }
        val debugScope = this
        val state = Debug.withDebugSource(source.bookSourceUrl, callback) {
            try {
                Debug.startDebug(debugScope, source, key)
                withTimeoutOrNull(timeoutMs) { finished.await() }
            } finally {
                Debug.cancelDebug()
            }
        }
        ok()
            .put("sessionId", sessionId)
            .put("path", args.optString("path"))
            .put("bookSourceUrl", source.bookSourceUrl)
            .put("key", key)
            .put("finished", state != null)
            .put("success", state == 1000)
            .put("logs", JSONArray(logs.takeLast(80)))
            .toString()
    }

    private fun applyBookSource(args: JSONObject?): String {
        args ?: return error("missing arguments")
        val sessionId = sessionId(args)
        val source = sourceFromWorkspace(sessionId, args.optString("path"))
            ?: return error("invalid book source file")
        if (source.bookSourceUrl.isBlank()) return error("bookSourceUrl is empty")
        if (source.bookSourceName.isBlank()) return error("bookSourceName is empty")
        val oldSource = appDb.bookSourceDao.getBookSource(source.bookSourceUrl)
        val backupPath = oldSource?.let {
            val file = resolvePath(
                sessionId,
                "book_sources/db_backups/${safeFileName(source.bookSourceName)}_${System.currentTimeMillis()}.json",
                allowMissing = true
            ).getOrNull()
            file?.parentFile?.mkdirs()
            file?.writeText(JSONObject(GSON.toJson(it)).toString(2))
            file?.let { target -> relativePath(sessionId, target) }
        }
        appDb.bookSourceDao.insert(source)
        return ok()
            .put("sessionId", sessionId)
            .put("path", args.optString("path"))
            .put("saved", true)
            .put("bookSourceUrl", source.bookSourceUrl)
            .put("bookSourceName", source.bookSourceName)
            .put("databaseBackupPath", backupPath ?: JSONObject.NULL)
            .toString()
    }

    private fun sessionId(args: JSONObject?): String {
        return args?.optString("sessionId").orEmpty()
            .trim()
            .take(80)
            .replace(Regex("[^A-Za-z0-9_-]"), "_")
            .trim('_')
            .ifBlank { DEFAULT_SESSION_ID }
    }

    private fun workspaceRoot(sessionId: String): File {
        return File(appCtx.filesDir, "ai_workspace/$sessionId").apply { mkdirs() }
    }

    private fun backupRoot(sessionId: String): File {
        return File(workspaceRoot(sessionId), ".backups").apply { mkdirs() }
    }

    private fun resolveBackupFile(sessionId: String, backupId: String): Result<File> {
        if (backupId.isBlank() || backupId.contains("..")) {
            return Result.failure(IllegalArgumentException("invalid backupId"))
        }
        val root = backupRoot(sessionId).canonicalFile
        val file = File(root, backupId).canonicalFile
        if (!file.toPath().startsWith(root.toPath()) || file == root || !file.isFile) {
            return Result.failure(IllegalArgumentException("backup not found"))
        }
        return Result.success(file)
    }

    private fun latestBackupForPath(sessionId: String, relativePath: String): Pair<String, File>? {
        val root = backupRoot(sessionId)
        if (!root.exists()) return null
        return root.walkTopDown()
            .filter { it.isFile && it.name != "manifest.json" }
            .mapNotNull { file ->
                val backupId = root.toPath().relativize(file.toPath()).toString().replace('\\', '/')
                val backedPath = backupId.substringAfter('/', backupId)
                if (backedPath == relativePath) backupId to file else null
            }
            .maxByOrNull { it.second.lastModified() }
    }

    private fun resolvePath(sessionId: String, rawPath: String, allowMissing: Boolean = false): Result<File> {
        val path = rawPath.trim().replace('\\', '/').trimStart('/')
        if (path.isBlank()) return Result.success(workspaceRoot(sessionId).canonicalFile)
        if (path.contains(':')) return Result.failure(IllegalArgumentException("absolute paths are not allowed"))
        if (path.contains("..")) return Result.failure(IllegalArgumentException("path traversal is not allowed"))
        val target = File(workspaceRoot(sessionId), path).canonicalFile
        val root = workspaceRoot(sessionId).canonicalFile
        if (target != root && !target.toPath().startsWith(root.toPath())) {
            return Result.failure(IllegalArgumentException("path is outside workspace"))
        }
        if (!allowMissing && !target.exists()) {
            return Result.failure(IllegalArgumentException("path not found"))
        }
        return Result.success(target)
    }

    private fun resolveEditableFile(sessionId: String, path: String): Result<File> {
        val file = resolvePath(sessionId, path).getOrElse { return Result.failure(it) }
        validateWritableFile(file).getOrElse { return Result.failure(it) }
        if (!file.isFile) return Result.failure(IllegalArgumentException("file not found"))
        return Result.success(file)
    }

    private fun validateWritableFile(file: File): Result<Unit> {
        if (!isTextWorkspaceFile(file)) {
            return Result.failure(IllegalArgumentException("unsupported file extension"))
        }
        if (file.exists() && file.length() > MAX_FILE_BYTES) {
            return Result.failure(IllegalArgumentException("file is too large"))
        }
        return Result.success(Unit)
    }

    private fun readWorkspaceText(file: File): Result<String> {
        if (!file.isFile) return Result.failure(IllegalArgumentException("file not found"))
        if (file.length() > MAX_FILE_BYTES) {
            return Result.failure(IllegalArgumentException("file is too large"))
        }
        return runCatching { file.readText() }
    }

    private fun isTextWorkspaceFile(file: File): Boolean {
        val extension = file.extension.lowercase()
        val name = file.name.lowercase()
        return extension in allowedExtensions || name in allowedFileNames
    }

    private fun backupIfNeeded(
        sessionId: String,
        file: File,
        mode: String,
        existed: Boolean
    ): Result<String?> {
        if (!existed || !file.isFile) return Result.success(null)
        val shouldBackup = when (mode) {
            "false" -> false
            "true" -> true
            else -> true
        }
        if (!shouldBackup) return Result.success(null)
        val relative = relativePath(sessionId, file)
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US).format(Date())
        val backupFile = File(backupRoot(sessionId), "$stamp/$relative").canonicalFile
        backupFile.parentFile?.mkdirs()
        file.copyTo(backupFile, overwrite = true)
        return Result.success(backupRoot(sessionId).toPath().relativize(backupFile.toPath()).toString().replace('\\', '/'))
    }

    private fun relativePath(sessionId: String, file: File): String {
        return workspaceRoot(sessionId).canonicalFile.toPath()
            .relativize(file.canonicalFile.toPath())
            .toString()
            .replace('\\', '/')
            .ifBlank { "." }
    }

    private fun sourceFromWorkspace(sessionId: String, path: String): BookSource? {
        val file = resolvePath(sessionId, path).getOrNull() ?: return null
        if (!file.isFile) return null
        val text = readWorkspaceText(file).getOrNull() ?: return null
        return GSON.fromJsonObject<BookSource>(text).getOrNull()
    }

    private fun findBookSource(args: JSONObject): BookSource? {
        args.optString("bookSourceUrl").trim().takeIf { it.isNotBlank() }?.let { url ->
            appDb.bookSourceDao.getBookSource(url)?.let { return it }
        }
        val searchKey = args.optString("searchKey").trim()
        if (searchKey.isBlank()) return null
        return appDb.bookSourceDao.all.firstOrNull { source ->
            source.bookSourceName.contains(searchKey, ignoreCase = true) ||
                    source.bookSourceUrl.contains(searchKey, ignoreCase = true)
        }
    }

    private fun readBookSourceFromArgs(args: JSONObject): BookSource? {
        args.optString("sourceJson").takeIf { it.isNotBlank() }?.let { json ->
            GSON.fromJsonObject<BookSource>(json).getOrNull()?.let { return it }
        }
        val sourceUrl = args.optString("bookSourceUrl").trim()
        if (sourceUrl.isBlank()) return null
        return BookSource(
            bookSourceUrl = sourceUrl,
            bookSourceName = args.optString("bookSourceName").ifBlank { sourceUrl },
            bookSourceGroup = args.optString("bookSourceGroup").takeIf { it.isNotBlank() },
            searchUrl = args.optString("searchUrl").takeIf { it.isNotBlank() }
        )
    }

    private fun mutationResult(
        sessionId: String,
        file: File,
        before: String,
        after: String,
        backupId: String?
    ): String {
        return ok()
            .put("sessionId", sessionId)
            .put("path", relativePath(sessionId, file))
            .put("changed", before != after)
            .put("size", file.length())
            .put("backupId", backupId ?: JSONObject.NULL)
            .put("beforeHash", sha256(before))
            .put("afterHash", sha256(after))
            .put("diffPreview", diffPreview(before, after))
            .toString()
    }

    private fun countOccurrences(text: String, needle: String): Int {
        var count = 0
        var index = 0
        while (true) {
            index = text.indexOf(needle, index)
            if (index < 0) return count
            count++
            index += needle.length
        }
    }

    private fun applyWorkspaceReplacement(
        text: String,
        item: JSONObject,
        index: Int
    ): Result<String> {
        return when (item.optString("type", "literal").ifBlank { "literal" }) {
            "literal" -> applyLiteralReplacement(text, item, index)
            "regex" -> applyRegexReplacement(text, item, index)
            else -> Result.failure(IllegalArgumentException("unsupported replacement type at $index"))
        }
    }

    private fun applyLiteralReplacement(
        text: String,
        item: JSONObject,
        index: Int
    ): Result<String> {
        val oldText = item.optString("oldText")
        val newText = item.optString("newText")
        if (oldText.isEmpty()) {
            return Result.failure(IllegalArgumentException("oldText is empty at $index"))
        }
        val expectMatches = item.optInt("expectMatches", 1).coerceAtLeast(0)
        val replacement = resolveLiteralReplacement(
            text = text,
            oldText = oldText,
            newText = newText,
            expectMatches = expectMatches,
            autoUnescape = item.optBoolean("autoUnescape", true)
        ).getOrElse {
            return Result.failure(IllegalArgumentException("${it.message} at $index"))
        }
        return Result.success(text.replace(replacement.oldText, replacement.newText))
    }

    private fun resolveLiteralReplacement(
        text: String,
        oldText: String,
        newText: String,
        expectMatches: Int,
        autoUnescape: Boolean
    ): Result<LiteralReplacement> {
        val directCount = countOccurrences(text, oldText)
        if (directCount == expectMatches) {
            return Result.success(LiteralReplacement(oldText, newText, false, null))
        }
        if (!autoUnescape || directCount != 0) {
            return Result.failure(
                IllegalArgumentException("oldText must match exactly $expectMatches time(s), matched $directCount")
            )
        }
        val candidates = literalUnescapeCandidates(oldText, newText)
            .distinctBy { it.oldText to it.newText }
            .filter { it.oldText != oldText }
            .map { candidate -> candidate to countOccurrences(text, candidate.oldText) }
            .filter { (_, count) -> count == expectMatches }
        if (candidates.size == 1) {
            val candidate = candidates.single().first
            return Result.success(candidate)
        }
        val counts = literalUnescapeCandidates(oldText, newText)
            .distinctBy { it.oldText }
            .take(4)
            .joinToString("; ") { candidate ->
                "${candidate.note}: ${countOccurrences(text, candidate.oldText)}"
            }
        return Result.failure(
            IllegalArgumentException(
                "oldText must match exactly $expectMatches time(s), matched 0. " +
                        "Auto-unescape candidates did not produce one safe match. Candidate counts: $counts. " +
                        "Use workspace_read_matches, workspace_replace_regex, or workspace_edit_lines."
            )
        )
    }

    private fun literalUnescapeCandidates(oldText: String, newText: String): List<LiteralReplacement> {
        fun unescapeQuotes(value: String) = value.replace("\\\"", "\"")
        fun unescapeSlashes(value: String) = value.replace("\\\\", "\\")
        fun unescapeCommon(value: String) = unescapeSlashes(unescapeQuotes(value))
        return listOf(
            LiteralReplacement(unescapeQuotes(oldText), unescapeQuotes(newText), true, "unescaped quoted double quotes"),
            LiteralReplacement(unescapeSlashes(oldText), unescapeSlashes(newText), true, "collapsed double backslashes"),
            LiteralReplacement(unescapeCommon(oldText), unescapeCommon(newText), true, "unescaped quotes and collapsed backslashes")
        )
    }

    private fun applyRegexReplacement(
        text: String,
        item: JSONObject,
        index: Int
    ): Result<String> {
        val patternText = item.optString("pattern")
        if (patternText.isEmpty()) {
            return Result.failure(IllegalArgumentException("pattern is empty at $index"))
        }
        val replacement = item.optString("replacement")
        val flags = regexFlags(item.optString("flags"))
        val pattern = runCatching { Pattern.compile(patternText, flags) }
            .getOrElse { return Result.failure(IllegalArgumentException("invalid regex at $index: ${it.message}")) }
        val matcher = pattern.matcher(text)
        val matchCount = countRegexMatches(matcher)
        val expectMatches = item.optInt("expectMatches", 1).coerceAtLeast(0)
        if (matchCount != expectMatches) {
            return Result.failure(
                IllegalArgumentException("regex must match exactly $expectMatches time(s) at $index, matched $matchCount")
            )
        }
        val maxMatches = item.optInt("maxMatches", expectMatches).coerceAtLeast(0)
        val syntax = item.optString("replacementSyntax", "python").ifBlank { "python" }
        val javaReplacement = when (syntax) {
            "java", "kotlin" -> replacement
            else -> pythonReplacementToJava(replacement)
        }
        return runCatching {
            replaceRegexMatches(text, pattern, javaReplacement, maxMatches)
        }.recoverCatching {
            throw IllegalArgumentException("invalid replacement at $index: ${it.message}")
        }
    }

    private fun compileSearchPattern(patternText: String, rawFlags: String): Result<Pattern> {
        if (patternText.isBlank()) {
            return Result.failure(IllegalArgumentException("pattern is empty"))
        }
        return runCatching { Pattern.compile(patternText, regexFlags(rawFlags)) }
    }

    private fun collectRegexMatches(
        sessionId: String,
        file: File,
        text: String,
        pattern: Pattern,
        maxMatches: Int,
        contextLines: Int
    ): List<JSONObject> {
        if (maxMatches <= 0) return emptyList()
        val lines = text.lines()
        val matcher = pattern.matcher(text)
        val result = mutableListOf<JSONObject>()
        while (matcher.find() && result.size < maxMatches) {
            val lineNumber = lineNumberAt(text, matcher.start())
            val column = columnAt(text, matcher.start())
            val fromLine = (lineNumber - contextLines).coerceAtLeast(1)
            val toLine = (lineNumber + contextLines).coerceAtMost(lines.size)
            result += JSONObject().apply {
                put("path", relativePath(sessionId, file))
                put("line", lineNumber)
                put("column", column)
                put("start", matcher.start())
                put("end", matcher.end())
                put("match", matcher.group().limitForJson(2_000))
                put("groups", JSONArray().apply {
                    for (groupIndex in 1..matcher.groupCount()) {
                        put(runCatching { matcher.group(groupIndex) }.getOrNull()?.limitForJson(1_000) ?: JSONObject.NULL)
                    }
                })
                put("contextStartLine", fromLine)
                put(
                    "context",
                    lines.subList(fromLine - 1, toLine)
                        .mapIndexed { offset, line -> "${fromLine + offset}: $line" }
                        .joinToString("\n")
                        .limitForJson(4_000)
                )
            }
        }
        return result
    }

    private fun lineNumberAt(text: String, offset: Int): Int {
        return text.substring(0, offset.coerceIn(0, text.length)).count { it == '\n' } + 1
    }

    private fun columnAt(text: String, offset: Int): Int {
        val safeOffset = offset.coerceIn(0, text.length)
        val lineStart = text.lastIndexOf('\n', (safeOffset - 1).coerceAtLeast(0)).let {
            if (it < 0) 0 else it + 1
        }
        return safeOffset - lineStart + 1
    }

    private fun String.limitForJson(maxChars: Int): String {
        return if (length <= maxChars) this else take(maxChars) + "\n...<truncated ${length - maxChars} chars>"
    }

    private fun regexFlags(rawFlags: String): Int {
        var flags = 0
        rawFlags.forEach { flag ->
            when (flag.lowercaseChar()) {
                'i' -> flags = flags or Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE
                'm' -> flags = flags or Pattern.MULTILINE
                's' -> flags = flags or Pattern.DOTALL
                'x' -> flags = flags or Pattern.COMMENTS
            }
        }
        return flags
    }

    private fun countRegexMatches(matcher: Matcher): Int {
        var count = 0
        while (matcher.find()) {
            count++
        }
        matcher.reset()
        return count
    }

    private fun replaceRegexMatches(
        text: String,
        pattern: Pattern,
        replacement: String,
        maxMatches: Int
    ): String {
        if (maxMatches <= 0) return text
        val matcher = pattern.matcher(text)
        val builder = StringBuffer()
        var replaced = 0
        while (matcher.find()) {
            if (replaced >= maxMatches) break
            matcher.appendReplacement(builder, replacement)
            replaced++
        }
        matcher.appendTail(builder)
        return builder.toString()
    }

    private fun pythonReplacementToJava(replacement: String): String {
        val builder = StringBuilder()
        var literal = StringBuilder()
        var index = 0

        fun flushLiteral() {
            if (literal.isNotEmpty()) {
                builder.append(Matcher.quoteReplacement(literal.toString()))
                literal = StringBuilder()
            }
        }

        while (index < replacement.length) {
            val char = replacement[index]
            if (char != '\\' || index + 1 >= replacement.length) {
                literal.append(char)
                index++
                continue
            }

            val next = replacement[index + 1]
            when {
                next.isDigit() && next != '0' -> {
                    flushLiteral()
                    var end = index + 2
                    while (end < replacement.length && replacement[end].isDigit()) {
                        end++
                    }
                    builder.append('$').append(replacement.substring(index + 1, end))
                    index = end
                }

                next == 'g' && index + 2 < replacement.length && replacement[index + 2] == '<' -> {
                    val end = replacement.indexOf('>', startIndex = index + 3)
                    if (end < 0) {
                        throw IllegalArgumentException("unterminated python group reference")
                    }
                    val group = replacement.substring(index + 3, end)
                    if (!group.matches(Regex("""[A-Za-z][A-Za-z0-9_]*""")) && !group.matches(Regex("""[1-9][0-9]*"""))) {
                        throw IllegalArgumentException("invalid python group reference: $group")
                    }
                    flushLiteral()
                    if (group.first().isDigit()) {
                        builder.append('$').append(group)
                    } else {
                        builder.append("\${").append(group).append('}')
                    }
                    index = end + 1
                }

                next == '\\' -> {
                    literal.append('\\')
                    index += 2
                }

                next == 'n' -> {
                    literal.append('\n')
                    index += 2
                }

                next == 'r' -> {
                    literal.append('\r')
                    index += 2
                }

                next == 't' -> {
                    literal.append('\t')
                    index += 2
                }

                else -> {
                    literal.append(next)
                    index += 2
                }
            }
        }
        flushLiteral()
        return builder.toString()
    }

    private fun diffPreview(before: String, after: String): String {
        return unifiedDiff(before, after, contextLines = 2, maxChars = 2_000)
    }

    private fun unifiedDiff(
        before: String,
        after: String,
        contextLines: Int,
        maxChars: Int
    ): String {
        if (before == after) return ""
        val beforeLines = before.lines()
        val afterLines = after.lines()
        var prefix = 0
        val minSize = minOf(beforeLines.size, afterLines.size)
        while (prefix < minSize && beforeLines[prefix] == afterLines[prefix]) {
            prefix++
        }
        var suffix = 0
        while (
            suffix < beforeLines.size - prefix &&
            suffix < afterLines.size - prefix &&
            beforeLines[beforeLines.lastIndex - suffix] == afterLines[afterLines.lastIndex - suffix]
        ) {
            suffix++
        }
        val beforeStart = (prefix - contextLines).coerceAtLeast(0)
        val afterStart = (prefix - contextLines).coerceAtLeast(0)
        val beforeEnd = (beforeLines.size - suffix + contextLines).coerceAtMost(beforeLines.size)
        val afterEnd = (afterLines.size - suffix + contextLines).coerceAtMost(afterLines.size)
        return buildString {
            append("--- before\n")
            append("+++ after\n")
            append("@@ -")
            append(beforeStart + 1)
            append(',')
            append((beforeEnd - beforeStart).coerceAtLeast(0))
            append(" +")
            append(afterStart + 1)
            append(',')
            append((afterEnd - afterStart).coerceAtLeast(0))
            append(" @@\n")
            val beforeChangedStart = prefix
            val beforeChangedEnd = beforeLines.size - suffix
            val afterChangedStart = prefix
            val afterChangedEnd = afterLines.size - suffix
            beforeLines.subList(beforeStart, beforeChangedStart).forEach { append("  ").append(it).append('\n') }
            beforeLines.subList(beforeChangedStart, beforeChangedEnd).forEach { append("- ").append(it).append('\n') }
            afterLines.subList(afterChangedStart, afterChangedEnd).forEach { append("+ ").append(it).append('\n') }
            afterLines.subList(afterChangedEnd, afterEnd).forEach { append("  ").append(it).append('\n') }
            if (beforeStart > 0 || beforeEnd < beforeLines.size || afterStart > 0 || afterEnd < afterLines.size) {
                append("...<diff truncated to changed window>\n")
            }
        }.limitForJson(maxChars)
    }

    private fun sha256(text: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun safeFileName(name: String): String {
        return name.replace(Regex("[\\\\/:*?\"<>|\\s]+"), "_").trim('_').ifBlank { "book_source" }.take(80)
    }

    private fun ok() = JSONObject().put("ok", true)

    private fun error(message: String) = JSONObject().apply {
        put("ok", false)
        put("error", message)
    }.toString()

    private fun stringProp(description: String) = JSONObject().apply {
        put("type", "string")
        put("description", description)
    }

    private fun booleanProp(description: String) = JSONObject().apply {
        put("type", "boolean")
        put("description", description)
    }

    private fun listFilesDefinition() = functionDef(
        TOOL_LIST_FILES,
        "List files in the sandboxed AI workspace. Use recursive=true to inspect a project tree before editing.",
        JSONObject()
            .put("sessionId", stringProp("Optional workspace session id."))
            .put("path", stringProp("Relative directory path."))
            .put("recursive", booleanProp("Whether to list files recursively."))
            .put("maxFiles", JSONObject().put("type", "integer").put("description", "Maximum files to return. Defaults to 500."))
    )

    private fun readFileDefinition() = functionDef(
        TOOL_READ_FILE,
        "Read a file from the sandboxed AI workspace. Use offset and maxChars for large files. Use workspace_read_lines when you need stable line numbers for workspace_edit_lines.",
        JSONObject()
            .put("sessionId", stringProp("Optional workspace session id."))
            .put("path", stringProp("Relative file path."))
            .put("offset", JSONObject().put("type", "integer"))
            .put("maxChars", JSONObject().put("type", "integer"))
    )

    private fun readLinesDefinition() = functionDef(
        TOOL_READ_LINES,
        "Read a numbered line range from one workspace file. This is the preferred read tool before workspace_edit_lines.",
        JSONObject()
            .put("sessionId", stringProp("Optional workspace session id."))
            .put("path", stringProp("Relative file path."))
            .put("startLine", JSONObject().put("type", "integer").put("description", "1-based first line. Defaults to 1."))
            .put("endLine", JSONObject().put("type", "integer").put("description", "1-based last line, inclusive."))
            .put("maxLines", JSONObject().put("type", "integer").put("description", "Maximum lines to return when endLine is omitted. Defaults to 120."))
    )

    private fun readMatchesDefinition() = functionDef(
        TOOL_READ_MATCHES,
        "Read regex matches from one workspace file with line numbers, context, and capture groups. Use this before regex edits to verify exact targets.",
        JSONObject()
            .put("sessionId", stringProp("Optional workspace session id."))
            .put("path", stringProp("Relative file path."))
            .put("pattern", stringProp("Regex pattern. Java/PC-style regex; use flags separately."))
            .put("flags", stringProp("Regex flags: i=ignore case, m=multiline, s=dot matches newline, x=comments."))
            .put("contextLines", JSONObject().put("type", "integer").put("description", "Context lines around each match. Defaults to 2."))
            .put("maxMatches", JSONObject().put("type", "integer").put("description", "Maximum matches to return. Defaults to 50."))
    )

    private fun searchFilesDefinition() = functionDef(
        TOOL_SEARCH_FILES,
        "Search workspace project files by regex and return matching file paths, line numbers, context, and capture groups.",
        JSONObject()
            .put("sessionId", stringProp("Optional workspace session id."))
            .put("path", stringProp("Relative directory path. Defaults to workspace root."))
            .put("pattern", stringProp("Regex pattern to search in file contents."))
            .put("flags", stringProp("Regex flags: i=ignore case, m=multiline, s=dot matches newline, x=comments."))
            .put("pathPattern", stringProp("Optional regex filter for relative file paths, e.g. \\\\.kt$ or book_sources/.*\\\\.json$."))
            .put("contextLines", JSONObject().put("type", "integer").put("description", "Context lines around each match. Defaults to 1."))
            .put("maxFiles", JSONObject().put("type", "integer").put("description", "Maximum files to scan. Defaults to 200."))
            .put("maxMatches", JSONObject().put("type", "integer").put("description", "Maximum matches to return. Defaults to 100."))
    )

    private fun saveInputFileDefinition() = functionDef(
        TOOL_SAVE_INPUT_FILE,
        "Save user-provided data into the workspace as a file so it can be inspected, regex-searched, and edited in later steps.",
        JSONObject()
            .put("sessionId", stringProp("Optional workspace session id."))
            .put("path", stringProp("Optional target relative path. If omitted, saves under inputs/."))
            .put("name", stringProp("Friendly input name used when path is omitted."))
            .put("extension", stringProp("File extension used when path is omitted. Defaults to txt."))
            .put("content", stringProp("Raw user data or file content to save."))
            .put("backup", stringProp("auto, true, or false."))
    )

    private fun writeFileDefinition() = functionDef(
        TOOL_WRITE_FILE,
        "Create, overwrite, or append a file in the sandboxed AI workspace. Existing files are backed up by default.",
        JSONObject()
            .put("sessionId", stringProp("Optional workspace session id."))
            .put("path", stringProp("Relative file path."))
            .put("content", stringProp("File content."))
            .put("mode", stringProp("create, overwrite, or append."))
            .put("backup", stringProp("auto, true, or false."))
    )

    private fun editFileDefinition() = functionDef(
        TOOL_EDIT_FILE,
        "Batch edit a workspace file by exact literal or regex replacements. Prefer workspace_replace_text for one exact text change, workspace_replace_regex for regex/rule patterns, and workspace_edit_lines when line numbers are known.",
        JSONObject()
            .put("sessionId", stringProp("Optional workspace session id."))
            .put("path", stringProp("Relative file path."))
            .put("replacements", JSONObject().apply {
                put("type", "array")
                put("items", JSONObject().apply {
                    put("type", "object")
                    put("properties", JSONObject().apply {
                        put("type", stringProp("literal or regex. Defaults to literal."))
                        put("oldText", stringProp("Exact text to replace when type=literal."))
                        put("newText", stringProp("Replacement text when type=literal."))
                        put("pattern", stringProp("Regex pattern when type=regex. Use Python/PC style flags separately."))
                        put("replacement", stringProp("Regex replacement when type=regex. Defaults to Python-style replacement syntax: \\1, \\g<1>, and \\g<name>. Literal $ and backslashes are allowed in python mode."))
                        put("flags", stringProp("Regex flags: i=ignore case, m=multiline, s=dot matches newline, x=comments."))
                        put("expectMatches", JSONObject().apply {
                            put("type", "integer")
                            put("description", "Expected match count. Defaults to 1.")
                        })
                        put("maxMatches", JSONObject().apply {
                            put("type", "integer")
                            put("description", "Maximum replacements to apply. Defaults to expectMatches.")
                        })
                        put("replacementSyntax", stringProp("python, java, or kotlin. Defaults to python."))
                    })
                    put("required", JSONArray())
                })
            })
            .put("backup", stringProp("auto, true, or false."))
    )

    private fun replaceTextDefinition() = functionDef(
        TOOL_REPLACE_TEXT,
        "Replace one exact text snippet in a workspace file. Use this for plain text only. It safely auto-recovers common over-escaped JSON strings such as \\\\\" when exactly one candidate matches.",
        JSONObject()
            .put("sessionId", stringProp("Optional workspace session id."))
            .put("path", stringProp("Relative file path."))
            .put("oldText", stringProp("Exact text to replace. For regex-like content, prefer workspace_replace_regex."))
            .put("newText", stringProp("Replacement text."))
            .put("expectMatches", JSONObject().put("type", "integer").put("description", "Expected exact match count. Defaults to 1."))
            .put("autoUnescape", booleanProp("Whether to recover common over-escaped literal strings when direct matching finds 0. Defaults to true."))
            .put("backup", stringProp("auto, true, or false."))
    )

    private fun replaceRegexDefinition() = functionDef(
        TOOL_REPLACE_REGEX,
        "Replace text by regex in one workspace file. Use this for Legado rules, escaped quotes, capture groups, or any pattern-like edit. Replacement syntax defaults to Python/PC style: \\1, \\g<1>, \\g<name>.",
        JSONObject()
            .put("sessionId", stringProp("Optional workspace session id."))
            .put("path", stringProp("Relative file path."))
            .put("pattern", stringProp("Regex pattern. Java regex with flags separately; model may use familiar PC/Python-style groups."))
            .put("replacement", stringProp("Replacement text. Defaults to Python-style replacement syntax: \\1, \\g<1>, and \\g<name>."))
            .put("flags", stringProp("Regex flags: i=ignore case, m=multiline, s=dot matches newline, x=comments."))
            .put("expectMatches", JSONObject().put("type", "integer").put("description", "Expected regex match count. Defaults to 1."))
            .put("maxMatches", JSONObject().put("type", "integer").put("description", "Maximum replacements. Defaults to expectMatches."))
            .put("replacementSyntax", stringProp("python, java, or kotlin. Defaults to python."))
            .put("backup", stringProp("auto, true, or false."))
    )

    private fun editLinesDefinition() = functionDef(
        TOOL_EDIT_LINES,
        "Replace a 1-based inclusive line range in a workspace file. Use only after workspace_read_lines or workspace_read_matches returns stable line numbers. Pass expectedText when possible to prevent stale-line edits.",
        JSONObject()
            .put("sessionId", stringProp("Optional workspace session id."))
            .put("path", stringProp("Relative file path."))
            .put("startLine", JSONObject().put("type", "integer").put("description", "1-based first line to replace."))
            .put("endLine", JSONObject().put("type", "integer").put("description", "1-based last line to replace, inclusive."))
            .put("expectedText", stringProp("Optional exact current text in the selected line range. The edit fails if it does not match."))
            .put("replacement", stringProp("Replacement text for the whole line range. Empty string deletes the range."))
            .put("backup", stringProp("auto, true, or false."))
    )

    private fun insertTextDefinition() = functionDef(
        TOOL_INSERT_TEXT,
        "Insert text at file start/end or before/after an exact anchor. Use this for additive edits instead of overwriting whole files.",
        JSONObject()
            .put("sessionId", stringProp("Optional workspace session id."))
            .put("path", stringProp("Relative file path."))
            .put("text", stringProp("Text to insert."))
            .put("position", stringProp("before, after, start, or end. Defaults to after."))
            .put("anchor", stringProp("Exact anchor text for before/after insertion. Not needed for start/end."))
            .put("expectMatches", JSONObject().put("type", "integer").put("description", "Expected anchor match count. Defaults to 1."))
            .put("backup", stringProp("auto, true, or false."))
    )

    private fun diffFileDefinition() = functionDef(
        TOOL_DIFF_FILE,
        "Show a unified diff after editing a workspace file. Pass the backupId returned by an edit tool, or omit it to compare with the latest backup for that path. Use expectedContains/expectedNotContains/expectedRegex for lightweight validation.",
        JSONObject()
            .put("sessionId", stringProp("Optional workspace session id."))
            .put("path", stringProp("Relative file path."))
            .put("backupId", stringProp("Backup id returned by edit/write/restore tools. If omitted, latest backup for this path is used."))
            .put("beforeContent", stringProp("Optional explicit before text when no backup is available."))
            .put("contextLines", JSONObject().put("type", "integer").put("description", "Context lines around changed block. Defaults to 3."))
            .put("maxChars", JSONObject().put("type", "integer").put("description", "Maximum diff characters. Defaults to 12000."))
            .put("expectedContains", stringProp("Optional text that must exist in the current file after the edit."))
            .put("expectedNotContains", stringProp("Optional text that must not exist in the current file after the edit."))
            .put("expectedRegex", stringProp("Optional regex that must match the current file after the edit."))
            .put("expectedRegexFlags", stringProp("Regex flags for expectedRegex: i, m, s, x."))
    )

    private fun deleteFileDefinition() = functionDef(
        TOOL_DELETE_FILE,
        "Delete one file or an empty directory from the sandboxed AI workspace. Files are backed up by default.",
        JSONObject()
            .put("sessionId", stringProp("Optional workspace session id."))
            .put("path", stringProp("Relative path."))
            .put("backup", stringProp("auto, true, or false."))
    )

    private fun listBackupsDefinition() = functionDef(
        TOOL_LIST_BACKUPS,
        "List backups in the AI workspace.",
        JSONObject().put("sessionId", stringProp("Optional workspace session id.")).put("path", stringProp("Optional path filter."))
    )

    private fun createBackupDefinition() = functionDef(
        TOOL_CREATE_BACKUP,
        "Explicitly create a backup snapshot of an existing workspace file before risky edits, regex replacements, deletes, or apply operations.",
        JSONObject()
            .put("sessionId", stringProp("Optional workspace session id."))
            .put("path", stringProp("Relative file path to back up."))
    )

    private fun restoreBackupDefinition() = functionDef(
        TOOL_RESTORE_BACKUP,
        "Restore a backup into the AI workspace.",
        JSONObject()
            .put("sessionId", stringProp("Optional workspace session id."))
            .put("backupId", stringProp("Backup id returned by workspace tools."))
            .put("path", stringProp("Optional restore target path."))
    )

    private fun importBookSourceDefinition() = functionDef(
        TOOL_IMPORT_BOOK_SOURCE,
        "Import an existing local Legado book source into the AI workspace as JSON.",
        JSONObject()
            .put("sessionId", stringProp("Optional workspace session id."))
            .put("bookSourceUrl", stringProp("Exact source URL."))
            .put("searchKey", stringProp("Search by source name or URL."))
            .put("path", stringProp("Optional target file path."))
    )

    private fun createBookSourceFileDefinition() = functionDef(
        TOOL_CREATE_BOOK_SOURCE_FILE,
        "Create a Legado book source JSON file in the AI workspace without saving it to the database.",
        JSONObject()
            .put("sessionId", stringProp("Optional workspace session id."))
            .put("path", stringProp("Optional target file path."))
            .put("sourceJson", stringProp("Full BookSource JSON."))
            .put("bookSourceUrl", stringProp("Book source URL for minimal creation."))
            .put("bookSourceName", stringProp("Book source name for minimal creation."))
            .put("bookSourceGroup", stringProp("Book source group."))
            .put("searchUrl", stringProp("Search URL rule."))
            .put("backup", stringProp("auto, true, or false."))
    )

    private fun debugBookSourceDefinition() = functionDef(
        TOOL_DEBUG_BOOK_SOURCE,
        "Debug a Legado book source JSON file from the AI workspace using the native debug flow.",
        JSONObject()
            .put("sessionId", stringProp("Optional workspace session id."))
            .put("path", stringProp("Workspace source JSON path."))
            .put("key", stringProp("Debug key, detail URL, ++toc URL, or --content URL."))
            .put("timeoutMs", JSONObject().put("type", "integer"))
    )

    private fun applyBookSourceDefinition() = functionDef(
        TOOL_APPLY_BOOK_SOURCE,
        "Parse a workspace source JSON file and save it to the local book source database. Existing database source is copied to workspace backup first.",
        JSONObject()
            .put("sessionId", stringProp("Optional workspace session id."))
            .put("path", stringProp("Workspace source JSON path."))
    )

    private fun functionDef(name: String, description: String, properties: JSONObject): JSONObject {
        return JSONObject().apply {
            put("type", "function")
            put("function", JSONObject().apply {
                put("name", name)
                put("description", description)
                put("parameters", JSONObject().apply {
                    put("type", "object")
                    put("properties", properties)
                    put("additionalProperties", false)
                })
            })
        }
    }
}
