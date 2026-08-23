package io.legado.app.model.localBook.epubcore.web

import android.annotation.SuppressLint
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.View
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebView.VisualStateCallback
import io.legado.app.constant.AppLog
import io.legado.app.model.localBook.epubcore.archive.EpubArchive
import io.legado.app.model.localBook.epubcore.archive.EpubPath
import io.legado.app.utils.runOnUI
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import splitties.init.appCtx
import java.io.ByteArrayInputStream
import java.io.Closeable
import java.net.URLConnection
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

class EpubWebLayoutSession(
    private val archive: EpubArchive
) : Closeable {

    private val mutex = Mutex()
    private val handler = Handler(Looper.getMainLooper())
    private var webView: WebView? = null
    @Volatile
    private var closed = false
    @Volatile
    private var layoutToken = 0L
    private var pendingToken: Long? = null
    private var pendingCompletion: ((EpubWebLayoutDocument?) -> Unit)? = null

    suspend fun layout(request: EpubWebLayoutRequest): EpubWebLayoutDocument? {
        if (closed) return null
        if (request.html.isBlank() || request.viewportWidthPx <= 0 || request.viewportHeightPx <= 0) {
            return null
        }
        return mutex.withLock {
            withTimeoutOrNull(request.timeoutMillis) {
                layoutLocked(request)
            }
        }
    }

    private suspend fun layoutLocked(request: EpubWebLayoutRequest): EpubWebLayoutDocument? {
        return suspendCancellableCoroutine { continuation ->
            val token = ++layoutToken
            val completed = AtomicBoolean(false)
            val finish: (EpubWebLayoutDocument?) -> Unit = finish@ { result ->
                if (!completed.compareAndSet(false, true)) return@finish
                synchronized(this@EpubWebLayoutSession) {
                    if (pendingToken == token) {
                        pendingToken = null
                        pendingCompletion = null
                    }
                }
                handler.removeCallbacksAndMessages(token)
                runOnUI {
                    if (layoutToken == token) {
                        webView?.stopLoading()
                        webView?.loadUrl(WebViewBlank)
                    }
                }
                if (continuation.isActive) {
                    continuation.resume(result)
                }
            }

            continuation.invokeOnCancellation {
                val invalidated = synchronized(this@EpubWebLayoutSession) {
                    if (layoutToken == token) {
                        layoutToken++
                        true
                    } else {
                        false
                    }
                }
                finish(null)
                if (invalidated) {
                    runOnUI {
                        if (layoutToken == token + 1) {
                            webView?.stopLoading()
                            webView?.loadUrl(WebViewBlank)
                        }
                    }
                }
            }

            val registered = synchronized(this@EpubWebLayoutSession) {
                if (closed || !continuation.isActive) {
                    false
                } else {
                    pendingToken = token
                    pendingCompletion = finish
                    true
                }
            }
            if (!registered) {
                finish(null)
                return@suspendCancellableCoroutine
            }

            runOnUI {
                if (closed || !continuation.isActive) {
                    finish(null)
                    return@runOnUI
                }
                runCatching {
                    val view = ensureWebView()
                    val baseUrl = buildBaseUrl(request.chapterHref)
                    view.webViewClient = LayoutClient(
                        request = request,
                        baseUrl = baseUrl,
                        token = token,
                        onResult = finish
                    )
                    view.measure(
                        View.MeasureSpec.makeMeasureSpec(request.viewportWidthPx, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(request.viewportHeightPx, View.MeasureSpec.EXACTLY)
                    )
                    view.layout(0, 0, request.viewportWidthPx, request.viewportHeightPx)
                    view.evaluateJavascript(
                        "window.__legadoEpubLayoutToken=$token;window.__legadoEpubLayoutState=null",
                        null
                    )
                    view.loadDataWithBaseURL(baseUrl, wrapHtml(request), "text/html", "UTF-8", null)
                }.onFailure {
                    AppLog.putDebug("EPUB Web layout start failed: ${it.localizedMessage}", it)
                    finish(null)
                }
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun ensureWebView(): WebView {
        webView?.let { return it }
        return WebView(appCtx).apply {
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = false
                databaseEnabled = false
                cacheMode = WebSettings.LOAD_NO_CACHE
                loadsImagesAutomatically = true
                blockNetworkImage = false
                allowFileAccess = false
                allowContentAccess = false
                textZoom = 100
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                }
            }
            setBackgroundColor(Color.TRANSPARENT)
        }.also { webView = it }
    }

    override fun close() {
        val completion = synchronized(this) {
            closed = true
            layoutToken++
            pendingCompletion.also {
                pendingToken = null
                pendingCompletion = null
            }
        }
        completion?.invoke(null)
        handler.removeCallbacksAndMessages(null)
        runOnUI {
            webView?.run {
                stopLoading()
                loadUrl(WebViewBlank)
                destroy()
            }
            webView = null
        }
    }

    private inner class LayoutClient(
        private val request: EpubWebLayoutRequest,
        private val baseUrl: String,
        private val token: Long,
        private val onResult: (EpubWebLayoutDocument?) -> Unit
    ) : WebViewClient() {

        override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
            return archiveResponse(request?.url?.toString(), this.request)
        }

        @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
        override fun shouldInterceptRequest(view: WebView?, url: String?): WebResourceResponse? {
            return archiveResponse(url, request)
        }

        override fun onPageFinished(view: WebView, url: String?) {
            if (url != baseUrl) return
            if (token != layoutToken) return
            evaluateAfterVisualState(view, request, token, onResult)
        }

        override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
            if (webView === view) {
                webView = null
            }
            runCatching { view?.destroy() }
            if (token == layoutToken) {
                onResult(null)
            }
            return true
        }
    }

    private fun evaluateAfterVisualState(
        view: WebView,
        request: EpubWebLayoutRequest,
        token: Long,
        onResult: (EpubWebLayoutDocument?) -> Unit
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            view.postVisualStateCallback(token, object : VisualStateCallback() {
                override fun onComplete(requestId: Long) {
                    if (requestId == token && token == layoutToken) {
                        evaluateWhenReady(view, request, token, onResult)
                    }
                }
            })
        } else {
            evaluateWhenReady(view, request, token, onResult)
        }
    }

    private fun evaluateWhenReady(
        view: WebView,
        request: EpubWebLayoutRequest,
        token: Long,
        onResult: (EpubWebLayoutDocument?) -> Unit
    ) {
        val script = buildLayoutJs(request, token)
        var retry = 0
        fun evaluate() {
            if (token != layoutToken) return
            view.evaluateJavascript(script) { raw ->
                if (token != layoutToken) return@evaluateJavascript
                val decoded = decodeJavascriptString(raw)
                if (decoded == PendingResult && retry++ < 60) {
                    handler.postAtTime(::evaluate, token, android.os.SystemClock.uptimeMillis() + 100L)
                    return@evaluateJavascript
                }
                runCatching { EpubWebLayoutJsonParser.parseJavascriptResult(request, raw) }
                    .onSuccess(onResult)
                    .onFailure {
                        AppLog.putDebug("EPUB Web layout parse failed: ${it.localizedMessage}", it)
                        onResult(null)
                    }
            }
        }
        evaluate()
    }

    private fun wrapHtml(request: EpubWebLayoutRequest): String {
        val css = buildReaderCss(request)
        val html = request.html
        val headClose = Regex("</head\\s*>", RegexOption.IGNORE_CASE)
        val htmlTag = Regex("<html[\\s>]", RegexOption.IGNORE_CASE)
        return if (headClose.containsMatchIn(html)) {
            html.replace(headClose, "<style id=\"legado-reader-css\">$css</style></head>")
        } else if (htmlTag.containsMatchIn(html)) {
            html.replaceFirst(
                Regex("<html([^>]*)>", RegexOption.IGNORE_CASE),
                "<html$1><head><style id=\"legado-reader-css\">$css</style></head>"
            )
        } else {
            "<html><head><style id=\"legado-reader-css\">$css</style></head><body>$html</body></html>"
        }
    }

    private fun buildReaderCss(request: EpubWebLayoutRequest): String {
        val color = "#%06X".format(0xFFFFFF and request.textColor)
        val readerFontFace = if (!request.readerFontFamily.isNullOrBlank() && !request.readerFontUrl.isNullOrBlank()) {
            """
            @font-face {
              font-family: ${request.readerFontFamily.cssString()};
              src: url('${request.readerFontUrl.cssUrl()}');
            }
            """.trimIndent()
        } else {
            ""
        }
        val justifyCss = if (request.textFullJustify) {
            """
              text-align: justify;
              text-align-last: auto;
              text-justify: inter-character;
            """.trimIndent()
        } else {
            ""
        }
        return """
            $readerFontFace
            html {
              margin: 0 !important;
              padding: 0 !important;
              width: ${request.viewportWidthPx}px !important;
              height: ${request.viewportHeightPx}px !important;
              overflow: hidden !important;
              background: transparent !important;
              font-size: ${request.fontSizePx}px;
              line-height: ${request.lineHeightPx}px;
            }
            :root {
              font-size: ${request.fontSizePx}px;
              line-height: ${request.lineHeightPx}px;
            }
            body {
              margin: 0 !important;
              padding: 0 !important;
              width: ${request.viewportWidthPx}px !important;
              height: ${request.viewportHeightPx}px !important;
              overflow: visible !important;
              color: $color;
              font-size: ${request.fontSizePx}px;
              line-height: ${request.lineHeightPx}px;
              letter-spacing: ${request.letterSpacingEm}em;
              $justifyCss
              -webkit-column-width: ${request.viewportWidthPx}px;
              column-width: ${request.viewportWidthPx}px;
              -webkit-column-gap: 0;
              column-gap: 0;
              -webkit-column-fill: auto;
              column-fill: auto;
            }
            body, body * {
              box-sizing: border-box;
            }
            p, li, blockquote, div {
              overflow-wrap: break-word;
              word-break: break-word;
              $justifyCss
            }
            img, svg, video, canvas {
              max-width: 100%;
              height: auto;
            }
            body > article,
            body > section,
            body > main,
            body > div.book-wrapper,
            body > article.book-wrapper {
              max-width: none !important;
              width: 100% !important;
              margin-left: 0 !important;
              margin-right: 0 !important;
              padding-left: 0 !important;
              padding-right: 0 !important;
              background-color: transparent !important;
              border-left-width: 0 !important;
              border-right-width: 0 !important;
              box-shadow: none !important;
            }
        """.trimIndent()
    }

    private fun String.cssString(): String {
        return "'${replace("\\", "\\\\").replace("'", "\\'")}'"
    }

    private fun String.cssJsString(): String {
        return "'${replace("\\", "\\\\").replace("'", "\\'").replace("\n", "\\n").replace("\r", "")}'"
    }

    private fun String.cssUrl(): String {
        return replace("\\", "\\\\").replace("'", "\\'")
    }

    private fun buildLayoutJs(request: EpubWebLayoutRequest, token: Long): String {
        val waitForImages = request.html.contains("<img", ignoreCase = true) ||
            request.html.contains("<image", ignoreCase = true) ||
            request.html.contains("background-image", ignoreCase = true) ||
            request.html.contains("url(", ignoreCase = true)
        val waitForFonts = request.html.contains("@font-face", ignoreCase = true) ||
            !request.readerFontUrl.isNullOrBlank()
        val settleDelay = if (waitForImages || waitForFonts) 80 else 16
        return """
            (function() {
              var EXPECTED_TOKEN = $token;
              var MAX_GLYPHS = 2600;
              var glyphCount = 0;
              window.__legadoEpubLayoutToken = EXPECTED_TOKEN;
              function isCurrent() {
                return window.__legadoEpubLayoutToken === EXPECTED_TOKEN;
              }
              if (window.__legadoEpubLayoutState && window.__legadoEpubLayoutState.status === 'ready') {
                return window.__legadoEpubLayoutState.result;
              }
              if (window.__legadoEpubLayoutState && window.__legadoEpubLayoutState.status === 'pending') {
                return '${PendingResult}';
              }
              window.__legadoEpubLayoutState = { status: 'pending', result: null };
              var VIEWPORT_W = ${request.viewportWidthPx};
              var VIEWPORT_H = ${request.viewportHeightPx};
              var READER_PAD_LEFT = ${request.readerPaddingLeftPx.coerceAtLeast(0)};
              var READER_PAD_TOP = ${request.readerPaddingTopPx.coerceAtLeast(0)};
              var READER_PAD_RIGHT = ${request.readerPaddingRightPx.coerceAtLeast(0)};
              var READER_PAD_BOTTOM = ${request.readerPaddingBottomPx.coerceAtLeast(0)};
              var PAGE_W = VIEWPORT_W;
              var PAGE_H = Math.max(1, VIEWPORT_H - READER_PAD_TOP - READER_PAD_BOTTOM);
              var READER_TEXT_COLOR = ${request.textColor};
              var MAX_TEXT = 12000;
              var MAX_ITEMS = 8000;
              function readyImages() {
                if (!$waitForImages) return Promise.resolve();
                var imgs = Array.prototype.slice.call(document.images || []);
                return Promise.all(imgs.map(function(img) {
                  if (img.complete) return Promise.resolve();
                  return new Promise(function(resolve) {
                    img.addEventListener('load', resolve, { once: true });
                    img.addEventListener('error', resolve, { once: true });
                    setTimeout(resolve, 1200);
                  });
                }));
              }
              function readyFonts() {
                if (!$waitForFonts) return Promise.resolve();
                if (document.fonts && document.fonts.ready) return document.fonts.ready.catch(function(){});
                return Promise.resolve();
              }
              function cleanText(value) {
                return String(value || '').replace(/[\t\r\n ]+/g, ' ');
              }
              function visibleText(value) {
                return cleanText(value).replace(/^[ ]+|[ ]+$/g, '');
              }
              function number(value) {
                var parsed = parseFloat(value);
                return isFinite(parsed) ? parsed : null;
              }
              function color(value) {
                if (!value || value === 'transparent' || value === 'rgba(0, 0, 0, 0)') return null;
                var m = String(value).match(/rgba?\(([^)]+)\)/);
                if (!m) return null;
                var parts = m[1].split(',').map(function(v){ return parseFloat(v); });
                if (parts.length < 3) return null;
                var a = parts.length > 3 ? parts[3] : 1;
                return {
                  r: Math.max(0, Math.min(255, Math.round(parts[0]))),
                  g: Math.max(0, Math.min(255, Math.round(parts[1]))),
                  b: Math.max(0, Math.min(255, Math.round(parts[2]))),
                  a: Math.max(0, Math.min(1, a))
                };
              }
              function colorInt(value) {
                var c = color(value);
                if (!c || c.a <= 0) return null;
                return ((Math.round(c.a * 255) & 255) << 24) | ((c.r & 255) << 16) | ((c.g & 255) << 8) | (c.b & 255);
              }
              function firstCssLayer(value) {
                var raw = String(value || '').trim();
                if (!raw) return null;
                var quote = null;
                var depth = 0;
                for (var i = 0; i < raw.length; i++) {
                  var ch = raw.charAt(i);
                  if (quote) {
                    if (ch === quote && raw.charAt(i - 1) !== '\\') quote = null;
                    continue;
                  }
                  if (ch === '"' || ch === "'") {
                    quote = ch;
                  } else if (ch === '(') {
                    depth++;
                  } else if (ch === ')') {
                    if (depth > 0) depth--;
                  } else if (ch === ',' && depth === 0) {
                    return raw.substring(0, i).trim();
                  }
                }
                return raw;
              }
              function firstBackgroundUrl(value) {
                var raw = firstCssLayer(value);
                if (!raw || raw === 'none') return null;
                var match = raw.match(/url\((['"]?)(.*?)\1\)/i);
                return match && match[2] ? match[2].trim() : null;
              }
              function cssContentText(value) {
                var raw = String(value || '').trim();
                if (!raw || raw === 'none' || raw === 'normal') return '';
                var parts = [];
                var index = 0;
                function readString(start) {
                  var quote = raw.charAt(start);
                  var out = '';
                  for (var i = start + 1; i < raw.length; i++) {
                    var ch = raw.charAt(i);
                    if (ch === '\\' && i + 1 < raw.length) {
                      i++;
                      var escaped = raw.charAt(i);
                      out += (escaped === 'A' || escaped === 'a') ? '\n' : escaped;
                    } else if (ch === quote) {
                      return { text: out, end: i + 1 };
                    } else {
                      out += ch;
                    }
                  }
                  return { text: out, end: raw.length };
                }
                while (index < raw.length) {
                  while (index < raw.length && /\s/.test(raw.charAt(index))) index++;
                  var ch = raw.charAt(index);
                  if (ch === '"' || ch === "'") {
                    var item = readString(index);
                    parts.push(item.text);
                    index = item.end;
                    continue;
                  }
                  var next = index;
                  while (next < raw.length && !/\s/.test(raw.charAt(next))) next++;
                  var token = raw.substring(index, next).toLowerCase();
                  if (token && token !== 'open-quote' && token !== 'close-quote' && token !== 'no-open-quote' && token !== 'no-close-quote' && token.indexOf('url(') !== 0) {
                    parts.push(raw.substring(index, next));
                  }
                  index = next + 1;
                }
                return cleanText(parts.join(''));
              }
              function copyPseudoTextStyle(from, to) {
                [
                  'display', 'white-space', 'font', 'font-family', 'font-size', 'font-weight', 'font-style',
                  'font-variant', 'line-height', 'letter-spacing', 'color', 'opacity', 'text-decoration',
                  'vertical-align', 'text-transform', 'direction', 'writing-mode', '-webkit-writing-mode',
                  'margin', 'padding', 'border', 'border-radius'
                ].forEach(function(name) {
                  var value = from.getPropertyValue(name);
                  if (value) to.setProperty(name, value, from.getPropertyPriority(name));
                });
                if (!to.display || to.display === 'none') to.display = 'inline';
                to.setProperty('position', 'static', 'important');
              }
              function isHeadingLike(element) {
                var tag = String(element && element.tagName || '').toLowerCase();
                if (tag === 'h1' || tag === 'h2' || tag === 'h3' || tag === 'h4' || tag === 'h5' || tag === 'h6' || tag === 'title') return true;
                var role = String(element && element.getAttribute && (element.getAttribute('role') || '') || '').toLowerCase();
                if (role === 'heading') return true;
                var className = String(element && element.className || '');
                return /(^|\s)(title|chapter-title|heading|headline|subtitle)(\s|$)/i.test(className);
              }
              function isolatePseudoLine(element, span) {
                if (!isHeadingLike(element)) return;
                span.style.setProperty('display', 'block', 'important');
                span.style.setProperty('width', '100%', 'important');
                span.style.setProperty('max-width', '100%', 'important');
                span.style.setProperty('white-space', 'normal', 'important');
                span.style.setProperty('clear', 'both', 'important');
                span.style.setProperty('float', 'none', 'important');
                span.style.setProperty('position', 'static', 'important');
                span.style.setProperty('overflow', 'visible', 'important');
              }
              function addPseudoSuppressionStyle() {
                if (document.getElementById('legado-epub-pseudo-suppression')) return;
                var style = document.createElement('style');
                style.id = 'legado-epub-pseudo-suppression';
                style.textContent = '[data-epub-pseudo-before="true"]::before{content:none !important;}[data-epub-pseudo-after="true"]::after{content:none !important;}';
                (document.head || document.documentElement).appendChild(style);
              }
              function materializePseudoContent(root) {
                if (!root || !window.getComputedStyle) return;
                var elements = [root].concat(Array.prototype.slice.call(root.querySelectorAll('*')));
                var additions = [];
                for (var i = 0; i < elements.length && i < MAX_ITEMS; i++) {
                  var el = elements[i];
                  if (!el || el.nodeType !== 1 || el.getAttribute('data-epub-generated-pseudo')) continue;
                  ['before', 'after'].forEach(function(side) {
                    var pseudo = null;
                    try {
                      pseudo = window.getComputedStyle(el, '::' + side);
                    } catch (e) {
                      pseudo = null;
                    }
                    if (!pseudo || pseudo.display === 'none' || pseudo.visibility === 'hidden') return;
                    var text = cssContentText(pseudo.content);
                    if (!visibleText(text)) return;
                    additions.push({ element: el, side: side, text: text, style: pseudo });
                  });
                }
                if (!additions.length) return;
                addPseudoSuppressionStyle();
                additions.forEach(function(item) {
                  var span = document.createElement('span');
                  span.setAttribute('data-epub-generated-pseudo', item.side);
                  span.textContent = item.text;
                  copyPseudoTextStyle(item.style, span.style);
                  isolatePseudoLine(item.element, span);
                  if (item.side === 'before') {
                    item.element.setAttribute('data-epub-pseudo-before', 'true');
                    item.element.insertBefore(span, item.element.firstChild);
                  } else {
                    item.element.setAttribute('data-epub-pseudo-after', 'true');
                    item.element.appendChild(span);
                  }
                });
              }
              function mark(element, path) {
                if (!element || element.nodeType !== 1) return;
                element.setAttribute('data-epub-node-path', path);
                var elementIndex = 0;
                var children = element.childNodes || [];
                for (var i = 0; i < children.length; i++) {
                  var child = children[i];
                  if (child.nodeType === 1) {
                    var tag = String(child.tagName || '').toLowerCase();
                    mark(child, path + '/' + tag + '/' + (elementIndex++));
                  }
                }
              }
              var rootBounds = { left: 0, top: 0 };
              function prepareRoot(root) {
                root = root || document.body || document.documentElement;
                if (root && root.nodeType === 1) {
                    root.setAttribute('id', 'legado-epub-page-root');
                    root.style.width = PAGE_W + 'px';
                    root.style.height = PAGE_H + 'px';
                    document.documentElement.style.fontSize = '${request.fontSizePx}px';
                    document.documentElement.style.lineHeight = '${request.lineHeightPx}px';
                    root.style.fontSize = '${request.fontSizePx}px';
                    root.style.lineHeight = '${request.lineHeightPx}px';
                    root.style.webkitColumnWidth = PAGE_W + 'px';
                  root.style.columnWidth = PAGE_W + 'px';
                  root.style.webkitColumnGap = '0px';
                  root.style.columnGap = '0px';
                  root.style.webkitColumnFill = 'auto';
                  root.style.columnFill = 'auto';
                  root.style.overflow = 'visible';
                }
                rootBounds = root.getBoundingClientRect();
                return root;
              }
              function sliceRootByFragments(root) {
                var startId = ${request.startFragmentId?.let { JSONObject.quote(it) } ?: "null"};
                var endId = ${request.endFragmentId?.let { JSONObject.quote(it) } ?: "null"};
                if (!root || (!startId && !endId)) return root;
                var start = startId ? document.getElementById(startId) : null;
                var end = endId && endId !== startId ? document.getElementById(endId) : null;
                if (!start && !end) return root;
                var range = document.createRange();
                try {
                  range.selectNodeContents(root);
                  if (start) range.setStartBefore(start);
                  if (end) range.setEndBefore(end);
                  var wrapper = document.createElement('div');
                  wrapper.setAttribute('data-epub-fragment-root', 'true');
                  wrapper.appendChild(range.cloneContents());
                  while (root.firstChild) root.removeChild(root.firstChild);
                  root.appendChild(wrapper);
                  return root;
                } catch (e) {
                  return root;
                } finally {
                  range.detach();
                }
              }
              function rectPage(rect) {
                var left = rect.left - rootBounds.left;
                return Math.floor(Math.max(0, left + 0.5) / PAGE_W);
              }
              function localRect(rect) {
                if (!rect || rect.width <= 0.5 || rect.height <= 0.5) return null;
                var page = rectPage(rect);
                var left = rect.left - rootBounds.left - page * PAGE_W;
                var right = rect.right - rootBounds.left - page * PAGE_W;
                var top = rect.top - rootBounds.top;
                var bottom = rect.bottom - rootBounds.top;
                left = Math.max(0, Math.min(PAGE_W, left));
                right = Math.max(0, Math.min(PAGE_W, right));
                top = Math.max(0, Math.min(PAGE_H, top));
                bottom = Math.max(0, Math.min(PAGE_H, bottom));
                if (right - left <= 0.5 || bottom - top <= 0.5) return null;
                return {
                  page: page,
                  left: left,
                  top: top + READER_PAD_TOP,
                  right: right,
                  bottom: bottom + READER_PAD_TOP,
                  width: right - left,
                  height: bottom - top
                };
              }
              function localTextRect(rect) {
                if (!rect || rect.width <= 0.5 || rect.height <= 0.5) return null;
                var page = rectPage(rect);
                var left = rect.left - rootBounds.left - page * PAGE_W;
                var right = rect.right - rootBounds.left - page * PAGE_W;
                var top = rect.top - rootBounds.top;
                var bottom = rect.bottom - rootBounds.top;
                if (top < -0.5 || bottom > PAGE_H + 0.5) return null;
                left = Math.max(0, Math.min(PAGE_W, left));
                right = Math.max(0, Math.min(PAGE_W, right));
                if (right - left <= 0.5 || bottom - top <= 0.5) return null;
                return {
                  page: page,
                  left: left,
                  top: top + READER_PAD_TOP,
                  right: right,
                  bottom: bottom + READER_PAD_TOP,
                  width: right - left,
                  height: bottom - top
                };
              }
              function localRects(rect) {
                if (!rect || rect.width <= 0.5 || rect.height <= 0.5) return [];
                var startPage = rectPage(rect);
                var endPage = Math.floor(Math.max(0, rect.right - rootBounds.left - 0.5) / PAGE_W);
                var result = [];
                for (var page = startPage; page <= endPage; page++) {
                  var columnLeft = rootBounds.left + page * PAGE_W;
                  var columnRight = columnLeft + PAGE_W;
                  var clipped = {
                    left: Math.max(rect.left, columnLeft),
                    top: rect.top,
                    right: Math.min(rect.right, columnRight),
                    bottom: rect.bottom,
                    width: Math.min(rect.right, columnRight) - Math.max(rect.left, columnLeft),
                    height: rect.bottom - rect.top
                  };
                  var local = localRect(clipped);
                  if (local) result.push(local);
                }
                return result;
              }
              function childContentUnionLocal(element, fallbackLocal) {
                var rects = [];
                var children = element && element.children ? Array.prototype.slice.call(element.children) : [];
                for (var i = 0; i < children.length && i < 200; i++) {
                  var child = children[i];
                  var childStyle = window.getComputedStyle(child);
                  if (!childStyle || childStyle.display === 'none' || childStyle.visibility === 'hidden') continue;
                  var childRects = Array.prototype.slice.call(child.getClientRects ? child.getClientRects() : []);
                  for (var j = 0; j < childRects.length; j++) {
                    var local = localRect(childRects[j]);
                    if (local && local.page === fallbackLocal.page) rects.push(local);
                  }
                }
                if (!rects.length && visibleText(element.textContent).length) {
                  var range = document.createRange();
                  try {
                    range.selectNodeContents(element);
                    var textRects = Array.prototype.slice.call(range.getClientRects ? range.getClientRects() : []);
                    for (var k = 0; k < textRects.length; k++) {
                      var textLocal = localRect(textRects[k]);
                      if (textLocal && textLocal.page === fallbackLocal.page) rects.push(textLocal);
                    }
                  } catch (e) {
                  } finally {
                    range.detach();
                  }
                }
                if (!rects.length) return fallbackLocal;
                var left = fallbackLocal.right;
                var top = fallbackLocal.bottom;
                var right = fallbackLocal.left;
                var bottom = fallbackLocal.top;
                for (var r = 0; r < rects.length; r++) {
                  left = Math.min(left, rects[r].left);
                  top = Math.min(top, rects[r].top);
                  right = Math.max(right, rects[r].right);
                  bottom = Math.max(bottom, rects[r].bottom);
                }
                var style = window.getComputedStyle(element);
                var pad = Math.max(0, number(style.paddingTop) || 0, number(style.paddingRight) || 0, number(style.paddingBottom) || 0, number(style.paddingLeft) || 0);
                var border = Math.max(0, number(style.borderTopWidth) || 0, number(style.borderRightWidth) || 0, number(style.borderBottomWidth) || 0, number(style.borderLeftWidth) || 0);
                var inset = pad + border;
                left = Math.max(fallbackLocal.left, left - inset);
                top = Math.max(fallbackLocal.top, top - inset);
                right = Math.min(fallbackLocal.right, right + inset);
                bottom = Math.min(fallbackLocal.bottom, bottom + inset);
                if (right - left <= 0.5 || bottom - top <= 0.5) return fallbackLocal;
                return {
                  page: fallbackLocal.page,
                  left: left,
                  top: top,
                  right: right,
                  bottom: bottom,
                  width: right - left,
                  height: bottom - top
                };
              }
              function isOversizedDecoration(local) {
                return local.height > VIEWPORT_H * 0.72 && local.width > VIEWPORT_W * 0.5;
              }
              function shouldShrinkDecorationBox(element, local, hasBackgroundImage) {
                if (!element || !local || hasBackgroundImage) return false;
                if (isReaderRootShell(element)) return false;
                var tag = String(element.tagName || '').toLowerCase();
                if (tag === 'html' || tag === 'body') return false;
                if (isOversizedDecoration(local)) return true;
                if (local.width > PAGE_W * 0.92 && visibleText(element.textContent).length > 0) return true;
                return false;
              }
              function validRect(r) {
                return localRect(r) != null;
              }
              function anchorFor(node, offset) {
                var el = node && node.nodeType === 1 ? node : node && node.parentElement;
                while (el && !el.getAttribute('data-epub-node-path')) el = el.parentElement;
                return { nodePath: el ? el.getAttribute('data-epub-node-path') : 'body', textOffset: Math.max(0, offset || 0) };
              }
              function pushPage(pages, pageIndex) {
                while (pages.length <= pageIndex) {
                  pages.push({ pageIndex: pages.length, columnOffsetPx: pages.length * PAGE_W, text: '', start: null, end: null, boxes: [], fragments: [] });
                }
                return pages[pageIndex];
              }
              function fontWeightValue(value) {
                var parsed = number(value);
                if (parsed != null) return parsed;
                var raw = String(value || '').toLowerCase();
                return raw.indexOf('bold') >= 0 ? 700 : 400;
              }
              function textScaleXValue(style) {
                var transform = String(style.transform || style.webkitTransform || '');
                var matrix = transform.match(/matrix\(([^)]+)\)/);
                if (matrix) {
                  var parts = matrix[1].split(',').map(function(v){ return parseFloat(v); });
                  if (parts.length >= 1 && isFinite(parts[0]) && parts[0] > 0) return parts[0];
                }
                var stretch = String(style.fontStretch || '');
                if (stretch.indexOf('%') > 0) {
                  var percent = parseFloat(stretch);
                  if (isFinite(percent) && percent > 0) return percent / 100;
                }
                return 1;
              }
              function nearestTextRole(element) {
                  var current = element;
                  while (current && current.nodeType === 1) {
                    var tag = String(current.tagName || '').toLowerCase();
                    if (tag === 'rt' || tag === 'rp' || tag === 'ruby' || tag === 'sup' || tag === 'sub') {
                      return { tagName: tag, kind: textKindForTag(tag) };
                    }
                    if (tag === 'h1' || tag === 'h2' || tag === 'h3' || tag === 'h4' || tag === 'h5' || tag === 'h6' || tag === 'title' || tag === 'b' || tag === 'strong' || tag === 'em' || tag === 'i') {
                      return { tagName: tag, kind: 'text' };
                    }
                    current = current.parentElement;
                  }
                var fallback = String(element && element.tagName || '').toLowerCase();
                      return { tagName: fallback || null, kind: 'text' };
                    }
              function hasExplicitFontFamily(element) {
                var current = element;
                while (current && current.nodeType === 1) {
                  if (current.getAttribute && current.getAttribute('data-legado-reader-font') === '1') {
                    current = current.parentElement;
                    continue;
                  }
                  var inlineStyle = current.getAttribute && current.getAttribute('style');
                  if (inlineStyle && /(^|;)\s*font-family\s*:/i.test(inlineStyle)) return true;
                  var sheets = document.styleSheets || [];
                  for (var i = 0; i < sheets.length; i++) {
                    var rules;
                    try {
                      rules = sheets[i].cssRules || sheets[i].rules;
                    } catch (e) {
                      rules = null;
                    }
                    if (!rules) continue;
                    for (var j = 0; j < rules.length; j++) {
                      var rule = rules[j];
                      if (!rule || !rule.style || !rule.selectorText) continue;
                      if (!rule.style.fontFamily && !rule.style.getPropertyValue('font-family')) continue;
                      try {
                        if (current.matches && current.matches(rule.selectorText)) return true;
                      } catch (e2) {
                      }
                    }
                  }
                  current = current.parentElement;
                }
                return false;
              }
              function applyReaderFont(root) {
                var family = ${request.readerFontFamily?.cssJsString() ?: "null"};
                if (!family || !root) return;
                var nodes = [];
                var walker = document.createTreeWalker(root, NodeFilter.SHOW_TEXT, {
                  acceptNode: function(node) {
                    return visibleText(node.nodeValue).length ? NodeFilter.FILTER_ACCEPT : NodeFilter.FILTER_REJECT;
                  }
                });
                while (walker.nextNode() && nodes.length < MAX_ITEMS) nodes.push(walker.currentNode);
                for (var i = 0; i < nodes.length; i++) {
                  var parent = nodes[i].parentElement || (nodes[i].parentNode && nodes[i].parentNode.nodeType === 1 ? nodes[i].parentNode : null);
                  if (!parent || hasExplicitFontFamily(parent)) continue;
                  parent.setAttribute('data-legado-reader-font', '1');
                  parent.style.fontFamily = family + ', sans-serif';
                }
              }
              function hasDirectVisibleText(element) {
                var children = element && element.childNodes || [];
                for (var i = 0; i < children.length; i++) {
                  if (children[i].nodeType === 3 && visibleText(children[i].nodeValue).length) return true;
                }
                return false;
              }
              function isReaderInsetTarget(element) {
                if (!element || element.nodeType !== 1) return false;
                var tag = String(element.tagName || '').toLowerCase();
                if (tag === 'h1' || tag === 'h2' || tag === 'h3' || tag === 'h4' || tag === 'h5' || tag === 'h6' ||
                    tag === 'title' || tag === 'rt' || tag === 'rp' || tag === 'ruby' || tag === 'sup' || tag === 'sub') return false;
                if (tag !== 'p' && tag !== 'li' && tag !== 'blockquote' && tag !== 'div') return false;
                if (tag === 'div' && !hasDirectVisibleText(element)) return false;
                var style = window.getComputedStyle(element);
                if (!style || style.display === 'none' || style.visibility === 'hidden') return false;
                var display = String(style.display || '').toLowerCase();
                if (display.indexOf('table') >= 0 || display.indexOf('flex') >= 0 || display.indexOf('grid') >= 0 || display === 'inline') return false;
                var position = String(style.position || '').toLowerCase();
                if (position === 'absolute' || position === 'fixed') return false;
                if (firstBackgroundUrl(style.backgroundImage) != null || colorInt(style.backgroundColor) != null) return false;
                if ((number(style.borderTopWidth) || 0) > 0 || (number(style.borderRightWidth) || 0) > 0 ||
                    (number(style.borderBottomWidth) || 0) > 0 || (number(style.borderLeftWidth) || 0) > 0) return false;
                if (element.closest && element.closest('figure, table, svg, ruby, h1, h2, h3, h4, h5, h6')) return false;
                return visibleText(element.textContent).length > 0;
              }
              function addCssPx(value, delta) {
                var base = number(value) || 0;
                return Math.max(0, base + delta) + 'px';
              }
              function applyReaderInsets(root) {
                var left = ${request.readerPaddingLeftPx.coerceAtLeast(0)};
                var top = ${request.readerPaddingTopPx.coerceAtLeast(0)};
                var right = ${request.readerPaddingRightPx.coerceAtLeast(0)};
                var bottom = ${request.readerPaddingBottomPx.coerceAtLeast(0)};
                if (!root || (left <= 0 && top <= 0 && right <= 0 && bottom <= 0)) return;
                var raw = Array.prototype.slice.call(root.querySelectorAll('p, li, blockquote, div'));
                var targets = [];
                for (var i = 0; i < raw.length; i++) {
                  if (isReaderInsetTarget(raw[i])) targets.push(raw[i]);
                }
                var firstBodyIndex = -1;
                for (var k = 0; k < targets.length; k++) {
                  if (!isHeadingLike(targets[k])) {
                    firstBodyIndex = k;
                    break;
                  }
                }
                for (var j = 0; j < targets.length; j++) {
                  var el = targets[j];
                  var style = window.getComputedStyle(el);
                  el.setAttribute('data-legado-reader-inset', '1');
                  if (left > 0) el.style.marginLeft = addCssPx(style.marginLeft, left);
                  if (right > 0) el.style.marginRight = addCssPx(style.marginRight, right);
                  if (j === firstBodyIndex && top > 0) el.style.marginTop = addCssPx(style.marginTop, top);
                  if (j === targets.length - 1 && bottom > 0) el.style.marginBottom = addCssPx(style.marginBottom, bottom);
                }
              }
              function textDecorationValue(style) {
                return style.textDecorationLine || style.webkitTextDecorationLine || style.textDecoration || null;
              }
              function textKindForTag(tag) {
                if (tag === 'ruby') return 'ruby';
                if (tag === 'rt' || tag === 'rp') return 'rubyText';
                if (tag === 'sup') return 'superscript';
                if (tag === 'sub') return 'subscript';
                return 'text';
              }
              function measuredTextWidth(text, style, letterSpacing) {
                if (!text) return null;
                var mono = monoTextWidth(text, style, letterSpacing);
                if (mono != null) return mono;
                var canvas = measuredTextWidth.canvas || (measuredTextWidth.canvas = document.createElement('canvas'));
                var ctx = canvas.getContext && canvas.getContext('2d');
                if (!ctx) return null;
                ctx.font = [
                  style.fontStyle || 'normal',
                  style.fontVariant || 'normal',
                  style.fontWeight || 'normal',
                  style.fontSize || '16px',
                  style.fontFamily || 'sans-serif'
                ].join(' ');
                var width = ctx.measureText(text).width;
                var spacing = letterSpacing || 0;
                if (isFinite(spacing) && spacing !== 0 && text.length > 1) width += spacing * (text.length - 1);
                return isFinite(width) && width > 0 ? width : null;
              }
              function cjkAdvance(style, letterSpacing) {
                var size = number(style && style.fontSize) || ${request.fontSizePx};
                var spacing = letterSpacing || 0;
                return Math.max(1, size + (isFinite(spacing) ? spacing : 0));
              }
              function monoTextWidth(text, style, letterSpacing) {
                var segments = graphemeSegments(text);
                if (!segments.length) return null;
                return cjkAdvance(style, letterSpacing) * segments.length;
              }
              function isForbiddenLineStart(text) {
                return /^[，。！？；：、）」』》】]/.test(String(text || ''));
              }
              function isForbiddenLineEnd(text) {
                return /[（「『《【]$/.test(String(text || ''));
              }
              function elasticLineMetrics(text, style, letterSpacing, targetWidth, allowStretch) {
                var segments = graphemeSegments(text).filter(function(item) { return visibleText(item.text); });
                var count = segments.length;
                var baseAdvance = cjkAdvance(style, letterSpacing);
                if (!count || !isFinite(targetWidth) || targetWidth <= 0) {
                  return { advance: baseAdvance, width: baseAdvance * count, scale: 1, count: count };
                }
                var natural = baseAdvance * count;
                var scale = targetWidth / natural;
                var minScale = 0.94;
                var maxScale = allowStretch ? 1.08 : 1.0;
                if (scale >= minScale && scale <= maxScale) {
                  return { advance: baseAdvance * scale, width: targetWidth, scale: scale, count: count };
                }
                if (scale > maxScale && allowStretch && (targetWidth - natural) / targetWidth <= 0.18) {
                  var cappedStretch = Math.min(scale, maxScale);
                  return { advance: baseAdvance * cappedStretch, width: baseAdvance * cappedStretch * count, scale: cappedStretch, count: count };
                }
                return { advance: baseAdvance, width: natural, scale: 1, count: count };
              }
              function baselineFor(local, fontSize, lineHeight, tag) {
                var size = fontSize || Math.max(1, local.height);
                var height = lineHeight || local.height;
                var baseline = (local.height - height) / 2 + (height - size) / 2 + size * 0.82;
                if (tag === 'rt' || tag === 'rp') baseline = Math.min(local.height - 1, size * 0.82);
                if (tag === 'sup') baseline = Math.min(local.height - 1, size * 0.78);
                if (tag === 'sub') baseline = Math.min(local.height - 1, local.height - size * 0.18);
                return Math.max(1, Math.min(local.height - 1, baseline));
              }
              function baselineMarkerFor(node, offset, local) {
                var parent = node && node.parentNode;
                if (!parent || !local) return null;
                var marker = document.createElement('span');
                marker.style.cssText = 'display:inline-block;width:0;height:0;overflow:hidden;vertical-align:baseline;padding:0;margin:0;border:0;line-height:0';
                var range = document.createRange();
                try {
                  range.setStart(node, Math.max(0, Math.min(offset, String(node.nodeValue || '').length)));
                  range.collapse(true);
                  range.insertNode(marker);
                  var rect = marker.getBoundingClientRect();
                  if (!rect) return null;
                  var page = rectPage(rect);
                  if (page !== local.page) return null;
                  var baseline = rect.top - rootBounds.top - (local.top - READER_PAD_TOP);
                  if (!isFinite(baseline)) return null;
                  return Math.max(1, Math.min(local.height - 1, baseline));
                } catch (e) {
                  return null;
                } finally {
                  range.detach();
                  if (marker.parentNode) marker.parentNode.removeChild(marker);
                }
              }
              function baselineInfoFor(node, offset, local, style, tag) {
                var estimated = baselineFor(local, number(style.fontSize), number(style.lineHeight), tag);
                var marker = baselineMarkerFor(node, offset, local);
                var baseline = marker != null ? marker : estimated;
                var source = marker != null ? 'marker' : 'estimated';
                var size = number(style.fontSize) || Math.max(1, local.height);
                var lineHeight = number(style.lineHeight) || local.height;
                var ascent = Math.max(1, Math.min(local.height * 2, baseline));
                var descent = Math.max(1, Math.min(local.height * 2, local.height - baseline));
                if (tag === 'rt' || tag === 'rp') {
                  ascent = Math.max(1, Math.min(local.height * 2, size * 0.86));
                  descent = Math.max(1, Math.min(local.height * 2, size * 0.24));
                } else if (tag === 'sup') {
                  ascent = Math.max(1, Math.min(local.height * 2, size * 0.86));
                  descent = Math.max(1, Math.min(local.height * 2, size * 0.22));
                } else if (tag === 'sub') {
                  ascent = Math.max(1, Math.min(local.height * 2, size * 0.72));
                  descent = Math.max(1, Math.min(local.height * 2, size * 0.36));
                } else {
                  ascent = Math.max(1, Math.min(local.height * 2, Math.max(size * 0.8, (lineHeight - size) / 2 + size * 0.8)));
                  descent = Math.max(1, Math.min(local.height * 2, Math.max(size * 0.25, lineHeight - ascent)));
                }
                return { baseline: baseline, source: source, ascent: ascent, descent: descent };
              }
              function visibleRects(range) {
                var raw = Array.prototype.slice.call(range.getClientRects());
                return raw.filter(function(rect) { return validRect(rect); });
              }
              function visibleTextRects(range) {
                var raw = Array.prototype.slice.call(range.getClientRects());
                return raw.filter(function(rect) { return localTextRect(rect) != null; });
              }
              function makeRange(node, start, end) {
                var range = document.createRange();
                range.setStart(node, Math.max(0, start));
                range.setEnd(node, Math.max(start, end));
                return range;
              }
              function firstVisibleRect(node, start, end) {
                var range = makeRange(node, start, end);
                var rects = visibleTextRects(range);
                range.detach();
                return rects.length ? rects[0] : null;
              }
              function sameVisualLine(a, b) {
                if (!a || !b || rectPage(a) !== rectPage(b)) return false;
                var top = Math.max(a.top, b.top);
                var bottom = Math.min(a.bottom, b.bottom);
                var overlap = Math.max(0, bottom - top);
                var minHeight = Math.max(1, Math.min(a.height, b.height));
                if (overlap >= minHeight * 0.55) return true;
                return Math.abs(a.top - b.top) <= Math.max(2, minHeight * 0.35);
              }
              function rangeFitsLine(node, start, end, baseRect) {
                var range = makeRange(node, start, end);
                var rects = Array.prototype.slice.call(range.getClientRects()).filter(function(rect) {
                  return rect && rect.width > 0.5 && rect.height > 0.5;
                });
                range.detach();
                if (!rects.length) return false;
                for (var i = 0; i < rects.length; i++) {
                  if (!sameVisualLine(rects[i], baseRect)) return false;
                  if (localTextRect(rects[i]) == null) return false;
                }
                return true;
              }
              function unionRects(rects) {
                if (!rects.length) return null;
                var left = rects[0].left;
                var top = rects[0].top;
                var right = rects[0].right;
                var bottom = rects[0].bottom;
                for (var i = 1; i < rects.length; i++) {
                  left = Math.min(left, rects[i].left);
                  top = Math.min(top, rects[i].top);
                  right = Math.max(right, rects[i].right);
                  bottom = Math.max(bottom, rects[i].bottom);
                }
                return { left: left, top: top, right: right, bottom: bottom, width: right - left, height: bottom - top };
              }
              function lineInfo(node, start, end, baseRect) {
                var range = makeRange(node, start, end);
                var rects = visibleTextRects(range).filter(function(rect) { return sameVisualLine(rect, baseRect); });
                var text = range.toString();
                range.detach();
                return { rect: unionRects(rects), text: text };
              }
              function graphemeSegments(text) {
                text = String(text || '');
                if (!text) return [];
                if (typeof Intl !== 'undefined' && Intl.Segmenter) {
                  try {
                    var segmenter = new Intl.Segmenter(undefined, { granularity: 'grapheme' });
                    var result = [];
                    var iterator = segmenter.segment(text)[Symbol.iterator]();
                    var item = iterator.next();
                    while (!item.done) {
                      result.push({ text: item.value.segment, index: item.value.index });
                      item = iterator.next();
                    }
                    return result;
                  } catch (e) {}
                }
                var fallback = [];
                var offset = 0;
                var parts = Array.from(text);
                for (var i = 0; i < parts.length; i++) {
                  fallback.push({ text: parts[i], index: offset });
                  offset += parts[i].length;
                }
                return fallback;
              }
              function glyphRectsForLine(node, start, end, baseRect, lineBaseline, style, letterSpacing, advanceOverride) {
                if (glyphCount >= MAX_GLYPHS) return [];
                var raw = String(node.nodeValue || '');
                var slice = raw.substring(start, end);
                var segments = graphemeSegments(slice);
                var glyphs = [];
                var advance = advanceOverride || cjkAdvance(style, letterSpacing);
                var cursorX = localTextRect(baseRect).left;
                for (var i = 0; i < segments.length; i++) {
                  if (glyphCount >= MAX_GLYPHS) break;
                  var text = segments[i].text;
                  if (!visibleText(text)) continue;
                  var glyphStart = start + segments[i].index;
                  var glyphEnd = glyphStart + text.length;
                  var range = makeRange(node, glyphStart, glyphEnd);
                  var rects = visibleTextRects(range).filter(function(rect) { return sameVisualLine(rect, baseRect); });
                  range.detach();
                  var rect = unionRects(rects);
                  var local = localTextRect(rect);
                  if (!local) continue;
                  glyphs.push({
                    text: text,
                    x: cursorX,
                    y: local.top,
                    width: advance,
                    height: local.height,
                    baseline: lineBaseline
                  });
                  cursorX += advance;
                  glyphCount++;
                }
                return glyphs;
              }
              function addTextFragments(root, pages) {
                var nodes = [];
                var walker = document.createTreeWalker(root, NodeFilter.SHOW_TEXT, {
                  acceptNode: function(node) {
                    return visibleText(node.nodeValue).length ? NodeFilter.FILTER_ACCEPT : NodeFilter.FILTER_REJECT;
                  }
                });
                while (walker.nextNode() && nodes.length < MAX_ITEMS) nodes.push(walker.currentNode);
                var count = 0;
                for (var nodeIndex = 0; nodeIndex < nodes.length && count < MAX_ITEMS; nodeIndex++) {
                  var node = nodes[nodeIndex];
                  var rawText = String(node.nodeValue || '');
                  if (!visibleText(rawText)) continue;
                  var parent = node.parentElement || (node.parentNode && node.parentNode.nodeType === 1 ? node.parentNode : null) || root;
                  var style = window.getComputedStyle(parent);
                  var fontSize = number(style.fontSize);
                  var fontWeight = fontWeightValue(style.fontWeight);
                  var lineHeight = number(style.lineHeight);
                  var letterSpacing = number(style.letterSpacing) || 0;
                  var colorValue = colorInt(style.color);
                  if (colorValue === READER_TEXT_COLOR) colorValue = null;
                  var opacity = number(style.opacity);
                  var role = nearestTextRole(parent);
                  var tagName = role.tagName;
                  var textScaleX = textScaleXValue(style);
                  var readerFontInherited = !!(${request.readerFontFamily != null}) && !hasExplicitFontFamily(parent);
                    var pending = [];
                  var cursor = 0;
                  while (cursor < rawText.length && count < MAX_ITEMS) {
                    if (!visibleText(rawText.charAt(cursor))) {
                      cursor++;
                      continue;
                    }
                    var baseRect = firstVisibleRect(node, cursor, cursor + 1);
                    if (!baseRect) {
                      cursor++;
                      continue;
                    }
                    var low = cursor + 1;
                    var high = rawText.length;
                    var best = low;
                    while (low <= high) {
                      var mid = Math.floor((low + high) / 2);
                      if (rangeFitsLine(node, cursor, mid, baseRect)) {
                        best = mid;
                        low = mid + 1;
                      } else {
                        high = mid - 1;
                      }
                    }
                    var startOffset = cursor;
                    var info = lineInfo(node, cursor, best, baseRect);
                    var lineText = visibleText(info.text);
                    var local = localTextRect(info.rect);
                    cursor = Math.max(best, cursor + 1);
                    if (!lineText || !local) continue;
                    var lineLeft = 0;
                    var lineRight = Math.max(lineLeft + 1, PAGE_W - READER_PAD_LEFT - READER_PAD_RIGHT);
                    var targetLineWidth = Math.max(1, lineRight - lineLeft);
                    var lineId = local.page + ':' + Math.round(local.top * 10);
                    var measuredWidth = measuredTextWidth(lineText, style, letterSpacing);
                    var allowStretch = ${request.textFullJustify} && !isForbiddenLineEnd(lineText);
                    if (measuredWidth && measuredWidth > targetLineWidth * 0.94 && lineText.length > 1) {
                      var fitLow = startOffset + 1;
                      var fitHigh = Math.min(rawText.length, best + 4);
                      var fitBest = fitLow;
                      while (fitLow <= fitHigh) {
                        var fitMid = Math.floor((fitLow + fitHigh) / 2);
                        var fitInfo = lineInfo(node, startOffset, fitMid, baseRect);
                        var fitText = visibleText(fitInfo.text);
                        var fitLocal = localTextRect(fitInfo.rect);
                        var fitMetrics = fitText ? elasticLineMetrics(fitText, style, letterSpacing, targetLineWidth, allowStretch) : null;
                        var legalStart = !isForbiddenLineStart(rawText.substring(fitMid).trim());
                        if (fitText && fitLocal && fitMetrics && fitMetrics.scale >= 0.94 && fitMetrics.scale <= 1.08 && legalStart) {
                          fitBest = fitMid;
                          fitLow = fitMid + 1;
                        } else {
                          fitHigh = fitMid - 1;
                        }
                      }
                      if (fitBest < best) {
                        best = fitBest;
                        info = lineInfo(node, startOffset, best, baseRect);
                        lineText = visibleText(info.text);
                        local = localTextRect(info.rect);
                        measuredWidth = lineText ? measuredTextWidth(lineText, style, letterSpacing) : null;
                        allowStretch = ${request.textFullJustify} && !isForbiddenLineEnd(lineText);
                        cursor = Math.max(best, startOffset + 1);
                        if (!lineText || !local) continue;
                        lineId = local.page + ':' + Math.round(local.top * 10);
                      }
                    }
                    var lineMetrics = elasticLineMetrics(lineText, style, letterSpacing, targetLineWidth, allowStretch);
                    measuredWidth = lineMetrics.width;
                    pending.push({
                      startOffset: startOffset,
                      endOffset: cursor,
                      lineText: lineText,
                      local: local,
                      measuredWidth: measuredWidth,
                      effectiveAdvance: lineMetrics.advance,
                      textScaleX: textScaleX,
                      baseRect: baseRect,
                      lineId: lineId,
                      lineLeft: lineLeft,
                      lineRight: lineRight
                    });
                    count++;
                  }
                  for (var pendingIndex = pending.length - 1; pendingIndex >= 0; pendingIndex--) {
                    var fragment = pending[pendingIndex];
                    var baselineInfo = baselineInfoFor(node, fragment.startOffset, fragment.local, style, tagName);
                    fragment.baseline = baselineInfo.baseline;
                    fragment.baselineSource = baselineInfo.source;
                    fragment.webAscent = baselineInfo.ascent;
                    fragment.webDescent = baselineInfo.descent;
                    fragment.glyphs = role.kind === 'text'
                      ? glyphRectsForLine(
                          node,
                          fragment.startOffset,
                          fragment.endOffset,
                          fragment.baseRect,
                          fragment.baseline,
                          style,
                          letterSpacing,
                          fragment.effectiveAdvance
                        )
                      : [];
                  }
                  for (var pushIndex = 0; pushIndex < pending.length; pushIndex++) {
                    var fragment = pending[pushIndex];
                    var page = pushPage(pages, fragment.local.page);
                    var anchor = anchorFor(node, fragment.startOffset);
                    var endAnchor = anchorFor(node, fragment.endOffset);
                    if (!page.start) page.start = anchor;
                    page.end = endAnchor;
                    if (page.text.length < MAX_TEXT) page.text += (page.text ? '\n' : '') + fragment.lineText;
                    page.fragments.push({
                      type: 'text',
                      text: fragment.lineText,
                      kind: role.kind,
                      source: anchor,
                      rect: fragment.local,
                      baseline: fragment.baseline,
                      baselineSource: fragment.baselineSource,
                      fontSize: fontSize,
                      lineHeight: lineHeight,
                      letterSpacing: letterSpacing,
                      textScaleX: fragment.textScaleX,
                      rectWidth: fragment.local.width,
                      measuredTextWidth: fragment.measuredWidth,
                      lineId: fragment.lineId,
                      lineLeft: fragment.lineLeft,
                      lineRight: fragment.lineRight,
                      tagName: tagName,
                      fontFamily: style.fontFamily || null,
                      readerFontInherited: readerFontInherited,
                      direction: style.direction || null,
                      writingMode: style.writingMode || style.webkitWritingMode || null,
                      textDecoration: textDecorationValue(style),
                      color: colorValue,
                      bold: fontWeight >= 600,
                      italic: style.fontStyle === 'italic' || style.fontStyle === 'oblique',
                      opacity: opacity == null ? 1 : opacity,
                      webAscent: fragment.webAscent,
                      webDescent: fragment.webDescent,
                      glyphs: fragment.glyphs || []
                    });
                  }
                }
              }
              function addRootBackgroundFragments(root, pages, pageCount) {
                var candidates = [];
                if (document.documentElement) candidates.push(document.documentElement);
                if (document.body && candidates.indexOf(document.body) < 0) candidates.push(document.body);
                if (root && candidates.indexOf(root) < 0) candidates.push(root);
                for (var i = 0; i < candidates.length; i++) {
                  var el = candidates[i];
                  if (!el || el.nodeType !== 1) continue;
                  var style = window.getComputedStyle(el);
                  if (!style || style.display === 'none' || style.visibility === 'hidden') continue;
                  var bg = colorInt(style.backgroundColor);
                  var bgImage = firstBackgroundUrl(style.backgroundImage);
                  if (bg == null && bgImage == null) continue;
                  var path = el.getAttribute('data-epub-node-path') || String(el.tagName || 'body').toLowerCase();
                  var anchor = { nodePath: path, textOffset: 0 };
                  var opacity = number(style.opacity);
                  for (var page = 0; page < pageCount; page++) {
                    pushPage(pages, page).boxes.push({
                      type: 'box',
                      source: anchor,
                      rect: {
                        page: page,
                        left: 0,
                        top: 0,
                        right: PAGE_W,
                        bottom: VIEWPORT_H,
                        width: PAGE_W,
                        height: VIEWPORT_H
                      },
                      backgroundColor: bg,
                      backgroundImage: bgImage,
                      backgroundRepeat: 'no-repeat',
                      backgroundSize: 'cover',
                      backgroundPosition: firstCssLayer(style.backgroundPosition),
                      borderColor: null,
                      borderWidth: 0,
                      borderRadius: 0,
                      opacity: opacity == null ? 1 : opacity,
                      pageBackground: true
                    });
                  }
                }
              }
              function addElementFragments(root, pages) {
                var elements = Array.prototype.slice.call(root.querySelectorAll('*'));
                for (var i = 0; i < elements.length && i < MAX_ITEMS; i++) {
                  var el = elements[i];
                  var style = window.getComputedStyle(el);
                  if (style.display === 'none' || style.visibility === 'hidden') continue;
                  var path = el.getAttribute('data-epub-node-path') || 'body';
                  var anchor = { nodePath: path, textOffset: 0 };
                  var tag = String(el.tagName || '').toLowerCase();
                  var opacity = number(style.opacity);
                  var rects = Array.prototype.slice.call(el.getClientRects ? el.getClientRects() : []);
                  if (!rects.length) rects = [el.getBoundingClientRect()];
                  if (tag === 'img' || tag === 'image') {
                    var href = el.getAttribute('src') || el.getAttribute('href') || el.getAttribute('xlink:href') || (el.getAttributeNS ? el.getAttributeNS('http://www.w3.org/1999/xlink', 'href') : '') || '';
                    var imageLocal = localRect(el.getBoundingClientRect());
                    if (!imageLocal) continue;
                    pushPage(pages, imageLocal.page).fragments.push({
                        type: 'image',
                        href: href,
                        alt: el.getAttribute('alt') || '',
                        source: anchor,
                        rect: imageLocal,
                        opacity: opacity == null ? 1 : opacity,
                        borderRadius: number(style.borderTopLeftRadius) || 0
                      });
                  } else {
                    var bg = colorInt(style.backgroundColor);
                    var bgImage = firstBackgroundUrl(style.backgroundImage);
                    var hasBackgroundImage = bgImage != null;
                    if (isReaderRootShell(el) && !hasBackgroundImage) continue;
                    var borderWidth = number(style.borderTopWidth) || 0;
                    var borderColor = colorInt(style.borderTopColor);
                    if (bg != null || hasBackgroundImage || (borderWidth > 0 && borderColor != null)) {
                      for (var rectIndex = rects.length - 1; rectIndex >= 0; rectIndex--) {
                        var locals = localRects(rects[rectIndex]);
                        for (var localIndex = locals.length - 1; localIndex >= 0; localIndex--) {
                          var local = locals[localIndex];
                          var boxLocal = shouldShrinkDecorationBox(el, local, hasBackgroundImage) ? childContentUnionLocal(el, local) : local;
                          pushPage(pages, boxLocal.page).boxes.push({
                            type: 'box',
                            source: anchor,
                            rect: boxLocal,
                            backgroundColor: bg,
                            backgroundImage: bgImage,
                            backgroundRepeat: firstCssLayer(style.backgroundRepeat),
                            backgroundSize: firstCssLayer(style.backgroundSize),
                            backgroundPosition: firstCssLayer(style.backgroundPosition),
                            borderColor: borderColor,
                            borderWidth: borderWidth,
                            borderRadius: number(style.borderTopLeftRadius) || 0,
                            opacity: opacity == null ? 1 : opacity
                          });
                        }
                      }
                    }
                  }
                }
              }
              function isReaderRootShell(el) {
                if (!el || !el.parentElement) return false;
                var parentTag = String(el.parentElement.tagName || '').toLowerCase();
                if (parentTag !== 'body') return false;
                var tag = String(el.tagName || '').toLowerCase();
                if (tag === 'article' || tag === 'section' || tag === 'main') return true;
                var className = String(el.className || '');
                return /(^|\s)(book-wrapper|wrapper|container|page|paper)(\s|$)/i.test(className);
              }
              readyFonts().then(readyImages).then(function() {
                return new Promise(function(resolve) {
                  requestAnimationFrame(function() {
                    setTimeout(function() {
                      if (!isCurrent()) {
                        resolve('${PendingResult}');
                        return;
                      }
                      var root = sliceRootByFragments(document.body || document.documentElement);
                      root = prepareRoot(root);
                      mark(root, 'body');
                      materializePseudoContent(root);
                      applyReaderFont(root);
                      applyReaderInsets(root);
                      rootBounds = root.getBoundingClientRect();
                      var pageCount = Math.max(1, Math.ceil(Math.max(root.scrollWidth, document.documentElement.scrollWidth, root.getBoundingClientRect().width) / PAGE_W));
                      var warnings = [];
                      var pages = [];
                      for (var i = 0; i < pageCount; i++) pushPage(pages, i);
                      addRootBackgroundFragments(root, pages, pageCount);
                      addElementFragments(root, pages);
                      addTextFragments(root, pages);
                      for (var i = pages.length; i < Math.max(pageCount, pages.length); i++) pushPage(pages, i);
                      pages.forEach(function(page) {
                        if (page.boxes && page.boxes.length) {
                          page.fragments = page.boxes.concat(page.fragments);
                        }
                        delete page.boxes;
                      });
                      resolve(JSON.stringify({
                        protocolVersion: 5,
                        pageCount: pages.length,
                        warnings: warnings,
                        pages: pages
                      }));
                    }, $settleDelay);
                  });
                });
              }).then(function(result) {
                if (!isCurrent()) return;
                window.__legadoEpubLayoutState = { status: 'ready', result: result };
              }).catch(function(error) {
                if (!isCurrent()) return;
                window.__legadoEpubLayoutState = {
                  status: 'ready',
                  result: JSON.stringify({ pageCount: 0, pages: [], error: String(error || '') })
                };
              });
              return '${PendingResult}';
            })();
        """.trimIndent()
    }

    private fun decodeJavascriptString(value: String?): String? {
        if (value.isNullOrBlank() || value == "null") return null
        val trimmed = value.trim()
        return if (trimmed.length >= 2 && trimmed.first() == '"' && trimmed.last() == '"') {
            runCatching { JSONObject(trimmed).getString("value") }.getOrNull() ?: trimmed.substring(1, trimmed.length - 1)
        } else {
            trimmed
        }
    }

    private fun archiveResponse(url: String?, request: EpubWebLayoutRequest): WebResourceResponse? {
        val path = archivePath(url) ?: return emptyResponse()
        if (path == ReaderFontPath) {
            return readerFontResponse(request)
        }
        return runCatching {
            if (!archive.exists(path)) return emptyResponse()
            val bytes = archive.readBytes(path)
            WebResourceResponse(
                mimeType(path),
                if (path.endsWith(".css", true) || path.endsWith(".html", true) || path.endsWith(".xhtml", true)) "UTF-8" else null,
                ByteArrayInputStream(bytes)
            )
        }.getOrElse {
            emptyResponse()
        }
    }

    private fun readerFontResponse(request: EpubWebLayoutRequest): WebResourceResponse {
        val fontPath = request.readerFontPath?.takeIf { it.isNotBlank() } ?: return emptyResponse()
        return runCatching {
            val inputStream = if (fontPath.startsWith("content://", ignoreCase = true)) {
                appCtx.contentResolver.openInputStream(Uri.parse(fontPath))
            } else {
                java.io.File(fontPath.removePrefix("file://")).inputStream()
            } ?: return emptyResponse()
            WebResourceResponse(mimeType(fontPath), null, inputStream)
        }.getOrElse {
            emptyResponse()
        }
    }

    private fun emptyResponse(): WebResourceResponse {
        return WebResourceResponse("text/plain", "UTF-8", ByteArrayInputStream(ByteArray(0)))
    }

    private fun archivePath(url: String?): String? {
        if (url.isNullOrBlank()) return null
        val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return null
        if (!uri.host.equals(Host, ignoreCase = true)) return null
        val path = uri.path?.trimStart('/') ?: return null
        if (path.isBlank()) return null
        return EpubPath.normalize(Uri.decode(path))
    }

    private fun buildBaseUrl(chapterHref: String): String {
        return "https://$Host/${Uri.encode(EpubPath.stripFragment(chapterHref), "/")}"
    }

    private fun mimeType(path: String): String {
        return when (path.substringAfterLast('.', "").lowercase()) {
            "css" -> "text/css"
            "html", "htm" -> "text/html"
            "xhtml", "xml" -> "application/xhtml+xml"
            "svg" -> "image/svg+xml"
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            "ttf" -> "font/ttf"
            "otf" -> "font/otf"
            "woff" -> "font/woff"
            "woff2" -> "font/woff2"
            else -> URLConnection.guessContentTypeFromName(path) ?: "application/octet-stream"
        }
    }

    companion object {
        private const val Host = "epub.local"
        private const val ReaderFontPath = "__legado_reader_font__"
        private const val WebViewBlank = "about:blank"
        private const val PendingResult = "__LEGADO_EPUB_PENDING__"
    }
}
