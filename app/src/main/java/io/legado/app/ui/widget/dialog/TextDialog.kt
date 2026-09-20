package io.legado.app.ui.widget.dialog

import android.content.DialogInterface
import android.os.Build
import android.os.Bundle
import android.text.method.LinkMovementMethod
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.textclassifier.TextClassifier
import android.widget.TextView
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.text.HtmlCompat
import com.bumptech.glide.Glide
import io.legado.app.R
import io.legado.app.help.CacheManager
import io.legado.app.help.IntentData
import io.legado.app.lib.theme.uiTypeface
import io.legado.app.ui.code.CodeEditActivity
import io.legado.app.ui.widget.compose.AppDialogFrame
import io.legado.app.ui.widget.compose.AppDialogSize
import io.legado.app.ui.widget.compose.AppDialogStyle
import io.legado.app.ui.widget.compose.ComposeDialogFragment
import io.legado.app.ui.widget.compose.LegadoMiuixActionButton
import io.legado.app.ui.widget.compose.rememberAppDialogStyle
import io.legado.app.ui.widget.compose.toMiuixPalette
import io.legado.app.utils.startActivity
import io.noties.markwon.Markwon
import io.noties.markwon.ext.tables.TablePlugin
import io.noties.markwon.html.HtmlPlugin
import io.noties.markwon.image.glide.GlideImagesPlugin
import kotlinx.coroutines.delay
import androidx.compose.material3.MaterialTheme
import io.legado.app.ui.theme.bodySecondary


class TextDialog() : ComposeDialogFragment() {

    enum class Mode {
        MD, HTML, TEXT
    }

    constructor(
        title: String,
        content: String?,
        mode: Mode = Mode.TEXT,
        time: Long = 0,
        autoClose: Boolean = false
    ) : this() {
        arguments = Bundle().apply {
            putString("title", title)
            putString("content", IntentData.put(content))
            putString("mode", mode.name)
            putLong("time", time)
        }
        isCancelable = false
        this.autoClose = autoClose
    }

    override val dialogSize: AppDialogSize = AppDialogSize.Wide
    override val dialogHeight: Int = ViewGroup.LayoutParams.WRAP_CONTENT

    private var time = 0L
    private var autoClose: Boolean = false
    private var onDismissListener: DialogInterface.OnDismissListener? = null
    // F191：可选「逐条翻阅」（默认关闭，单条模式行为与改造前完全一致）
    private var pageTotal = 0
    private var pageStart = 0
    private var pageTitleProvider: ((Int) -> String)? = null
    private var pageContentProvider: ((Int) -> String)? = null

    /**
     * F191（log 详情翻阅）：传「条数 + 起始下标 + 标题/内容提供者」而非整表内容——
     * 内容是**按需构造**的（日志堆栈可能很大，逐条预构造整表纯属浪费）。
     * 已知上限：provider 为运行时持有，进程重建后退化为构造时传入的单条内容（不崩、不误解）。
     */
    fun setPaging(
        total: Int,
        startIndex: Int,
        titleProvider: (Int) -> String,
        contentProvider: (Int) -> String
    ) {
        pageTotal = total
        pageStart = startIndex.coerceIn(0, (total - 1).coerceAtLeast(0))
        pageTitleProvider = titleProvider
        pageContentProvider = contentProvider
    }

    fun setOnDismissListener(listener: DialogInterface.OnDismissListener?) {
        this.onDismissListener = listener
    }

