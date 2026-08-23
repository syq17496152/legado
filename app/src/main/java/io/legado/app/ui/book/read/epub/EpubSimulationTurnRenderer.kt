package io.legado.app.ui.book.read.epub

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Path
import android.graphics.PointF
import android.graphics.Region
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.widget.Scroller
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin

@Suppress("DEPRECATION")
internal class EpubSimulationTurnRenderer {

    private var viewWidth = 1
    private var viewHeight = 1
    private var direction = 0
    private var startX = 0f
    private var startY = 0f
    private var touchX = 0.1f
    private var touchY = 0.1f
    private var cornerX = 1
    private var cornerY = 1
    private var middleX = 0f
    private var middleY = 0f
    private var degrees = 0f
    private var touchToCornerDis = 0f
    private var isRtOrLb = false
    private var maxLength = 1f

    private val path0 = Path()
    private val path1 = Path()
    private val bezierStart1 = PointF()
    private val bezierControl1 = PointF()
    private val bezierVertex1 = PointF()
    private val bezierEnd1 = PointF()
    private val bezierStart2 = PointF()
    private val bezierControl2 = PointF()
    private val bezierVertex2 = PointF()
    private val bezierEnd2 = PointF()

    private val folderShadowDrawableRL = GradientDrawable(
        GradientDrawable.Orientation.RIGHT_LEFT,
        intArrayOf(0x333333, -0x4fcccccd)
    ).apply { gradientType = GradientDrawable.LINEAR_GRADIENT }
    private val folderShadowDrawableLR = GradientDrawable(
        GradientDrawable.Orientation.LEFT_RIGHT,
        intArrayOf(0x333333, -0x4fcccccd)
    ).apply { gradientType = GradientDrawable.LINEAR_GRADIENT }
    private val backShadowDrawableRL = GradientDrawable(
        GradientDrawable.Orientation.RIGHT_LEFT,
        intArrayOf(-0xeeeeef, 0x111111)
    ).apply { gradientType = GradientDrawable.LINEAR_GRADIENT }
    private val backShadowDrawableLR = GradientDrawable(
        GradientDrawable.Orientation.LEFT_RIGHT,
        intArrayOf(-0xeeeeef, 0x111111)
    ).apply { gradientType = GradientDrawable.LINEAR_GRADIENT }
    private val frontShadowDrawableVLR = GradientDrawable(
        GradientDrawable.Orientation.LEFT_RIGHT,
        intArrayOf(-0x7feeeeef, 0x111111)
    ).apply { gradientType = GradientDrawable.LINEAR_GRADIENT }
    private val frontShadowDrawableVRL = GradientDrawable(
        GradientDrawable.Orientation.RIGHT_LEFT,
        intArrayOf(-0x7feeeeef, 0x111111)
    ).apply { gradientType = GradientDrawable.LINEAR_GRADIENT }
    private val frontShadowDrawableHTB = GradientDrawable(
        GradientDrawable.Orientation.TOP_BOTTOM,
        intArrayOf(-0x7feeeeef, 0x111111)
    ).apply { gradientType = GradientDrawable.LINEAR_GRADIENT }
    private val frontShadowDrawableHBT = GradientDrawable(
        GradientDrawable.Orientation.BOTTOM_TOP,
        intArrayOf(-0x7feeeeef, 0x111111)
    ).apply { gradientType = GradientDrawable.LINEAR_GRADIENT }

    fun setViewSize(width: Int, height: Int) {
        viewWidth = width.coerceAtLeast(1)
        viewHeight = height.coerceAtLeast(1)
        maxLength = hypot(viewWidth.toDouble(), viewHeight.toDouble()).toFloat()
    }

    fun start(direction: Int, startX: Float, startY: Float) {
        this.direction = direction
        this.startX = startX
        this.startY = startY
        calcCornerXY(startX, startY)
        when {
            direction < 0 && startX > viewWidth / 2f -> calcCornerXY(startX, viewHeight.toFloat())
            direction < 0 -> calcCornerXY(viewWidth - startX, viewHeight.toFloat())
            direction > 0 && viewWidth / 2f > startX -> calcCornerXY(viewWidth - startX, startY)
        }
        updateTouch(startX, startY)
    }

    fun updateTouch(x: Float, y: Float) {
        var nextY = y
        if ((startY > viewHeight / 3f && startY < viewHeight * 2f / 3f) || direction < 0) {
            nextY = viewHeight.toFloat()
        }
        if (startY > viewHeight / 3f && startY < viewHeight / 2f && direction > 0) {
            nextY = 1f
        }
        setTouchPoint(x, nextY)
    }

