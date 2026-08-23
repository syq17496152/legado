package io.legado.app.ui.about

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.material3.LocalTextStyle
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.viewinterop.AndroidView
import io.legado.app.R
import io.legado.app.help.update.AppUpdate
import io.legado.app.lib.theme.uiTypeface
import io.legado.app.model.Download
import io.legado.app.ui.widget.compose.AppDialogFrame
import io.legado.app.ui.widget.compose.AppDialogSize
import io.legado.app.ui.widget.compose.ComposeDialogFragment
import io.legado.app.ui.widget.compose.LegadoMiuixActionButton
import io.legado.app.ui.widget.compose.rememberAppDialogStyle
import io.legado.app.ui.widget.compose.toMiuixPalette
import io.legado.app.utils.ConvertUtils
import io.legado.app.utils.toastOnUi
import io.noties.markwon.Markwon
import io.noties.markwon.ext.tables.TablePlugin
import io.noties.markwon.html.HtmlPlugin
import io.noties.markwon.image.glide.GlideImagesPlugin
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class UpdateDialog() : ComposeDialogFragment() {

    override val dialogSize: AppDialogSize = AppDialogSize.Confirm

    constructor(updateInfo: AppUpdate.UpdateInfo) : this() {
        arguments = Bundle().apply {
            putString("newVersion", updateInfo.tagName)
            putString("updateBody", updateInfo.updateLog)
            putString("url", updateInfo.downloadUrl)
            putString("name", updateInfo.fileName)
            putLong("assetSize", updateInfo.assetSize)
            putLong("publishDate", updateInfo.publishDate)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                val style = rememberAppDialogStyle()
                val title = arguments?.getString("newVersion").orEmpty()
                val updateBody = arguments?.getString("updateBody")
                val url = arguments?.getString("url")
                val name = arguments?.getString("name")
                // 显示包大小与发布日期 (sync-upstream-optimizations-20260816 R3)
                val assetInfo = buildString {
                    val assetSize = arguments?.getLong("assetSize") ?: 0L
                    if (assetSize > 0) append(ConvertUtils.formatFileSize(assetSize))
                    val publishDate = arguments?.getLong("publishDate") ?: 0L
                    if (publishDate > 0) {
                        if (isNotEmpty()) append(" ｜ ")
                        append(
                            SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(publishDate))
                        )
                    }
                }.takeIf { it.isNotEmpty() }

                if (updateBody == null) {
                    toastOnUi("没有数据")
                    dismissAllowingStateLoss()
                    return@setContent
                }

                val context = LocalContext.current
                val markwon = Markwon.builder(context)
                    .usePlugin(GlideImagesPlugin.create(context))
                    .usePlugin(HtmlPlugin.create())
                    .usePlugin(TablePlugin.create(context))
                    .build()

                CompositionLocalProvider(
                    LocalTextStyle provides LocalTextStyle.current.copy(fontFamily = style.bodyFontFamily)
                ) {
                    AppDialogFrame(
                        title = title,
                        message = assetInfo,
                        content = {
                            AndroidView(
                                modifier = Modifier.fillMaxWidth(),
                                factory = { ctx ->
                                    TextView(ctx).apply {
                                        setTextColor(style.primaryText.toArgb())
                                        typeface = ctx.uiTypeface()
                                        setTextIsSelectable(true)
                                        val pad = (12 * ctx.resources.displayMetrics.density).toInt()
                                        setPadding(pad, pad, pad, pad)
                                    }
                                },
                                update = { textView ->
                                    markwon.setMarkdown(textView, updateBody)
                                }
                            )
                        },
                        actions = {
                            val palette = style.toMiuixPalette()
                            LegadoMiuixActionButton(
                                text = stringResource(R.string.cancel),
                                palette = palette,
                                onClick = { dismissAllowingStateLoss() },
                                cornerRadius = style.actionRadius
                            )
                            LegadoMiuixActionButton(
                                text = stringResource(R.string.action_download),
                                palette = palette,
                                onClick = {
                                    if (url != null && name != null) {
                                        Download.start(requireContext(), url, name)
                                        toastOnUi(R.string.download_start)
                                    }
                                },
                                primary = true,
                                cornerRadius = style.actionRadius
                            )
                        }
                    )
                }
            }
        }
    }

}
