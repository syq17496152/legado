package io.legado.app.help.book

import com.google.gson.internal.LinkedTreeMap
import com.script.ScriptBindings
import com.script.buildScriptBindings
import com.script.rhino.RhinoScriptEngine
import io.legado.app.constant.AppLog
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.ParagraphRule
import io.legado.app.data.entities.ParagraphRuleVar
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.CacheManager
import io.legado.app.help.http.CookieStore
import io.legado.app.model.Debug
import io.legado.app.utils.GSON
import io.legado.app.utils.MD5Utils
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withTimeout
import org.mozilla.javascript.NativeArray
import org.mozilla.javascript.NativeObject
import org.mozilla.javascript.Scriptable
import org.mozilla.javascript.Scriptable.NOT_FOUND
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import java.util.LinkedHashMap

object ParagraphRuleProcessor {
    private const val CLICK_PREFIX = "paragraphRule:"
    private const val RULE_PREFIX = "rule:"
    private const val PROCESS_CACHE_MAX_SIZE = 16
    private const val PROCESS_CACHE_MAX_BYTES = 8L * 1024L * 1024L
    private const val PARAGRAPH_ANCHOR_MIN_CONTAINMENT_LENGTH = 13
    private const val PARAGRAPH_ANCHOR_GRAM_HASH_BASE = 1_000_003L
    private const val PARAGRAPH_ANCHOR_GRAM_INDEX_BUDGET = 32_768
    private val pclickAttributeRegex = Regex("""("pclick"\s*:\s*")((?:\\.|[^"\\])*)(")""")
    private val pclickImageRegex = Regex(
        """<img\b[^>]*(?:"pclick"\s*:\s*"((?:\\.|[^"\\])*)"|data-legado-pclick\s*=\s*"([^"]*)")[^>]*>"""
    )
    private val paragraphImageRegex = Regex("""<img\b[^>]*>""", RegexOption.IGNORE_CASE)
    private val paragraphHtmlTagRegex = Regex("""<[^>]+>""")
    private val paragraphWhitespaceRegex = Regex("""\s+""")
    private val processCache = LinkedHashMap<String, CachedBookContent>(PROCESS_CACHE_MAX_SIZE, 0.75f, true)
    private var processCacheBytes = 0L

    interface BrowserCallback {
        fun showBrowser(
            url: String,
            html: String? = null,
            preloadJs: String? = null,
            config: String? = null,
            sourceKey: String? = null
        ): Boolean
    }

    data class ParagraphItem(
        val index: Int,
        val text: String,
        val start: Int,
        val end: Int,
        val separator: String,
        val sourcePosition: Int = index - 1
    )

    data class DebugResult(
        val content: String,
        val logs: List<String>
    )

    fun isParagraphClick(click: String?): Boolean {
        return click?.startsWith(CLICK_PREFIX) == true || click?.startsWith(RULE_PREFIX) == true
    }

    fun wrapClick(ruleId: Long, js: String): String = "$CLICK_PREFIX$RULE_PREFIX$ruleId\n$js"

    private fun wrapRuleClick(ruleId: Long, js: String): String = "$RULE_PREFIX$ruleId:$js"

    suspend fun process(book: Book, chapter: BookChapter, content: BookContent): BookContent {
        if (book.isEpub || content.textList.isEmpty()) return content
        val rules = appDb.paragraphRuleDao.enabledRulesForBook(book.bookUrl)
        if (rules.isEmpty()) return content
        val ruleStates = rules.map { RuleState(it, readVars(it.id)) }
        val cacheKey = processCacheKey(book, chapter, content, ruleStates)
        synchronized(processCache) {
            processCache[cacheKey]?.content?.let { return it }
        }
        val original = content.textList.joinToString("\n")
        val protectedContent = SpecialContentProtector.protect(original)
        var result = ChapterResult(
            protectedContent.content,
            protectedContent.content.split('\n'),
            content.sourceIndexes.normalizedSourceIndexes(content.textList.size)
        )
        val context = currentCoroutineContext()
        var hasFailure = false
        for (rule in rules) {
            if (rule.script.isBlank()) continue
            result = kotlin.runCatching {
                withTimeout(rule.validTimeout()) {
                    applyRule(rule, book, chapter, result, context)
                }
            }.onFailure {
                hasFailure = true
                when (it) {
                    is TimeoutCancellationException -> AppLog.put("ParagraphRule:${rule.id} script timeout")
                    is CancellationException -> throw it
                    else -> AppLog.put("ParagraphRule:${rule.id} script error: ${it.localizedMessage ?: it}", it)
                }
            }.getOrDefault(result)
        }
        val restoredParagraphs = result.paragraphs.map { protectedContent.restore(it) }
        val restoredContent = restoredParagraphs.joinToString("\n")
        val restoredSourceIndexes = result.sourceIndexes.normalizedSourceIndexes(restoredParagraphs.size)
        val processed = if (restoredContent == original && restoredSourceIndexes == content.sourceIndexes) {
            content
        } else {
            content.copy(textList = restoredParagraphs, sourceIndexes = restoredSourceIndexes)
        }
        if (!hasFailure) {
            synchronized(processCache) {
                putProcessCache(cacheKey, processed)
            }
        }
        return processed
    }

