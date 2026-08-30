package io.legado.app.ui.video

import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import io.legado.app.model.VideoPlay

/**
 * 画质增强控制器（video-player-image-enhance A 期，AD-01/AD-02/AD-04）
 *
 * A 期色彩调节：亮度/对比度/饱和度/色温四参数合成单一 ColorMatrix，
 * 经 TextureView 硬件层 Paint Filter 应用（K2 已实测生效：MEmu 负片实证 2026-08-29）。
 *
 * 应用时机（A1.3 关键实证）：GSY 在播放状态变化时会重置渲染视图，
 * 必须在播放事件回调（onPrepared/全屏切换/切集数/降级返回）后重新 apply，
 * 禁止仅在 onViewCreated 一次性应用。
 *
 * 渲染视图获取（AD-02）：每次从 view 树实时遍历查找 TextureView（不缓存引用），
 * GSY 默认渲染为 TextureView（K1 反编译实锤 sRenderType=TEXTURE）。
 */
object ImageEnhanceController {

    /** 当前播放器视图弱引用（VideoFragment onViewCreated 注册，供设置面板跨组件实时刷新，AD-02 不强持有） */
    @Volatile
    private var currentPlayerView: java.lang.ref.WeakReference<View>? = null

    /** AD-04: 缓存 Paint（复用实例，避免滑条拖动帧级 new Paint） */
    @Volatile
    private var cachedPaint: Paint? = null

    /** AD-04: 最近一次应用的四参数指纹（b/c/s/t 打包），未变化时跳过重建 */
    @Volatile
    private var lastFingerprint: Long = Long.MIN_VALUE

    /** AD-04: 最近一次应用滤镜的视图（GSY 重建 TextureView 后指纹虽同仍需重挂层） */
    @Volatile
    private var lastAppliedView: java.lang.ref.WeakReference<View>? = null

    /** 注册当前播放器视图并立即应用滤镜（VideoFragment.onViewCreated 调用） */
    fun registerPlayerView(v: View?) {
        currentPlayerView = v?.let { java.lang.ref.WeakReference(it) }
        apply(v)
    }

    /** 对已注册的播放器视图重新应用滤镜（设置面板滑条/预设变更时调用，实时预览 RA2） */
    fun applyToRegistered() {
        currentPlayerView?.get()?.let { apply(it) }
    }

    /** 画质增强是否启用 */
    fun isEnabled(): Boolean = VideoPlay.enhanceEnabled

    /**
     * 四参数合成单一 ColorMatrix
     * 像素作用顺序：色温 → 饱和度 → 对比度 → 亮度（design Technical Approach）
     * 参数：十倍整值（亮度/对比度/色温 -500~500，饱和度 -1000~1000）
     */
    fun buildColorMatrix(): ColorMatrix {
        val brightness = VideoPlay.enhanceBrightness / 10f   // -50.0 ~ 50.0
        val contrast = VideoPlay.enhanceContrast / 10f       // -50.0 ~ 50.0
        val saturation = VideoPlay.enhanceSaturation / 10f   // -100.0 ~ 100.0
        val colorTemp = VideoPlay.enhanceColorTemp / 10f     // -50.0 ~ 50.0

        val cm = ColorMatrix()

        // 1. 色温：暖色(t>0) R 增益↑ B 增益↓，冷色反向，G 守恒（RGB 对角增益，保留亮度近似守恒）
        val rGain = 1f + colorTemp / 100f
        val bGain = 1f - colorTemp / 100f
        cm.postConcat(
            ColorMatrix(
                floatArrayOf(
                    rGain, 0f, 0f, 0f, 0f,
                    0f, 1f, 0f, 0f, 0f,
                    0f, 0f, bGain, 0f, 0f,
                    0f, 0f, 0f, 1f, 0f
                )
            )
        )

        // 2. 饱和度：因子 1 + s/100（0=灰度，1=原画，2=双倍）
        val satFactor = (1f + saturation / 100f).coerceIn(0f, 2f)
        val satMatrix = ColorMatrix()
        satMatrix.setSaturation(satFactor)
        cm.postConcat(satMatrix)

        // 3+4. 对比度（围绕中灰 128 缩放）与亮度（RGB 偏移）合并矩阵
        //     out = cf*(in-128) + 128 + bo = cf*in + (128*(1-cf) + bo)
        val cFactor = (1f + contrast / 50f).coerceIn(0f, 2f)
        val bOffset = brightness * 2.55f   // -50 → -127.5，+50 → +127.5
        val gray = 128f * (1f - cFactor) + bOffset
        cm.postConcat(
            ColorMatrix(
                floatArrayOf(
                    cFactor, 0f, 0f, 0f, gray,
                    0f, cFactor, 0f, 0f, gray,
                    0f, 0f, cFactor, 0f, gray,
                    0f, 0f, 0f, 1f, 0f
                )
            )
        )
        return cm
    }

