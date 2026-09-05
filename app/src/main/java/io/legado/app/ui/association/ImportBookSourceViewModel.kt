package io.legado.app.ui.association

import android.app.Application
import android.net.Uri
import androidx.lifecycle.MutableLiveData
import com.jayway.jsonpath.JsonPath
import io.legado.app.R
import io.legado.app.base.BaseViewModel
import io.legado.app.constant.AppConst
import io.legado.app.constant.AppLog
import io.legado.app.constant.AppPattern
import io.legado.app.data.appDb
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.BookSourcePart
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.book.ContentProcessor
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
import io.legado.app.utils.inputStream
import io.legado.app.utils.isAbsUrl
import io.legado.app.utils.isJsonArray
import io.legado.app.utils.isJsonObject
import io.legado.app.utils.isUri
import io.legado.app.utils.splitNotBlank
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit


class ImportBookSourceViewModel(app: Application) : BaseViewModel(app) {
    var isAddGroup = false
    var groupName: String? = null
    val errorLiveData = MutableLiveData<String>()
    val successLiveData = MutableLiveData<Int>()

    /** 合集子链接下载进度（已完成/总数），驱动导入弹框 loading 文案（spinner-fix delta 2026-09-05） */
    val progressLiveData = MutableLiveData<Pair<Int, Int>>()

    val allSources = arrayListOf<BookSource>()
    val checkSources = arrayListOf<BookSourcePart?>()
    val selectStatus = arrayListOf<Boolean>()
    val newSourceStatus = arrayListOf<Boolean>()
    val updateSourceStatus = arrayListOf<Boolean>()

    val isSelectAll: Boolean
        get() {
            selectStatus.forEach {
                if (!it) {
                    return false
                }
            }
            return true
        }

    val isSelectAllNew: Boolean
        get() {
            newSourceStatus.forEachIndexed { index, b ->
                if (b && !selectStatus[index]) {
                    return false
                }
            }
            return true
        }

