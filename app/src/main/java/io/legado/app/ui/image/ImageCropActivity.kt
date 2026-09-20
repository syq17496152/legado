package io.legado.app.ui.image

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.RectF
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.FrameLayout
import androidx.compose.ui.graphics.toArgb
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.base.BaseActivity
import io.legado.app.constant.AppLog
import io.legado.app.databinding.ActivityImageCropBinding
import io.legado.app.help.http.addHeaders
import io.legado.app.help.http.newCallResponse
import io.legado.app.help.http.okHttpClient
import io.legado.app.lib.theme.accentColor
import io.legado.app.model.analyzeRule.AnalyzeUrl
import io.legado.app.ui.widget.compose.AppSemanticColors
import io.legado.app.utils.ImageProcessUtils
import io.legado.app.utils.dpToPx
import io.legado.app.utils.printOnDebug
import io.legado.app.utils.setLightStatusBar
import io.legado.app.utils.setNavigationBarColorAuto
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.roundToInt

class ImageCropActivity : BaseActivity<ActivityImageCropBinding>(
    transparent = true,
    imageBg = false
) {

    companion object {
        const val EXTRA_URI = "uri"
        const val EXTRA_ASPECT_WIDTH = "aspectWidth"
        const val EXTRA_ASPECT_HEIGHT = "aspectHeight"
        const val EXTRA_DIR_NAME = "dirName"
        const val EXTRA_PREFIX = "prefix"
        const val EXTRA_TARGET_WIDTH = "targetWidth"
        const val EXTRA_OUTPUT_PATH = "outputPath"
        const val EXTRA_VIEWPORT_ONLY = "viewportOnly"
        const val EXTRA_RESULT_PATH = "resultPath"
        const val EXTRA_RESULT_CROP_LEFT = "resultCropLeft"
        const val EXTRA_RESULT_CROP_TOP = "resultCropTop"
        const val EXTRA_RESULT_CROP_RIGHT = "resultCropRight"
        const val EXTRA_RESULT_CROP_BOTTOM = "resultCropBottom"
    }

    override val binding by viewBinding(ActivityImageCropBinding::inflate)

    private var sourceBitmap: Bitmap? = null
    private var aspectWidth = 1
    private var aspectHeight = 1
    private var dirName = "images"
    private var prefix = "crop"
    private var targetWidth = 1600
    private var outputPath: String? = null
    private var viewportOnly = false
    // 优化 2：可恢复失败的页内错误条 —— 重试动作就地登记（按失败阶段决定重载图片还是重跑裁剪）
    private var retryAction: (() -> Unit)? = null

    override fun setupSystemBar() {
        super.setupSystemBar()
        setLightStatusBar(false)
        setNavigationBarColorAuto(Color.BLACK)
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        aspectWidth = intent.getIntExtra(EXTRA_ASPECT_WIDTH, 1).coerceAtLeast(1)
        aspectHeight = intent.getIntExtra(EXTRA_ASPECT_HEIGHT, 1).coerceAtLeast(1)
        dirName = intent.getStringExtra(EXTRA_DIR_NAME).orEmpty().ifBlank { "images" }
        prefix = intent.getStringExtra(EXTRA_PREFIX).orEmpty().ifBlank { "crop" }
        targetWidth = intent.getIntExtra(EXTRA_TARGET_WIDTH, 1600).coerceAtLeast(128)
        outputPath = intent.getStringExtra(EXTRA_OUTPUT_PATH)
        viewportOnly = intent.getBooleanExtra(EXTRA_VIEWPORT_ONLY, false)
        binding.cropOverlay.setAspect(aspectWidth, aspectHeight)
        binding.photoView.setScaleType(ImageView.ScaleType.CENTER_INSIDE)
        binding.photoView.setMaxScale(6f)
        binding.btnCancel.setOnClickListener { finish() }
        binding.btnConfirm.setOnClickListener { saveCrop() }
        // 优化 1a：比例徽标要跟随取景框（setAspect / 尺寸变化都会重算 cropRect）
        binding.cropOverlay.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            updateAspectBadge()
        }
        // 优化 2：错误条重试入口（动作由失败点登记）
        binding.btnRetry.setTextColor(accentColor)
        binding.btnRetry.setOnClickListener { retryAction?.invoke() }
        binding.tvError.setTextColor(AppSemanticColors.Danger.toArgb())
        binding.cropOverlay.post {
            updatePhotoViewport()
            updateAspectBadge()
        }
        loadImage()
    }

    /** 优化 1a：取景框左上角常驻比例徽标（蓝图 preview-optimized.html 帧 3） */
    private fun updateAspectBadge() {
        val cropRect = binding.cropOverlay.getCropRect()
        if (cropRect.isEmpty) return
        binding.tvAspectBadge.text = "$aspectWidth:$aspectHeight"
        binding.tvAspectBadge.post {
            val layoutParams = binding.tvAspectBadge.layoutParams as FrameLayout.LayoutParams
            layoutParams.leftMargin = (cropRect.left + 8.dpToPx()).roundToInt()
            layoutParams.topMargin = (cropRect.top + 8.dpToPx()).roundToInt()
            binding.tvAspectBadge.layoutParams = layoutParams
            binding.tvAspectBadge.visibility = View.VISIBLE
        }
    }

    /**
     * 优化 2：可恢复失败改为页内错误条（danger 语义 + 重试）。
     * 不可恢复错误（如「图片链接为空」）仍走 toast + finish，不进此条。
     */
    private fun showCropError(reason: String, retry: () -> Unit) {
        retryAction = retry
        binding.tvError.text = getString(R.string.image_crop_failed, reason)
        binding.errorBar.visibility = View.VISIBLE
    }

    private fun hideCropError() {
        retryAction = null
        binding.errorBar.visibility = View.GONE
    }

    /** 优化 1b：确认键保存中态（spinner 顶替确认键 + 禁用 + 文案切换） */
    private fun setSavingState(saving: Boolean) {
        binding.btnConfirm.visibility = if (saving) View.GONE else View.VISIBLE
        binding.btnConfirm.isEnabled = !saving
        binding.progressSave.visibility = if (saving) View.VISIBLE else View.GONE
        binding.tvHint.setText(if (saving) R.string.image_crop_saving else R.string.image_crop_hint)
    }

    override fun onDestroy() {
        sourceBitmap?.recycle()
        sourceBitmap = null
        super.onDestroy()
    }

    private fun loadImage() {
        val uri = intent.getStringExtra(EXTRA_URI)?.let { Uri.parse(it) }
        if (uri == null) {
            // 不可恢复（调用方未传图片）：保留原 toast + finish 语义
            toastOnUi(getString(R.string.image_crop_failed, getString(R.string.error_image_url_empty)))
            finish()
            return
        }
        hideCropError()
        lifecycleScope.launch {
            val bitmap = withContext(Dispatchers.IO) {
                kotlin.runCatching {
                    decodeBitmapFromStableFile(uri)
                }.onFailure {
                    it.printOnDebug()
                }.getOrNull()
            }
            if (bitmap == null) {
                // 可恢复（远端 403/格式不支持等）：页内错误条 + 重试，不再关页
                showCropError(getString(R.string.error_decode_bitmap)) { loadImage() }
                return@launch
            }
            sourceBitmap = bitmap
            binding.photoView.setImageBitmap(bitmap)
            binding.photoView.post {
                updatePhotoViewport()
            }
        }
    }

    private suspend fun decodeBitmapFromStableFile(uri: Uri): Bitmap? {
        val tempDir = File(cacheDir, "image_crop_source").apply { mkdirs() }
        val tempFile = File.createTempFile("source_", ".img", tempDir)
        try {
            copyImageSourceToFile(uri, tempFile)
            if (!tempFile.exists() || tempFile.length() <= 0L) return null
            val metrics = resources.displayMetrics
            val expectHeight = (targetWidth * aspectHeight.toFloat() / aspectWidth)
                .roundToInt()
                .coerceAtLeast(128)
            val decodeWidth = maxOf(metrics.widthPixels, targetWidth).coerceAtLeast(128)
            val decodeHeight = maxOf(metrics.heightPixels, expectHeight).coerceAtLeast(128)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                decodeBitmapWithImageDecoder(tempFile, decodeWidth, decodeHeight, uri)?.let {
                    return it
                }
            }
            val options = decodeBounds(tempFile)
            if (options.outWidth <= 0 || options.outHeight <= 0) return null
            val sampleSize = ImageProcessUtils.calculateSampleSize(
                options.outWidth,
                options.outHeight,
                decodeWidth,
                decodeHeight
            )
            return decodeBitmapWithBitmapFactory(tempFile, sampleSize)
        } finally {
            tempFile.delete()
        }
    }

    private suspend fun copyImageSourceToFile(uri: Uri, target: File) {
        if (uri.scheme.equals("http", true) || uri.scheme.equals("https", true)) {
            val analyzeUrl = AnalyzeUrl(uri.toString())
            okHttpClient.newCallResponse(0) {
                addHeaders(analyzeUrl.headerMap)
                url(analyzeUrl.urlNoQuery)
            }.use { response ->
                if (!response.isSuccessful) {
                    error("HTTP ${response.code}")
                }
                response.body.byteStream().use { input ->
                    target.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
            }
            return
        }
        contentResolver.openInputStream(uri)?.use { input ->
            target.outputStream().use { output ->
                input.copyTo(output)
            }
        } ?: error("Open input stream failed")
    }

    private fun decodeBounds(file: File): BitmapFactory.Options {
        return BitmapFactory.Options().apply {
            inJustDecodeBounds = true
            file.inputStream().use {
                BitmapFactory.decodeStream(it, null, this)
            }
        }
    }

    private fun decodeBitmapWithImageDecoder(
        file: File,
        targetDecodeWidth: Int,
        targetDecodeHeight: Int,
        sourceUri: Uri
    ): Bitmap? {
        return kotlin.runCatching {
            val source = ImageDecoder.createSource(file)
            ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                decoder.isMutableRequired = false
                val sampleSize = ImageProcessUtils.calculateSampleSize(
                    info.size.width,
                    info.size.height,
                    targetDecodeWidth,
                    targetDecodeHeight
                )
                val width = (info.size.width / sampleSize).coerceAtLeast(1)
                val height = (info.size.height / sampleSize).coerceAtLeast(1)
                decoder.setTargetSize(width, height)
            }.copy(Bitmap.Config.ARGB_8888, false)
        }.onFailure {
            AppLog.putDebug(
                "ImageDecoder failed for crop source: uri=$sourceUri, size=${file.length()}",
                it
            )
        }.getOrNull()
    }

    private fun decodeBitmapWithBitmapFactory(file: File, sampleSize: Int): Bitmap? {
        return file.inputStream().use {
            BitmapFactory.decodeStream(
                it,
                null,
                BitmapFactory.Options().apply {
                    inSampleSize = sampleSize
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                }
            )
        }
    }

    private fun updatePhotoViewport() {
        val cropRect = binding.cropOverlay.getCropRect()
        if (cropRect.isEmpty) return
        val layoutParams = (binding.photoView.layoutParams as? FrameLayout.LayoutParams)
            ?: FrameLayout.LayoutParams(
                cropRect.width().roundToInt(),
                cropRect.height().roundToInt()
            )
        layoutParams.width = cropRect.width().roundToInt()
        layoutParams.height = cropRect.height().roundToInt()
        layoutParams.leftMargin = cropRect.left.roundToInt()
        layoutParams.topMargin = cropRect.top.roundToInt()
        binding.photoView.layoutParams = layoutParams
        binding.photoView.scaleType = ImageView.ScaleType.CENTER_INSIDE
        binding.photoView.post {
            binding.photoView.fillRect(
                RectF(
                    0f,
                    0f,
                    binding.photoView.width.toFloat(),
                    binding.photoView.height.toFloat()
                )
            )
        }
    }

    private fun saveCrop() {
        val bitmap = sourceBitmap ?: return
        val cropRect = RectF(0f, 0f, binding.photoView.width.toFloat(), binding.photoView.height.toFloat())
        val matrix = binding.photoView.getDisplayMatrixCopy()
        binding.btnConfirm.isEnabled = false
        if (viewportOnly) {
            val viewport = calculateNormalizedCropRect(bitmap, cropRect, matrix)
            if (viewport == null || outputPath.isNullOrBlank()) {
                binding.btnConfirm.isEnabled = true
                toastOnUi(getString(R.string.image_crop_failed, getString(R.string.unknown)))
                return
            }
            setResult(
                Activity.RESULT_OK,
                Intent()
                    .putExtra(EXTRA_RESULT_PATH, outputPath)
                    .putExtra(EXTRA_RESULT_CROP_LEFT, viewport.left)
                    .putExtra(EXTRA_RESULT_CROP_TOP, viewport.top)
                    .putExtra(EXTRA_RESULT_CROP_RIGHT, viewport.right)
                    .putExtra(EXTRA_RESULT_CROP_BOTTOM, viewport.bottom)
            )
            finish()
            return
        }
        lifecycleScope.launch {
            setSavingState(true)
            val resultPath = withContext(Dispatchers.IO) {
                kotlin.runCatching {
                    val cropped = cropVisibleBitmap(bitmap, cropRect, matrix) ?: return@runCatching null
                    try {
                        ImageProcessUtils.saveBitmapToFile(
                            context = this@ImageCropActivity,
                            bitmap = cropped,
                            aspectWidth = aspectWidth,
                            aspectHeight = aspectHeight,
                            dirName = dirName,
                            prefix = prefix,
                            targetWidth = targetWidth,
                            outputPath = outputPath
                        )
                    } finally {
                        cropped.recycle()
                    }
                }.onFailure {
                    it.printOnDebug()
                }.getOrNull()
            }
            if (resultPath.isNullOrBlank()) {
                // 可恢复：恢复按钮态 + 页内错误条（重试直接重跑本次裁剪）
                setSavingState(false)
                showCropError(getString(R.string.unknown)) { saveCrop() }
                return@launch
            }
            setResult(Activity.RESULT_OK, Intent().putExtra(EXTRA_RESULT_PATH, resultPath))
            finish()
        }
    }

    private fun cropVisibleBitmap(source: Bitmap, cropRect: RectF, matrix: Matrix): Bitmap? {
        if (cropRect.isEmpty) return null
        val cropWidth = cropRect.width().roundToInt().coerceAtLeast(1)
        val cropHeight = cropRect.height().roundToInt().coerceAtLeast(1)
        val output = Bitmap.createBitmap(cropWidth, cropHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        canvas.drawColor(Color.TRANSPARENT)
        val drawMatrix = Matrix(matrix).apply {
            postTranslate(-cropRect.left, -cropRect.top)
        }
        val drawable = BitmapDrawable(resources, source)
        drawable.setBounds(0, 0, source.width, source.height)
        canvas.concat(drawMatrix)
        drawable.draw(canvas)
        return output
    }

    private fun calculateNormalizedCropRect(source: Bitmap, cropRect: RectF, matrix: Matrix): RectF? {
        if (cropRect.isEmpty || source.width <= 0 || source.height <= 0) return null
        val inverse = Matrix()
        if (!matrix.invert(inverse)) return null
        val sourceRect = RectF(cropRect)
        inverse.mapRect(sourceRect)
        sourceRect.intersect(0f, 0f, source.width.toFloat(), source.height.toFloat())
        if (sourceRect.isEmpty) return null
        return RectF(
            (sourceRect.left / source.width).coerceIn(0f, 1f),
            (sourceRect.top / source.height).coerceIn(0f, 1f),
            (sourceRect.right / source.width).coerceIn(0f, 1f),
            (sourceRect.bottom / source.height).coerceIn(0f, 1f)
        ).takeIf { it.right > it.left && it.bottom > it.top }
    }
}
