package io.legado.app.model.localBook.epubcore.font

import io.legado.app.constant.AppLog
import io.legado.app.model.localBook.epubcore.EpubRegex
import io.legado.app.model.localBook.epubcore.archive.EpubArchive
import io.legado.app.model.localBook.epubcore.archive.EpubPath
import io.legado.app.model.localBook.epubcore.pkg.XmlTools
import io.legado.app.model.localBook.epubcore.pkg.attr
import io.legado.app.model.localBook.epubcore.pkg.elements
import java.io.FilterInputStream
import java.io.InputStream
import java.security.MessageDigest

/**
 * EPUB 内嵌字体去混淆（迁移自 archive v15 `epubcore/font/EpubFontDeobfuscatingArchive.kt`，算法未改）。
 *
 * 背景：部分 EPUB 会按出版规范对**内嵌字体**做"混淆"（obfuscation）——即在字体文件**前缀**若干字节上
 * 与一个由全书标识派生的密钥做循环 XOR。换言之，字体文件**本身不可直接使用**，必须先还原前缀。
 * 本仓此前 `font/` 只有 4 个**解析**文件、无去混淆 ⇒ 这类书的内嵌字体一律加载失败（观感为"乱码/回退字体"）。
 *
 * 两种算法（前缀长度与密钥派生不同）：
 * - **IDPF**（`http://www.idpf.org/2008/embedding`）：密钥 = `SHA-1(唯一标识的 UTF-8 字节)`（**去空白**后），前缀 1040 字节；
 * - **Adobe**（`http://ns.adobe.com/pdf/enc#RC`）：密钥 = `唯一标识` 去 `urn:uuid:` 前缀与 `-` 后的 **32 位十六进制** 字节，前缀 1024 字节。
 *
 * 诡计：`<EncryptedData>` 只承载"算法 + 被加密资源的 URI"，**不带密钥本体**（密钥由标识推导）。
 * 标识本体来自 OPF metadata 的 `identifier`，故 [wrap] 需要调用方在**解析完包之后**传入。
 *
 * 装饰器语义：除被混淆条目外**完全透传**（`exists`/`list`/`canonicalPath`/`entrySize`/`close` 直接委派），
 * 未识别算法 / 读不到 `encryption.xml` / 标识缺失 ⇒ **原样返回 delegate**（不改变既有行为）。
 */
