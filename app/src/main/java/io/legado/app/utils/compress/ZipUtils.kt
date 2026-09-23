package io.legado.app.utils.compress

import android.annotation.SuppressLint
import io.legado.app.utils.DebugLog
import io.legado.app.utils.compress.ZipUtils.zipFile
import io.legado.app.utils.printOnDebug
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.zip.GZIPOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

@SuppressLint("ObsoleteSdkInt")
@Suppress("unused", "MemberVisibilityCanBePrivate")
object ZipUtils {

    /** 限量拷贝的块大小（8KB） */
    private const val COPY_BUFFER_SIZE = 8 * 1024

    fun gzipByteArray(byteArray: ByteArray): ByteArray {
        val byteOut = ByteArrayOutputStream()
        val zip = GZIPOutputStream(byteOut)
        return zip.use {
            it.write(byteArray)
            byteOut.use {
                byteOut.toByteArray()
            }
        }
    }

    fun zipByteArray(byteArray: ByteArray, fileName: String): ByteArray {
        val byteOut = ByteArrayOutputStream()
        val zipOutputStream = ZipOutputStream(byteOut)
        zipOutputStream.putNextEntry(ZipEntry(fileName))
        zipOutputStream.write(byteArray)
        zipOutputStream.closeEntry()
        zipOutputStream.finish()
        return zipOutputStream.use {
            byteOut.use {
                byteOut.toByteArray()
            }
        }
    }

    /**
     * Zip the files.
     *
     * @param srcFiles    The source of files.
     * @param zipFilePath The path of ZIP file.
     * @return `true`: success<br></br>`false`: fail
     * @throws IOException if an I/O error has occurred
     */
    suspend fun zipFiles(
        srcFiles: Collection<String>,
        zipFilePath: String
    ): Boolean {
        return zipFiles(srcFiles, zipFilePath, null)
    }

    /**
     * Zip the files.
     *
     * @param srcFilePaths The paths of source files.
     * @param zipFilePath  The path of ZIP file.
     * @param comment      The comment.
     * @return `true`: success<br></br>`false`: fail
     * @throws IOException if an I/O error has occurred
     */
    suspend fun zipFiles(
        srcFilePaths: Collection<String>?,
        zipFilePath: String?,
        comment: String?
    ): Boolean = withContext(IO) {
        if (srcFilePaths == null || zipFilePath == null) return@withContext false
        ZipOutputStream(FileOutputStream(zipFilePath)).use {
            for (srcFile in srcFilePaths) {
                if (!zipFile(getFileByPath(srcFile)!!, "", it, comment))
                    return@withContext false
            }
            return@withContext true
        }
    }

    /**
     * Zip the files.
     *
     * @param srcFiles The source of files.
     * @param zipFile  The ZIP file.
     * @param comment  The comment.
     * @return `true`: success<br></br>`false`: fail
     * @throws IOException if an I/O error has occurred
     */
    @Throws(IOException::class)
    @JvmOverloads
    fun zipFiles(
        srcFiles: Collection<File>?,
        zipFile: File?,
        comment: String? = null
    ): Boolean {
        if (srcFiles == null || zipFile == null) return false
        ZipOutputStream(FileOutputStream(zipFile)).use {
            for (srcFile in srcFiles) {
                if (!zipFile(srcFile, "", it, comment)) return false
            }
            return true
        }
    }

    /**
     * Zip the file.
     *
     * @param srcFilePath The path of source file.
     * @param zipFilePath The path of ZIP file.
     * @return `true`: success<br></br>`false`: fail
     * @throws IOException if an I/O error has occurred
     */
    @Throws(IOException::class)
    fun zipFile(
        srcFilePath: String,
        zipFilePath: String
    ): Boolean {
        return zipFile(getFileByPath(srcFilePath), getFileByPath(zipFilePath), null)
    }

    /**
     * Zip the file.
     *
     * @param srcFilePath The path of source file.
     * @param zipFilePath The path of ZIP file.
     * @param comment     The comment.
     * @return `true`: success<br></br>`false`: fail
     * @throws IOException if an I/O error has occurred
     */
    @Throws(IOException::class)
    fun zipFile(
        srcFilePath: String,
        zipFilePath: String,
        comment: String
    ): Boolean {
        return zipFile(getFileByPath(srcFilePath), getFileByPath(zipFilePath), comment)
    }

