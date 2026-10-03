package io.legado.app.help.source

import io.legado.app.constant.AppLog
import io.legado.app.data.entities.RssSource
import io.legado.app.utils.ACache
import io.legado.app.utils.MD5Utils
import com.script.rhino.runScriptWithContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.coroutines.EmptyCoroutineContext

private val aCache by lazy { ACache.get("rssSortUrl") }

private val sortUrlJsExecutor by lazy {
    Executors.newSingleThreadExecutor { r ->
        Thread(r, "sortUrlJs").apply { isDaemon = true }
    }
}

private fun RssSource.getSortUrlsKey(): String {
    return MD5Utils.md5Encode(sourceUrl + sortUrl)
}

/**
 * 隐式分页识别（**纯函数**，可 JVM 单测；2026-10-01 用户报障修复 · 缺陷③）。
 *
 * 为什么放宽：原判定只认**字面量** `{{page}}`，而解析层 `AnalyzeUrl.replaceKeyPageJs()` 对 `{{...}}`
 * 是**通用 JS 求值**（`page` 为绑定变量）——识别层与解析层能力不对等 ⇒ 用户写
 * `page={{Math.floor(Math.random() * 100) + 1}}` 这类随机/自定义分页表达式**完全不生效**
 * （既不翻页、也不出现页码入口）。现放宽为「列表 URL 含任意 `{{...}}` 内联 JS 占位」，
 * 自然覆盖 `{{page}}`。
 *
 * 死循环兜底：`RssArticlesViewModel.loadMoreSuccess()` 的「首末条均已入库 ⇒ 判定到底」去重，
 * 可把「URL 含与本页无关的模板且内容不变」这类误判的代价限制在**多 1 次请求**内。
 */
object RssImplicitPageDetector {

    /**
     * @param ruleNextPage 源显式下一页规则（非空时以它为准，不做隐式判定）
     * @param requestUrl   当前请求的列表 URL（分类 / 搜索 / 分页 URL）
     */
    fun detect(ruleNextPage: String?, requestUrl: String?): Boolean {
        // 显式规则优先；无规则时要求 URL 确实含内联 JS 占位，避免对同一 URL 无限重复请求
        return ruleNextPage.isNullOrEmpty() &&
            !requestUrl.isNullOrEmpty() &&
            requestUrl.contains("{{") && requestUrl.contains("}}")
    }
}

/**
 * 老源兼容判定：未填列表下一页规则(ruleNextPage)但请求 URL 含**内联 JS 占位**（含 `{{page}}`，
 * 也含随机/自定义表达式）时，隐式按 PAGE 模式翻页。判定单源见 [RssImplicitPageDetector.detect]。
 */
fun RssSource.autoNextPageEnabled(requestUrl: String): Boolean =
    RssImplicitPageDetector.detect(ruleNextPage, requestUrl)

suspend fun RssSource.sortUrls(): List<Pair<String, String>> {
    return arrayListOf<Pair<String, String>>().apply {
        val sortUrlsKey = getSortUrlsKey()
        kotlin.runCatching {
            var str = sortUrl
            if (sortUrl?.startsWith("<js>", false) == true
                || sortUrl?.startsWith("@js:", false) == true
            ) {
                str = aCache.getAsString(sortUrlsKey)
                if (str.isNullOrBlank()) {
                    val jsStr = if (sortUrl!!.startsWith("@")) {
                        sortUrl!!.substring(4)
                    } else {
                        sortUrl!!.substring(4, sortUrl!!.lastIndexOf("<"))
                    }
                    str = withContext(Dispatchers.IO) {
                        val future = sortUrlJsExecutor.submit<String?> {
                            try {
                                runScriptWithContext(EmptyCoroutineContext) {
                                    evalJS(jsStr).toString()
                                }
                            } catch (e: Exception) {
                                AppLog.put("sortUrls JS failed: ${e.localizedMessage}")
                                null
                            }
                        }
                        try {
                            future.get(30, TimeUnit.SECONDS)
                        } catch (e: java.util.concurrent.TimeoutException) {
                            future.cancel(true)
                            AppLog.put("sortUrls JS timeout(30s)")
                            null
                        }
                    }
                    if (!str.isNullOrBlank()) {
                        aCache.put(sortUrlsKey, str)
                    }
                }
            }
            // &&& 优先于 && 匹配，避免 "a&&&b" 被拆成 ["a", "&b"] 残留单个 & 前缀
            str?.split("(&&&|&&|\n)+".toRegex())?.forEach { sort ->
                val name = sort.substringBefore("::")
                val url = sort.substringAfter("::", "")
                if (url.isNotEmpty()) {
                    add(Pair(name, url))
                }
            }
            if (isEmpty()) {
                add(Pair("", sourceUrl))
            }
        }
    }
}

suspend fun RssSource.removeSortCache() {
    withContext(Dispatchers.IO) {
        aCache.remove(getSortUrlsKey())
    }
}

/**
 * 预执行 searchUrl 中的 JS，在独立线程执行避免协程死锁
 * AnalyzeUrl 在协程IO线程执行JS时，若JS调用java.ajax()会导致死锁
 */
suspend fun RssSource.getSearchUrl(searchKey: String): String? {
    val searchUrl = searchUrl ?: return null
    if (!searchUrl.startsWith("<js>", true) && !searchUrl.startsWith("@js:", true)) {
        return searchUrl
    }
    val jsStr = if (searchUrl.startsWith("@", true)) {
        searchUrl.substring(4)
    } else {
        searchUrl.substring(4, searchUrl.lastIndexOf("<"))
    }
    return withContext(Dispatchers.IO) {
        val future = sortUrlJsExecutor.submit<String?> {
            try {
                runScriptWithContext(EmptyCoroutineContext) {
                    evalJS(jsStr) {
                        put("key", searchKey)
                    }.toString()
                }
            } catch (e: Exception) {
                AppLog.put("getSearchUrl: JS execution failed: ${e.localizedMessage}")
                null
            }
        }
        try {
            future.get(30, TimeUnit.SECONDS)
        } catch (e: java.util.concurrent.TimeoutException) {
            future.cancel(true)
            AppLog.put("getSearchUrl JS timeout(30s)")
            null
        }
    }
}