internal class EpubFontDeobfuscatingArchive private constructor(
    private val delegate: EpubArchive,
    private val transformations: Map<String, Transformation>
) : EpubArchive {

    override fun exists(path: String): Boolean = delegate.exists(path)

    override fun list(): List<String> = delegate.list()

    override fun canonicalPath(path: String): String? = delegate.canonicalPath(path)

    override fun readBytes(path: String, maxBytes: Long): ByteArray {
        val bytes = delegate.readBytes(path, maxBytes)
        transformation(path)?.apply(bytes)
        return bytes
    }

    override fun entrySize(path: String): Long? = delegate.entrySize(path)

    override fun openStream(path: String): InputStream {
        val source = delegate.openStream(path)
        val transformation = transformation(path) ?: return source
        return XorPrefixInputStream(source, transformation.key, transformation.prefixLength)
    }

    override fun close() = delegate.close()

    /** 命中判定必须先归一（去 fragment/query + 大小写）再查表，否则 `x.otf#id` 之类引用会漏。 */
    private fun transformation(path: String): Transformation? {
        val canonical = delegate.canonicalPath(EpubPath.stripFragment(path)) ?: return null
        return transformations[canonical]
    }

    private data class Transformation(
        val key: ByteArray,
        val prefixLength: Int
    ) {
        fun apply(bytes: ByteArray) {
            repeat(minOf(prefixLength, bytes.size)) { index ->
                bytes[index] = (bytes[index].toInt() xor key[index % key.size].toInt()).toByte()
            }
        }
    }

    /**
     * 流式 XOR 前缀还原。
     *
     * ⚠️ 只还原**前缀（`position < prefixLength`）**，其后字节原样透传——`read(byte[])` 重载按
     * "本次读取区间与剩余前缀的交集"计算还原长度，避免一次性把整个字体读进内存。
     * ⚠️ `skip`/`mark`/`reset` 必须同步维护 `position`，否则 WebView 分段读字体时前缀会被二次/漏还原
     * （表现为"字体时好时坏"，极难定位）。
     */
    private class XorPrefixInputStream(
        source: InputStream,
        private val key: ByteArray,
        private val prefixLength: Int
    ) : FilterInputStream(source) {

        private var position = 0L
        private var markedPosition = -1L

        override fun read(): Int {
            val value = super.read()
            if (value < 0) return value
            val decoded = if (position < prefixLength) {
                value xor (key[(position % key.size).toInt()].toInt() and 0xff)
            } else {
                value
            }
            position++
            return decoded
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            val count = super.read(buffer, offset, length)
            if (count <= 0) return count
            val transformed = minOf(count.toLong(), (prefixLength - position).coerceAtLeast(0L)).toInt()
            repeat(transformed) { relativeIndex ->
                val absolutePosition = position + relativeIndex
                val bufferIndex = offset + relativeIndex
                buffer[bufferIndex] = (
                    buffer[bufferIndex].toInt() xor
                        key[(absolutePosition % key.size).toInt()].toInt()
                    ).toByte()
            }
            position += count
            return count
        }

        override fun skip(byteCount: Long): Long {
            return super.skip(byteCount).also { position += it }
        }

        @Synchronized
        override fun mark(readLimit: Int) {
            super.mark(readLimit)
            markedPosition = position
        }

        @Synchronized
        override fun reset() {
            super.reset()
            if (markedPosition >= 0L) position = markedPosition
        }
    }

    companion object {
        private const val ENCRYPTION_PATH = "META-INF/encryption.xml"
        private const val MAX_ENCRYPTION_BYTES = 1L * 1024L * 1024L
        private const val IDPF_ALGORITHM = "http://www.idpf.org/2008/embedding"
        private const val ADOBE_ALGORITHM = "http://ns.adobe.com/pdf/enc#RC"
        private const val IDPF_PREFIX_LENGTH = 1040
        private const val ADOBE_PREFIX_LENGTH = 1024
        private val SCHEME = EpubRegex.compile("^[a-zA-Z][a-zA-Z0-9+.-]*:")
        private val UUID_PREFIX = EpubRegex.compile("^urn:uuid:", RegexOption.IGNORE_CASE)
        private val XML_WHITESPACE = setOf(' ', '\t', '\r', '\n')

        /**
         * 若 [delegate] 含 `META-INF/encryption.xml` 且声明了可识别算法，则返回包装后的 archive；
         * 否则**原样返回** [delegate]（调用方无需分支判断）。
         *
         * @param publicationIdentifier OPF metadata 中的唯一标识（`identifier`）；为空则无法派生密钥 ⇒ 不包装。
         */
        fun wrap(delegate: EpubArchive, publicationIdentifier: String?): EpubArchive {
            val encryptionPath = delegate.canonicalPath(ENCRYPTION_PATH) ?: return delegate
            val document = runCatching {
                XmlTools.parse(delegate.readBytes(encryptionPath, MAX_ENCRYPTION_BYTES))
            }.getOrElse { error ->
                AppLog.putDebug("EPUB font encryption metadata could not be parsed", error)
                return delegate
            }
            // IDPF 密钥基于"去空白后的标识"；Adobe 密钥基于 UUID 的十六进制部分。
            val normalizedIdentifier = publicationIdentifier
                ?.filterNot(XML_WHITESPACE::contains)
                ?.takeIf { it.isNotEmpty() }
            val idpfKey by lazy {
                normalizedIdentifier?.let { identifier ->
                    MessageDigest.getInstance("SHA-1").digest(identifier.toByteArray(Charsets.UTF_8))
                }
            }
            val adobeKey by lazy { normalizedIdentifier?.let(::adobeKey) }
            val transformations = LinkedHashMap<String, Transformation>()
            document.elements("EncryptedData").forEach { encryptedData ->
                val algorithm = encryptedData.elements("EncryptionMethod")
                    .firstOrNull()
                    ?.let { it.attr("Algorithm") ?: it.attr("algorithm") }
                    ?.trim()
                    ?: return@forEach
                val transformation = when (algorithm) {
                    IDPF_ALGORITHM -> idpfKey?.let { Transformation(it, IDPF_PREFIX_LENGTH) }
                    ADOBE_ALGORITHM -> adobeKey?.let { Transformation(it, ADOBE_PREFIX_LENGTH) }
                    else -> null
                } ?: return@forEach
                val uri = encryptedData.elements("CipherReference")
                    .firstOrNull()
                    ?.let { it.attr("URI") ?: it.attr("uri") }
                    ?.trim()
                    ?: return@forEach
                val path = archivePath(delegate, uri) ?: return@forEach
                transformations.putIfAbsent(path, transformation)
            }
            return if (transformations.isEmpty()) {
                delegate
            } else {
                EpubFontDeobfuscatingArchive(delegate, transformations)
            }
        }

        /** 被加密条目的 URI（相对包根）→ archive 内真实条目路径；外部/绝对 URL 一律拒。 */
        private fun archivePath(archive: EpubArchive, uri: String): String? {
            if (uri.isBlank() || uri.startsWith("//") || SCHEME.containsMatchIn(uri)) return null
            val normalized = EpubPath.stripFragment(EpubPath.resolve("", uri))
                .takeIf { it.isNotBlank() } ?: return null
            return archive.canonicalPath(normalized)
        }

        private fun adobeKey(identifier: String): ByteArray? {
            val hex = identifier
                .replace(UUID_PREFIX, "")
                .replace("-", "")
            if (hex.length != 32 || hex.any { it.digitToIntOrNull(16) == null }) return null
            return ByteArray(16) { index ->
                hex.substring(index * 2, index * 2 + 2).toInt(16).toByte()
            }
        }
    }
}