    suspend fun process(book: Book, chapter: BookChapter, content: String): String {
        if (book.isEpub) return content
        val rules = appDb.paragraphRuleDao.enabledRulesForBook(book.bookUrl)
        if (rules.isEmpty()) return content
        val protectedContent = SpecialContentProtector.protect(content)
        var result = protectedContent.content
        val context = currentCoroutineContext()
        for (rule in rules) {
            if (rule.script.isBlank()) continue
            result = kotlin.runCatching {
                withTimeout(rule.validTimeout()) {
                    applyRule(rule, book, chapter, result, context)
                }
            }.onFailure {
                when (it) {
                    is TimeoutCancellationException -> AppLog.put("ParagraphRule:${rule.id} script timeout")
                    is CancellationException -> throw it
                    else -> AppLog.put("ParagraphRule:${rule.id} script error: ${it.localizedMessage ?: it}", it)
                }
            }.getOrDefault(result)
        }
        return protectedContent.restore(result)
    }

    suspend fun debug(rule: ParagraphRule, book: Book, chapter: BookChapter, content: String): DebugResult {
        if (book.isEpub) {
            throw NoStackTraceException("Paragraph rules do not run on EPUB books.")
        }
        if (rule.script.isBlank()) {
            throw NoStackTraceException("Paragraph rule script is empty.")
        }
        val normalizedContent = ContentProcessor.get(book.name, book.origin)
            .getContent(book, chapter, content, includeTitle = false)
            .textList
            .joinToString("\n")
        val logs = arrayListOf<String>()
        val debugSource = "paragraph_rule_${rule.id}"
        val callback = object : Debug.Callback {
            override fun printLog(state: Int, msg: String) {
                logs.add(msg)
            }
        }
        val processed = Debug.withDebugSource(debugSource, callback) {
            Debug.log(debugSource, "paragraph debug start: ${book.name} / ${chapter.title}")
            withTimeout(rule.validTimeout()) {
                applyRule(rule, book, chapter, normalizedContent, currentCoroutineContext())
            }.also {
                Debug.log(debugSource, "paragraph debug finish")
            }
        }
        return DebugResult(processed, logs)
    }

    fun evalClick(
        book: Book,
        chapter: BookChapter,
        click: String,
        result: String,
        browserCallback: BrowserCallback,
        coroutineContext: CoroutineContext = EmptyCoroutineContext
    ): Boolean {
        if (!isParagraphClick(click)) return false
        val (ruleId, js) = parseClick(click) ?: return false
        val rule = appDb.paragraphRuleDao.get(ruleId) ?: return false
        val ctx = buildCtx(rule, book, chapter, result, emptyList(), readVars(rule.id))
        val scope = buildScope(rule, book, chapter, result, ctx, coroutineContext, browserCallback)
        val script = buildString {
            append(jsCompatPrelude())
            append(jsCtxPrelude(ctx))
            if (rule.jsLib.isNotBlank()) append(rule.jsLib).append('\n')
            append(js)
        }
        RhinoScriptEngine.eval(script, scope, coroutineContext)
        return true
    }

    private fun applyRule(
        rule: ParagraphRule,
        book: Book,
        chapter: BookChapter,
        current: ChapterResult,
        coroutineContext: CoroutineContext
    ): ChapterResult {
        val paragraphs = parseParagraphs(current.paragraphs)
        if (paragraphs.isEmpty()) return current
        val ctx = buildCtx(rule, book, chapter, current.content, paragraphs, readVars(rule.id))
        val scope = buildScope(rule, book, chapter, current.content, ctx, coroutineContext, null)
        val script = buildRuleScript(rule, ctx)
        val jsResult = RhinoScriptEngine.eval(script, scope, coroutineContext)
        writeVars(rule.id, ctx["vars"])
        return applyChapterResult(current, paragraphs, jsResult).wrapPclicks(rule.id)
    }

