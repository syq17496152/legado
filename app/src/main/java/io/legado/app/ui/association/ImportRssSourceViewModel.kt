package io.legado.app.ui.association

import android.app.Application
import androidx.core.net.toUri
import androidx.lifecycle.MutableLiveData
import com.jayway.jsonpath.JsonPath
import io.legado.app.R
import io.legado.app.base.BaseViewModel
import io.legado.app.constant.AppConst
import io.legado.app.constant.AppLog
import io.legado.app.constant.AppPattern
import io.legado.app.data.appDb
import io.legado.app.data.entities.RssSource
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.config.AppConfig
import io.legado.app.help.http.decompressed
import io.legado.app.help.http.newCallResponseBody
import io.legado.app.help.http.okHttpClient
import io.legado.app.help.http.plainImportClient
import io.legado.app.help.source.SourceHelp
import io.legado.app.model.RuleUpdate
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonArray
import io.legado.app.utils.fromJsonObject
import io.legado.app.utils.isAbsUrl
import io.legado.app.utils.isJsonArray
import io.legado.app.utils.isJsonObject
import io.legado.app.utils.isUri
import io.legado.app.utils.jsonPath
import io.legado.app.utils.readText
import io.legado.app.utils.splitNotBlank
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import splitties.init.appCtx

class ImportRssSourceViewModel(app: Application) : BaseViewModel(app) {
    var isAddGroup = false
    var groupName: String? = null
    val errorLiveData = MutableLiveData<String>()
    val successLiveData = MutableLiveData<Int>()

    /** 合集子链接下载进度（已完成/总数），驱动导入弹框 loading 文案（spinner-fix delta 2026-09-05 同型修复） */
    val progressLiveData = MutableLiveData<Pair<Int, Int>>()

    val allSources = arrayListOf<RssSource>()
    val checkSources = arrayListOf<RssSource?>()
    val selectStatus = arrayListOf<Boolean>()

    val isSelectAll: Boolean
        get() {
            selectStatus.forEach {
                if (!it) {
                    return false
                }
            }
            return true
        }

    val selectCount: Int
        get() {
            var count = 0
            selectStatus.forEach {
                if (it) {
                    count++
                }
            }
            return count
        }

    fun importSelect(finally: () -> Unit) {
        execute {
            val group = groupName?.trim()
            val keepName = AppConfig.importKeepName
            val keepGroup = AppConfig.importKeepGroup
            val keepEnable = AppConfig.importKeepEnable
            val selectSource = arrayListOf<RssSource>()
            selectStatus.forEachIndexed { index, b ->
                if (b) {
                    val source = allSources[index]
                    checkSources[index]?.let {
                        if (keepName) {
                            source.sourceName = it.sourceName
                        }
                        if (keepGroup) {
                            source.sourceGroup = it.sourceGroup
                        }
                        if (keepEnable) {
                            source.enabled = it.enabled
                        }
                        source.customOrder = it.customOrder
                    }
                    if (!group.isNullOrEmpty()) {
                        if (isAddGroup) {
                            val groups = linkedSetOf<String>()
                            source.sourceGroup?.splitNotBlank(AppPattern.splitGroupRegex)?.let {
                                groups.addAll(it)
                            }
                            groups.add(group)
                            source.sourceGroup = groups.joinToString(",")
                        } else {
                            source.sourceGroup = group
                        }
                    }
                    selectSource.add(source)
                }
            }
            SourceHelp.insertRssSource(*selectSource.toTypedArray())
        }.onFinally {
            finally.invoke()
        }
    }

    fun importSource(text: String) {
        execute {
            importSourceAwait(text)
        }.onError {
            errorLiveData.postValue("ImportError:${it.localizedMessage}")
            AppLog.put("ImportError:${it.localizedMessage}", it)
        }.onSuccess {
            comparisonSource()
        }
    }