    fun setTouchPoint(x: Float, y: Float) {
        touchX = x.takeIf { it.isFinite() } ?: 0.1f
        touchY = y.takeIf { it.isFinite() } ?: 0.1f
        if (abs(touchX) < 0.1f) touchX = 0.1f
        if (abs(touchY) < 0.1f) touchY = 0.1f
    }

    fun startCompleteAnimation(scroller: Scroller, animationSpeed: Int): Boolean {
        if (direction == 0) return false
        val dx = if (cornerX > 0 && direction > 0) {
            -(viewWidth + touchX)
        } else {
            viewWidth - touchX
        }
        val dy = if (cornerY > 0) {
            viewHeight - touchY
        } else {
            1f - touchY
        }
        val duration = if (dx != 0f) {
            (animationSpeed * abs(dx) / viewWidth).toInt()
        } else {
            (animationSpeed * abs(dy) / viewHeight).toInt()
        }.coerceAtLeast(80)
        scroller.startScroll(touchX.toInt(), touchY.toInt(), dx.toInt(), dy.toInt(), duration)
        return true
    }

    fun draw(
        canvas: Canvas,
        currentBitmap: Bitmap?,
        targetBitmap: Bitmap?,
        backgroundColor: Int
    ) {
        if (direction == 0 || currentBitmap == null || targetBitmap == null) return
        calcPoints()
        if (direction > 0) {
            drawCurrentPageArea(canvas, currentBitmap)
            drawNextPageAreaAndShadow(canvas, targetBitmap)
            drawCurrentPageShadow(canvas)
            drawCurrentBackArea(canvas, backgroundColor)
        } else {
            drawCurrentPageArea(canvas, targetBitmap)
            drawNextPageAreaAndShadow(canvas, currentBitmap)
            drawCurrentPageShadow(canvas)
            drawCurrentBackArea(canvas, backgroundColor)
        }
    }