    private fun applyRule(
        rule: ParagraphRule,
        book: Book,
        chapter: BookChapter,
        content: String,
        coroutineContext: CoroutineContext
    ): String {
        val paragraphs = parseContent(content)
        if (paragraphs.isEmpty()) return content
        val ctx = buildCtx(rule, book, chapter, content, paragraphs, readVars(rule.id))
        val scope = buildScope(rule, book, chapter, content, ctx, coroutineContext, null)
        val script = buildRuleScript(rule, ctx)
        val jsResult = RhinoScriptEngine.eval(script, scope, coroutineContext)
        writeVars(rule.id, ctx["vars"])
        return wrapPclicks(rule.id, applyResult(content, paragraphs, jsResult))
    }

    private fun buildRuleScript(rule: ParagraphRule, ctx: Map<String, Any?>): String {
        return buildString {
            append(jsCompatPrelude())
            append(jsCtxPrelude(ctx))
            if (rule.jsLib.isNotBlank()) append(rule.jsLib).append('\n')
            append(rule.script).append('\n')
            append("""
                ;(function(){
                    var __paragraphRuleResult;
                    if (typeof process === 'function') {
                        __paragraphRuleResult = process(ctx);
                    } else if (typeof result !== 'undefined') {
                        __paragraphRuleResult = result;
                    } else {
                        __paragraphRuleResult = ctx.result;
                    }
                    return (typeof __paragraphRuleResult === 'undefined' || __paragraphRuleResult === null) ? ctx.result : __paragraphRuleResult;
                })();
            """.trimIndent())
        }
    }

    private fun jsCompatPrelude(): String {
        return """
            ;(function(){
                if (!Array.prototype.forEach) {
                    Array.prototype.forEach = function(callback, thisArg) {
                        if (this == null) throw new TypeError('Array.prototype.forEach called on null or undefined');
                        var object = Object(this);
                        var length = object.length >>> 0;
                        for (var i = 0; i < length; i++) {
                            if (i in object) callback.call(thisArg, object[i], i, object);
                        }
                    };
                }
                if (!Array.prototype.map) {
                    Array.prototype.map = function(callback, thisArg) {
                        if (this == null) throw new TypeError('Array.prototype.map called on null or undefined');
                        var object = Object(this);
                        var length = object.length >>> 0;
                        var result = new Array(length);
                        for (var i = 0; i < length; i++) {
                            if (i in object) result[i] = callback.call(thisArg, object[i], i, object);
                        }
                        return result;
                    };
                }
                if (!Array.prototype.filter) {
                    Array.prototype.filter = function(callback, thisArg) {
                        if (this == null) throw new TypeError('Array.prototype.filter called on null or undefined');
                        var object = Object(this);
                        var length = object.length >>> 0;
                        var result = [];
                        for (var i = 0; i < length; i++) {
                            if (i in object && callback.call(thisArg, object[i], i, object)) result.push(object[i]);
                        }
                        return result;
                    };
                }
                if (!Array.isArray) {
                    Array.isArray = function(value) {
                        return Object.prototype.toString.call(value) === '[object Array]';
                    };
                }
            })();
        """.trimIndent() + '\n'
    }

    private fun buildCtx(
        rule: ParagraphRule,
        book: Book,
        chapter: BookChapter,
        content: String,
        paragraphs: List<ParagraphItem>,
        vars: MutableMap<String, String>
    ): Map<String, Any?> {
        val chapterRequestUrl = chapterRequestUrl(chapter)
        return linkedMapOf(
            "book" to linkedMapOf(
                "name" to book.name,
                "author" to book.author,
                "bookUrl" to book.bookUrl,
                "tocUrl" to book.tocUrl,
                "origin" to book.origin,
                "originName" to book.originName,
                "type" to book.type,
                "durChapterIndex" to book.durChapterIndex,
                "durChapterTitle" to book.durChapterTitle,
                "latestChapterTitle" to book.latestChapterTitle,
                "variable" to book.variable
            ),
            "chapter" to linkedMapOf(
                "bookUrl" to chapter.bookUrl,
                "title" to chapter.title,
                "url" to chapterRequestUrl,
                "rawUrl" to chapter.url,
                "requestUrl" to chapterRequestUrl,
                "absoluteUrl" to chapterRequestUrl,
                "baseUrl" to chapter.baseUrl,
                "index" to chapter.index,
                "isVolume" to chapter.isVolume,
                "isVip" to chapter.isVip,
                "isPay" to chapter.isPay
            ),
            "content" to content,
            "paragraphs" to paragraphs.map {
                linkedMapOf(
                    "index" to it.index,
                    "text" to it.text,
                    "start" to it.start,
                    "end" to it.end,
                    "separator" to it.separator
                )
            },
            "result" to content,
            "rule" to linkedMapOf(
                "id" to rule.id,
                "name" to rule.name,
                "timeoutMillisecond" to rule.timeoutMillisecond,
                "order" to rule.order,
                "updateTime" to rule.updateTime
            ),
            "vars" to vars
        )
    }

