package io.legado.app.ui.book.read

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Looper
import android.provider.MediaStore
import android.util.Base64
import android.view.View
import android.webkit.WebChromeClient
import android.webkit.JavascriptInterface
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.bitmap.DownsampleStrategy
import com.bumptech.glide.request.RequestOptions
import io.legado.app.R
import io.legado.app.constant.AppLog
import io.legado.app.help.config.ShareNoteTemplateManager
import io.legado.app.help.glide.ImageLoader
import io.legado.app.utils.GSON
import io.legado.app.utils.normalizeFileName
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext
import kotlin.math.ceil
import kotlin.math.sqrt

object ShareNoteImageRenderer {

    private const val MAX_EXPORT_WIDTH = 1080
    private const val MAX_EXPORT_HEIGHT = 16000
    private const val MAX_EXPORT_PIXELS = 16_000_000
    private const val MAX_FALLBACK_EXPORT_PIXELS = 8_000_000
    private const val READY_TIMEOUT_MS = 8000L
    private const val CAPTURE_TIMEOUT_MS = 25000L
    private const val HTML2_CANVAS_ASSET = "share_note_templates/lib/html2canvas.min.js"
    private const val CAPTURE_BRIDGE_NAME = "ReedenShareBridge"
    private const val BRIDGE_CHUNK_SIZE = 256 * 1024
    private const val MAX_CAPTURE_BASE64_LENGTH = 32 * 1024 * 1024

    private val renderMutex = Mutex()
    private var html2CanvasScriptCache: String? = null

    data class Payload(
        val type: String = "note",
        val generatedAt: String,
        val posterWidth: Int = 375,
        val profile: Profile = Profile(),
        val appearance: Appearance = Appearance(),
        val book: Book,
        val note: Note
    )

    data class Profile(
        val name: String = "读者",
        val bio: String = "一段认真读过的文字",
        val avatar: String? = null
    )

    data class Appearance(
        val hideComment: Boolean = false,
        val colorTheme: ColorTheme? = null,
        val fontFamily: String = "",
        val fontFace: Any? = null,
        val backgroundImage: String? = null,
        val backgroundCssSize: String = "cover",
        val backgroundMaskOpacity: Float = 0f
    )

    data class ColorTheme(
        val backgroundColor: String,
        val textColor: String,
        val mutedColor: String,
        val surfaceColor: String = backgroundColor,
        val secondaryTextColor: String = textColor,
        val dividerColor: String = mutedColor
    )

    data class Book(
        val title: String,
        val author: String = "",
        val description: String = "",
        val cover: String? = null,
        val type: String = "",
        val kind: String = "",
        val tags: String = "",
        val rating: Float? = null,
        val sizeText: String = "",
        val wordCountText: String = "",
        val readTimeText: String = "",
        val readStatusText: String = "",
        val readProgressText: String = "",
        val readProgressPercent: Float? = null,
        val lastReadTime: String = ""
    )

    data class Note(
        val createAt: String,
        val sectionName: String,
        val description: String,
        val comment: String = ""
    )

    data class CaptureSize(
        val width: Int,
        val height: Int,
        val left: Int = 0,
        val top: Int = 0
    )

    data class RenderResult(
        val file: File,
        val width: Int,
        val height: Int
    )

    private data class JsPngResult(
        val base64: String,
        val width: Int,
        val height: Int
    )

    suspend fun renderShareImage(
        context: Context,
        entry: ShareNoteTemplateManager.Entry,
        payload: Payload,
        style: ShareNoteTemplateManager.ShareStyle = ShareNoteTemplateManager.currentStyle()
    ): File = renderMutex.withLock {
        runCatching {
            val result = withContext(Dispatchers.Main) {
                val webView = WebView(context).apply {
                    configureWebView(this)
                    visibility = View.VISIBLE
                }
                try {
                    renderHtmlToPngFileWithFallback(
                        context = context,
                        webView = webView,
                        entry = entry,
                        payload = payload,
                        targetWidth = initialWidth(entry),
                        style = style
                    )
                } finally {
                    webView.stopLoading()
                    webView.destroy()
                }
            }
            return@withLock result.file
        }.onFailure {
            if (it is CancellationException && it !is TimeoutCancellationException) throw it
            if (it is VirtualMachineError || it is ThreadDeath || it is LinkageError) throw it
        }

        val bitmap = withContext(Dispatchers.Main) {
            val width = initialWidth(entry)
            val webView = WebView(context).apply {
                configureWebView(this)
                visibility = View.VISIBLE
            }
            try {
                loadInto(webView, entry, payload, width, style)
                drawWebViewToBitmap(webView, MAX_FALLBACK_EXPORT_PIXELS)
            } finally {
                webView.stopLoading()
                webView.destroy()
            }
        }
        writeBitmapToCache(context, bitmap)
    }

    suspend fun renderMountedWebView(
        context: Context,
        webView: WebView,
        entry: ShareNoteTemplateManager.Entry,
        payload: Payload,
        targetWidth: Int,
        style: ShareNoteTemplateManager.ShareStyle = ShareNoteTemplateManager.currentStyle()
    ): RenderResult = renderMutex.withLock {
        withContext(Dispatchers.Main) {
            renderHtmlToPngFileWithFallback(
                context = context,
                webView = webView,
                entry = entry,
                payload = payload,
                targetWidth = targetWidth,
                style = style
            )
        }
    }

