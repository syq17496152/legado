package io.legado.app.ui.image

import android.app.Activity
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.widget.AppCompatImageButton
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.viewbinding.ViewBinding
import io.legado.app.R
import io.legado.app.base.BaseActivity
import io.legado.app.base.attachComposeContent
import io.legado.app.base.composeShell
import io.legado.app.constant.AppLog
import io.legado.app.help.http.addHeaders
import io.legado.app.help.http.newCallResponse
import io.legado.app.help.http.okHttpClient
import io.legado.app.lib.theme.accentColor
import io.legado.app.model.analyzeRule.AnalyzeUrl
import io.legado.app.ui.widget.ImageCropOverlayView
import io.legado.app.ui.widget.compose.AppSemanticColors
import io.legado.app.ui.widget.image.PhotoView
import io.legado.app.utils.ImageProcessUtils
import io.legado.app.utils.dpToPx
import io.legado.app.utils.printOnDebug
import io.legado.app.utils.setLightStatusBar
import io.legado.app.utils.setNavigationBarColorAuto
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.roundToInt

class ImageCropActivity : BaseActivity<ViewBinding>(
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

    // CE 5.2（compose 包）：原 activity_image_crop.xml 已退役 ⇒ composeShell 合成壳 +
    // attachComposeContent 单源承载；**整棵 View 子树在一个 `AndroidView` 内原样托管**，
    // 托管容器仍是 `FrameLayout`——两处动态几何依赖 `FrameLayout.LayoutParams` 语义
    // （`cropOverlay.getCropRect()` 驱动 photoView 尺寸/边距与比例徽标位置），换成 Compose
    // 布局会丢掉该坐标系，故容器类型不变（`replace_edit` / `code_edit` 同范式）。
    override val binding: ViewBinding by lazy { composeShell(this) }

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

    // ==================== CE 5.2：原 XML 节点的程序化等价物 ====================
    // 逐节点复刻 activity_image_crop.xml：根 FrameLayout（深色底）/ photoView / cropOverlay /
    // 比例徽标 / 错误条（tv_error + 重试键）/ 操作栏（取消 + 提示 + 保存中 spinner + 确认）。

    /** 画布固定深色底（具名资源，替代原 XML 行内深色字面值；画布域，不随应用主题）。 */
    private val canvasColor: Int get() = ContextCompat.getColor(this, R.color.image_canvas_dark)

    /** 原 `@android:color/white`（画布域亮色控件压在图片上；画布域登记见 theme_token_allowlist.json）。 */
    private fun canvasWhite(): Int = Color.WHITE

    private val tvError by lazy {
        TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            textSize = 13f
        }
    }
    private val btnRetry by lazy {
        TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            )
            setBackgroundResource(selectableItemBackgroundRes())
            val h = 12.dpToPx()
            val v = 6.dpToPx()
            setPadding(h, v, h, v)
            setText(R.string.retry)
            textSize = 13f
            setTypeface(typeface, Typeface.BOLD)
        }
    }

    /** 原 `error_bar`（底部、左右 16dp、下 76dp；`bg_image_crop_error_bar` 底 + 重试键）。 */
    private val errorBar by lazy {
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
            setBackgroundResource(R.drawable.bg_image_crop_error_bar)
            setPadding(14.dpToPx(), 10.dpToPx(), 6.dpToPx(), 10.dpToPx())
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.BOTTOM
                marginStart = 16.dpToPx()
                marginEnd = 16.dpToPx()
                bottomMargin = 76.dpToPx()
            }
            addView(tvError)
            addView(btnRetry)
        }
    }

    private val tvHint by lazy {
        TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                .apply {
                    marginStart = 16.dpToPx()
                    marginEnd = 16.dpToPx()
                }
            gravity = Gravity.CENTER
            setText(R.string.image_crop_hint)
            setTextColor(canvasWhite())
            textSize = 14f
        }
    }

    /** 原 `progress_save`（`?android:attr/progressBarStyleSmall` + 白色 indeterminate）。 */
    private val progressSave by lazy {
        ProgressBar(this, null, android.R.attr.progressBarStyleSmall).apply {
            layoutParams = LinearLayout.LayoutParams(44.dpToPx(), 44.dpToPx())
            setPadding(12.dpToPx(), 12.dpToPx(), 12.dpToPx(), 12.dpToPx())
            indeterminateTintList = ColorStateList.valueOf(canvasWhite())
            visibility = View.GONE
        }
    }

    private val btnCancel by lazy {
        cropToolButton(R.drawable.ic_baseline_close, R.string.cancel)
    }
    private val btnConfirm by lazy {
        cropToolButton(R.drawable.ic_check, R.string.confirm)
    }

    /** 原 `action_bar`（底部 64dp、左右 16dp：取消 / 提示 / 保存中 spinner / 确认）。 */
    private val actionBar by lazy {
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(16.dpToPx(), 0, 16.dpToPx(), 0)
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, 64.dpToPx()
            ).apply { gravity = Gravity.BOTTOM }
            addView(btnCancel)
            addView(tvHint)
            addView(progressSave)
            addView(btnConfirm)
        }
    }

    /** 原 `tv_aspect_badge`（`bg_image_crop_aspect_badge` 底 / monospace 粗体 11sp / 白字）。 */
    private val tvAspectBadge by lazy {
        TextView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT
            )
            setBackgroundResource(R.drawable.bg_image_crop_aspect_badge)
            typeface = Typeface.MONOSPACE
            val h = 8.dpToPx()
            val v = 3.dpToPx()
            setPadding(h, v, h, v)
            setTextColor(canvasWhite())
            textSize = 11f
            setTypeface(typeface, Typeface.BOLD)
            visibility = View.GONE
        }
    }

    private val photoView by lazy {
        PhotoView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(canvasColor)
        }
    }
    private val cropOverlay by lazy {
        ImageCropOverlayView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
    }

    /** 原 XML 根 `FrameLayout`（深色底 + 五个子节点，声明顺序即 z-order）。 */
    private val cropRoot by lazy {
        FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(canvasColor)
            addView(photoView)
            addView(cropOverlay)
            addView(tvAspectBadge)
            addView(errorBar)
            addView(actionBar)
        }
    }

    /** 原操作栏按钮（44dp + `bg_image_crop_toolbar` 圆底 + 10dp 内边距 + 白色图标）。 */
    private fun cropToolButton(iconRes: Int, descRes: Int): AppCompatImageButton =
        AppCompatImageButton(this).apply {
            layoutParams = LinearLayout.LayoutParams(44.dpToPx(), 44.dpToPx())
            setBackgroundResource(R.drawable.bg_image_crop_toolbar)
            contentDescription = getString(descRes)
            val p = 10.dpToPx()
            setPadding(p, p, p, p)
            setImageResource(iconRes)
            setColorFilter(canvasWhite())
        }

    /** `?attr/selectableItemBackground` 的样式资源 id（原错误条重试键的波纹底）。 */
    private fun selectableItemBackgroundRes(): Int {
        val outValue = TypedValue()
        theme.resolveAttribute(android.R.attr.selectableItemBackground, outValue, true)
        return outValue.resourceId
    }

    /**
     * CE 5.2：Compose 承载页面骨架。
     *
     * 本页是**纯画布 / 手势页（无顶栏）**：Compose 侧只承载合成壳与单点 `AndroidView`，
     * 全部 View 节点（`PhotoView` / `ImageCropOverlayView` / 三个条形控件）在 `FrameLayout`
     * 容器内原样复刻 XML —— 手势必填、动态几何与 drawable 底均一字未改（三不影响口径）。
     */
    private fun initComposeContent() {
        // 原 XML 根底行内深色字面值（已具名化为 R.color.image_canvas_dark）
        binding.root.setBackgroundColor(canvasColor)
        binding.root.attachComposeContent {
            Box(modifier = Modifier.fillMaxSize()) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { cropRoot }
                )
            }
        }
    }

    override fun setupSystemBar() {
        super.setupSystemBar()
        setLightStatusBar(false)
        setNavigationBarColorAuto(Color.BLACK)
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        initComposeContent()
        aspectWidth = intent.getIntExtra(EXTRA_ASPECT_WIDTH, 1).coerceAtLeast(1)
        aspectHeight = intent.getIntExtra(EXTRA_ASPECT_HEIGHT, 1).coerceAtLeast(1)
        dirName = intent.getStringExtra(EXTRA_DIR_NAME).orEmpty().ifBlank { "images" }
        prefix = intent.getStringExtra(EXTRA_PREFIX).orEmpty().ifBlank { "crop" }
        targetWidth = intent.getIntExtra(EXTRA_TARGET_WIDTH, 1600).coerceAtLeast(128)
        outputPath = intent.getStringExtra(EXTRA_OUTPUT_PATH)
        viewportOnly = intent.getBooleanExtra(EXTRA_VIEWPORT_ONLY, false)
        cropOverlay.setAspect(aspectWidth, aspectHeight)
        photoView.setScaleType(ImageView.ScaleType.CENTER_INSIDE)
        photoView.setMaxScale(6f)
        btnCancel.setOnClickListener { finish() }
        btnConfirm.setOnClickListener { saveCrop() }
        // 优化 1a：比例徽标要跟随取景框（setAspect / 尺寸变化都会重算 cropRect）
        cropOverlay.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            updateAspectBadge()
        }
        // 优化 2：错误条重试入口（动作由失败点登记）
        btnRetry.setTextColor(accentColor)
        btnRetry.setOnClickListener { retryAction?.invoke() }
        tvError.setTextColor(AppSemanticColors.Danger.toArgb())
        cropOverlay.post {
            updatePhotoViewport()
            updateAspectBadge()
        }
        loadImage()
    }

    /** 优化 1a：取景框左上角常驻比例徽标（蓝图 preview-optimized.html 帧 3） */
    private fun updateAspectBadge() {
        val cropRect = cropOverlay.getCropRect()
        if (cropRect.isEmpty) return
        tvAspectBadge.text = "$aspectWidth:$aspectHeight"
        tvAspectBadge.post {
            val layoutParams = tvAspectBadge.layoutParams as FrameLayout.LayoutParams
            layoutParams.leftMargin = (cropRect.left + 8.dpToPx()).roundToInt()
            layoutParams.topMargin = (cropRect.top + 8.dpToPx()).roundToInt()
            tvAspectBadge.layoutParams = layoutParams
            tvAspectBadge.visibility = View.VISIBLE
        }
    }

    /**
     * 优化 2：可恢复失败改为页内错误条（danger 语义 + 重试）。
     * 不可恢复错误（如「图片链接为空」）仍走 toast + finish，不进此条。
     */
    private fun showCropError(reason: String, retry: () -> Unit) {
        retryAction = retry
        tvError.text = getString(R.string.image_crop_failed, reason)
        errorBar.visibility = View.VISIBLE
    }

    private fun hideCropError() {
        retryAction = null
        errorBar.visibility = View.GONE
    }

    /** 优化 1b：确认键保存中态（spinner 顶替确认键 + 禁用 + 文案切换） */
    private fun setSavingState(saving: Boolean) {
        btnConfirm.visibility = if (saving) View.GONE else View.VISIBLE
        btnConfirm.isEnabled = !saving
        progressSave.visibility = if (saving) View.VISIBLE else View.GONE
        tvHint.setText(if (saving) R.string.image_crop_saving else R.string.image_crop_hint)
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
            photoView.setImageBitmap(bitmap)
            photoView.post {
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
        val cropRect = cropOverlay.getCropRect()
        if (cropRect.isEmpty) return
        val layoutParams = (photoView.layoutParams as? FrameLayout.LayoutParams)
            ?: FrameLayout.LayoutParams(
                cropRect.width().roundToInt(),
                cropRect.height().roundToInt()
            )
        layoutParams.width = cropRect.width().roundToInt()
        layoutParams.height = cropRect.height().roundToInt()
        layoutParams.leftMargin = cropRect.left.roundToInt()
        layoutParams.topMargin = cropRect.top.roundToInt()
        photoView.layoutParams = layoutParams
        photoView.scaleType = ImageView.ScaleType.CENTER_INSIDE
        photoView.post {
            photoView.fillRect(
                RectF(
                    0f,
                    0f,
                    photoView.width.toFloat(),
                    photoView.height.toFloat()
                )
            )
        }
    }

    private fun saveCrop() {
        val bitmap = sourceBitmap ?: return
        val cropRect = RectF(0f, 0f, photoView.width.toFloat(), photoView.height.toFloat())
        val matrix = photoView.getDisplayMatrixCopy()
        btnConfirm.isEnabled = false
        if (viewportOnly) {
            val viewport = calculateNormalizedCropRect(bitmap, cropRect, matrix)
            if (viewport == null || outputPath.isNullOrBlank()) {
                btnConfirm.isEnabled = true
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