    private suspend fun importSourceAwait(text: String) {
        val mText = text.trim()
        when {
            mText.isJsonObject() -> kotlin.runCatching {
                val json = JsonPath.parse(mText)
                val urls = json.read<List<String>>("$.sourceUrls")
                if (!urls.isNullOrEmpty()) {
                    // spinner-fix delta 2026-09-05 同型修复：并行下载+进度上报（原串行无进度）
                    importSourceUrls(urls)
                }
            }.onFailure {
                GSON.fromJsonArray<RssSource>(mText).getOrThrow().let {
                    val source = it.firstOrNull() ?: return@let
                    if (source.sourceUrl.isEmpty()) {
                        throw NoStackTraceException("不是订阅源")
                    }
                    allSources.addAll(it)
                }
            }

            mText.isJsonArray() -> {
                GSON.fromJsonArray<RssSource>(mText).getOrThrow().let {
                    val source = it.firstOrNull() ?: return@let
                    if (source.sourceUrl.isEmpty()) {
                        throw NoStackTraceException("不是订阅源")
                    }
                    allSources.addAll(it)
                }
            }

            mText.isAbsUrl() -> {
                importSourceUrls(listOf(mText))
            }

            mText.isUri() -> {
                importSourceAwait(mText.toUri().readText(appCtx))
            }

            else -> throw NoStackTraceException(context.getString(R.string.wrong_format))
        }
    }

    /**
     * 合集子链接并行下载（spinner-fix delta 2026-09-05 同型修复，语义与书源导入一致）：
     * 限流并发+实时进度+失败聚合（单个失败不影响其余，全部失败抛第一个异常）；
     * 结果聚合回主协程单线程 addAll（allSources 非线程安全）
     */
    private suspend fun importSourceUrls(urls: List<String>) {
        val total = urls.size
        val done = java.util.concurrent.atomic.AtomicInteger(0)
        val results = coroutineScope {
            val semaphore = Semaphore(AppConfig.searchThreadCount)
            urls.map { url ->
                async {
                    semaphore.withPermit {
                        val r = kotlin.runCatching { fetchRssSourcesFromUrl(url) }
                        progressLiveData.postValue(done.incrementAndGet() to total)
                        r
                    }
                }
            }.awaitAll()
        }
        results.forEach { r ->
            r.getOrNull()?.let { allSources.addAll(it) }
        }
        val failures = results.filter { it.isFailure }
        failures.forEach {
            AppLog.put("ImportRssSourceUrlError:${it.exceptionOrNull()?.localizedMessage}")
        }
        if (failures.size == urls.size) {
            throw failures.first().exceptionOrNull() ?: NoStackTraceException("全部子链接获取失败")
        }
    }

    /** 下载并解析单个子链接，返回订阅源列表（不直接写 allSources，由聚合方统一写入） */
    private suspend fun fetchRssSourcesFromUrl(url: String): List<RssSource> {
        RuleUpdate.cacheRssSourceMap[url]?.also {
            RuleUpdate.cacheRssSourceMap.remove(url)
            return it
        }
        val sources = arrayListOf<RssSource>()
        plainImportClient.newCallResponseBody {
            if (url.endsWith("#requestWithoutUA")) {
                url(url.substringBeforeLast("#requestWithoutUA"))
                header(AppConst.UA_NAME, "null")
            } else {
                url(url)
            }
        }.decompressed().byteStream().use { body ->
            val items: List<Map<String, Any>> = jsonPath.parse(body).read("$")
            for (item in items) {
                if (!item.containsKey("sourceUrl")) {
                    throw NoStackTraceException("不是订阅源")
                }
                val jsonItem = jsonPath.parse(item)
                GSON.fromJsonObject<RssSource>(jsonItem.jsonString()).getOrThrow().let { source ->
                    sources.add(source)
                }
            }
        }
        return sources
    }

    private fun comparisonSource() {
        execute {
            // spinner-fix delta 2026-09-05 同型修复：批量 IN 查询替代逐条（分批 500 规避变量上限）
            val existing = allSources.map { it.sourceUrl }
                .chunked(500)
                .flatMap { appDb.rssSourceDao.getRssSources(*it.toTypedArray()) }
                .associateBy { it.sourceUrl }
            allSources.forEachIndexed { index, it ->
                val has = existing[it.sourceUrl]
                checkSources.add(has)
                selectStatus.add(has == null || has.lastUpdateTime < it.lastUpdateTime)
            }
            successLiveData.postValue(allSources.size)
        }
    }

}