    suspend fun renderPreview(
        context: Context,
        entry: ShareNoteTemplateManager.Entry,
        force: Boolean = false,
        style: ShareNoteTemplateManager.ShareStyle = ShareNoteTemplateManager.currentStyle()
    ): File? = renderMutex.withLock {
        val file = ShareNoteTemplateManager.previewFile(entry, style)
        if (!force && isUsablePng(file)) return@withLock file
        file.delete()
        try {
            val result = withContext(Dispatchers.Main) {
                val webView = WebView(context).apply {
                    configureWebView(this)
                    visibility = View.VISIBLE
                }
                try {
                    renderHtmlToPngFileWithFallback(
                        context = context,
                        webView = webView,
                        entry = entry,
                        payload = previewPayload(),
                        targetWidth = initialWidth(entry),
                        maxCaptureHeight = 260,
                        style = style
                    )
                } finally {
                    webView.stopLoading()
                    webView.destroy()
                }
            }
            withContext(Dispatchers.IO) {
                result.file.copyTo(file, overwrite = true)
                result.file.delete()
                file.takeIf(::isUsablePng)
            }
        } catch (e: CancellationException) {
            if (e !is TimeoutCancellationException) throw e
            null
        } catch (e: Exception) {
            null
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    fun configureWebView(webView: WebView) {
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.settings.allowFileAccess = true
        webView.settings.allowContentAccess = true
        webView.settings.cacheMode = WebSettings.LOAD_DEFAULT
        webView.settings.loadWithOverviewMode = false
        webView.settings.useWideViewPort = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            webView.settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        }
        webView.settings.blockNetworkImage = false
        webView.setBackgroundColor(android.graphics.Color.TRANSPARENT)
        webView.isVerticalScrollBarEnabled = false
        webView.isHorizontalScrollBarEnabled = false
        webView.overScrollMode = View.OVER_SCROLL_NEVER
        webView.webChromeClient = WebChromeClient()
    }

    suspend fun loadInto(
        webView: WebView,
        entry: ShareNoteTemplateManager.Entry,
        payload: Payload,
        targetWidth: Int,
        style: ShareNoteTemplateManager.ShareStyle = ShareNoteTemplateManager.currentStyle()
    ): CaptureSize = withContext(Dispatchers.Main) {
        val width = targetWidth.coerceIn(240, MAX_EXPORT_WIDTH)
        loadHtml(webView, entry, GSON.toJson(payload), width, style)
        val capture = evaluateCaptureSize(webView, width, captureViewportHeight(entry))
        val height = capture.height.coerceIn(180, MAX_EXPORT_HEIGHT)
        layoutWebView(webView, width, height)
        waitForDraw()
        capture.copy(width = width, height = height)
    }

    suspend fun exportMountedWebView(context: Context, webView: WebView): File = renderMutex.withLock {
        val bitmap = withContext(Dispatchers.Main) {
            drawWebViewToBitmap(webView)
        }
        writeBitmapToCache(context, bitmap)
    }

    suspend fun savePngToGallery(context: Context, pngFile: File): Uri = withContext(Dispatchers.IO) {
        val albumName = context.shareNoteAlbumName()
        val name = "${albumName}_${System.currentTimeMillis()}.png"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/$albumName")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("MediaStore insert failed")
            try {
                resolver.openOutputStream(uri)?.use { output ->
                    pngFile.inputStream().use { input -> input.copyTo(output) }
                } ?: throw IllegalStateException("MediaStore openOutputStream failed")
                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
                uri
            } catch (e: Throwable) {
                resolver.delete(uri, null, null)
                throw e
            }
        } else {
            val dir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
                albumName
            ).apply { mkdirs() }
            val target = File(dir, name)
            pngFile.inputStream().use { input ->
                FileOutputStream(target).use { output -> input.copyTo(output) }
            }
            Uri.fromFile(target)
        }
    }

    private fun Context.shareNoteAlbumName(): String {
        return getString(R.string.app_name)
            .trim()
            .normalizeFileName()
            .ifBlank { "Archive" }
    }

    private suspend fun renderHtmlToPngFile(
        context: Context,
        webView: WebView,
        entry: ShareNoteTemplateManager.Entry,
        payload: Payload?,
        targetWidth: Int,
        maxCaptureHeight: Int? = null,
        style: ShareNoteTemplateManager.ShareStyle = ShareNoteTemplateManager.currentStyle()
    ): RenderResult {
        check(Looper.myLooper() == Looper.getMainLooper())
        val bridge = CaptureBridge()
        val payloadJson = payload?.let { GSON.toJson(preparePayloadAssets(context, it, style)) }
        webView.removeJavascriptInterface(CAPTURE_BRIDGE_NAME)
        webView.addJavascriptInterface(bridge, CAPTURE_BRIDGE_NAME)
        return try {
            loadCaptureHtml(
                context = context,
                webView = webView,
                entry = entry,
                payloadJson = payloadJson,
                targetWidth = targetWidth,
                maxCaptureHeight = maxCaptureHeight,
                style = style
            )
            val png = withTimeout(CAPTURE_TIMEOUT_MS) { bridge.await() }
            val file = writePngBase64ToCache(context, png.base64)
            if (!isUsablePng(file)) {
                file.delete()
                throw IllegalStateException("captured image is invalid")
            }
            RenderResult(file, png.width, png.height)
        } finally {
            webView.removeJavascriptInterface(CAPTURE_BRIDGE_NAME)
        }
    }

    private suspend fun renderHtmlToPngFileWithFallback(
        context: Context,
        webView: WebView,
        entry: ShareNoteTemplateManager.Entry,
        payload: Payload?,
        targetWidth: Int,
        maxCaptureHeight: Int? = null,
        style: ShareNoteTemplateManager.ShareStyle = ShareNoteTemplateManager.currentStyle()
    ): RenderResult {
        return runCatching {
            renderHtmlToPngFile(
                context = context,
                webView = webView,
                entry = entry,
                payload = payload,
                targetWidth = targetWidth,
                maxCaptureHeight = maxCaptureHeight,
                style = style
            )
        }.getOrElse { error ->
            if (error is CancellationException && error !is TimeoutCancellationException) throw error
            if (error is VirtualMachineError || error is ThreadDeath || error is LinkageError) throw error
            AppLog.put("Share note html2canvas render failed, fallback to WebView draw\n${error.localizedMessage}", error)
            val width = targetWidth.coerceIn(240, MAX_EXPORT_WIDTH)
            val payloadJson = payload?.let { GSON.toJson(preparePayloadAssets(context, it, style)) }
            loadHtml(webView, entry, payloadJson, width, style)
            val size = evaluateCaptureSize(webView, width, maxCaptureHeight ?: captureViewportHeight(entry))
            val height = (maxCaptureHeight?.let { size.height.coerceAtMost(it) } ?: size.height)
                .coerceIn(180, MAX_EXPORT_HEIGHT)
            layoutWebView(webView, width, height)
            waitForDraw()
            val bitmap = drawWebViewToBitmap(webView, MAX_FALLBACK_EXPORT_PIXELS)
            val outWidth = bitmap.width
            val outHeight = bitmap.height
            val file = writeBitmapToCache(context, bitmap)
            if (!isUsablePng(file)) {
                file.delete()
                throw IllegalStateException("fallback captured image is invalid")
            }
            RenderResult(file, outWidth.takeIf { it > 0 } ?: width, outHeight.takeIf { it > 0 } ?: height)
        }
    }

    private suspend fun loadCaptureHtml(
        context: Context,
        webView: WebView,
        entry: ShareNoteTemplateManager.Entry,
        payloadJson: String?,
        targetWidth: Int,
        maxCaptureHeight: Int? = null,
        style: ShareNoteTemplateManager.ShareStyle = ShareNoteTemplateManager.currentStyle()
    ) {
        val html = prepareHtml(
            html = ShareNoteTemplateManager.readTemplateHtml(entry),
            payloadJson = payloadJson,
            style = style,
            extraBodyScript = buildCaptureScript(context, maxCaptureHeight)
        )
        val width = targetWidth.coerceIn(240, MAX_EXPORT_WIDTH)
        layoutWebView(webView, width, captureViewportHeight(entry))
        waitPageLoaded(webView, html, ShareNoteTemplateManager.baseUrl(entry))
    }

    private suspend fun preparePayloadAssets(
        context: Context,
        payload: Payload,
        style: ShareNoteTemplateManager.ShareStyle
    ): Payload {
        val cover = resolveImageDataUrl(context.applicationContext, payload.book.cover)
        val avatar = resolveImageDataUrl(context.applicationContext, payload.profile.avatar)
        return payload.copy(
            profile = payload.profile.copy(avatar = avatar),
            appearance = payload.appearance.withRuntimeStyle(style),
            book = payload.book.copy(cover = cover)
        )
    }

    private fun previewPayload(): Payload {
        return Payload(
            generatedAt = "2026-06-20 15:30:00",
            profile = Profile(
                name = "读者",
                bio = "一段认真读过的文字"
            ),
            book = Book(
                title = "清平乐",
                author = "佚名",
                description = "一本留给夜晚慢慢翻阅的书。",
                type = "古典文学",
                kind = "古典文学",
                tags = "古典文学",
                wordCountText = "13 万字",
                readTimeText = "2小时18分钟",
                readStatusText = "在读",
                readProgressText = "72%",
                readProgressPercent = 0.72f,
                lastReadTime = "2026-06-20 15:30:00"
            ),
            note = Note(
                createAt = "2026-06-20 15:30:00",
                sectionName = "第一章",
                description = "一段文字被认真读过以后，便不只是书中的句子，也会成为某一日的风声与光。",
                comment = "这里记录当时的想法。卡片会随着文字内容自然变高，邮票齿孔不会被截图裁掉。"
            )
        )
    }

    private fun Appearance.withRuntimeStyle(style: ShareNoteTemplateManager.ShareStyle): Appearance {
        val palette = ShareNoteTemplateManager.palette(style.paletteId)
        return copy(
            colorTheme = colorTheme ?: ColorTheme(
                backgroundColor = palette.background,
                textColor = palette.text,
                mutedColor = palette.accent,
                surfaceColor = palette.surface,
                secondaryTextColor = palette.secondaryText,
                dividerColor = palette.divider
            ),
            fontFamily = fontFamily.ifBlank { fontFamilyCss(style) }
        )
    }

    private suspend fun resolveImageDataUrl(context: Context, path: String?): String? = withContext(Dispatchers.IO) {
        val value = path?.trim().orEmpty()
        if (value.isBlank()) return@withContext null
        if (value.startsWith("data:image/", ignoreCase = true)) return@withContext value
        runCatching {
            val isRemote = value.startsWith("http://", ignoreCase = true) ||
                value.startsWith("https://", ignoreCase = true)
            val options = RequestOptions()
                .format(com.bumptech.glide.load.DecodeFormat.PREFER_ARGB_8888)
                .disallowHardwareConfig()
                .downsample(DownsampleStrategy.CENTER_INSIDE)
            val builder = ImageLoader.loadBitmap(context, value)
                .apply(options)
                .let { if (isRemote) it.onlyRetrieveFromCache(true) else it }
            val target = builder.submit(640, 900)
            try {
                target.get(4, TimeUnit.SECONDS).toDataUrl()
            } finally {
                Glide.with(context).clear(target)
            }
        }.getOrNull()
    }

    private fun Bitmap.toDataUrl(): String {
        val output = ByteArrayOutputStream()
        val format = if (hasAlpha()) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG
        val mime = if (format == Bitmap.CompressFormat.PNG) "image/png" else "image/jpeg"
        compress(format, if (format == Bitmap.CompressFormat.PNG) 100 else 88, output)
        return "data:$mime;base64,${Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP)}"
    }

    private suspend fun writePngBase64ToCache(context: Context, base64: String): File = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "share_note").apply { mkdirs() }
        val file = File(dir, "share_note_${System.currentTimeMillis()}.png")
        val clean = base64.substringAfter(",", base64).trim()
        if (clean.length > MAX_CAPTURE_BASE64_LENGTH) {
            throw IllegalStateException("captured image is too large")
        }
        FileOutputStream(file).use {
            it.write(Base64.decode(clean, Base64.DEFAULT))
        }
        file
    }

    private suspend fun loadHtml(
        webView: WebView,
        entry: ShareNoteTemplateManager.Entry,
        payloadJson: String?,
        targetWidth: Int = initialWidth(entry),
        style: ShareNoteTemplateManager.ShareStyle = ShareNoteTemplateManager.currentStyle()
    ) {
        val html = prepareHtml(ShareNoteTemplateManager.readTemplateHtml(entry), payloadJson, style)
        val width = targetWidth.coerceIn(240, MAX_EXPORT_WIDTH)
        layoutWebView(webView, width, captureViewportHeight(entry))
        waitPageLoaded(webView, html, ShareNoteTemplateManager.baseUrl(entry))
        waitUntilReady(webView)
    }

    private fun initialWidth(entry: ShareNoteTemplateManager.Entry): Int {
        return if (entry.meta.canvas == ShareNoteTemplateManager.CANVAS_WIDE) {
            720
        } else {
            entry.meta.width
        }.coerceIn(240, MAX_EXPORT_WIDTH)
    }

    private fun initialHeight(entry: ShareNoteTemplateManager.Entry): Int {
        return if (entry.meta.canvas == ShareNoteTemplateManager.CANVAS_WIDE) {
            entry.meta.height
        } else {
            900
        }.coerceIn(240, 2400)
    }

    private fun captureViewportHeight(entry: ShareNoteTemplateManager.Entry): Int {
        return if (entry.meta.canvas == ShareNoteTemplateManager.CANVAS_WIDE) {
            initialHeight(entry)
        } else {
            1
        }
    }

    private fun layoutWebView(webView: WebView, width: Int, height: Int) {
        webView.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY)
        )
        webView.layout(0, 0, width, height)
    }

    private suspend fun waitUntilReady(webView: WebView) {
        withTimeoutOrNull(READY_TIMEOUT_MS) {
            waitDocumentReady(webView)
        }
        waitFontsReady(webView)
        waitImagesReady(webView)
        waitForDraw()
    }

    private suspend fun waitDocumentReady(webView: WebView) {
        pollJs(webView, 80L) {
            """document.readyState === "complete";"""
        }
    }

    private suspend fun waitFontsReady(webView: WebView) {
        runCatching {
            withTimeoutOrNull(1500L) {
                pollJs(webView, 80L) {
                    """
                        (function(){
                          return !document.fonts || document.fonts.status === "loaded";
                        })();
                    """.trimIndent()
                }
            }
        }
    }

    private suspend fun waitImagesReady(webView: WebView) {
        withTimeoutOrNull(3000L) {
            pollJs(webView, 100L) {
                """
                    (function(){
                      var imgs = Array.prototype.slice.call(document.images || []);
                      return imgs.length === 0 || imgs.every(function(img){ return img.complete; });
                    })();
                """.trimIndent()
            }
        }
    }

    private suspend fun pollJs(webView: WebView, intervalMs: Long, script: () -> String) {
        while (true) {
            coroutineContext.ensureActive()
            if (evaluateBoolean(webView, script())) return
            delay(intervalMs)
        }
    }

    private suspend fun evaluateCaptureSize(
        webView: WebView,
        fallbackWidth: Int,
        fallbackHeight: Int
    ): CaptureSize {
        val raw = evaluateString(
            webView,
            """
                (function(){
                  var explicitNode = document.querySelector('[data-reeden-capture]');
                  var node = explicitNode || document.body;
                  var rect = node.getBoundingClientRect();
                  var widthSource = explicitNode
                    ? Math.max(rect.width || 0, node.scrollWidth || 0, node.offsetWidth || 0, $fallbackWidth)
                    : Math.max(rect.width || 0, document.body.scrollWidth || 0, $fallbackWidth);
                  var heightSource = explicitNode
                    ? Math.max(rect.height || 0, node.scrollHeight || 0, node.offsetHeight || 0, $fallbackHeight)
                    : Math.max(rect.height || 0, document.body.scrollHeight || 0, $fallbackHeight);
                  return JSON.stringify({
                    left: Math.max(0, Math.floor(rect.left || 0)),
                    top: Math.max(0, Math.floor(rect.top || 0)),
                    width: Math.ceil(widthSource),
                    height: Math.ceil(heightSource)
                  });
                })();
            """.trimIndent()
        )
        val json = runCatching { JSONObject(raw) }.getOrNull()
        return CaptureSize(
            width = json?.optInt("width", fallbackWidth)?.coerceIn(240, MAX_EXPORT_WIDTH) ?: fallbackWidth,
            height = json?.optInt("height", fallbackHeight)?.coerceIn(180, MAX_EXPORT_HEIGHT) ?: fallbackHeight,
            left = json?.optInt("left", 0) ?: 0,
            top = json?.optInt("top", 0) ?: 0
        )
    }

    private suspend fun evaluateBoolean(webView: WebView, script: String): Boolean {
        return evaluateString(webView, script) == "true"
    }

    private suspend fun evaluateString(webView: WebView, script: String): String {
        return suspendCancellableCoroutine { continuation ->
            webView.evaluateJavascript(script) { raw ->
                if (!continuation.isActive) {
                    return@evaluateJavascript
                }
                val value = raw
                    ?.trim()
                    ?.trim('"')
                    ?.replace("\\\"", "\"")
                    ?.replace("\\\\", "\\")
                    .orEmpty()
                continuation.resume(value)
            }
        }
    }

    private suspend fun waitForDraw() {
        delay(32)
    }

    private fun prepareHtml(
        html: String,
        payloadJson: String?,
        style: ShareNoteTemplateManager.ShareStyle = ShareNoteTemplateManager.currentStyle(),
        extraBodyScript: String? = null
    ): String {
        val styleTag = buildStyleTag(style)
        if (payloadJson.isNullOrBlank()) {
            return appendBeforeBody(appendBeforeHeadEnd(html, styleTag), extraBodyScript)
        }
        val escaped = escapeJsonForScript(payloadJson)
        val payloadCall = "window.ReedenShareTemplate.render($escaped);"
        val replacedHtml = replaceMockRenderCalls(html, payloadCall)
        if (replacedHtml != html) {
            return appendBeforeBody(appendBeforeHeadEnd(replacedHtml, styleTag), extraBodyScript)
        }
        val script = """
            <script>
            window.ReedenShareTemplatePayload = $escaped;
            if (window.ReedenShareTemplate && typeof window.ReedenShareTemplate.render === "function") {
              window.ReedenShareTemplate.render(window.ReedenShareTemplatePayload);
            }
            </script>
        """.trimIndent()
        return appendBeforeBody(appendBeforeHeadEnd(html, styleTag), script + extraBodyScript.orEmpty())
    }

    private fun replaceMockRenderCalls(html: String, payloadCall: String): String {
        val regex = Regex(
            """(?:window\s*\.\s*)?ReedenShareTemplate\s*\.\s*render\s*\(\s*(?:window\s*\.\s*)?ReedenShareTemplateMock\s*\)\s*;?"""
        )
        return regex.replace(html) { payloadCall }
    }

    private fun escapeJsonForScript(json: String): String {
        return json
            .replace("<", "\\u003C")
            .replace(">", "\\u003E")
            .replace("&", "\\u0026")
            .replace("\u2028", "\\u2028")
            .replace("\u2029", "\\u2029")
    }

    private fun buildStyleTag(style: ShareNoteTemplateManager.ShareStyle): String {
        val palette = ShareNoteTemplateManager.palette(style.paletteId)
        val fontFamily = fontFamilyCss(style)
        val accentSoft = cssColorWithAlpha(palette.accent, 0.12f)
        return """
            <style id="reeden-share-runtime-style">
            :root {
              --reeden-share-bg: ${palette.background};
              --reeden-share-surface: ${palette.surface};
              --reeden-share-text: ${palette.text};
              --reeden-share-secondary: ${palette.secondaryText};
              --reeden-share-accent: ${palette.accent};
              --reeden-share-divider: ${palette.divider};
              --reeden-share-font: $fontFamily;
            }
            html, body {
              background: transparent !important;
              color: var(--reeden-share-text) !important;
              font-family: var(--reeden-share-font) !important;
            }
            [data-reeden-capture] {
              background: var(--reeden-share-surface) !important;
              color: var(--reeden-share-text) !important;
              border-color: var(--reeden-share-divider) !important;
              font-family: var(--reeden-share-font) !important;
            }
            [data-reeden-capture] * {
              font-family: inherit !important;
            }
            [data-reeden-capture] .eyebrow,
            [data-reeden-capture] h2,
            [data-reeden-capture] .chip {
              color: var(--reeden-share-accent) !important;
            }
            [data-reeden-capture] .subtitle,
            [data-reeden-capture] .footer,
            [data-reeden-capture] .info-row b {
              color: var(--reeden-share-secondary) !important;
            }
            [data-reeden-capture] .body-text,
            [data-reeden-capture] .info-row span,
            [data-reeden-capture] blockquote {
              color: var(--reeden-share-text) !important;
            }
            [data-reeden-capture] .chip {
              border-color: var(--reeden-share-accent) !important;
              background: $accentSoft !important;
            }
            [data-reeden-capture] blockquote {
              border-left-color: var(--reeden-share-accent) !important;
            }
            [data-reeden-capture] .section,
            [data-reeden-capture] .footer {
              border-top-color: var(--reeden-share-divider) !important;
            }
            </style>
        """.trimIndent()
    }

    private fun fontFamilyCss(style: ShareNoteTemplateManager.ShareStyle): String {
        return when (style.fontFamily) {
            ShareNoteTemplateManager.FONT_SERIF -> "Georgia, 'Noto Serif CJK SC', 'Source Han Serif SC', serif"
            ShareNoteTemplateManager.FONT_ROUND -> "'MiSans', 'HarmonyOS Sans SC', 'Noto Sans CJK SC', sans-serif"
            ShareNoteTemplateManager.FONT_MONO -> "'JetBrains Mono', 'Roboto Mono', monospace"
            else -> "-apple-system, BlinkMacSystemFont, 'Segoe UI', 'Noto Sans CJK SC', sans-serif"
        }
    }

    private fun cssColorWithAlpha(hex: String, alpha: Float): String {
        val clean = hex.trim().removePrefix("#")
        val rgb = when (clean.length) {
            6 -> clean
            8 -> clean.takeLast(6)
            else -> return "rgba(0,0,0,0)"
        }
        return runCatching {
            val r = rgb.substring(0, 2).toInt(16)
            val g = rgb.substring(2, 4).toInt(16)
            val b = rgb.substring(4, 6).toInt(16)
            "rgba($r,$g,$b,${alpha.coerceIn(0f, 1f)})"
        }.getOrDefault("rgba(0,0,0,0)")
    }

    private fun appendBeforeHeadEnd(html: String, content: String): String {
        if (content.isBlank()) return html
        val headEnd = Regex("</head>", RegexOption.IGNORE_CASE).find(html)
        return if (headEnd != null) {
            html.replaceRange(headEnd.range, "$content${headEnd.value}")
        } else {
            "$content$html"
        }
    }

    private fun appendBeforeBody(html: String, script: String?): String {
        if (script.isNullOrBlank()) return html
        val bodyEnd = Regex("</body>", RegexOption.IGNORE_CASE).find(html)
        return if (bodyEnd != null) {
            html.replaceRange(bodyEnd.range, "$script${bodyEnd.value}")
        } else {
            html + script
        }
    }

    private fun buildCaptureScript(context: Context, maxCaptureHeight: Int? = null): String {
        val previewMaxHeight = maxCaptureHeight?.coerceIn(1, MAX_EXPORT_HEIGHT) ?: 0
        return """
            <script>
            ${html2CanvasScript(context)}
            </script>
            <script>
            (function(){
              var bridgeName = "$CAPTURE_BRIDGE_NAME";
              var maxWidth = $MAX_EXPORT_WIDTH;
              var maxHeight = $MAX_EXPORT_HEIGHT;
              var maxPixels = $MAX_EXPORT_PIXELS;
              var chunkSize = $BRIDGE_CHUNK_SIZE;
              var imageTimeout = 3000;
              var previewMaxHeight = $previewMaxHeight;

              function bridge() {
                return window[bridgeName];
              }

              function reportError(error) {
                try {
                  var message = error && error.message ? error.message : String(error || "unknown");
                  var stack = error && error.stack ? error.stack : "";
                  bridge().onError(message, stack);
                } catch (_) {}
              }

              function timeout(ms) {
                return new Promise(function(resolve) { setTimeout(resolve, ms); });
              }

              function nextFrames() {
                return new Promise(function(resolve) {
                  requestAnimationFrame(function() {
                    requestAnimationFrame(resolve);
                  });
                });
              }

              function waitFonts() {
                if (!document.fonts || !document.fonts.ready) return Promise.resolve();
                return Promise.race([document.fonts.ready, timeout(1500)]);
              }

              function waitImages() {
                var imgs = Array.prototype.slice.call(document.images || []);
                if (!imgs.length) return Promise.resolve();
                return Promise.all(imgs.map(function(img) {
                  return new Promise(function(resolve) {
                    function done(ok) {
                      clearTimeout(timer);
                      img.onload = null;
                      img.onerror = null;
                      if (!ok || (!img.naturalWidth && !img.naturalHeight)) {
                        img.style.visibility = "hidden";
                      }
                      resolve();
                    }
                    if (img.complete) {
                      done(!!(img.naturalWidth || img.naturalHeight));
                      return;
                    }
                    var timer = setTimeout(function() { done(false); }, imageTimeout);
                    img.onload = function() { done(true); };
                    img.onerror = function() { done(false); };
                  });
                }));
              }

              function captureScale(width, height) {
                var byWidth = maxWidth / Math.max(width, 1);
                var byHeight = maxHeight / Math.max(height, 1);
                var byPixels = Math.sqrt(maxPixels / Math.max(width * height, 1));
                var preferred = Math.max(1, Math.min(window.devicePixelRatio || 1, 3));
                return Math.max(0.2, Math.min(preferred, byWidth, byHeight, byPixels));
              }

              function normalizeCaptureNode(node) {
                if (!node || !node.hasAttribute || !node.hasAttribute("data-reeden-capture")) return;
                if (node.getAttribute("data-reeden-fixed-viewport") === "true") return;
                var viewportHeight = Math.max(window.innerHeight || document.documentElement.clientHeight || 1, 1);
                var computedHeight = 0;
                try {
                  computedHeight = parseFloat(window.getComputedStyle(node).height) || 0;
                } catch (_) {}
                node.style.setProperty("min-height", "0", "important");
                if (computedHeight > 0 && computedHeight <= viewportHeight + 1 && node.scrollHeight > computedHeight + 1) {
                  node.style.setProperty("height", "auto", "important");
                }
              }

              function sendCanvas(canvas) {
                var dataUrl = canvas.toDataURL("image/png");
                var comma = dataUrl.indexOf(",");
                var base64 = comma >= 0 ? dataUrl.substring(comma + 1) : dataUrl;
                var id = String(Date.now()) + "_" + String(Math.random()).slice(2);
                bridge().onImageStart(id, canvas.width, canvas.height, base64.length);
                for (var i = 0; i < base64.length; i += chunkSize) {
                  bridge().onImageChunk(id, base64.substring(i, i + chunkSize));
                }
                bridge().onImageEnd(id);
              }

              async function runCapture() {
                if (!window.html2canvas) throw new Error("html2canvas not loaded");
                await waitFonts();
                await waitImages();
                await nextFrames();
                var node = document.querySelector("[data-reeden-capture]") || document.body;
                if (!node) throw new Error("capture node missing");
                normalizeCaptureNode(node);
                await nextFrames();
                var rect = node.getBoundingClientRect();
                var width = Math.ceil(Math.max(rect.width || 0, node.scrollWidth || 0, node.offsetWidth || 0, 1));
                var height = Math.ceil(Math.max(rect.height || 0, node.scrollHeight || 0, node.offsetHeight || 0, 1));
                var doc = document.documentElement || document.body;
                var body = document.body || doc;
                var captureHeight = previewMaxHeight > 0 ? Math.min(height, previewMaxHeight) : height;
                var scale = captureScale(width, captureHeight);
                var canvas = await html2canvas(node, {
                  backgroundColor: null,
                  scale: scale,
                  useCORS: false,
                  allowTaint: false,
                  logging: false,
                  scrollX: 0,
                  scrollY: 0,
                  width: width,
                  height: captureHeight,
                  windowWidth: Math.ceil(Math.max(doc.scrollWidth || 0, body.scrollWidth || 0, width)),
                  windowHeight: Math.ceil(Math.max(doc.scrollHeight || 0, body.scrollHeight || 0, height))
                });
                sendCanvas(canvas);
              }

              var started = false;
              function startCapture() {
                if (started) return;
                started = true;
                setTimeout(function() { runCapture().catch(reportError); }, 0);
              }

              if (document.readyState === "loading") {
                document.addEventListener("DOMContentLoaded", startCapture, { once: true });
                window.addEventListener("load", startCapture, { once: true });
                setTimeout(startCapture, 1200);
              } else {
                startCapture();
              }
            })();
            </script>
        """.trimIndent()
    }

    private fun html2CanvasScript(context: Context): String {
        html2CanvasScriptCache?.let { return it }
        val script = context.assets.open(HTML2_CANVAS_ASSET).bufferedReader().use { it.readText() }
        html2CanvasScriptCache = script
        return script
    }

    fun isUsableRenderResult(result: RenderResult): Boolean {
        return result.width > 1 && result.height > 1 && isUsablePng(result.file)
    }

    private class CaptureBridge {
        private val deferred = CompletableDeferred<JsPngResult>()
        private val lock = Any()
        private var imageId: String? = null
        private var width = 0
        private var height = 0
        private var builder: StringBuilder? = null

        suspend fun await(): JsPngResult = deferred.await()

        @JavascriptInterface
        fun onImageStart(id: String?, width: Int, height: Int, totalLength: Int) {
            if (id.isNullOrBlank() || deferred.isCompleted) return
            val pixels = width.toLong() * height.toLong()
            if (
                width <= 1 ||
                height <= 1 ||
                pixels > MAX_EXPORT_PIXELS.toLong() ||
                totalLength <= 0 ||
                totalLength > MAX_CAPTURE_BASE64_LENGTH
            ) {
                deferred.completeExceptionally(IllegalStateException("captured image is too large"))
                return
            }
            synchronized(lock) {
                if (!deferred.isCompleted) {
                    imageId = id
                    this.width = width
                    this.height = height
                    builder = StringBuilder(totalLength)
                }
            }
        }

        @JavascriptInterface
        fun onImageChunk(id: String?, chunk: String?) {
            if (id.isNullOrBlank() || chunk == null || deferred.isCompleted) return
            var tooLarge = false
            synchronized(lock) {
                if (id == imageId) {
                    val current = builder
                    if (current != null) {
                        if (current.length + chunk.length > MAX_CAPTURE_BASE64_LENGTH) {
                            builder = null
                            tooLarge = true
                        } else {
                            current.append(chunk)
                        }
                    }
                }
            }
            if (tooLarge && !deferred.isCompleted) {
                deferred.completeExceptionally(IllegalStateException("captured image is too large"))
            }
        }

        @JavascriptInterface
        fun onImageEnd(id: String?) {
            if (id.isNullOrBlank() || deferred.isCompleted) return
            val result = synchronized(lock) {
                if (id != imageId) return
                val data = builder?.toString().orEmpty()
                builder = null
                JsPngResult(data, width, height)
            }
            if (result.base64.isBlank()) {
                deferred.completeExceptionally(IllegalStateException("empty captured image"))
            } else {
                deferred.complete(result)
            }
        }

        @JavascriptInterface
        fun onError(message: String?, stack: String?) {
            if (deferred.isCompleted) return
            val detail = listOfNotNull(message?.takeIf { it.isNotBlank() }, stack?.takeIf { it.isNotBlank() })
                .joinToString("\n")
                .ifBlank { "html2canvas capture failed" }
            deferred.completeExceptionally(IllegalStateException(detail))
        }
    }

    private suspend fun waitPageLoaded(webView: WebView, html: String, baseUrl: String) {
        suspendCancellableCoroutine<Unit> { continuation ->
            val resumed = AtomicBoolean(false)
            var finishRunnable: Runnable? = null
            lateinit var timeoutRunnable: Runnable
            fun resumeOnce(error: Throwable? = null) {
                if (!resumed.compareAndSet(false, true)) return
                webView.removeCallbacks(timeoutRunnable)
                finishRunnable?.let(webView::removeCallbacks)
                if (!continuation.isActive) return
                if (error != null) continuation.resumeWithException(error) else continuation.resume(Unit)
            }
            timeoutRunnable = Runnable {
                resumeOnce()
            }
            webView.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    val runnable = Runnable { resumeOnce() }
                    finishRunnable = runnable
                    webView.postDelayed(runnable, 120)
                }
            }
            continuation.invokeOnCancellation {
                webView.removeCallbacks(timeoutRunnable)
                finishRunnable?.let(webView::removeCallbacks)
            }
            webView.loadDataWithBaseURL(baseUrl, html, "text/html", "UTF-8", null)
            webView.postDelayed(timeoutRunnable, READY_TIMEOUT_MS)
        }
    }

    private fun drawWebViewToBitmap(
        webView: WebView,
        maxPixels: Int = MAX_EXPORT_PIXELS
    ): Bitmap {
        check(Looper.myLooper() == Looper.getMainLooper())
        val width = webView.width.coerceAtLeast(1)
        val height = webView.height.coerceAtLeast(1)
        val scale = exportScale(width, height, maxPixels)
        val outWidth = ceil(width * scale).toInt().coerceAtLeast(1)
        val outHeight = ceil(height * scale).toInt().coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(outWidth, outHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.scale(scale, scale)
        webView.draw(canvas)
        return bitmap
    }

    private fun exportScale(width: Int, height: Int, maxPixels: Int = MAX_EXPORT_PIXELS): Float {
        val byWidth = MAX_EXPORT_WIDTH.toFloat() / width.toFloat()
        val byHeight = MAX_EXPORT_HEIGHT.toFloat() / height.toFloat()
        val byPixels = sqrt(maxPixels.toFloat() / (width.toFloat() * height.toFloat()))
        return minOf(1f, byWidth, byHeight, byPixels).coerceAtMost(1f)
    }

    private fun isUsablePng(file: File): Boolean {
        if (!file.exists() || file.length() <= 512L) return false
        return runCatching {
            val options = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            BitmapFactory.decodeFile(file.absolutePath, options)
            options.outWidth > 1 && options.outHeight > 1
        }.getOrDefault(false)
    }

    private suspend fun writeBitmapToCache(context: Context, bitmap: Bitmap): File = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "share_note").apply { mkdirs() }
        val file = File(dir, "share_note_${System.currentTimeMillis()}.png")
        try {
            FileOutputStream(file).use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 96, it)
            }
            file
        } finally {
            bitmap.recycle()
        }
    }

}