    private fun drawCurrentBackArea(canvas: Canvas, backgroundColor: Int) {
        val i = ((bezierStart1.x + bezierControl1.x) / 2).toInt()
        val f1 = abs(i - bezierControl1.x)
        val i1 = ((bezierStart2.y + bezierControl2.y) / 2).toInt()
        val f2 = abs(i1 - bezierControl2.y)
        val f3 = min(f1, f2)
        path1.reset()
        path1.moveTo(bezierVertex2.x, bezierVertex2.y)
        path1.lineTo(bezierVertex1.x, bezierVertex1.y)
        path1.lineTo(bezierEnd1.x, bezierEnd1.y)
        path1.lineTo(touchX, touchY)
        path1.lineTo(bezierEnd2.x, bezierEnd2.y)
        path1.close()
        val folderShadowDrawable: GradientDrawable
        val left: Int
        val right: Int
        if (isRtOrLb) {
            left = (bezierStart1.x - 1).toInt()
            right = (bezierStart1.x + f3 + 1).toInt()
            folderShadowDrawable = folderShadowDrawableLR
        } else {
            left = (bezierStart1.x - f3 - 1).toInt()
            right = (bezierStart1.x + 1).toInt()
            folderShadowDrawable = folderShadowDrawableRL
        }
        val saveCount = canvas.save()
        canvas.clipPath(path0)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            canvas.clipPath(path1)
        } else {
            canvas.clipPath(path1, Region.Op.INTERSECT)
        }
        canvas.drawColor(backgroundColor)
        canvas.rotate(degrees, bezierStart1.x, bezierStart1.y)
        folderShadowDrawable.setBounds(
            left,
            bezierStart1.y.toInt(),
            right,
            (bezierStart1.y + maxLength).toInt()
        )
        folderShadowDrawable.draw(canvas)
        canvas.restoreToCount(saveCount)
    }

    private fun drawCurrentPageShadow(canvas: Canvas) {
        val degree = if (isRtOrLb) {
            Math.PI / 4 - atan2(bezierControl1.y - touchY, touchX - bezierControl1.x)
        } else {
            Math.PI / 4 - atan2(touchY - bezierControl1.y, touchX - bezierControl1.x)
        }
        val d1 = (25f * 1.414f * cos(degree)).toFloat()
        val d2 = (25f * 1.414f * sin(degree)).toFloat()
        val x = touchX + d1
        val y = if (isRtOrLb) touchY + d2 else touchY - d2
        path1.reset()
        path1.moveTo(x, y)
        path1.lineTo(touchX, touchY)
        path1.lineTo(bezierControl1.x, bezierControl1.y)
        path1.lineTo(bezierStart1.x, bezierStart1.y)
        path1.close()
        val saveCount1 = canvas.save()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            canvas.clipOutPath(path0)
        } else {
            canvas.clipPath(path0, Region.Op.XOR)
        }
        canvas.clipPath(path1, Region.Op.INTERSECT)
        var leftX: Int
        var rightX: Int
        var currentPageShadow: GradientDrawable
        if (isRtOrLb) {
            leftX = bezierControl1.x.toInt()
            rightX = (bezierControl1.x + 25).toInt()
            currentPageShadow = frontShadowDrawableVLR
        } else {
            leftX = (bezierControl1.x - 25).toInt()
            rightX = (bezierControl1.x + 1).toInt()
            currentPageShadow = frontShadowDrawableVRL
        }
        var rotateDegrees = Math.toDegrees(
            atan2(touchX - bezierControl1.x, bezierControl1.y - touchY).toDouble()
        ).toFloat()
        canvas.rotate(rotateDegrees, bezierControl1.x, bezierControl1.y)
        currentPageShadow.setBounds(
            leftX,
            (bezierControl1.y - maxLength).toInt(),
            rightX,
            bezierControl1.y.toInt()
        )
        currentPageShadow.draw(canvas)
        canvas.restoreToCount(saveCount1)

        path1.reset()
        path1.moveTo(x, y)
        path1.lineTo(touchX, touchY)
        path1.lineTo(bezierControl2.x, bezierControl2.y)
        path1.lineTo(bezierStart2.x, bezierStart2.y)
        path1.close()
        val saveCount2 = canvas.save()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            canvas.clipOutPath(path0)
        } else {
            canvas.clipPath(path0, Region.Op.XOR)
        }
        canvas.clipPath(path1)
        if (isRtOrLb) {
            leftX = bezierControl2.y.toInt()
            rightX = (bezierControl2.y + 25).toInt()
            currentPageShadow = frontShadowDrawableHTB
        } else {
            leftX = (bezierControl2.y - 25).toInt()
            rightX = (bezierControl2.y + 1).toInt()
            currentPageShadow = frontShadowDrawableHBT
        }
        rotateDegrees = Math.toDegrees(
            atan2(bezierControl2.y - touchY, bezierControl2.x - touchX).toDouble()
        ).toFloat()
        canvas.rotate(rotateDegrees, bezierControl2.x, bezierControl2.y)
        val temp = if (bezierControl2.y < 0) {
            bezierControl2.y - viewHeight
        } else {
            bezierControl2.y
        }.toDouble()
        val hmg = hypot(bezierControl2.x.toDouble(), temp)
        if (hmg > maxLength) {
            currentPageShadow.setBounds(
                (bezierControl2.x - 25 - hmg).toInt(),
                leftX,
                (bezierControl2.x + maxLength - hmg).toInt(),
                rightX
            )
        } else {
            currentPageShadow.setBounds(
                (bezierControl2.x - maxLength).toInt(),
                leftX,
                bezierControl2.x.toInt(),
                rightX
            )
        }
        currentPageShadow.draw(canvas)
        canvas.restoreToCount(saveCount2)
    }

    private fun drawNextPageAreaAndShadow(canvas: Canvas, bitmap: Bitmap) {
        path1.reset()
        path1.moveTo(bezierStart1.x, bezierStart1.y)
        path1.lineTo(bezierVertex1.x, bezierVertex1.y)
        path1.lineTo(bezierVertex2.x, bezierVertex2.y)
        path1.lineTo(bezierStart2.x, bezierStart2.y)
        path1.lineTo(cornerX.toFloat(), cornerY.toFloat())
        path1.close()
        degrees = Math.toDegrees(
            atan2(
                (bezierControl1.x - cornerX).toDouble(),
                bezierControl2.y - cornerY.toDouble()
            )
        ).toFloat()
        val leftX: Int
        val rightX: Int
        val backShadowDrawable: GradientDrawable
        if (isRtOrLb) {
            leftX = bezierStart1.x.toInt()
            rightX = (bezierStart1.x + touchToCornerDis / 4).toInt()
            backShadowDrawable = backShadowDrawableLR
        } else {
            leftX = (bezierStart1.x - touchToCornerDis / 4).toInt()
            rightX = bezierStart1.x.toInt()
            backShadowDrawable = backShadowDrawableRL
        }
        val saveCount = canvas.save()
        canvas.clipPath(path0)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            canvas.clipPath(path1)
        } else {
            canvas.clipPath(path1, Region.Op.INTERSECT)
        }
        canvas.drawBitmap(bitmap, 0f, 0f, null)
        canvas.rotate(degrees, bezierStart1.x, bezierStart1.y)
        backShadowDrawable.setBounds(
            leftX,
            bezierStart1.y.toInt(),
            rightX,
            (maxLength + bezierStart1.y).toInt()
        )
        backShadowDrawable.draw(canvas)
        canvas.restoreToCount(saveCount)
    }

    private fun drawCurrentPageArea(canvas: Canvas, bitmap: Bitmap) {
        path0.reset()
        path0.moveTo(bezierStart1.x, bezierStart1.y)
        path0.quadTo(bezierControl1.x, bezierControl1.y, bezierEnd1.x, bezierEnd1.y)
        path0.lineTo(touchX, touchY)
        path0.lineTo(bezierEnd2.x, bezierEnd2.y)
        path0.quadTo(bezierControl2.x, bezierControl2.y, bezierStart2.x, bezierStart2.y)
        path0.lineTo(cornerX.toFloat(), cornerY.toFloat())
        path0.close()
        val saveCount = canvas.save()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            canvas.clipOutPath(path0)
        } else {
            canvas.clipPath(path0, Region.Op.XOR)
        }
        canvas.drawBitmap(bitmap, 0f, 0f, null)
        canvas.restoreToCount(saveCount)
    }

    private fun calcCornerXY(x: Float, y: Float) {
        cornerX = if (x <= viewWidth / 2f) 0 else viewWidth
        cornerY = if (y <= viewHeight / 2f) 0 else viewHeight
        isRtOrLb = (cornerX == 0 && cornerY == viewHeight) ||
            (cornerY == 0 && cornerX == viewWidth)
    }

    private fun calcPoints() {
        middleX = (touchX + cornerX) / 2
        middleY = (touchY + cornerY) / 2
        bezierControl1.x =
            middleX - (cornerY - middleY) * (cornerY - middleY) / (cornerX - middleX)
        bezierControl1.y = cornerY.toFloat()
        bezierControl2.x = cornerX.toFloat()

        val f4 = cornerY - middleY
        if (f4 == 0f) {
            bezierControl2.y = middleY - (cornerX - middleX) * (cornerX - middleX) / 0.1f
        } else {
            bezierControl2.y =
                middleY - (cornerX - middleX) * (cornerX - middleX) / (cornerY - middleY)
        }
        bezierStart1.x = bezierControl1.x - (cornerX - bezierControl1.x) / 2
        bezierStart1.y = cornerY.toFloat()

        if (touchX > 0 && touchX < viewWidth) {
            if (bezierStart1.x < 0 || bezierStart1.x > viewWidth) {
                if (bezierStart1.x < 0) {
                    bezierStart1.x = viewWidth - bezierStart1.x
                }
                val f1 = abs(cornerX - touchX)
                val f2 = viewWidth * f1 / bezierStart1.x
                touchX = abs(cornerX - f2)
                val f3 = abs(cornerX - touchX) * abs(cornerY - touchY) / f1
                touchY = abs(cornerY - f3)
                middleX = (touchX + cornerX) / 2
                middleY = (touchY + cornerY) / 2
                bezierControl1.x =
                    middleX - (cornerY - middleY) * (cornerY - middleY) / (cornerX - middleX)
                bezierControl1.y = cornerY.toFloat()
                bezierControl2.x = cornerX.toFloat()
                val f5 = cornerY - middleY
                if (f5 == 0f) {
                    bezierControl2.y =
                        middleY - (cornerX - middleX) * (cornerX - middleX) / 0.1f
                } else {
                    bezierControl2.y =
                        middleY - (cornerX - middleX) * (cornerX - middleX) / (cornerY - middleY)
                }
                bezierStart1.x = bezierControl1.x - (cornerX - bezierControl1.x) / 2
            }
        }
        bezierStart2.x = cornerX.toFloat()
        bezierStart2.y = bezierControl2.y - (cornerY - bezierControl2.y) / 2
        touchToCornerDis = hypot((touchX - cornerX).toDouble(), (touchY - cornerY).toDouble()).toFloat()
        bezierEnd1.set(getCross(PointF(touchX, touchY), bezierControl1, bezierStart1, bezierStart2))
        bezierEnd2.set(getCross(PointF(touchX, touchY), bezierControl2, bezierStart1, bezierStart2))
        bezierVertex1.x = (bezierStart1.x + 2 * bezierControl1.x + bezierEnd1.x) / 4
        bezierVertex1.y = (2 * bezierControl1.y + bezierStart1.y + bezierEnd1.y) / 4
        bezierVertex2.x = (bezierStart2.x + 2 * bezierControl2.x + bezierEnd2.x) / 4
        bezierVertex2.y = (2 * bezierControl2.y + bezierStart2.y + bezierEnd2.y) / 4
    }

    private fun getCross(p1: PointF, p2: PointF, p3: PointF, p4: PointF): PointF {
        val a1 = (p2.y - p1.y) / (p2.x - p1.x)
        val b1 = (p1.x * p2.y - p2.x * p1.y) / (p1.x - p2.x)
        val a2 = (p4.y - p3.y) / (p4.x - p3.x)
        val b2 = (p3.x * p4.y - p4.x * p3.y) / (p3.x - p4.x)
        val x = (b2 - b1) / (a1 - a2)
        return PointF(x, a1 * x + b1)
    }
}
