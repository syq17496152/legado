package io.legado.app.utils

import android.util.Log
import io.legado.app.constant.AppLog
import io.legado.app.data.entities.BaseSource
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.RssSource
import java.io.ByteArrayInputStream
import java.io.InputStream

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
        //解密库hutool.crypto ByteArray|InputStream -> ByteArray
        return kotlin.runCatching {
            source?.evalJS(ruleJs) {
                put("book", book)
                put("result", bytes)
                put("src", src)
            } as ByteArray
        }.onFailure {
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
            Log.e(TAG, "decode: src=${src.take(80)}, ruleJs=${ruleJs.take(60)}, source=${source?.getKey()}")
            val bytes = source?.evalJS(ruleJs) {
                put("book", book)
                put("result", inputStream)
                put("src", src)
            } as ByteArray
            Log.e(TAG, "decode result: size=${bytes.size}, src=${src.take(60)}")
            ByteArrayInputStream(bytes)
        }.onFailure {
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