    override fun onDismiss(dialog: DialogInterface) {
        super.onDismiss(dialog)
        onDismissListener?.onDismiss(dialog)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val args = arguments ?: Bundle()
        val title = args.getString("title").orEmpty()
        val content = IntentData.get(args.getString("content")) ?: ""
        val mode = args.getString("mode") ?: Mode.TEXT.name
        val initialTime = args.getLong("time", 0L)
        val fragment = this
        val pagingTotal = pageTotal
        val pagingStart = pageStart
        val titleProvider = pageTitleProvider
        val contentProvider = pageContentProvider

        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                val style = rememberAppDialogStyle()
                val palette = style.toMiuixPalette()
                var countdownSeconds by remember {
                    mutableIntStateOf((initialTime / 1000).toInt())
                }
                var canClose by remember { mutableStateOf(initialTime <= 0) }
                // F191：翻阅下标（仅 pagingTotal>1 时生效）；内容按页缓存，避免每次重组重建堆栈文本
                var pageIndex by remember { mutableIntStateOf(pagingStart) }
                val currentTitle = remember(pageIndex) {
                    if (pagingTotal > 1) titleProvider?.invoke(pageIndex) ?: title else title
                }
                val currentContent = remember(pageIndex) {
                    if (pagingTotal > 1) contentProvider?.invoke(pageIndex) ?: content else content
                }

                LaunchedEffect(initialTime) {
                    if (initialTime > 0) {
                        var remaining = initialTime
                        while (remaining > 0) {
                            delay(1000)
                            remaining -= 1000
                            countdownSeconds = (remaining / 1000).toInt()
                        }
                        canClose = true
                        if (autoClose) {
                            dismissAllowingStateLoss()
                        }
                    }
                }

                AppDialogFrame(
                    title = currentTitle,
                    scrollContent = false,
                    content = {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            if (pagingTotal > 1) {
                                PagingBar(
                                    index = pageIndex,
                                    total = pagingTotal,
                                    accent = style.accent,
                                    secondary = style.secondaryText,
                                    onPrev = { if (pageIndex > 0) pageIndex-- },
                                    onNext = { if (pageIndex < pagingTotal - 1) pageIndex++ }
                                )
                            }
                            TextDialogContent(
                                content = currentContent,
                                mode = mode,
                                style = style
                            )
                        }
                    },
                    actions = {
                        if (countdownSeconds > 0) {
                            Text(
                                text = "${countdownSeconds}s",
                                color = style.accent,
                                fontSize = MaterialTheme.typography.bodySecondary.fontSize,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(end = 8.dp)
                            )
                        }
                        if (canClose) {
                            LegadoMiuixActionButton(
                                text = stringResource(R.string.close),
                                palette = palette,
                                onClick = { dismissAllowingStateLoss() },
                                cornerRadius = style.actionRadius
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                        }
                        LegadoMiuixActionButton(
                            text = stringResource(R.string.edit_content),
                            palette = palette,
                            onClick = {
                                val cacheKey = "code_text_${System.currentTimeMillis()}"
                                CacheManager.putMemory(cacheKey, content)
                                fragment.startActivity<CodeEditActivity> {
                                    putExtra("cacheKey", cacheKey)
                                    putExtra("title", title)
                                    putExtra(
                                        "languageName",
                                        if (mode == Mode.MD.name) {
                                            "text.html.markdown"
                                        } else {
                                            "text.html.basic"
                                        }
                                    )
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

@Composable
private fun PagingBar(
    index: Int,
    total: Int,
    accent: Color,
    secondary: Color,
    onPrev: () -> Unit,
    onNext: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.End
    ) {
        PageStep("‹", index > 0, accent, secondary, onPrev)
        Text(
            text = stringResource(R.string.dialog_page_position, index + 1, total),
            color = secondary,
            fontSize = MaterialTheme.typography.bodySecondary.fontSize,
            modifier = Modifier.padding(horizontal = 8.dp)
        )
        PageStep("›", index < total - 1, accent, secondary, onNext)
    }
}

/** 翻阅步进按钮（符号为语言无关字形，与日志页搜索框「×」同口径）；越界侧置灰不可点，不做循环 */
@Composable
private fun PageStep(
    symbol: String,
    enabled: Boolean,
    accent: Color,
    secondary: Color,
    onClick: () -> Unit
) {
    Text(
        text = symbol,
        color = if (enabled) accent else secondary.copy(alpha = 0.35f),
        fontSize = 18.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .clickable(enabled = enabled) { onClick() }
            .padding(horizontal = 12.dp, vertical = 2.dp)
    )
}

@Composable
private fun TextDialogContent(
    content: String,
    mode: String,
    style: AppDialogStyle
) {
    val screenHeight = LocalConfiguration.current.screenHeightDp.dp
    val maxContentHeight = (screenHeight * 0.72f).coerceIn(200.dp, 600.dp)

    when (mode) {
        TextDialog.Mode.MD.name -> {
            MarkdownContent(
                content = content,
                style = style,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = maxContentHeight)
            )
        }

        TextDialog.Mode.HTML.name -> {
            HtmlContent(
                content = content,
                style = style,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = maxContentHeight)
            )
        }

        else -> {
            val displayText = if (content.length >= 32 * 1024) {
                content.take(32 * 1024) + "\n\n数据太大，无法全部显示…"
            } else {
                content
            }
            val scrollState = rememberScrollState()
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = maxContentHeight)
                    .verticalScroll(scrollState)
            ) {
                SelectionContainer {
                    Text(
                        text = displayText,
                        color = style.secondaryText,
                        fontSize = MaterialTheme.typography.bodyMedium.fontSize,
                        lineHeight = 20.sp,
                        fontFamily = style.bodyFontFamily
                    )
                }
            }
        }
    }
}

@Composable
private fun MarkdownContent(
    content: String,
    style: AppDialogStyle,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val markwon = remember(context) {
        Markwon.builder(context)
            .usePlugin(GlideImagesPlugin.create(Glide.with(context)))
            .usePlugin(HtmlPlugin.create())
            .usePlugin(TablePlugin.create(context))
            .build()
    }
    val textColorArgb = remember(style) { style.secondaryText.toArgb() }
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            TextView(ctx).apply {
                setPadding(12, 12, 12, 12)
                movementMethod = LinkMovementMethod.getInstance()
                isFocusable = true
                setTextIsSelectable(true)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    setTextClassifier(TextClassifier.NO_OP)
                }
                includeFontPadding = true
            }
        },
        update = { textView ->
            textView.setTextColor(textColorArgb)
            textView.textSize = 14f
            textView.typeface = context.uiTypeface()
            markwon.setMarkdown(textView, content)
        }
    )
}

@Composable
private fun HtmlContent(
    content: String,
    style: AppDialogStyle,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val spanned = remember(content) {
        HtmlCompat.fromHtml(content, HtmlCompat.FROM_HTML_MODE_COMPACT)
    }
    val textColorArgb = remember(style) { style.secondaryText.toArgb() }
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            TextView(ctx).apply {
                setPadding(12, 12, 12, 12)
                movementMethod = LinkMovementMethod.getInstance()
                isFocusable = true
                setTextIsSelectable(true)
                includeFontPadding = true
            }
        },
        update = { textView ->
            textView.setTextColor(textColorArgb)
            textView.textSize = 14f
            textView.typeface = context.uiTypeface()
            textView.text = spanned
        }
    )
}