    /**
     * Zip the file.
     *
     * @param srcFile The source of file.
     * @param zipFile The ZIP file.
     * @param comment The comment.
     * @return `true`: success<br></br>`false`: fail
     * @throws IOException if an I/O error has occurred
     */
    @Throws(IOException::class)
    @JvmOverloads
    fun zipFile(
        srcFile: File?,
        zipFile: File?,
        comment: String? = null
    ): Boolean {
        if (srcFile == null || zipFile == null) return false
        ZipOutputStream(FileOutputStream(zipFile)).use { zos ->
            return zipFile(srcFile, "", zos, comment)
        }
    }

    @Throws(IOException::class)
    private fun zipFile(
        srcFile: File,
        rootPath: String,
        zos: ZipOutputStream,
        comment: String?
    ): Boolean {
        var rootPath1 = rootPath
        if (!srcFile.exists()) return true
        rootPath1 = rootPath1 + (if (isSpace(rootPath1)) "" else File.separator) + srcFile.name
        if (srcFile.isDirectory) {
            val fileList = srcFile.listFiles()
            if (fileList == null || fileList.isEmpty()) {
                val entry = ZipEntry("$rootPath1/")
                entry.comment = comment
                zos.putNextEntry(entry)
                zos.closeEntry()
            } else {
                for (file in fileList) {
                    if (!zipFile(file, rootPath1, zos, comment)) return false
                }
            }
        } else {
            BufferedInputStream(FileInputStream(srcFile)).use {
                val entry = ZipEntry(rootPath1)
                entry.comment = comment
                zos.putNextEntry(entry)
                it.copyTo(zos)
                zos.closeEntry()
            }
        }
        return true
    }

    /**
     * R21（B4）解压防护阈值（**双阈值 + 条目数**）。
     *
     * 定档依据（真实样本实测，2026-09-24）：
     * - 仓内最大真实样本 = 主题包（5 条目 / 解压后 2.3MB / 单条目最大 1.3MB）；
     * - 完整备份包规模上限取决于书库（DB + 书源 + 高亮 JSON），重库场景实测在数百 MB 量级；
     * ⇒ 阈值取「真实样本 × 百倍以上」的宽松上界：单条目 [DEFAULT_MAX_ENTRY_BYTES]、总量
     * [DEFAULT_MAX_TOTAL_BYTES]、条目数 [DEFAULT_MAX_ENTRIES]。既不会误伤正常包，
     * 又能让「压缩炸弹」（如数十 KB 膨胀到数 GB）在写盘前 fail-fast。
     *
     * 已知上限：阈值是**绝对量**（非压缩比），故高压缩比但总量小的正常包不会被挡；
     * 升级路径：如需更严，可改「压缩比 + 绝对量」双条件（需先统计正常包的压缩比分布）。
     */
    data class UnzipLimits(
        val maxEntryBytes: Long = DEFAULT_MAX_ENTRY_BYTES,
        val maxTotalBytes: Long = DEFAULT_MAX_TOTAL_BYTES,
        val maxEntries: Int = DEFAULT_MAX_ENTRIES
    )

    const val DEFAULT_MAX_ENTRY_BYTES: Long = 256L * 1024 * 1024
    const val DEFAULT_MAX_TOTAL_BYTES: Long = 1024L * 1024 * 1024
    const val DEFAULT_MAX_ENTRIES: Int = 20_000

    /** 默认阈值（调用方可传更严的 [UnzipLimits]，多用于测试与受限导入场景） */
    val DEFAULT_UNZIP_LIMITS = UnzipLimits()

    @Throws(SecurityException::class)
    fun unZipToPath(
        file: File,
        path: String,
        filter: ((String) -> Boolean)? = null,
        limits: UnzipLimits = DEFAULT_UNZIP_LIMITS
    ): List<File> {
        return FileInputStream(file).use {
            unZipToPath(it, path, filter, limits)
        }
    }

