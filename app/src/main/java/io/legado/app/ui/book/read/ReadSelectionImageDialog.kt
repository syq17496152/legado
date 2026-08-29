package io.legado.app.ui.book.read

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.constant.EventBus
import io.legado.app.data.entities.AiGeneratedImage
import io.legado.app.help.ai.AiImageGalleryManager
import io.legado.app.help.ai.AiImagePromptRewriter
import io.legado.app.help.ai.AiImageService
import io.legado.app.help.book.BookHelp
import io.legado.app.help.book.ContentProcessor
import io.legado.app.help.glide.ImageLoader
import io.legado.app.model.ReadBook
import io.legado.app.ui.widget.compose.AppDialogFrame
import io.legado.app.ui.widget.compose.AppDialogSize
import io.legado.app.ui.widget.compose.ComposeDialogFragment
import io.legado.app.ui.widget.compose.LegadoMiuixActionButton
import io.legado.app.ui.widget.compose.LegadoMiuixPalette
import io.legado.app.ui.widget.compose.rememberAppDialogStyle
import io.legado.app.ui.widget.compose.toMiuixPalette
import io.legado.app.ui.widget.image.PhotoView
import io.legado.app.utils.postEvent
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ReadSelectionImageDialog() : ComposeDialogFragment() {

    override val dialogSize: AppDialogSize = AppDialogSize.Management

    constructor(prompt: String, paragraphIndex: Int, paragraphText: String) : this() {
        arguments = Bundle().apply {
            putString(EXTRA_PROMPT, prompt)
            putInt(EXTRA_PARAGRAPH_INDEX, paragraphIndex)
            putString(EXTRA_PARAGRAPH_TEXT, paragraphText)
        }
    }

    private var currentImage by mutableStateOf<AiGeneratedImage?>(null)
    private var loading by mutableStateOf(true)
    private var optimizing by mutableStateOf(false)
    private var inserting by mutableStateOf(false)
    private var errorMessage by mutableStateOf<String?>(null)
    private var promptValue by mutableStateOf(TextFieldValue(""))
    private var generateJob: Job? = null
    private val prompt: String
        get() = arguments?.getString(EXTRA_PROMPT).orEmpty()
    private val paragraphIndex: Int
        get() = arguments?.getInt(EXTRA_PARAGRAPH_INDEX, -1) ?: -1
    private val paragraphText: String
        get() = arguments?.getString(EXTRA_PARAGRAPH_TEXT).orEmpty()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        promptValue = TextFieldValue(prompt)
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                val style = rememberAppDialogStyle()
                val palette = style.toMiuixPalette()
                LaunchedEffect(Unit) { generateImage() }
                AppDialogFrame(
                    title = stringResource(R.string.ai_image_generate),
                    scrollContent = false,
                    content = {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(340.dp)
                                .clip(RoundedCornerShape(style.actionRadius))
                                .background(style.fieldSurface),
                            contentAlignment = Alignment.Center
                        ) {
                            when {
                                loading -> {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(42.dp),
                                        color = style.accent
                                    )
                                }

                                errorMessage != null -> {
                                    Text(
                                        text = errorMessage.orEmpty(),
                                        color = style.secondaryText,
                                        fontSize = 14.sp,
                                        textAlign = TextAlign.Center,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(18.dp)
                                    )
                                }

                                currentImage != null -> {
                                    val target = currentImage
                                    AndroidView(
                                        factory = { ctx ->
                                            PhotoView(ctx).apply {
                                                scaleType = ImageView.ScaleType.CENTER_INSIDE
                                            }
                                        },
                                        update = { photoView ->
                                            ImageLoader.load(requireContext(), target!!.localPath)
                                                .error(R.drawable.image_loading_error)
                                                .into(photoView)
                                        },
                                        modifier = Modifier.fillMaxSize()
                                    )
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(10.dp))
                        OutlinedTextField(
                            value = promptValue,
                            onValueChange = { promptValue = it },
                            modifier = Modifier.fillMaxWidth(),
                            minLines = 3,
                            maxLines = 6,
                            keyboardOptions = KeyboardOptions(
                                capitalization = KeyboardCapitalization.Sentences
                            ),
                            shape = RoundedCornerShape(style.actionRadius),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = style.primaryText,
                                unfocusedTextColor = style.primaryText,
                                focusedContainerColor = style.fieldSurface,
                                unfocusedContainerColor = style.fieldSurface,
                                cursorColor = style.accent,
                                focusedBorderColor = style.accent.copy(alpha = 0.55f),
                                unfocusedBorderColor = style.stroke,
                                focusedPlaceholderColor = style.secondaryText,
                                unfocusedPlaceholderColor = style.secondaryText
                            ),
                            textStyle = MaterialTheme.typography.bodyMedium.copy(
                                color = style.primaryText,
                                fontFamily = style.bodyFontFamily
                            ),
                            placeholder = {
                                Text(
                                    text = stringResource(R.string.ai_image_prompt_hint),
                                    color = style.secondaryText,
                                    fontSize = 13.sp
                                )
                            }
                        )
                    },
                    actions = {
                        DialogActionButton(
                            text = stringResource(R.string.ai_image_optimize_prompt),
                            enabled = !loading && !optimizing,
                            palette = palette,
                            cornerRadius = style.actionRadius,
                            onClick = { optimizePrompt() }
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        DialogActionButton(
                            text = stringResource(R.string.ai_image_regenerate),
                            enabled = !loading,
                            palette = palette,
                            cornerRadius = style.actionRadius,
                            onClick = { generateImage() }
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        DialogActionButton(
                            text = stringResource(R.string.ai_image_insert),
                            enabled = !loading && !inserting && currentImage != null,
                            palette = palette,
                            cornerRadius = style.actionRadius,
                            onClick = { insertCurrentImage() }
                        )
                    }
                )
            }
        }
    }

    override fun onDestroyView() {
        generateJob?.cancel()
        super.onDestroyView()
    }

    private fun generateImage() {
        val content = promptValue.text.trim()
        if (content.isBlank()) {
            showError(getString(R.string.ai_image_no_selection))
            return
        }
        generateJob?.cancel()
        currentImage = null
        errorMessage = null
        loading = true
        generateJob = lifecycleScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    AiImageService.generateAndStore(
                        content,
                        metadata = readSelectionMetadata()
                    )
                }
            }.onSuccess { image ->
                currentImage = image
                errorMessage = null
                loading = false
            }.onFailure { error ->
                showError(error.localizedMessage ?: getString(R.string.ai_image_generate_failed))
            }
        }
    }

    private fun optimizePrompt() {
        val content = promptValue.text.trim()
        if (content.isBlank()) {
            showError(getString(R.string.ai_image_no_selection))
            return
        }
        optimizing = true
        lifecycleScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    AiImagePromptRewriter.rewrite(content, paragraphText)
                }
            }.onSuccess { rewritten ->
                promptValue = TextFieldValue(
                    rewritten,
                    selection = TextRange(rewritten.length)
                )
            }.onFailure { error ->
                showError(error.localizedMessage ?: getString(R.string.ai_image_generate_failed))
            }
            optimizing = false
        }
    }

    private fun insertCurrentImage() {
        val image = currentImage ?: return
        inserting = true
        lifecycleScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    insertImageToCurrentChapter(image)
                }
            }.onSuccess { inserted ->
                if (inserted) {
                    ReadBook.clearTextChapter()
                    postEvent(EventBus.UP_CONFIG, arrayListOf(5))
                    toastOnUi(R.string.ai_image_inserted)
                    dismissAllowingStateLoss()
                } else {
                    showError(getString(R.string.ai_image_insert_failed))
                }
            }.onFailure { error ->
                showError(error.localizedMessage ?: getString(R.string.ai_image_insert_failed))
            }
            inserting = false
        }
    }

    private fun insertImageToCurrentChapter(image: AiGeneratedImage): Boolean {
        val book = ReadBook.book ?: return false
        val chapter = ReadBook.curTextChapter?.chapter ?: return false
        val rawContent = BookHelp.getContent(book, chapter).orEmpty()
        if (rawContent.isBlank()) return false
        val contentProcessor = ContentProcessor.get(book.name, book.origin)
        val lines = contentProcessor.getContent(book, chapter, rawContent, includeTitle = false)
            .textList
            .toMutableList()
        if (lines.isEmpty()) return false
        val targetIndex = paragraphIndex.takeIf { it in lines.indices }
            ?: findParagraphIndex(lines, paragraphText)
            ?: return false
        val imageTag = """<img src="${AiImageGalleryManager.imageUri(image.id)}">"""
        if (!lines[targetIndex].contains(imageTag)) {
            lines[targetIndex] = lines[targetIndex].trimEnd() + imageTag
            BookHelp.saveText(book, chapter, lines.joinToString("\n"))
        }
        AiImageGalleryManager.setFavorite(image.id, true, null)
        return true
    }

    private fun readSelectionMetadata(): AiImageGalleryManager.ImageMetadata {
        val book = ReadBook.book
        val chapter = ReadBook.curTextChapter?.chapter
        return AiImageGalleryManager.ImageMetadata(
            bookName = book?.name.orEmpty(),
            bookAuthor = book?.author.orEmpty(),
            chapterIndex = chapter?.index ?: ReadBook.durChapterIndex,
            chapterTitle = chapter?.title.orEmpty(),
            sourceType = AiImageGalleryManager.SOURCE_TYPE_READ_INSERT,
            sourceText = paragraphText
        )
    }

    private fun findParagraphIndex(lines: List<String>, target: String): Int? {
        val normalizedTarget = normalizeParagraph(target)
        if (normalizedTarget.isBlank()) return null
        return lines.indexOfFirst { line ->
            val normalizedLine = normalizeParagraph(line)
            normalizedLine == normalizedTarget ||
                normalizedLine.contains(normalizedTarget) ||
                normalizedTarget.contains(normalizedLine)
        }.takeIf { it >= 0 }
    }

    private fun normalizeParagraph(text: String): String {
        return text.replace(Regex("""\s+"""), "")
            .replace("\u3000", "")
            .trim()
    }

    private fun showError(message: String) {
        loading = false
        errorMessage = message
    }

    @Composable
    private fun DialogActionButton(
        text: String,
        enabled: Boolean,
        palette: LegadoMiuixPalette,
        cornerRadius: Dp,
        onClick: () -> Unit
    ) {
        LegadoMiuixActionButton(
            text = text,
            palette = palette,
            onClick = { if (enabled) onClick() },
            cornerRadius = cornerRadius,
            modifier = Modifier.alpha(if (enabled) 1f else 0.45f)
        )
    }

    companion object {
        private const val EXTRA_PROMPT = "prompt"
        private const val EXTRA_PARAGRAPH_INDEX = "paragraphIndex"
        private const val EXTRA_PARAGRAPH_TEXT = "paragraphText"
    }
}
