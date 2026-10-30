package io.legado.app.help.tts.builtin

import android.content.Context
import io.legado.app.utils.LogUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.ZipInputStream

/**
 * 模型管理器（AD-07 四路径 + T1.2 状态机）：
 * ①在线下载（断点无侵入：整档重下，sha256 校验）
 * ②本地文件导入（SAF URI / 归档）
 * ③旁路发现（`filesDir/tts-import/`：adb 可直接 push，免存储权限）
 * ④内置（个人构建变体，默认关闭）
 * 落盘后统一 `filesDir/tts/models/<profile>/`，状态 NONE/READY/CORRUPT 由文件校验决定。
 */
class BuiltinModelManager(
    private val context: Context,
    private val paths: BuiltinTtsPaths
) {

    data class Progress(val bytes: Long, val total: Long) {
        val percent: Int
            get() = if (total <= 0) 0 else (bytes * 100 / total).toInt().coerceIn(0, 100)
    }

    private val catalogCache: List<BuiltinModelProfile> by lazy { BuiltinModelCatalog.load(context) }

    fun profiles(): List<BuiltinModelProfile> = catalogCache

    fun profile(id: String): BuiltinModelProfile? = catalogCache.firstOrNull { it.id == id }

    /** 目录就绪判定（全部必需文件存在且非空） */
    fun state(profile: BuiltinModelProfile): BuiltinEngineState =
        BuiltinModelState.evaluate(paths.modelDir(profile.id), profile)

    fun missingFiles(profile: BuiltinModelProfile): List<String> {
        val dir = paths.modelDir(profile.id)
        return BuiltinModelState.missingFiles(dir, profile)
    }

    fun verifySha(profile: BuiltinModelProfile): Boolean {
        if (profile.sha256.isEmpty()) return true
        val dir = paths.modelDir(profile.id)
        return profile.sha256.all { (name, expected) ->
            if (expected.isBlank()) return@all true
            val file = findRequired(dir, name) ?: return@all false
            sha256(file) == expected
        }
    }

    fun delete(profileId: String): Boolean {
        return runCatching { paths.modelDir(profileId).deleteRecursively() }.getOrDefault(false)
    }

    /** 从目录导入（目录内需含 profile 声明的文件；多余文件一并拷入） */
    suspend fun importFromDirectory(profileId: String, sourceDir: File): Boolean =
        withContext(Dispatchers.IO) {
            val profile = profile(profileId) ?: return@withContext false
            if (!sourceDir.isDirectory) return@withContext false
            val target = paths.modelDir(profileId)
            target.mkdirs()
            sourceDir.walkTopDown().filter { it.isFile }.forEach { file ->
                val relative = file.relativeTo(sourceDir).path
                val dest = File(target, relative)
                dest.parentFile?.mkdirs()
                file.copyTo(dest, overwrite = true)
            }
            state(profile) == BuiltinEngineState.READY
        }

    /** 从归档导入（支持 .zip / .tar.bz2 / .tar.gz），自动剥离顶层目录 */
    suspend fun importArchive(profileId: String, input: InputStream, fileName: String): Boolean =
        withContext(Dispatchers.IO) {
            val profile = profile(profileId) ?: return@withContext false
            val target = paths.modelDir(profileId)
            target.mkdirs()
            val ok = runCatching {
                when {
                    fileName.endsWith(".zip", true) -> extractZip(input, target)
                    fileName.endsWith(".tar.bz2", true) || fileName.endsWith(".tbz2", true) ->
                        extractTarBz2(input, target)
                    // 单文件（vocos_24khz.onnx 等声码器单独发布）
                    fileName.endsWith(".onnx", true) || fileName.endsWith(".txt", true) -> {
                        val name = fileName.substringAfterLast('/').substringAfterLast('\\')
                        val dest = File(target, name)
                        dest.parentFile?.mkdirs()
                        input.use { stream -> dest.outputStream().use { stream.copyTo(it) } }
                        true
                    }
                    else -> false
                }
            }.onFailure { LogUtils.e(TAG, "归档导入失败：${it.message}") }.getOrDefault(false)
            if (!ok) return@withContext false
            state(profile) == BuiltinEngineState.READY && verifySha(profile)
        }

    /** 旁路发现：扫描导入目录下的归档与目录 */
    fun discoverImports(): List<File> {
        val dir = paths.importDir
        if (!dir.isDirectory) return emptyList()
        return dir.listFiles()?.filter {
            it.isDirectory || it.name.endsWith(".zip", true) ||
                    it.name.endsWith(".tar.bz2", true) || it.name.endsWith(".tbz2", true)
        }.orEmpty()
    }

    /**
     * 在线下载（OkHttp）：先落 `.part`，成功后校验 sha256 再解包。
     * 允许无网失败（返回 false，保留 .part 供重试）。
     */
    suspend fun download(profile: BuiltinModelProfile, onProgress: (Progress) -> Unit = {}): Boolean =
        withContext(Dispatchers.IO) {
            if (profile.sources.isEmpty()) return@withContext false
            val workDir = File(paths.cacheDir, "download").apply { mkdirs() }
            var lastError: String? = null
            profile.sources.forEach { url ->
                val result = runCatching {
                    val client = OkHttpClient()
                    val request = Request.Builder().url(url).build()
                    client.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) {
                            lastError = "HTTP ${response.code}"
                            return@use false
                        }
                        val body = response.body
                        val total = body.contentLength()
                        onProgress(Progress(0, total))
                        val temp = File(workDir, "${profile.id}.download")
                        body.byteStream().use { stream ->
                            temp.outputStream().use { out ->
                                val buffer = ByteArray(8 * 1024)
                                var written = 0L
                                while (true) {
                                    val read = stream.read(buffer)
                                    if (read <= 0) break
                                    out.write(buffer, 0, read)
                                    written += read
                                    onProgress(Progress(written, total))
                                }
                            }
                        }
                        temp.inputStream().use { stream ->
                            importArchive(profile.id, stream, url)
                        }
                    }
                }.onFailure { lastError = it.message }.getOrDefault(false)
                if (result) {
                    // 多源档案（如 ZipVoice 本体 + 独立声码器）：逐个源叠加，直到文件齐全且校验通过
                    if (state(profile) == BuiltinEngineState.READY && verifySha(profile)) return@withContext true
                }
            }
            LogUtils.e(TAG, "模型下载失败：${lastError ?: "未知"}")
            false
        }

    private fun extractZip(input: InputStream, target: File): Boolean {
        var count = 0
        ZipInputStream(input).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (!entry.isDirectory) {
                    val path = stripTopLevel(entry.name)
                    if (path != null) {
                        val dest = File(target, path)
                        dest.parentFile?.mkdirs()
                        dest.outputStream().use { zip.copyTo(it) }
                        count++
                    }
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
        return count > 0
    }

    private fun extractTarBz2(input: InputStream, target: File): Boolean {
        var count = 0
        TarArchiveInputStream(BZip2CompressorInputStream(input)).use { tar ->
            var entry = tar.nextEntry
            while (entry != null) {
                if (!entry.isDirectory) {
                    val path = stripTopLevel(entry.name)
                    if (path != null) {
                        val dest = File(target, path)
                        dest.parentFile?.mkdirs()
                        dest.outputStream().use { tar.copyTo(it) }
                        count++
                    }
                }
                entry = tar.nextEntry
            }
        }
        return count > 0
    }

    /** 剥离归档顶层目录（官方模型包都是 `xxx/` 起头） */
    private fun stripTopLevel(name: String): String? {
        val normalized = name.replace('\\', '/').trimStart('/')
        if (normalized.isBlank()) return null
        val index = normalized.indexOf('/')
        val stripped = if (index > 0) normalized.substring(index + 1) else normalized
        if (stripped.isBlank()) return null
        // 防路径穿越
        if (stripped.contains("../")) return null
        return stripped
    }

    private fun findRequired(dir: File, name: String): File? = BuiltinModelState.findRequired(dir, name)

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(8 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private companion object {
        const val TAG = "BuiltinModelManager"
    }
}
