package io.legado.app.ui.association

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.fragment.app.viewModels
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.RequestOptions
import com.bumptech.glide.request.target.Target
import io.legado.app.R
import io.legado.app.help.glide.ImageLoader
import io.legado.app.help.glide.OkHttpModelLoader
import io.legado.app.help.source.SourceVerificationHelp
import io.legado.app.model.ImageProvider
import io.legado.app.ui.theme.LegadoTheme
import io.legado.app.ui.widget.compose.AppDialogFrame
import io.legado.app.ui.widget.compose.AppDialogSize
import io.legado.app.ui.widget.compose.ComposeDialogFragment
import io.legado.app.ui.widget.compose.LegadoMiuixActionButton
import io.legado.app.ui.widget.compose.rememberAppDialogStyle
import io.legado.app.ui.widget.compose.showComposeConfirmDialog
import io.legado.app.ui.widget.compose.toMiuixPalette
import io.legado.app.ui.widget.dialog.PhotoDialog
import io.legado.app.utils.showDialogFragment

/**
 * 图片验证码对话框
 * 结果保存在内存中
 * val key = "${sourceOrigin ?: ""}_verificationResult"
 * CacheManager.get(key)
 */
class VerificationCodeDialog() : ComposeDialogFragment() {

    constructor(
        imageUrl: String,
        sourceOrigin: String? = null,
        sourceName: String? = null,
        sourceType: Int
    ) : this() {
        arguments = Bundle().apply {
            putString("imageUrl", imageUrl)
            putString("sourceOrigin", sourceOrigin)
            putString("sourceName", sourceName)
            putInt("sourceType", sourceType)
        }
    }

    override val dialogSize: AppDialogSize = AppDialogSize.Form

    val viewModel by viewModels<VerificationCodeViewModel>()

    private var sourceOrigin: String? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val arguments = arguments ?: Bundle()
        viewModel.initData(arguments)
        sourceOrigin = arguments.getString("sourceOrigin")
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                LegadoTheme {
                    VerificationCodePanel(
                        imageUrl = arguments.getString("imageUrl").orEmpty(),
                        sourceName = arguments.getString("sourceName")
                    )
                }
            }
        }
    }

    @Composable
    private fun VerificationCodePanel(imageUrl: String, sourceName: String?) {
        val style = rememberAppDialogStyle()
        val palette = style.toMiuixPalette()
        var verificationCode by rememberSaveable { mutableStateOf("") }
        AppDialogFrame(
            title = stringResource(R.string.input_verification_code),
            message = sourceName?.takeIf { it.isNotBlank() },
            content = {
                VerificationCodeImage(imageUrl = imageUrl, sourceOrigin = sourceOrigin)
                Spacer(modifier = Modifier.height(8.dp))
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = stringResource(R.string.verification_code),
                        color = style.secondaryText,
                        fontSize = MaterialTheme.typography.bodySmall.fontSize,
                        fontWeight = FontWeight.Medium
                    )
                    OutlinedTextField(
                        value = verificationCode,
                        onValueChange = { verificationCode = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        shape = RoundedCornerShape(style.actionRadius),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = style.primaryText,
                            unfocusedTextColor = style.primaryText,
                            disabledTextColor = style.secondaryText,
                            focusedContainerColor = style.fieldSurface,
                            unfocusedContainerColor = style.fieldSurface,
                            disabledContainerColor = style.fieldSurface.copy(alpha = 0.58f),
                            cursorColor = style.accent,
                            focusedBorderColor = style.accent.copy(alpha = 0.55f),
                            unfocusedBorderColor = style.stroke,
                            disabledBorderColor = style.stroke.copy(alpha = 0.38f),
                            focusedPlaceholderColor = style.secondaryText,
                            unfocusedPlaceholderColor = style.secondaryText
                        ),
                        textStyle = MaterialTheme.typography.bodyMedium.copy(
                            color = style.primaryText,
                            fontFamily = style.bodyFontFamily
                        )
                    )
                }
            },
            actions = {
                LegadoMiuixActionButton(
                    text = stringResource(R.string.disable_source),
                    palette = palette,
                    onClick = {
                        viewModel.disableSource {
                            dismissAllowingStateLoss()
                        }
                    },
                    cornerRadius = style.actionRadius
                )
                Spacer(modifier = Modifier.width(8.dp))
                LegadoMiuixActionButton(
                    text = stringResource(R.string.delete_source),
                    palette = palette,
                    onClick = {
                        showComposeConfirmDialog(
                            title = getString(R.string.draw),
                            message = getString(R.string.sure_del) + "\n" + viewModel.sourceName,
                            positiveText = getString(R.string.yes),
                            negativeText = getString(R.string.no),
                            dangerPositive = true,
                            onPositive = {
                                viewModel.deleteSource {
                                    dismissAllowingStateLoss()
                                }
                            }
                        )
                    },
                    cornerRadius = style.actionRadius
                )
                Spacer(modifier = Modifier.width(8.dp))
                LegadoMiuixActionButton(
                    text = stringResource(R.string.ok),
                    palette = palette,
                    onClick = {
                        SourceVerificationHelp.setResult(sourceOrigin!!, verificationCode)
                        dismissAllowingStateLoss()
                    },
                    primary = true,
                    cornerRadius = style.actionRadius
                )
            }
        )
    }

    @Composable
    private fun VerificationCodeImage(imageUrl: String, sourceOrigin: String?) {
        AndroidView(
            factory = { context ->
                ImageView(context).apply {
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    if (imageUrl.isNotEmpty()) {
                        loadImage(imageUrl, sourceOrigin, this)
                    }
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(100.dp)
                .clickable {
                    showDialogFragment(PhotoDialog(imageUrl, sourceOrigin))
                }
        )
    }

    @SuppressLint("CheckResult")
    private fun loadImage(url: String, sourceUrl: String?, imageView: ImageView) {
        ImageProvider.remove(url)
        ImageLoader.loadBitmap(requireContext(), url).apply {
            sourceUrl?.let {
                apply(RequestOptions().set(OkHttpModelLoader.sourceOriginOption, it))
            }
        }.error(R.drawable.image_loading_error)
            .diskCacheStrategy(DiskCacheStrategy.NONE)
            .skipMemoryCache(true)
            .listener(object : RequestListener<Bitmap> {
                override fun onLoadFailed(
                    e: GlideException?,
                    model: Any?,
                    target: Target<Bitmap?>,
                    isFirstResource: Boolean
                ): Boolean {
                    return false
                }

                override fun onResourceReady(
                    resource: Bitmap,
                    model: Any,
                    target: Target<Bitmap?>?,
                    dataSource: DataSource,
                    isFirstResource: Boolean
                ): Boolean {
                    val bitmap = resource.copy(resource.config!!, true)
                    ImageProvider.put(url, bitmap) // 传给 PhotoDialog
                    return false
                }
            })
            .into(imageView)
    }

    override fun onDestroy() {
        SourceVerificationHelp.checkResult(sourceOrigin!!)
        super.onDestroy()
        activity?.finish()
    }

}