    val isSelectAllUpdate: Boolean
        get() {
            updateSourceStatus.forEachIndexed { index, b ->
                if (b && !selectStatus[index]) {
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
            val selectSource = arrayListOf<BookSource>()
            selectStatus.forEachIndexed { index, b ->
                if (b) {
                    val source = allSources[index]
                    checkSources[index]?.let {
                        if (keepName) {
                            source.bookSourceName = it.bookSourceName
                        }
                        if (keepGroup) {
                            source.bookSourceGroup = it.bookSourceGroup
                        }
                        if (keepEnable) {
                            source.enabled = it.enabled
                            source.enabledExplore = it.enabledExplore
                        }
                        source.customOrder = it.customOrder
                    }
                    if (!group.isNullOrEmpty()) {
                        if (isAddGroup) {
                            val groups = linkedSetOf<String>()
                            source.bookSourceGroup?.splitNotBlank(AppPattern.splitGroupRegex)?.let {
                                groups.addAll(it)
                            }
                            groups.add(group)
                            source.bookSourceGroup = groups.joinToString(",")
                        } else {
                            source.bookSourceGroup = group
                        }
                    }
                    selectSource.add(source)
                }
            }
            SourceHelp.insertBookSource(*selectSource.toTypedArray())
            ContentProcessor.upReplaceRules()
        }.onFinally {
            finally.invoke()
        }
    }

    fun importSource(text: String) {
        execute {
            val mText = text.trim()
            when {
                mText.isJsonObject() -> {
                    kotlin.runCatching {
                        val json = JsonPath.parse(mText)
                        json.read<List<String>>("$.sourceUrls")
                    }.onSuccess { listUrl ->
                        // spinner-fix delta 2026-09-05：合集子链接并行下载+进度上报，
                        // 替换原串行逐个下载（大合集每个最坏 60s 且全程无进度 → 用户感知"长时间无响应"）
                        importSourceUrls(listUrl)
                    }.onFailure {
                        GSON.fromJsonObject<BookSource>(mText).getOrThrow().let {
                            if (it.bookSourceUrl.isEmpty()) {
                                throw NoStackTraceException("不是书源")
                            }
                            allSources.add(it)
                        }
                    }
                }

                mText.isJsonArray() -> GSON.fromJsonArray<BookSource>(mText).getOrThrow()
                    .let { items ->
                        val source = items.firstOrNull() ?: return@let
                        if (source.bookSourceUrl.isEmpty()) {
                            throw NoStackTraceException("不是书源")
                        }
                        allSources.addAll(items)
                    }

                mText.isAbsUrl() -> {
                    importSourceUrls(listOf(mText))
                }

                mText.isUri() -> {
                    val uri = Uri.parse(mText)
                    uri.inputStream(context).getOrThrow().use { inputS ->
                        GSON.fromJsonArray<BookSource>(inputS).getOrThrow().let {
                            val source = it.firstOrNull() ?: return@let
                            if (source.bookSourceUrl.isEmpty()) {
                                throw NoStackTraceException("不是书源")
                            }
                            allSources.addAll(it)
                        }
                    }
                }

                else -> throw NoStackTraceException(context.getString(R.string.wrong_format))
            }
        }.onError {
            errorLiveData.postValue("ImportError:${it.localizedMessage}")
            AppLog.put("ImportError:${it.localizedMessage}", it)
        }.onSuccess {
            comparisonSource()
        }
    }

    /**
     * 合集子链接并行下载（spinner-fix delta 2026-09-05）：
     * - 限流并发（searchThreadCount），防无界并发打爆网络层
     * - 实时进度 postValue((已完成 to 总数))
     * - 聚合语义：单个子链接失败不影响其余（记 AppLog）；全部失败才抛第一个异常（与原串行失败语义等价）
     * - 结果聚合回主协程单线程 addAll（allSources 为非线程安全 ArrayList，禁止并行写）
     */
    private suspend fun importSourceUrls(urls: List<String>) {
        val total = urls.size
        val done = java.util.concurrent.atomic.AtomicInteger(0)
        val results = coroutineScope {
            val semaphore = Semaphore(AppConfig.searchThreadCount)
            urls.map { url ->
                async {
                    semaphore.withPermit {
                        val r = kotlin.runCatching { fetchBookSourcesFromUrl(url) }
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
            AppLog.put("ImportSourceUrlError:${it.exceptionOrNull()?.localizedMessage}")
        }
        if (failures.size == urls.size) {
            throw failures.first().exceptionOrNull() ?: NoStackTraceException("全部子链接获取失败")
        }
    }

    /** 下载并解析单个子链接，返回书源列表（不直接写 allSources，由聚合方统一写入） */
    private suspend fun fetchBookSourcesFromUrl(url: String): List<BookSource> {
        RuleUpdate.cacheBookSourceMap[url]?.also {
            RuleUpdate.cacheBookSourceMap.remove(url)
            return it
        }
        return plainImportClient.newCallResponseBody {
            if (url.endsWith("#requestWithoutUA")) {
                url(url.substringBeforeLast("#requestWithoutUA"))
                header(AppConst.UA_NAME, "null")
            } else {
                url(url)
            }
        }.decompressed().byteStream().use {
            GSON.fromJsonArray<BookSource>(it).getOrThrow().let { list ->
                val source = list.firstOrNull() ?: throw NoStackTraceException("不是书源")
                if (source.bookSourceUrl.isEmpty()) {
                    throw NoStackTraceException("不是书源")
                }
                list
            }
        }
    }

    private fun comparisonSource() {
        execute {
            // spinner-fix delta 2026-09-05：批量 IN 查询替代逐条——4MB 合集数千条逐条 Room
            // 事务查询数十秒，是"导入卡住不显示"的真凶；分批 500 规避 SQLite 变量上限
            val existing = allSources.map { it.bookSourceUrl }
                .chunked(500)
                .flatMap { appDb.bookSourceDao.getBookSourceParts(it) }
                .associateBy { it.bookSourceUrl }
            allSources.forEachIndexed { index, it ->
                val source = existing[it.bookSourceUrl]
                checkSources.add(source)
                selectStatus.add(source == null || source.lastUpdateTime < it.lastUpdateTime)
                newSourceStatus.add(source == null)
                updateSourceStatus.add(source != null && source.lastUpdateTime < it.lastUpdateTime)
            }
            successLiveData.postValue(allSources.size)
        }
    }

}