    private fun jsCtxPrelude(ctx: Map<String, Any?>): String {
        val ctxJsonLiteral = GSON.toJson(GSON.toJson(ctx))
        return """
            var ctx = JSON.parse($ctxJsonLiteral);
            var ruleId = ctx.rule.id;
            var vars = ctx.vars;
            var result = ctx.result;
        """.trimIndent() + '\n'
    }
    private fun buildScope(
        rule: ParagraphRule,
        book: Book,
        chapter: BookChapter,
        content: String,
        ctx: Map<String, Any?>,
        coroutineContext: CoroutineContext,
        browserCallback: BrowserCallback?
    ): Scriptable {
        val java = ParagraphRuleJsExtensions(rule, browserCallback)
        val bindings = buildScriptBindings { bindings ->
            bindings["java"] = java
            bindings["source"] = java
            bindings["cache"] = CacheManager
            bindings["cookie"] = CookieStore
            bindings["book"] = book
            bindings["chapter"] = chapter
            bindings["title"] = chapter.title
            bindings["src"] = content
            bindings["result"] = content
            bindings["rule"] = rule
            bindings["ruleId"] = rule.id
            bindings["vars"] = ctx["vars"]
            bindings["ctx"] = ctx
            bindings["baseUrl"] = chapterRequestUrl(chapter)
        }
        return RhinoScriptEngine.getRuntimeScope(bindings)
    }

    private fun chapterRequestUrl(chapter: BookChapter): String {
        val absoluteUrl = runCatching { chapter.getAbsoluteURL() }.getOrNull().orEmpty()
        return when {
            absoluteUrl.startsWith("http://", true) || absoluteUrl.startsWith("https://", true) -> absoluteUrl
            chapter.url.startsWith("http://", true) || chapter.url.startsWith("https://", true) -> chapter.url
            chapter.baseUrl.startsWith("http://", true) || chapter.baseUrl.startsWith("https://", true) -> chapter.baseUrl
            else -> ""
        }
    }

    private fun parseContent(content: String): List<ParagraphItem> {
        val list = arrayListOf<ParagraphItem>()
        var start = 0
        var index = 1
        var i = 0
        while (i <= content.length) {
            if (i == content.length || content[i] == '\n') {
                val end = if (i > start && content[i - 1] == '\r') i - 1 else i
                val text = content.substring(start, end)
                if (text.isNotBlank()) {
                    list.add(ParagraphItem(index++, text, start, end, if (i < content.length) "\n" else ""))
                }
                start = i + 1
            }
            i++
        }
        return list
    }

    private fun parseParagraphs(paragraphs: List<String>): List<ParagraphItem> {
        var start = 0
        var index = 1
        return paragraphs.mapIndexedNotNull { idx, text ->
            val separator = if (idx < paragraphs.lastIndex) "\n" else ""
            val item = if (text.isNotBlank()) {
                ParagraphItem(
                    index = index++,
                    text = text,
                    start = start,
                    end = start + text.length,
                    separator = separator,
                    sourcePosition = idx
                )
            } else {
                null
            }
            start += text.length + separator.length
            item
        }
    }

    private fun applyResult(original: String, paragraphs: List<ParagraphItem>, jsResult: Any?): String {
        val result = unwrap(jsResult)
        return when (result) {
            null -> original
            is String -> result
            is NativeArray -> fromList(result.toList())
            is List<*> -> fromList(result)
            is NativeObject -> fromPatch(original, paragraphs, result.toMap())
            is Map<*, *> -> fromPatch(original, paragraphs, result)
            else -> result.toString()
        }
    }

    private fun applyChapterResult(
        current: ChapterResult,
        paragraphs: List<ParagraphItem>,
        jsResult: Any?
    ): ChapterResult {
        val result = unwrap(jsResult) ?: return current
        return when (result) {
            is String -> chapterResultOf(result, current)
            is NativeArray -> fromListResult(result.toList(), current)
            is List<*> -> fromListResult(result, current)
            is NativeObject -> fromPatchResult(current, paragraphs, result.toMap())
            is Map<*, *> -> fromPatchResult(current, paragraphs, result)
            else -> chapterResultOf(result.toString(), current)
        }
    }

    private fun chapterResultOf(text: String, current: ChapterResult? = null): ChapterResult {
        val paragraphs = text.split('\n').filter { it.isNotBlank() }
        return ChapterResult(
            paragraphs.joinToString("\n"),
            paragraphs,
            current?.let { mapSourceIndexes(paragraphs, it.paragraphs, it.sourceIndexes) }
                ?: List(paragraphs.size) { -1 }
        )
    }

