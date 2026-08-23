package io.legado.app.ui.image

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.result.contract.ActivityResultContract

class ImageCropContract : ActivityResultContract<ImageCropContract.Params, ImageCropContract.Result?>() {

    override fun createIntent(context: Context, input: Params): Intent {
        return Intent(context, ImageCropActivity::class.java).apply {
            putExtra(ImageCropActivity.EXTRA_URI, input.uri.toString())
            putExtra(ImageCropActivity.EXTRA_ASPECT_WIDTH, input.aspectWidth)
            putExtra(ImageCropActivity.EXTRA_ASPECT_HEIGHT, input.aspectHeight)
            putExtra(ImageCropActivity.EXTRA_DIR_NAME, input.dirName)
            putExtra(ImageCropActivity.EXTRA_PREFIX, input.prefix)
            putExtra(ImageCropActivity.EXTRA_TARGET_WIDTH, input.targetWidth)
            putExtra(ImageCropActivity.EXTRA_OUTPUT_PATH, input.outputPath)
            putExtra(ImageCropActivity.EXTRA_VIEWPORT_ONLY, input.viewportOnly)
        }
    }

    override fun parseResult(resultCode: Int, intent: Intent?): Result? {
        if (resultCode != Activity.RESULT_OK) return null
        val path = intent?.getStringExtra(ImageCropActivity.EXTRA_RESULT_PATH) ?: return null
        return Result(
            path = path,
            cropLeft = intent.getFloatExtra(ImageCropActivity.EXTRA_RESULT_CROP_LEFT, Float.NaN)
                .takeIf { !it.isNaN() },
            cropTop = intent.getFloatExtra(ImageCropActivity.EXTRA_RESULT_CROP_TOP, Float.NaN)
                .takeIf { !it.isNaN() },
            cropRight = intent.getFloatExtra(ImageCropActivity.EXTRA_RESULT_CROP_RIGHT, Float.NaN)
                .takeIf { !it.isNaN() },
            cropBottom = intent.getFloatExtra(ImageCropActivity.EXTRA_RESULT_CROP_BOTTOM, Float.NaN)
                .takeIf { !it.isNaN() }
        )
    }

    data class Params(
        val uri: Uri,
        val aspectWidth: Int,
        val aspectHeight: Int,
        val dirName: String,
        val prefix: String,
        val targetWidth: Int = 1600,
        val outputPath: String? = null,
        val viewportOnly: Boolean = false
    )

    data class Result(
        val path: String,
        val cropLeft: Float? = null,
        val cropTop: Float? = null,
        val cropRight: Float? = null,
        val cropBottom: Float? = null
    )
}