    @Throws(SecurityException::class)
    fun unZipToPath(
        file: File,
        dir: File,
        filter: ((String) -> Boolean)? = null,
        limits: UnzipLimits = DEFAULT_UNZIP_LIMITS
    ): List<File> {
        return FileInputStream(file).use {
            unZipToPath(it, dir, filter, limits)
        }
    }

    @Throws(SecurityException::class)
    fun unZipToPath(
        inputStream: InputStream,
        path: String,
        filter: ((String) -> Boolean)? = null,
        limits: UnzipLimits = DEFAULT_UNZIP_LIMITS
    ): List<File> {
        return ZipInputStream(inputStream).use {
            unZipToPath(it, File(path), filter, limits)
        }
    }

    @Throws(SecurityException::class)
    fun unZipToPath(
        inputStream: InputStream,
        dir: File,
        filter: ((String) -> Boolean)? = null,
        limits: UnzipLimits = DEFAULT_UNZIP_LIMITS
    ): List<File> {
        return ZipInputStream(inputStream).use {
            unZipToPath(it, dir, filter, limits)
        }
    }

    /**
     * 解压核心（**所有解压调用点的唯一出口**）：ZipSlip 拦截 + [UnzipLimits] 三重防护。
     *
     * 防护点：
     * ① 路径逃逸（ZipSlip）：`canonicalPath` 必须落在目标目录内（原有行为，不回归）；
     * ② 条目数上限：防空包（海量空条目）耗尽 inode；
     * ③ 单条目体积上限：**声明值 + 实际写入量**双向拦截（声明 size=-1/撒谎也拦得住）；
     * ④ 总体积上限：累计写入量超限即中止。
     *
     * ⚠ 超限抛 [SecurityException]（与 ZipSlip 同类型，便于调用方统一处理）；
     * 中止时可能留已写出的部分文件（fail-fast 优先于回滚，目标目录由调用方清理）。
     */
    @Throws(SecurityException::class)
    private fun unZipToPath(
        zipInputStream: ZipInputStream,
        dir: File,
        filter: ((String) -> Boolean)? = null,
        limits: UnzipLimits = DEFAULT_UNZIP_LIMITS
    ): List<File> {
        val files = arrayListOf<File>()
        var entry: ZipEntry?
        var entryCount = 0
        var totalBytes = 0L
        while (zipInputStream.nextEntry.also { entry = it } != null) {
            val entryName = entry!!.name
            entryCount++
            if (entryCount > limits.maxEntries) {
                throw SecurityException("压缩包条目数超过上限（${limits.maxEntries}）")
            }
            val entryFile = File(dir, entryName)
            if (!entryFile.canonicalPath.startsWith(dir.canonicalPath)) {
                throw SecurityException("压缩文件只能解压到指定路径")
            }
            if (entry.isDirectory) {
                if (!entryFile.exists()) {
                    entryFile.mkdirs()
                }
                continue
            }
            if (entryFile.parentFile?.exists() != true) {
                entryFile.parentFile?.mkdirs()
            }
            if (filter != null && !filter.invoke(entryName)) continue
            // ③ 声明体积先拦（不写盘即可拒绝）
            if (entry.size > limits.maxEntryBytes) {
                throw SecurityException("压缩包单条目体积超过上限（${limits.maxEntryBytes} 字节）")
            }
            if (!entryFile.exists()) {
                entryFile.createNewFile()
                entryFile.setReadable(true)
                entryFile.setExecutable(true)
            }
            FileOutputStream(entryFile).use { out ->
                // ③④ 实际写入量兜底（声明 size 不可信时仍然拦得住）
                val written = copyWithLimit(
                    zipInputStream,
                    out,
                    singleLimit = limits.maxEntryBytes,
                    remainingTotal = limits.maxTotalBytes - totalBytes
                )
                totalBytes += written
            }
            files.add(entryFile)
        }
        return files
    }