    private fun fromList(list: List<*>): String {
        return buildString {
            var first = true
            list.forEach { raw ->
                val item = unwrap(raw)
                val text = when (item) {
                    is Map<*, *> -> item["text"]?.toString() ?: item["content"]?.toString()
                    else -> item?.toString()
                } ?: return@forEach
                if (text.isBlank()) return@forEach
                if (!first) append('\n')
                append(text)
                first = false
            }
        }
    }

    private fun fromListResult(list: List<*>, current: ChapterResult): ChapterResult {
        val paragraphs = arrayListOf<String>()
        val sourceIndexes = arrayListOf<Int>()
        list.forEach { raw ->
            val item = unwrap(raw)
            val text = when (item) {
                is Map<*, *> -> item["text"]?.toString() ?: item["content"]?.toString()
                else -> item?.toString()
            } ?: return@forEach
            if (text.isNotBlank()) {
                paragraphs.add(text)
                sourceIndexes.add(current.sourceIndexes.getOrElse(paragraphs.lastIndex) { -1 })
            }
        }
        return ChapterResult(paragraphs.joinToString("\n"), paragraphs, sourceIndexes)
    }

    private fun fromPatch(original: String, paragraphs: List<ParagraphItem>, patch: Map<*, *>): String {
        if (patch.containsKey("content")) return patch["content"]?.toString() ?: original
        if (patch.containsKey("result")) return patch["result"]?.toString() ?: original
        val byIndex = linkedMapOf<Int, String>()
        patch.forEach { (key, value) -> key?.toString()?.toIntOrNull()?.let { byIndex[it] = value?.toString() ?: "" } }
        if (byIndex.isEmpty()) return original
        return buildString {
            paragraphs.forEachIndexed { idx, item ->
                if (idx > 0) append(item.separator.ifEmpty { "\n" })
                append(byIndex[item.index] ?: item.text)
            }
        }
    }

    private fun fromPatchResult(
        current: ChapterResult,
        paragraphs: List<ParagraphItem>,
        patch: Map<*, *>
    ): ChapterResult {
        if (patch.containsKey("content")) {
            val text = patch["content"]?.toString() ?: current.content
            return chapterResultOf(text, current)
        }
        if (patch.containsKey("result")) {
            val text = patch["result"]?.toString() ?: current.content
            return chapterResultOf(text, current)
        }
        val byIndex = linkedMapOf<Int, String>()
        patch.forEach { (key, value) ->
            key?.toString()?.toIntOrNull()?.let { byIndex[it] = value?.toString() ?: "" }
        }
        if (byIndex.isEmpty()) return current
        val paragraphIndexes = paragraphs.map { it.index }.toHashSet()
        val out = current.paragraphs.toMutableList()
        val outSourceIndexes = current.sourceIndexes.normalizedSourceIndexes(out.size).toMutableList()
        paragraphs.forEach { item ->
            val replace = byIndex[item.index] ?: return@forEach
            val position = item.sourcePosition
            if (position in out.indices) {
                out[position] = replace
                outSourceIndexes[position] = current.sourceIndexes.getOrElse(position) { -1 }
            }
        }
        byIndex
            .filterKeys { it !in paragraphIndexes }
            .toSortedMap()
            .values
            .filter { it.isNotBlank() }
            .forEach {
                out.add(it)
                outSourceIndexes.add(-1)
            }
        return ChapterResult(out.joinToString("\n"), out, outSourceIndexes)
    }

    private fun unwrap(value: Any?): Any? = when (value) {
        is NativeObject -> value.toMap()
        is NativeArray -> value.toList()
        is LinkedTreeMap<*, *> -> value
        else -> value
    }

    private fun NativeObject.toMap(): Map<Any?, Any?> {
        val map = linkedMapOf<Any?, Any?>()
        ids.forEach { key ->
            val value = when (key) {
                is Int -> get(key, this)
                else -> get(key.toString(), this)
            }
            if (value != NOT_FOUND) map[key] = value
        }
        return map
    }

    private fun NativeArray.toList(): List<Any?> = ids.mapNotNull { key ->
        val value = when (key) {
            is Int -> get(key, this)
            else -> get(key.toString(), this)
        }
        value.takeIf { it != NOT_FOUND }
    }

    private fun parseClick(click: String): Pair<Long, String>? {
        val body = click.removePrefix(CLICK_PREFIX)
        if (!body.startsWith(RULE_PREFIX)) return null
        val lineEnd = body.indexOf('\n')
        if (lineEnd > RULE_PREFIX.length) {
            val id = body.substring(RULE_PREFIX.length, lineEnd).toLongOrNull() ?: return null
            return id to body.substring(lineEnd + 1)
        }
        val colon = body.indexOf(':', startIndex = RULE_PREFIX.length)
        if (colon <= RULE_PREFIX.length) return null
        val id = body.substring(RULE_PREFIX.length, colon).toLongOrNull() ?: return null
        return id to body.substring(colon + 1)
    }

