package io.legado.app.ui.book.read.epub

import android.graphics.Canvas
import android.graphics.Picture
import android.graphics.RectF
import io.legado.app.model.localBook.epubcore.layout.EpubCorePage
import kotlin.math.roundToInt

internal class EpubPageDisplayList private constructor(
    val pageIndex: Int,
    private val width: Int,
    private val height: Int,
    private val rendererVersion: Long,
    private val picture: Picture
) {

    fun matches(pageIndex: Int, width: Int, height: Int, rendererVersion: Long): Boolean {
        return this.pageIndex == pageIndex &&
            this.width == width &&
            this.height == height &&
            this.rendererVersion == rendererVersion
    }

    fun draw(canvas: Canvas) {
        canvas.drawPicture(picture)
    }

    companion object {
        fun record(
            renderer: EpubPageRenderer,
            page: EpubCorePage?,
            pageIndex: Int,
            pageCount: Int,
            viewport: RectF
        ): EpubPageDisplayList? {
            val width = viewport.width().roundToInt()
            val height = viewport.height().roundToInt()
            if (width <= 0 || height <= 0) return null

            val picture = Picture()
            val recordingCanvas = picture.beginRecording(width, height)
            try {
                renderer.drawPage(
                    canvas = recordingCanvas,
                    page = page,
                    pageIndex = pageIndex,
                    pageCount = pageCount,
                    viewport = RectF(0f, 0f, width.toFloat(), height.toFloat())
                )
            } finally {
                picture.endRecording()
            }
            return EpubPageDisplayList(
                pageIndex = pageIndex,
                width = width,
                height = height,
                rendererVersion = renderer.renderStateVersion,
                picture = picture
            )
        }
    }
}
