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
 * 老源兼容判定：未填列表下一页规则(ruleNextPage)但请求URL含{{page}}占位符时，
 * 隐式按PAGE模式翻页（原版行为：搜索/分类字段通过{{page}}占位引用分页信息即可自动翻页）。
 * 仅当URL确实含{{page}}时才启用，避免无分页占位符的源无限重复请求同一URL。
 */
fun RssSource.autoNextPageEnabled(requestUrl: String): Boolean {
    return ruleNextPage.isNullOrEmpty() && !requestUrl.isNullOrEmpty() && requestUrl.contains("{{page}}")
}

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