    /**
     * 对 playerView 视图树中的 TextureView 应用画质增强滤镜
     * 由播放事件钩子调用（onPrepared/全屏切换/切集数/降级返回后）
     * AD-04: 参数指纹未变且目标视图未重建时直接短路返回，消除拖动帧级硬件层重建
     */
    fun apply(root: View?) {
        root ?: return
        val tv = findTextureView(root) ?: return
        if (VideoPlay.enhanceEnabled) {
            val fingerprint = enhanceFingerprint()
            val sameAsLast = fingerprint == lastFingerprint &&
                cachedPaint != null && lastAppliedView?.get() === tv
            android.util.Log.d("EnhanceGov", "apply fp=$fingerprint shortCircuit=$sameAsLast")
            if (!sameAsLast) {
                val paint = cachedPaint ?: Paint().also { cachedPaint = it }
                paint.colorFilter = ColorMatrixColorFilter(buildColorMatrix())
                lastFingerprint = fingerprint
                lastAppliedView = java.lang.ref.WeakReference(tv)
                tv.setLayerType(View.LAYER_TYPE_HARDWARE, paint)
            }
        } else {
            reset(root)
        }
    }

    /** AD-04: 四参数（b/c/s/t 十倍整值，范围均落在 16bit 内）打包指纹 */
    private fun enhanceFingerprint(): Long =
        (VideoPlay.enhanceBrightness.toLong() and 0xFFFF) or
            ((VideoPlay.enhanceContrast.toLong() and 0xFFFF) shl 16) or
            ((VideoPlay.enhanceSaturation.toLong() and 0xFFFF) shl 32) or
            ((VideoPlay.enhanceColorTemp.toLong() and 0xFFFF) shl 48)

    /** 回退原画（移除滤镜层） */
    fun reset(root: View?) {
        root ?: return
        lastAppliedView = null
        findTextureView(root)?.setLayerType(View.LAYER_TYPE_NONE, null)
    }

    /**
     * B 批（B2.1）：锐化/降噪效果链注入播放引擎（AD-03 运行时 setVideoEffects 热更新）
     * 必须主线程调用（ExoPlayer verifyApplicationThread）；全关时显式清空（K4 防池化实例残留）
     * 访问链 playerManager 为 protected，实际注入委托给 ExoVideoManager.applyImageEnhanceEffects()
     * 效果在下一次视频管线构建时生效（media3 语义），onPrepared 钩子保证每次播放都会重建应用
     */
    fun applyEffectsToPlayer() {
        VideoPlay.videoManager.applyImageEnhanceEffects()
    }

    /** view 树实时遍历查找 TextureView（不缓存引用，AD-02） */
    private fun findTextureView(v: View): TextureView? {
        if (v is TextureView) return v
        if (v is ViewGroup) {
            for (i in 0 until v.childCount) {
                findTextureView(v.getChildAt(i))?.let { return it }
            }
        }
        return null
    }
}
