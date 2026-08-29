package io.legado.app.ui.dict

import android.os.Build
import android.os.Bundle
import android.text.method.LinkMovementMethod
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.textclassifier.TextClassifier
import android.widget.TextView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import com.bumptech.glide.Glide
import com.bumptech.glide.request.RequestOptions
import io.legado.app.R
import io.legado.app.data.entities.DictRule
import io.legado.app.help.GlideImageGetter
import io.legado.app.help.TextViewTagHandler
import io.legado.app.lib.theme.secondaryTextColor
import io.legado.app.ui.theme.LegadoTheme
import io.legado.app.ui.widget.compose.AppDialogFrame
import io.legado.app.ui.widget.compose.AppDialogSize
import io.legado.app.ui.widget.compose.ComposeDialogFragment
import io.legado.app.ui.widget.compose.LegadoMiuixActionButton
import io.legado.app.ui.widget.compose.LegadoMiuixChoiceRow
import io.legado.app.ui.widget.compose.rememberAppDialogStyle
import io.legado.app.ui.widget.compose.toMiuixPalette
import io.legado.app.ui.widget.dialog.PhotoDialog
import io.legado.app.utils.dpToPx
import io.legado.app.utils.setHtml
import io.legado.app.utils.setMarkdown
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.toastOnUi
import io.noties.markwon.Markwon
import io.noties.markwon.ext.tables.TablePlugin
import io.noties.markwon.html.HtmlPlugin
import io.noties.markwon.image.glide.GlideImagesPlugin
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 词典
 */
class DictDialog() : ComposeDialogFragment() {

    constructor(word: String) : this() {
        arguments = Bundle().apply {
            putString("word", word)
        }
    }

    override val dialogSize: AppDialogSize = AppDialogSize.Wide

    private val viewModel by viewModels<DictViewModel>()
    private var word: String? = null
    private var initGetter = false
    private var dictImageGetter: GlideImageGetter? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        word = arguments?.getString("word")
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                LegadoTheme {
                    DictPanel()
                }
            }
        }
    }

    @Composable
    private fun DictPanel() {
        val style = rememberAppDialogStyle()
        val palette = style.toMiuixPalette()
        val context = LocalContext.current
        val dictTextView = remember {
            TextView(context).apply {
                movementMethod = LinkMovementMethod.getInstance()
                setTextColor(context.secondaryTextColor)
                val pad = 16.dpToPx()
                setPadding(pad, pad, pad, pad)
            }
        }
        var dictRules by remember { mutableStateOf<List<DictRule>>(emptyList()) }
        var selectedRule by remember { mutableStateOf<DictRule?>(null) }
        var loading by remember { mutableStateOf(false) }
        var contentResult by remember { mutableStateOf<Pair<DictRule, String>?>(null) }

        fun loadDict(dictRule: DictRule) {
            val currentWord = word
            if (currentWord.isNullOrEmpty() || dictRule.name == selectedRule?.name) {
                return
            }
            selectedRule = dictRule
            loading = true
            viewModel.dict(dictRule, currentWord) { result ->
                loading = false
                contentResult = dictRule to result
            }
        }

        LaunchedEffect(Unit) {
            if (word.isNullOrEmpty()) {
                toastOnUi(R.string.cannot_empty)
                dismissAllowingStateLoss()
                return@LaunchedEffect
            }
            viewModel.initData { rules ->
                dictRules = rules
                // 对应原 TabLayout：首个 add 的 tab 会自动选中并加载
                rules.firstOrNull()?.let { loadDict(it) }
            }
        }

        LaunchedEffect(contentResult) {
            val result = contentResult ?: return@LaunchedEffect
            showDictContent(dictTextView, result.first, result.second)
        }

        AppDialogFrame(
            title = stringResource(R.string.dict),
            content = {
                if (dictRules.isNotEmpty()) {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 168.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        items(items = dictRules, key = { it.name }) { dictRule ->
                            LegadoMiuixChoiceRow(
                                text = dictRule.name,
                                selected = dictRule.name == selectedRule?.name,
                                palette = palette,
                                onClick = { loadDict(dictRule) },
                                minHeight = 40.dp
                            )
                        }
                    }
                }
                if (loading) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(48.dp),
                            color = style.accent
                        )
                    }
                }
                AndroidView(
                    factory = { dictTextView },
                    modifier = Modifier.fillMaxWidth()
                )
            },
            actions = {
                LegadoMiuixActionButton(
                    text = stringResource(R.string.ok),
                    palette = palette,
                    onClick = { dismissAllowingStateLoss() },
                    primary = true,
                    cornerRadius = style.actionRadius
                )
            }
        )
    }

    private fun showDictContent(textView: TextView, dictRule: DictRule, result: String) {
        val contentTrimS = result.trimStart()
        if (contentTrimS.startsWith("<md>")) {
            val lastIndex = contentTrimS.lastIndexOf("<")
            if (lastIndex < 4) {
                textView.text = contentTrimS
                return
            }
            val mark = contentTrimS.substring(4, lastIndex)
            viewLifecycleOwner.lifecycleScope.launch {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    textView.setTextClassifier(TextClassifier.NO_OP)
                }
                val markwon: Markwon
                val markdown = withContext(IO) {
                    markwon = Markwon.builder(requireContext())
                        .usePlugin(
                            GlideImagesPlugin.create(
                                Glide.with(requireContext())
                                    .applyDefaultRequestOptions(
                                        RequestOptions()
                                            .override(imgAvailableWidth(textView))
                                            .encodeQuality(88)
                                    )
                            )
                        )
                        .usePlugin(HtmlPlugin.create())
                        .usePlugin(TablePlugin.create(requireContext()))
                        .build()
                    markwon.toMarkdown(mark)
                }
                textView.setMarkdown(
                    markwon,
                    markdown,
                    imgOnLongClickListener = { source ->
                        showDialogFragment(PhotoDialog(source))
                    }
                )
            }
            return
        }
        textView.setHtml(
            result,
            obtainImageGetter(textView),
            TextViewTagHandler(object : TextViewTagHandler.OnButtonClickListener {
                override fun onButtonClick(name: String, click: String) {
                    viewModel.onButtonClick(dictRule, "button $name", click)
                }
            }),
            imgOnLongClickListener = { source ->
                showDialogFragment(PhotoDialog(source))
            },
            imgOnClickListener = { click ->
                viewModel.onButtonClick(dictRule, "image", click)
            }
        )
    }

    private fun obtainImageGetter(textView: TextView): GlideImageGetter {
        if (!initGetter) {
            initGetter = true
            dictImageGetter = GlideImageGetter(
                requireContext(),
                textView,
                lifecycle,
                imgAvailableWidth(textView)
            )
        }
        return dictImageGetter!!
    }

    private fun imgAvailableWidth(textView: TextView): Int {
        val width = textView.width - textView.paddingLeft - textView.paddingRight
        return if (width > 0) {
            width
        } else {
            // 首帧未完成布局时的兜底宽度，对应原全宽弹框减 16dp 内边距
            resources.displayMetrics.widthPixels - 32.dpToPx()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        if (initGetter) {
            dictImageGetter?.clear()
        }
    }
}
