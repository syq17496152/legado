package io.legado.app.utils

import android.text.TextUtils
import io.legado.app.lib.icu4j.CharsetDetector
import org.jsoup.Jsoup
import java.io.File
import java.nio.charset.Charset

/**
 * 自动获取文件的编码
 * */
@Suppress("MemberVisibilityCanBePrivate", "unused")
object EncodingDetect {

    private const val DEFAULT_ENCODE = "UTF-8"
    private const val GB18030 = "GB18030"

    private val headTagRegex = "(?i)<head>[\\s\\S]*?</head>".toRegex()
    private val headOpenBytes = "<head>".toByteArray()
    private val headCloseBytes = "</head>".toByteArray()

    fun getHtmlEncode(bytes: ByteArray): String {
        try {
            var head: String? = null
            val startIndex = bytes.indexOf(headOpenBytes)
            if (startIndex > -1) {
                val endIndex = bytes.indexOf(headCloseBytes, startIndex)
                if (endIndex > -1) {
                    head = String(bytes.copyOfRange(startIndex, endIndex + headCloseBytes.size))
                }
            }
            val doc = Jsoup.parseBodyFragment(head ?: headTagRegex.find(String(bytes))!!.value)
            val metaTags = doc.getElementsByTag("meta")
            var charsetStr: String
            for (metaTag in metaTags) {
                charsetStr = metaTag.attr("charset")
                if (!TextUtils.isEmpty(charsetStr)) {
                    return charsetStr
                }
                val httpEquiv = metaTag.attr("http-equiv")
                if (httpEquiv.equals("content-type", true)) {
                    val content = metaTag.attr("content")
                    val idx = content.indexOf("charset=", ignoreCase = true)
                    charsetStr = if (idx > -1) {
                        content.substring(idx + "charset=".length)
                    } else {
                        content.substringAfter(";")
                    }
                    if (!TextUtils.isEmpty(charsetStr)) {
                        return charsetStr
                    }
                }
            }
        } catch (ignored: Exception) {
        }
        return getEncode(bytes)
    }

    fun getEncode(bytes: ByteArray): String {
        val match = CharsetDetector().setText(bytes).detect()
        return resolveEncode(match?.name, bytes)
    }

    /**
     * 纯逻辑（REQ-09 / AD-14）：由「探测命中名 + 原始字节」裁决最终编码。
     *
     * 抽为独立函数以便**纯 JVM 单测**直接覆盖（不依赖 `CharsetDetector` 对样本的实际探测结果）：
     * GBK、GB2312 均属 GB18030 的真子集 —— 命中 GB 系时优先升到 GB18030，以正确解出 GB18030
     * 扩展字符（4 字节区）；若 GB18030 不可用，或该字节流以 GB18030 解码出现替换字符
     * （⇒ 实为其他编码，如被误判的 UTF-8），则回落 [DEFAULT_ENCODE]，与改造前 `?: "UTF-8"`
     * 同口径（只增不换）。
     */
    internal fun resolveEncode(detected: String?, bytes: ByteArray): String {
        val name = detected ?: DEFAULT_ENCODE
        if (!isGbFamily(name)) return name
        return if (isValidGb18030(bytes)) GB18030 else DEFAULT_ENCODE
    }

    /** 是否符合「GB 系」（GBK / GB2312 / GB18030 大小写不敏感）。 */
    private fun isGbFamily(name: String): Boolean =
        name.equals(GB18030, true) || name.equals("GBK", true) || name.equals("GB2312", true)

    /**
     * 以 GB18030 解码是否「干净」：GB18030 可用，且解码结果中 0 个替换字符（U+FFFD）。
     * 合法 GBK/GB2312 字节必为合法 GB18030，故出现替换字符即说明字节流并不属于 GB 系。
     *
     * 说明（诚实边界）：此处不引用 `AppConst.charsets` —— `AppConst` 的对象初始化会触及
     * `appCtx`（签名/包信息），在**纯 JVM 单测**环境下不可用；而「GB18030 已登记在
     * `AppConst.kt:100-101` 候选表内」是**静态事实**，用 `Charset.isSupported` 等价校验。
     */
    private fun isValidGb18030(bytes: ByteArray): Boolean {
        if (bytes.isEmpty()) return false
        if (!Charset.isSupported(GB18030)) return false
        return kotlin.runCatching {
            String(bytes, Charset.forName(GB18030)).none { it == '\uFFFD' }
        }.getOrDefault(false)
    }

    /**
     * 得到文件的编码
     */
    fun getEncode(filePath: String): String {
        return getEncode(File(filePath))
    }

    /**
     * 得到文件的编码
     */
    fun getEncode(file: File): String {
        val tempByte = getFileBytes(file)
        if (tempByte.isEmpty()) {
            return "UTF-8"
        }
        return getEncode(tempByte)
    }

    private fun getFileBytes(file: File): ByteArray {
        val byteArray = ByteArray(8000)
        var pos = 0
        try {
            file.inputStream().buffered().use {
                while (pos < byteArray.size) {
                    val n = it.read(byteArray, pos, 1)
                    if (n == -1) {
                        break
                    }
                    if (byteArray[pos] < 0) {
                        pos++
                    }
                }
            }
        } catch (e: Exception) {
            System.err.println("Error: $e")
        }
        return byteArray.copyOf(pos)
    }
}