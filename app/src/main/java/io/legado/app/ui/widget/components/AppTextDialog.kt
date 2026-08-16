package io.legado.app.ui.widget.components

import android.content.Context
import android.os.Build
import android.view.View
import android.widget.TextView
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.widget.TextViewCompat
import com.bumptech.glide.Glide
import com.bumptech.glide.request.RequestOptions
import io.legado.app.utils.setMarkdown
import io.noties.markwon.Markwon
import io.noties.markwon.image.glide.GlideImagesPlugin
import io.noties.markwon.html.HtmlPlugin
import io.noties.markwon.ext.tables.TablePlugin

/**
 * 文本查看对话框（L2 Dialog 族）：纯文本或 Markdown 渲染。
 * - isMarkdown=true 时用 Markwon（Glide 图片 + 表格 + HTML 插件）渲染
 * - 文本超长可滚动，最大高度 480dp
 */
@Composable
fun AppTextDialog(
    title: String,
    text: String,
    isMarkdown: Boolean = false,
    confirmText: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    AlertDialog(
        modifier = modifier,
        onDismissRequest = onDismiss,
        title = { Text(text = title, style = MaterialTheme.typography.titleMedium) },
        text = {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .heightIn(max = 480.dp)
                    .fillMaxWidth()
            ) {
                if (isMarkdown) {
                    MarkdownView(text = text)
                } else {
                    Text(
                        text = text,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(
                    text = confirmText,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    )
}

@Composable
private fun MarkdownView(text: String) {
    val context = LocalContext.current
    val markwon = remember(context) {
        Markwon.builder(context)
            .usePlugin(
                GlideImagesPlugin.create(
                    Glide.with(context)
                        .applyDefaultRequestOptions(
                            RequestOptions()
                                .override(600)
                                .encodeQuality(88)
                        )
                )
            )
            .usePlugin(HtmlPlugin.create())
            .usePlugin(TablePlugin.create(context))
            .build()
    }
    AndroidView(
        factory = { ctx ->
            TextView(ctx).apply {
                textSize = 14f
                setLineSpacing(0f, 1.2f)
            }
        },
        update = { view ->
            view.setMarkdown(
                markwon,
                markwon.toMarkdown(text),
                imgOnLongClickListener = {}
            )
        }
    )
}