    private fun wrapPclicks(ruleId: Long, text: String): String {
        return dedupeParagraphRuleImages(pclickAttributeRegex.replace(text) { match ->
            val raw = match.groupValues[2]
            val decoded = kotlin.runCatching { GSON.fromJson("\"$raw\"", String::class.java) }.getOrNull() ?: raw
            if (isParagraphClick(decoded)) {
                match.value
            } else {
                val encoded = GSON.toJson(wrapRuleClick(ruleId, decoded)).removeSurrounding("\"")
                match.groupValues[1] + encoded + match.groupValues[3]
            }
        })
    }

    private fun ChapterResult.wrapPclicks(ruleId: Long): ChapterResult {
        val wrapped = paragraphs.map { wrapPclicks(ruleId, it) }
        return ChapterResult(wrapped.joinToString("\n"), wrapped, sourceIndexes.normalizedSourceIndexes(wrapped.size))
    }

    private fun dedupeParagraphRuleImages(text: String): String {
        if (!text.contains("pclick") && !text.contains("data-legado-pclick")) return text
        val seen = hashSetOf<String>()
        return pclickImageRegex.replace(text) { match ->
            val key = match.groupValues.getOrNull(1)?.ifBlank { match.groupValues.getOrNull(2).orEmpty() }.orEmpty()
            if (key.isNotBlank() && !seen.add(key)) "" else match.value
        }
    }

    private fun readVars(ruleId: Long): MutableMap<String, String> {
        return appDb.paragraphRuleDao.vars(ruleId).associate { it.name to it.value }.toMutableMap()
    }

    private fun writeVars(ruleId: Long, values: Any?) {
        val map = when (val v = unwrap(values)) {
            is Map<*, *> -> v
            else -> return
        }
        map.forEach { (key, value) ->
            if (key != null) appDb.paragraphRuleDao.putVar(ParagraphRuleVar(ruleId, key.toString(), value?.toString() ?: ""))
        }
    }

    fun clickKey(ruleId: Long, js: String): String {
        return "{\"pclick\":${GSON.toJson(wrapClick(ruleId, js))}}"
    }

    fun stableKey(rule: ParagraphRule): String {
        return MD5Utils.md5Encode16("${rule.id}:${rule.updateTime}:${rule.timeoutMillisecond}:${rule.script}:${rule.jsLib}")
    }

    fun clearProcessCache(bookUrl: String? = null, chapterIndex: Int? = null) {
        synchronized(processCache) {
            if (bookUrl.isNullOrBlank()) {
                processCache.clear()
                processCacheBytes = 0L
                return
            }
            val prefix = if (chapterIndex == null) {
                "$bookUrl|"
            } else {
                "$bookUrl|$chapterIndex|"
            }
            val iterator = processCache.entries.iterator()
            while (iterator.hasNext()) {
                val entry = iterator.next()
                if (entry.key.startsWith(prefix)) {
                    processCacheBytes -= entry.value.estimatedBytes
                    iterator.remove()
                }
            }
        }
    }

    private fun putProcessCache(key: String, content: BookContent) {
        val estimatedBytes = estimateProcessCacheBytes(content)
        processCache.remove(key)?.let { processCacheBytes -= it.estimatedBytes }
        if (estimatedBytes > PROCESS_CACHE_MAX_BYTES) return
        processCache[key] = CachedBookContent(content, estimatedBytes)
        processCacheBytes += estimatedBytes
        val iterator = processCache.entries.iterator()
        while ((processCache.size > PROCESS_CACHE_MAX_SIZE || processCacheBytes > PROCESS_CACHE_MAX_BYTES) &&
            iterator.hasNext()
        ) {
            val eldest = iterator.next()
            processCacheBytes -= eldest.value.estimatedBytes
            iterator.remove()
        }
    }

    internal fun estimateProcessCacheBytes(content: BookContent): Long {
        val textBytes = content.textList.sumOf { it.length.toLong() * 2L + 40L }
        val sourceIndexBytes = content.sourceIndexes.size.toLong() * 4L
        return textBytes + sourceIndexBytes + 256L
    }

    private data class CachedBookContent(
        val content: BookContent,
        val estimatedBytes: Long
    )

