package io.legado.app.utils

import java.io.File
import java.io.InputStream
import java.security.MessageDigest

/**
 * R20（B4）UTF-8 SHA-256 统一工具（字符串 / 字节 / **流式**）。
 *
 * 动因：仓内多处各写一份 `MessageDigest.getInstance("SHA-256").digest(x.toByteArray())` +
 * `joinToString("%02x".format(it))`：
 * - 大输入（Lottie 模板 JSON、AI 工作区写入的文本/文件）会**一次性物化整段字节**；
 * - `%02x`.format 逐字节格式化，是纯 CPU 开销（同尺寸下比查表慢一个量级）。
 *
 * 本工具提供三条通道，摘要口径与旧实现**逐位一致**（单测与旧实现对照固化）：
 * - [hex]（String/ByteArray）：与旧实现等价，仅去掉格式化开销；
 * - [hex]（InputStream/File）：**分块 update**，峰值内存 ≈ [BUFFER_SIZE]（与输入大小无关）。
 *
 * 编码口径：`String` 一律按 **UTF-8**（Kotlin `toByteArray()` 的默认编码，与改造前一致）。
 */
object Utf8Sha256 {

    /** 流式分块大小（8KB：优于 4KB 页的对齐，且远小于任何实际载荷） */
    const val BUFFER_SIZE = 8 * 1024

    private val HEX_CHARS = "0123456789abcdef".toCharArray()

    fun hex(text: String): String = hex(text.toByteArray(Charsets.UTF_8))

    fun hex(bytes: ByteArray): String = toHex(MessageDigest.getInstance("SHA-256").digest(bytes))

    /**
     * 流式摘要：按 [bufferSize] 分块读取，**不物化整个输入**。
     *
     * 调用方负责关闭流（或使用 [hex] 的 `File` 重载）。
     */
    fun hex(input: InputStream, bufferSize: Int = BUFFER_SIZE): String {
        require(bufferSize > 0) { "bufferSize 必须为正数" }
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(bufferSize)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            if (read > 0) digest.update(buffer, 0, read)
        }
        return toHex(digest.digest())
    }

    /** 流式摘要（文件）：内部负责流的关闭。 */
    fun hex(file: File): String = file.inputStream().use { hex(it) }

    /** 小写十六进制（长度恒 64）；查表实现，避免 `String.format` 的格式化开销。 */
    private fun toHex(bytes: ByteArray): String {
        val out = CharArray(bytes.size * 2)
        for (index in bytes.indices) {
            val value = bytes[index].toInt() and 0xFF
            out[index * 2] = HEX_CHARS[value ushr 4]
            out[index * 2 + 1] = HEX_CHARS[value and 0x0F]
        }
        return String(out)
    }
}