package io.legado.app.ui.rss.source.edit

import android.app.Application
import android.content.Intent
import io.legado.app.R
import io.legado.app.base.BaseViewModel
import io.legado.app.data.appDb
import io.legado.app.data.entities.RssSource
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.AppCacheManager
import io.legado.app.help.ConcurrentRateLimiter.Companion.concurrentRecordMap
import io.legado.app.help.RuleComplete
import io.legado.app.help.http.CookieStore
import io.legado.app.help.http.okHttpClient
import io.legado.app.help.source.removeSortCache
import io.legado.app.model.SharedJsScope
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import io.legado.app.utils.getClipText
import io.legado.app.utils.printOnDebug
import io.legado.app.utils.stackTraceStr
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.Dispatchers


class RssSourceEditViewModel(application: Application) : BaseViewModel(application) {
    var autoComplete = false
    var rssSource: RssSource? = null

    fun initData(intent: Intent, onFinally: () -> Unit) {
        execute {
            val key = intent.getStringExtra("sourceUrl")
            if (key != null) {
                appDb.rssSourceDao.getByKey(key)?.let {
                    rssSource = it
                }
            }
        }.onFinally {
            onFinally()
        }
    }

    fun save(source: RssSource, success: ((RssSource) -> Unit)) {
        execute {
            if (source.sourceUrl.isBlank() || source.sourceName.isBlank()) {
                throw NoStackTraceException(context.getString(R.string.non_null_name_url))
            }
            val oldSource = rssSource ?: RssSource()
            if (!source.equal(oldSource)) {
                source.lastUpdateTime = System.currentTimeMillis()
                if (oldSource.sortUrl != source.sortUrl) {
                    oldSource.removeSortCache()
                }
                if (oldSource.jsLib != source.jsLib) {
                    SharedJsScope.remove(oldSource.jsLib)
                }
            }
            rssSource?.let {
                appDb.rssSourceDao.delete(it)
                //更新收藏的源地址
                if (it.sourceUrl != source.sourceUrl) {
                    appDb.rssStarDao.updateOrigin(source.sourceUrl, it.sourceUrl)
                    appDb.rssArticleDao.updateOrigin(source.sourceUrl, it.sourceUrl)
                    appDb.cacheDao.deleteSourceVariables(it.sourceUrl)
                    AppCacheManager.clearSourceVariables()
                }
            }
            appDb.rssSourceDao.insert(source)
            rssSource = source
            concurrentRecordMap.remove(source.sourceUrl) //删除并发限制缓存
            source
        }.onSuccess {
            success(it)
        }.onError {
            context.toastOnUi(it.localizedMessage)
            it.printOnDebug()
        }
    }

    fun pasteSource(onSuccess: (source: RssSource) -> Unit) {
        execute(context = Dispatchers.Main) {
            var source: RssSource? = null
            context.getClipText()?.let { json ->
                source = GSON.fromJsonObject<RssSource>(json).getOrThrow()
            }
            source
        }.onError {
            context.toastOnUi(it.localizedMessage)
        }.onSuccess {
            if (it != null) {
                onSuccess(it)
            } else {
                context.toastOnUi("格式不对")
            }
        }
    }

    fun importSource(text: String, finally: (source: RssSource) -> Unit) {
        execute {
            val text1 = text.trim()
            GSON.fromJsonObject<RssSource>(text1).getOrThrow().let {
                finally.invoke(it)
            }
        }.onError {
            context.toastOnUi(it.stackTraceStr)
        }
    }

    fun clearCookie(url: String) {
        execute {
            // BUG3 fix: 清除cookie时同步清理多层缓存
            // 1. 清除CookieStore中的cookie
            CookieStore.removeCookie(url)
            // 2. 清除OkHttp DiskCache（缓存了530等错误响应）
            kotlin.runCatching {
                okHttpClient.cache?.evictAll()
            }.onFailure {
                io.legado.app.constant.AppLog.putDebug("clearCookie: OkHttp cache evictAll failed: ${it.message}")
            }
            // 3. 清除WebView cookie
            kotlin.runCatching {
                android.webkit.CookieManager.getInstance().flush()
            }
            // 4. 清除ACache内存缓存
            kotlin.runCatching {
                io.legado.app.utils.ACache.get().remove(url)
            }
            io.legado.app.constant.AppLog.putDebugWithTag(
                "RssSourceEdit",
                "clearCookie: removed cookie+cache for url=/path/${url.hashCode()}",
                level = io.legado.app.constant.AppLog.Level.INFO
            )
        }
    }

    fun ruleComplete(rule: String?, preRule: String? = null, type: Int = 1): String? {
        if (autoComplete) {
            return RuleComplete.autoComplete(rule, preRule, type)
        }
        return rule
    }

}