    private fun processCacheKey(
        book: Book,
        chapter: BookChapter,
        content: BookContent,
        ruleStates: List<RuleState>
    ): String {
        val rulesKey = ruleStates.joinToString("|") { (rule, vars) ->
            val varsKey = MD5Utils.md5Encode16(GSON.toJson(vars))
            "${stableKey(rule)}:$varsKey"
        }
        val contentKey = MD5Utils.md5Encode16(content.textList.joinToString("\u0001"))
        return "${book.bookUrl}|${chapter.index}|${chapter.url}|$rulesKey|$contentKey"
    }

    private fun List<Int>.normalizedSourceIndexes(size: Int): List<Int> {
        return List(size) { index -> getOrElse(index) { -1 } }
    }

    internal fun mapSourceIndexes(
        newParagraphs: List<String>,
        oldParagraphs: List<String>,
        oldSourceIndexes: List<Int>
    ): List<Int> {
        val newAnchors = newParagraphs.map(::normalizeParagraphAnchor)
        val oldAnchors = oldParagraphs.map(::normalizeParagraphAnchor)
        val oldAnchorIndex = ParagraphAnchorIndex(oldAnchors)
        val used = UsedParagraphIndexes(oldParagraphs.size)
        return newParagraphs.mapIndexed { index, _ ->
            val direct = oldSourceIndexes.getOrElse(index) { -1 }
            val anchor = newAnchors[index]
            if (direct >= 0 && index < oldAnchors.size && sameParagraphAnchor(anchor, oldAnchors[index])) {
                used.markUsed(index)
                direct
            } else {
                val matchIndex = oldAnchorIndex.findFirstUnused(anchor, used)
                if (matchIndex >= 0) {
                    used.markUsed(matchIndex)
                    oldSourceIndexes.getOrElse(matchIndex) { -1 }
                } else {
                    -1
                }
            }
        }
    }

    private fun sameParagraphAnchor(left: String, right: String): Boolean {
        if (left.isBlank() || right.isBlank()) return false
        return left == right ||
            (left.length >= PARAGRAPH_ANCHOR_MIN_CONTAINMENT_LENGTH && right.contains(left)) ||
            (right.length >= PARAGRAPH_ANCHOR_MIN_CONTAINMENT_LENGTH && left.contains(right))
    }

    private fun normalizeParagraphAnchor(text: String): String {
        return text
            .replace(paragraphImageRegex, "")
            .replace(paragraphHtmlTagRegex, "")
            .replace(paragraphWhitespaceRegex, "")
            .replace("\u3000", "")
            .trim()
    }

    private class ParagraphAnchorIndex(
        private val anchors: List<String>
    ) {
        private var exactAnchors: Map<String, List<Int>>? = null
        private var firstGramAnchors: Map<Long, List<Int>>? = null
        private var allGramAnchors: Map<Long, List<Int>>? = null
        private var containmentIndexAvailable = false

        fun findFirstUnused(anchor: String, used: UsedParagraphIndexes): Int {
            if (anchor.isBlank()) return -1
            ensureIndexes()
            var best = firstMatchingCandidate(exactAnchors?.get(anchor), anchor, used, Int.MAX_VALUE)
            if (anchor.length < PARAGRAPH_ANCHOR_MIN_CONTAINMENT_LENGTH) {
                return best.takeUnless { it == Int.MAX_VALUE } ?: -1
            }
            if (!containmentIndexAvailable || anchor.gramCount() > PARAGRAPH_ANCHOR_GRAM_INDEX_BUDGET) {
                best = firstMatchingLinear(anchor, used, best)
                return best.takeUnless { it == Int.MAX_VALUE } ?: -1
            }

            val firstGramHash = firstParagraphAnchorGramHash(anchor)
            best = firstMatchingCandidate(allGramAnchors?.get(firstGramHash), anchor, used, best)
            val seenGramHashes = hashSetOf<Long>()
            forEachParagraphAnchorGramHash(anchor) { gramHash ->
                if (seenGramHashes.add(gramHash)) {
                    best = firstMatchingCandidate(firstGramAnchors?.get(gramHash), anchor, used, best)
                }
            }
            return best.takeUnless { it == Int.MAX_VALUE } ?: -1
        }

        private fun ensureIndexes() {
            if (exactAnchors != null) return
            val exact = hashMapOf<String, MutableList<Int>>()
            var gramCount = 0L
            anchors.forEachIndexed { index, anchor ->
                if (anchor.isNotBlank()) {
                    exact.getOrPut(anchor) { arrayListOf() }.add(index)
                    gramCount += anchor.gramCount()
                }
            }
            exactAnchors = exact
            if (gramCount > PARAGRAPH_ANCHOR_GRAM_INDEX_BUDGET) return

            val firstGram = hashMapOf<Long, MutableList<Int>>()
            val allGrams = hashMapOf<Long, MutableList<Int>>()
            anchors.forEachIndexed { index, anchor ->
                if (anchor.length < PARAGRAPH_ANCHOR_MIN_CONTAINMENT_LENGTH) return@forEachIndexed
                firstGram.getOrPut(firstParagraphAnchorGramHash(anchor)) { arrayListOf() }.add(index)
                val seenGramHashes = hashSetOf<Long>()
                forEachParagraphAnchorGramHash(anchor) { gramHash ->
                    if (seenGramHashes.add(gramHash)) {
                        allGrams.getOrPut(gramHash) { arrayListOf() }.add(index)
                    }
                }
            }
            firstGramAnchors = firstGram
            allGramAnchors = allGrams
            containmentIndexAvailable = true
        }

        private fun firstMatchingLinear(
            anchor: String,
            used: UsedParagraphIndexes,
            currentBest: Int
        ): Int {
            var index = used.nextUnusedAtOrAfter(0)
            while (index < currentBest && index < anchors.size) {
                if (sameParagraphAnchor(anchor, anchors[index])) return index
                index = used.nextUnusedAtOrAfter(index + 1)
            }
            return currentBest
        }

        private fun firstMatchingCandidate(
            candidates: List<Int>?,
            anchor: String,
            used: UsedParagraphIndexes,
            currentBest: Int
        ): Int {
            if (candidates.isNullOrEmpty()) return currentBest
            var best = currentBest
            var position = 0
            while (position < candidates.size) {
                val listedIndex = candidates[position]
                if (listedIndex >= best) break
                val unusedIndex = used.nextUnusedAtOrAfter(listedIndex)
                if (unusedIndex >= best || unusedIndex >= anchors.size) break
                if (unusedIndex != listedIndex) {
                    position = candidates.lowerBound(unusedIndex, position + 1)
                    continue
                }
                if (sameParagraphAnchor(anchor, anchors[listedIndex])) {
                    best = listedIndex
                }
                position++
            }
            return best
        }
    }

