package io.legado.app.help.exoplayer

import androidx.annotation.OptIn
import androidx.media3.common.Effect
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.ConvolutionFunction1D
import androidx.media3.effect.GaussianBlur
import androidx.media3.effect.SeparableConvolution
import io.legado.app.model.VideoPlay

/**
 * 画质增强效果集（video-player-image-enhance B 批，AD-06/AD-07）
 *
 * 全部基于 media3-effect 1.10.1 公开效果类组装（零手写 GL shader，规避 BaseGlShaderProgram
 * 纹理池管理风险；K7：API 签名已按 1.10.1 字节码核实——1.10.1 无 SinglePassGlEffect/VideoInfo，
 * SeparableConvolution.getConvolution(long): ConvolutionFunction1D 为官方锐化路径）。
 *
 * 效果链顺序（design Technical Approach）：降噪 → 锐化（先除噪再锐化防噪点放大）。
 */
@OptIn(UnstableApi::class)
object ImageEnhanceEffects {

    /** 锐化档位 → 1D 核系数 k（核 [-k, 1+2k, -k]，横向+纵向各卷积一次合成边缘增强，sum=1 亮度守恒） */
    fun sharpenK(level: Int): Float = when (level) {
        1 -> 0.15f
        2 -> 0.30f
        3 -> 0.50f
        else -> 0f
    }

    /** 降噪档位 → 高斯 sigma（GaussianBlur 可分离高斯，轻度模糊即轻度降噪） */
    fun denoiseSigma(level: Int): Float = when (level) {
        1 -> 0.5f
        2 -> 1.0f
        else -> 0f
    }

    /**
     * 组装画质增强效果链（B2.1）
     * @param sharpenLevel 0 关 / 1 轻 / 2 中 / 3 强
     * @param denoiseLevel 0 关 / 1 轻 / 2 中
     * 总开关关闭（enhanceEnabled=false）时返回空列表（调用方 setVideoEffects(emptyList()) 即 K4 清空残留）；
     * 单点守卫覆盖 onPrepared 重建路径与所有调用方，保证「关闭时完全回退原画渲染」语义
     */
    fun buildEffects(sharpenLevel: Int, denoiseLevel: Int): List<Effect> {
        if (!VideoPlay.enhanceEnabled) return emptyList()
        val effects = mutableListOf<Effect>()
        val sigma = denoiseSigma(denoiseLevel)
        if (sigma > 0f) {
            effects.add(GaussianBlur(sigma))
        }
        val k = sharpenK(sharpenLevel)
        if (k > 0f) {
            effects.add(SharpenEffect(k))
        }
        return effects
    }
}

/**
 * 锐化效果（B1.3）：1D 可分离锐化核 [-k, 1+2k, -k]
 * SeparableConvolution 的 toGlShaderProgram 由 media3 官方实现（横竖各卷积一次），
 * ConvolutionFunction1D 为连续函数语义（参照 GaussianFunction），分段表达 3-tap 核。
 */
@OptIn(UnstableApi::class)
class SharpenEffect(private val k: Float) : SeparableConvolution() {

    override fun getConvolution(presentationTimeUs: Long): ConvolutionFunction1D {
        return object : ConvolutionFunction1D {
            override fun domainStart(): Float = -1f
            override fun domainEnd(): Float = 1f
            override fun value(x: Float): Float = when {
                x < -0.5f -> -k
                x > 0.5f -> -k
                else -> 1f + 2f * k
            }
        }
    }
}
