package io.legado.app.help.crypto

import android.util.Log
import androidx.annotation.Keep
import cn.hutool.core.codec.Base64
import cn.hutool.core.io.IoUtil
import cn.hutool.core.util.HexUtil
import cn.hutool.crypto.symmetric.SymmetricCrypto
import io.legado.app.constant.AppLog
import io.legado.app.utils.EncoderUtils
import io.legado.app.utils.isHex
import java.io.InputStream
import java.nio.charset.Charset

@Keep
class SymmetricCryptoAndroid(
    algorithm: String,
    key: ByteArray?,
) : SymmetricCrypto(algorithm, key) {

    override fun encryptBase64(data: ByteArray): String {
        return EncoderUtils.base64Encode(encrypt(data))
    }

    override fun encryptBase64(data: String, charset: String?): String {
        return EncoderUtils.base64Encode(encrypt(data, charset))
    }

    override fun encryptBase64(data: String, charset: Charset?): String {
        return EncoderUtils.base64Encode(encrypt(data, charset))
    }

    override fun encryptBase64(data: String): String {
        return EncoderUtils.base64Encode(encrypt(data))
    }

    override fun encryptBase64(data: InputStream): String {
        return EncoderUtils.base64Encode(encrypt(data))
    }

    override fun decrypt(data: String): ByteArray {
        val bytes = if (data.isHex()) {
            HexUtil.decodeHex(data)
        } else {
            Base64.decode(data)
        }
        return try {
            decrypt(bytes)
        } catch (e: Exception) {
            // P1-2.1: 捕获加密解密异常（IllegalBlockSizeException/BadPaddingException 等）
            // 记录日志并返回空 ByteArray，避免异常传播导致播放崩溃
            val algoName = this.javaClass.simpleName
            AppLog.put("解密失败: algorithm=${algoName}, dataLen=${data.length}, exception=${e.javaClass.simpleName}", e)
            Log.d("RssDecrypt", "decrypt failed: algo=${algoName}, dataLen=${data.length}, exception=${e.javaClass.simpleName}")
            ByteArray(0)
        }
    }

    // 显式 override decrypt(InputStream)，确保 Rhino 能找到该方法。
    // 原因：decrypt(InputStream) 在 SymmetricDecryptor 接口中是 default 方法，
    // Rhino 1.8.1 无法识别 Java 8 interface default 方法，
    // 导致 JS 调用 decrypt(inputStream) 时找不到匹配方法，
    // 回退到 decrypt(String) 走错误路径（先做 Base64/Hex 解码再解密），
    // 最终产出垃圾数据，图片无法显示。
    override fun decrypt(data: InputStream): ByteArray {
        return decrypt(IoUtil.readBytes(data))
    }

}