    private class UsedParagraphIndexes(size: Int) {
        private val next = IntArray(size + 1) { it }

        fun markUsed(index: Int) {
            if (index !in 0 until next.lastIndex) return
            if (find(index) == index) {
                next[index] = find(index + 1)
            }
        }

        fun nextUnusedAtOrAfter(index: Int): Int {
            return find(index.coerceIn(0, next.lastIndex))
        }

        private fun find(index: Int): Int {
            var root = index
            while (next[root] != root) root = next[root]
            var current = index
            while (next[current] != current) {
                val parent = next[current]
                next[current] = root
                current = parent
            }
            return root
        }
    }

    private fun List<Int>.lowerBound(target: Int, fromIndex: Int): Int {
        var low = fromIndex.coerceAtMost(size)
        var high = size
        while (low < high) {
            val middle = (low + high).ushr(1)
            if (this[middle] < target) {
                low = middle + 1
            } else {
                high = middle
            }
        }
        return low
    }

    private fun firstParagraphAnchorGramHash(anchor: String): Long {
        var hash = 0L
        repeat(PARAGRAPH_ANCHOR_MIN_CONTAINMENT_LENGTH) { index ->
            hash = hash * PARAGRAPH_ANCHOR_GRAM_HASH_BASE + anchor[index].code + 1L
        }
        return hash
    }

    private fun String.gramCount(): Int {
        return (length - PARAGRAPH_ANCHOR_MIN_CONTAINMENT_LENGTH + 1).coerceAtLeast(0)
    }

    private inline fun forEachParagraphAnchorGramHash(anchor: String, action: (Long) -> Unit) {
        if (anchor.length < PARAGRAPH_ANCHOR_MIN_CONTAINMENT_LENGTH) return
        var highestPlace = 1L
        repeat(PARAGRAPH_ANCHOR_MIN_CONTAINMENT_LENGTH - 1) {
            highestPlace *= PARAGRAPH_ANCHOR_GRAM_HASH_BASE
        }
        var hash = firstParagraphAnchorGramHash(anchor)
        action(hash)
        for (index in PARAGRAPH_ANCHOR_MIN_CONTAINMENT_LENGTH until anchor.length) {
            val outgoing = anchor[index - PARAGRAPH_ANCHOR_MIN_CONTAINMENT_LENGTH].code + 1L
            val incoming = anchor[index].code + 1L
            hash = (hash - outgoing * highestPlace) * PARAGRAPH_ANCHOR_GRAM_HASH_BASE + incoming
            action(hash)
        }
    }

    private data class ChapterResult(
        val content: String,
        val paragraphs: List<String>,
        val sourceIndexes: List<Int>
    )

    private data class RuleState(
        val rule: ParagraphRule,
        val vars: MutableMap<String, String>
    )
}
