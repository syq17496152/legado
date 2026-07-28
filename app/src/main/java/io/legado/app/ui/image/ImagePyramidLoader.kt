package io.legado.app.ui.image

import android.graphics.BitmapFactory
import android.graphics.PointF
import androidx.core.net.toUri
import com.davemorrissey.labs.subscaleview.ImageSource
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView
import io.legado.app.constant.AppLog
import java.io.File

/**
 * 图片金字塔加载器（Phase 3.2 AD-06）
 *
 * 设计目标：
 * - 长图防 OOM：SSIV + BitmapRegionDecoder 按需解码可视区域瓦片，内存占用与图片尺寸无关
 * - 普通图保持 PhotoView（缩放/旋转能力不变）
 * - 适配性最大尺寸展示（Phase 3.3）：不变形不裁剪，极端尺寸安全兜底
 *
 * 路由规则（isLongImage）：
 * - 高宽比 > [LONG_ASPECT_RATIO]（3:1 条漫阈值）
 * - 或解码高度 > 屏幕高度 × [LONG_HEIGHT_SCREEN_MULTIPLIER]（2 倍）
 *
 * 数据来源：Glide downloadOnly() 落地磁盘缓存的原始文件（DATA 缓存），
 * SSIV 直接读取缓存文件做区域解码，不经内存 Bitmap 全量解码。
 */
object ImagePyramidLoader {

    /** 长图判定：高宽比阈值（条漫典型 3:1 以上） */
    const val LONG_ASPECT_RATIO = 3f

    /** 长图判定：解码高度超过屏幕高度的倍数 */
    const val LONG_HEIGHT_SCREEN_MULTIPLIER = 2

    /** 普通图 item 高度上限（屏幕高度倍数，超出后 fitCenter 居中显示，不变形不裁剪） */
    const val NORMAL_MAX_HEIGHT_SCREEN_MULTIPLIER = 4

    /** 长图 SSIV item 高度上限（屏幕高度倍数，超出后宽度优先填充 + 平移查看，防极端尺寸撑爆布局） */
    const val SSIV_MAX_HEIGHT_SCREEN_MULTIPLIER = 20

    /**
     * 判断是否为长图（走 SSIV 金字塔）
     *
     * @param imgW 图片原始宽度（px）
     * @param imgH 图片原始高度（px）
     * @param screenH 屏幕高度（px）
     */
    fun isLongImage(imgW: Int, imgH: Int, screenH: Int): Boolean {
        if (imgW <= 0 || imgH <= 0) return false
        return imgH.toFloat() / imgW.toFloat() > LONG_ASPECT_RATIO ||
            imgH > screenH * LONG_HEIGHT_SCREEN_MULTIPLIER
    }

    /**
     * 解码图片原始尺寸（inJustDecodeBounds，仅读文件头，不全量解码）
     *
     * @return 宽高数组 [w, h]；解码失败（非图片/文件损坏）返回 null
     */
    fun decodeBounds(file: File): IntArray? {
        return kotlin.runCatching {
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, opts)
            if (opts.outWidth > 0 && opts.outHeight > 0) {
                intArrayOf(opts.outWidth, opts.outHeight)
            } else {
                null
            }
        }.onFailure { e ->
            AppLog.putDebugWithTag(
                AppLog.TAG_IMAGE_CANVAS,
                "ImagePyramidLoader.decodeBounds failed: ${e.message?.take(80)}",
                level = AppLog.Level.WARN
            )
        }.getOrNull()
    }

    /**
     * 普通图展示高度（Phase 3.3 适配性最大尺寸）：
     * 宽填满屏幕后按宽高比折算高度，上限 [NORMAL_MAX_HEIGHT_SCREEN_MULTIPLIER] 倍屏高。
     * 超限时由 PhotoView fitCenter 居中显示（不变形不裁剪）。
     */
    fun normalDisplayHeight(imgW: Int, imgH: Int, screenW: Int, screenH: Int): Int {
        if (imgW <= 0) return (screenH * 0.6).toInt()
        val aspectHeight = screenW.toLong() * imgH / imgW
        val maxHeight = screenH.toLong() * NORMAL_MAX_HEIGHT_SCREEN_MULTIPLIER
        return aspectHeight.coerceAtMost(maxHeight).toInt()
    }

    /**
     * 长图 SSIV 展示高度（Phase 3.3 极端尺寸兜底）：
     * 按宽高比全量展开（不裁剪），上限 [SSIV_MAX_HEIGHT_SCREEN_MULTIPLIER] 倍屏高；
     * 超限时视图截断，SSIV 宽度优先填充 + 平移查看完整长图。
     */
    fun ssivDisplayHeight(imgW: Int, imgH: Int, screenW: Int, screenH: Int): Int {
        if (imgW <= 0) return screenH
        val aspectHeight = screenW.toLong() * imgH / imgW
        val maxHeight = screenH.toLong() * SSIV_MAX_HEIGHT_SCREEN_MULTIPLIER
        return aspectHeight.coerceAtMost(maxHeight).toInt()
    }

    /**
     * 将 Glide 磁盘缓存文件绑定到 SSIV（金字塔区域解码）
     *
     * - 视图高度 == 图片折算高度（未截断）：CENTER_INSIDE 恰好填满视图，无变形无裁剪
     * - 视图高度被上限截断（极端长图）：CUSTOM minScale=宽度填满，初始定位顶部，平移查看全图
     *
     * @param ssiv SubsamplingScaleImageView 实例
     * @param file Glide downloadOnly 磁盘缓存文件
     * @param imgW 图片原始宽度
     * @param imgH 图片原始高度
     * @param viewW 视图宽度（屏幕宽）
     * @param viewH 视图高度（ssivDisplayHeight 计算结果）
     */
    fun bindLongImage(
        ssiv: SubsamplingScaleImageView,
        file: File,
        imgW: Int,
        imgH: Int,
        viewW: Int,
        viewH: Int
    ) {
        ssiv.recycle()
        val capped = imgW > 0 && viewW.toLong() * imgH / imgW > viewH
        if (capped) {
            // 极端长图：宽度优先填充（minScale=viewW/imgW），初始定位顶部，平移查看剩余部分
            val minScale = viewW.toFloat() / imgW.toFloat()
            ssiv.setMinimumScaleType(SubsamplingScaleImageView.SCALE_TYPE_CUSTOM)
            ssiv.setMinScale(minScale)
            ssiv.setOnImageEventListener(object :
                SubsamplingScaleImageView.DefaultOnImageEventListener() {
                override fun onReady() {
                    // center 为源图坐标：水平居中，垂直定位到可视窗口中心使顶部对齐
                    ssiv.setScaleAndCenter(minScale, PointF(imgW / 2f, viewH / (2f * minScale)))
                }
            })
        } else {
            // 视图与图片等比：CENTER_INSIDE 恰好填满（缩放后宽高均贴合视图）
            ssiv.setMinimumScaleType(SubsamplingScaleImageView.SCALE_TYPE_CENTER_INSIDE)
        }
        ssiv.setImage(ImageSource.uri(file.toUri().toString()))
    }
}