    /**
     * 限量拷贝：逐块写入并在超限时抛 [SecurityException]。
     *
     * @param remainingTotal 允许写入的剩余总量（≤0 直接拒绝）
     * @return 实际写入字节数
     */
    @Throws(SecurityException::class)
    private fun copyWithLimit(
        input: InputStream,
        output: java.io.OutputStream,
        singleLimit: Long,
        remainingTotal: Long
    ): Long {
        if (remainingTotal <= 0L) {
            throw SecurityException("压缩包解压总量超过上限")
        }
        var written = 0L
        val buffer = ByteArray(COPY_BUFFER_SIZE)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            if (read == 0) continue
            written += read
            if (written > singleLimit) {
                throw SecurityException("压缩包单条目体积超过上限（$singleLimit 字节）")
            }
            if (written > remainingTotal) {
                throw SecurityException("压缩包解压总量超过上限")
            }
            output.write(buffer, 0, read)
        }
        return written
    }

    /* 遍历目录获取所有文件名 */
    @Throws(SecurityException::class)
    fun getFilesName(
        inputStream: InputStream,
        filter: ((String) -> Boolean)? = null
    ): List<String> {
        return ZipInputStream(inputStream).use {
            getFilesName(it, filter)
        }
    }

    @Throws(SecurityException::class)
    private fun getFilesName(
        zipInputStream: ZipInputStream,
        filter: ((String) -> Boolean)? = null
    ): List<String> {
        val fileNames = mutableListOf<String>()
        var entry: ZipEntry?
        while (zipInputStream.nextEntry.also { entry = it } != null) {
            if (entry!!.isDirectory) {
                continue
            }
            val fileName = entry.name
            if (filter != null && filter.invoke(fileName))
                fileNames.add(fileName)
        }
        return fileNames
    }

    /**
     * Return the files' path in ZIP file.
     *
     * @param zipFilePath The path of ZIP file.
     * @return the files' path in ZIP file
     * @throws IOException if an I/O error has occurred
     */
    @Throws(IOException::class)
    fun getFilesPath(zipFilePath: String): List<String>? {
        return getFilesPath(getFileByPath(zipFilePath))
    }

    /**
     * Return the files' path in ZIP file.
     *
     * @param zipFile The ZIP file.
     * @return the files' path in ZIP file
     * @throws IOException if an I/O error has occurred
     */
    @Throws(IOException::class)
    fun getFilesPath(zipFile: File?): List<String>? {
        if (zipFile == null) return null
        val paths = ArrayList<String>()
        val zip = ZipFile(zipFile)
        val entries = zip.entries()
        while (entries.hasMoreElements()) {
            val entryName = (entries.nextElement() as ZipEntry).name
            if (entryName.contains("../")) {
                DebugLog.e(javaClass.name, "entryName: $entryName is dangerous!")
                paths.add(entryName)
            } else {
                paths.add(entryName)
            }
        }
        zip.close()
        return paths
    }

    /**
     * Return the files' comment in ZIP file.
     *
     * @param zipFilePath The path of ZIP file.
     * @return the files' comment in ZIP file
     * @throws IOException if an I/O error has occurred
     */
    @Throws(IOException::class)
    fun getComments(zipFilePath: String): List<String>? {
        return getComments(getFileByPath(zipFilePath))
    }

    /**
     * Return the files' comment in ZIP file.
     *
     * @param zipFile The ZIP file.
     * @return the files' comment in ZIP file
     * @throws IOException if an I/O error has occurred
     */
    @Throws(IOException::class)
    fun getComments(zipFile: File?): List<String>? {
        if (zipFile == null) return null
        val comments = ArrayList<String>()
        val zip = ZipFile(zipFile)
        val entries = zip.entries()
        while (entries.hasMoreElements()) {
            val entry = entries.nextElement() as ZipEntry
            comments.add(entry.comment)
        }
        zip.close()
        return comments
    }

    private fun createOrExistsDir(file: File?): Boolean {
        return file != null && if (file.exists()) file.isDirectory else file.mkdirs()
    }

    private fun createOrExistsFile(file: File?): Boolean {
        if (file == null) return false
        if (file.exists()) return file.isFile
        if (!createOrExistsDir(file.parentFile)) return false
        return try {
            file.createNewFile()
        } catch (e: IOException) {
            e.printOnDebug()
            false
        }
    }

    private fun getFileByPath(filePath: String): File? {
        return if (isSpace(filePath)) null else File(filePath)
    }

    private fun isSpace(s: String?): Boolean {
        if (s == null) return true
        var i = 0
        val len = s.length
        while (i < len) {
            if (!Character.isWhitespace(s[i])) {
                return false
            }
            ++i
        }
        return true
    }
}