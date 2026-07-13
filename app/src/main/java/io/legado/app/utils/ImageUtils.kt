package io.legado.app.utils

import android.util.Log
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
     * @param isCover 根据这个执行书源中不同的解密规则
     * @return 解密失败返回Null 解密规则为空不处理
     */
    fun decode(
        src: String, bytes: ByteArray, isCover: Boolean,
        source: BaseSource?, book: Book? = null
    ): ByteArray? {
        val ruleJs = getRuleJs(source, isCover)
        if (ruleJs.isNullOrBlank()) return bytes
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
        }.getOrNull()
    }

    fun decode(
        src: String, inputStream: InputStream, isCover: Boolean,
        source: BaseSource?, book: Book? = null
    ): InputStream? {
        val ruleJs = getRuleJs(source, isCover)
        if (ruleJs.isNullOrBlank()) return inputStream
        //解密库hutool.crypto ByteArray|InputStream -> ByteArray
        // 恢复原版行为：直接把 InputStream 传给 JS 的 result 变量，
        // JS 调用 decrypt(result) 时由 SymmetricCryptoAndroid.decrypt(InputStream) 处理。
        // SymmetricCryptoAndroid 显式 override 了 decrypt(InputStream)，
        // 确保 Rhino 1.8.1 能找到该方法（原接口 default method 不可见）。
        return kotlin.runCatching {
            Log.d(TAG, "decode(InputStream): src=${src.take(80)}, ruleJs=${ruleJs.take(60)}, source=${source?.getKey()}")
            val result = source?.evalJS(ruleJs) {
                put("book", book)
                put("result", inputStream)
                put("src", src)
            }
            // P1-A 修复：evalJS 返回值类型容错
            // 根因：evalJS 可能返回 InputStream (okio.RealBufferedSource) 而非 ByteArray
            // 证据：appLog-26-07-12 ClassCastException: okio.RealBufferedSource$inputStream$1 cannot be cast to byte[]
            val bytes = when (result) {
                is ByteArray -> result
                is InputStream -> result.readBytes()
                else -> null
            } ?: return@runCatching null
            Log.d(TAG, "decode result: size=${bytes.size}")
            ByteArrayInputStream(bytes)
        }.onFailure {
            // P2-A 修复：协程取消异常必须重新抛出，不能视为解密错误污染日志
            if (it is CancellationException) throw it
            Log.e(TAG, "decode failed: ${it.message}", it)
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

}
