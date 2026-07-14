package io.legado.app.utils

import android.util.Log
import android.util.LruCache
import io.legado.app.constant.AppLog
import io.legado.app.data.entities.BaseSource
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.RssSource
import java.io.ByteArrayInputStream
import java.io.InputStream
import kotlinx.coroutines.CancellationException

/**
 * 加密图片解密工具
 */
object ImageUtils {

    private const val TAG = "ImgDecrypt"

    /**
     * P1-2 解密结果缓存：避免列表刷新时重复解密相同图片
     * 设计文档原方案为 JS 层 cache.get/put，但 CacheManager 只支持 String，ByteArray 无法缓存
     * 调整为 Kotlin 层 LruCache 缓存解密后的 ByteArray（基于 src 做 key）
     * F-P1-E 动态上限：根据设备可用内存自适应（maxMemory/32），范围 4-16MB
     * 已知上限：低端设备4MB/高端设备16MB | 升级路径：无
     */
    private val decodeCache = object : LruCache<String, ByteArray>(
        (Runtime.getRuntime().maxMemory() / 32).toInt().coerceIn(4 * 1024 * 1024, 16 * 1024 * 1024)
    ) {
        override fun sizeOf(key: String, value: ByteArray): Int = value.size
    }

    init {
        // F-P1-E 解密缓存动态上限初始化日志
        AppLog.put("ImageUtils 解密缓存上限: ${decodeCache.maxSize()} bytes")
    }

    /**
     * @param isCover 根据这个执行书源中不同的解密规则
     * @return 解密失败返回Null 解密规则为空不处理
     */
    fun decode(
        src: String, bytes: ByteArray, isCover: Boolean,
        source: BaseSource?, book: Book? = null
    ): ByteArray? {
        val ruleJs = getRuleJs(source, isCover)
        if (ruleJs.isNullOrBlank()) return bytes
        // P1-2 缓存命中检查：相同 src 的解密结果直接返回，避免列表刷新重复解密
        decodeCache.get(src)?.let { return it }
        // app-stability-round2 P1-2 修复：图片文件头检测，已知格式跳过解密
        // 根因：块校验只能过滤非块对齐数据，块对齐的未加密图片（如1024字节PNG）仍被强制解密
        // 证据：appLog 频现 IllegalBlockSizeException（logo.png 等未加密图片被强制解密）
        if (isKnownImageFormat(bytes)) {
            AppLog.putDebug("图片文件头检测命中已知格式, 跳过解密, src=${src.take(60)}")
            return bytes
        }
        // P2-A 修复：数据长度校验，非块对齐跳过解密
        // 根因：RssSource 配置了图片解密规则但图片实际未加密，强制解密导致 IllegalBlockSizeException
        // 证据：appLog 每个会话必现 "图片解密错误 src=...logo.png" + IllegalBlockSizeException DATA_NOT_MULTIPLE_OF_BLOCK_LENGTH
        // 常见块大小：DES=8, AES=16, SM4=16；非块对齐大概率未加密，跳过解密避免异常
        if (bytes.size % 8 != 0 && bytes.size % 16 != 0) {
            return bytes
        }
        //解密库hutool.crypto ByteArray|InputStream -> ByteArray
        return kotlin.runCatching {
            source?.evalJS(ruleJs) {
                put("book", book)
                put("result", bytes)
                put("src", src)
            } as ByteArray
        }.onFailure {
            // P2-A 修复：协程取消异常必须重新抛出，不能视为解密错误污染日志
            // 根因：runCatching 会吞掉 CancellationException，OkHttpStreamFetcher 协程取消时
            // evalJS 挂起点抛 JobCancellationException 被误记为"解密错误"
            if (it is CancellationException) throw it
            AppLog.putDebug("${src}解密错误", it)
        }.getOrNull()?.also {
            // P1-2 缓存解密结果，列表刷新时直接命中
            decodeCache.put(src, it)
        }
    }

    fun decode(
        src: String, inputStream: InputStream, isCover: Boolean,
        source: BaseSource?, book: Book? = null
    ): InputStream? {
        val ruleJs = getRuleJs(source, isCover)
        if (ruleJs.isNullOrBlank()) return inputStream
        // app-stability-round2 P1-2 修复：先转 ByteArray 复用 decode(ByteArray) 的文件头检测+块校验逻辑
        // 根因：原 decode(InputStream) 无任何校验直接 evalJS，未加密图片强制解密抛 IllegalBlockSizeException
        // 证据：appLog 频现 IllegalBlockSizeException（logo.png 等未加密图片被强制解密）
        // 副带修复 P1-A：readBytes 后 JS 收到 ByteArray 而非 InputStream，避免 okio.RealBufferedSource 类型容错问题
        return kotlin.runCatching {
            val bytes = inputStream.readBytes()
            val decoded = decode(src, bytes, isCover, source, book) ?: return@runCatching null
            ByteArrayInputStream(decoded)
        }.onFailure {
            // P2-A 修复：协程取消异常必须重新抛出，不能视为解密错误污染日志
            if (it is CancellationException) throw it
            Log.e(TAG, "decode(InputStream) failed: ${it.message}", it)
            AppLog.put("图片解密错误 src=${src.take(60)}", it)
        }.getOrNull()
    }

    fun skipDecode(source: BaseSource?, isCover: Boolean): Boolean {
        return getRuleJs(source, isCover).isNullOrBlank()
    }

    private fun getRuleJs(
        source: BaseSource?, isCover: Boolean
    ): String? {
        return when (source) {
            is BookSource ->
                if (isCover) source.coverDecodeJs
                else source.getContentRule().imageDecode

            is RssSource -> source.coverDecodeJs
            else -> null
        }
    }

    /**
     * 图片文件头检测：判断字节数组是否为已知图片格式（未加密）
     * PNG: 89 50 4E 47 | JPG: FF D8 FF | GIF: 47 49 46 38 | WebP: 52 49 46 46 (RIFF)
     * 命中已知格式说明图片未加密，应跳过解密避免 IllegalBlockSizeException
     * app-stability-round2 P1-2 修复：块校验只能过滤非块对齐数据，块对齐的未加密图片仍会被强制解密
     */
    private fun isKnownImageFormat(bytes: ByteArray): Boolean {
        if (bytes.size < 4) return false
        // PNG: 89 50 4E 47
        if (bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() &&
            bytes[2] == 0x4E.toByte() && bytes[3] == 0x47.toByte()) return true
        // JPG: FF D8 FF
        if (bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() && bytes[2] == 0xFF.toByte()) return true
        // GIF: 47 49 46 38 (GIF8)
        if (bytes[0] == 0x47.toByte() && bytes[1] == 0x49.toByte() &&
            bytes[2] == 0x46.toByte() && bytes[3] == 0x38.toByte()) return true
        // WebP: 52 49 46 46 (RIFF)
        if (bytes[0] == 0x52.toByte() && bytes[1] == 0x49.toByte() &&
            bytes[2] == 0x46.toByte() && bytes[3] == 0x46.toByte()) return true
        return false
    }

}
