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
import io.legado.app.model.ImportCheck
import io.legado.app.model.QualityCheckSession
import io.legado.app.model.RuleUpdate
import io.legado.app.model.SourceQualityChecker
import io.legado.app.model.SourceQualityReport
import io.legado.app.model.SourceQualityScorer
import io.legado.app.model.SourceQualitySession
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

    /** 导入校验：L1 静态报告，与 allSources 平行；关闭时为 null 占位 */
    val l1Reports = arrayListOf<SourceQualityReport?>()

    /** 校验进度（已校验/总数/已过滤数） */
    val checkProgressLiveData = MutableLiveData<Triple<Int, Int, Int>>()

    /** 校验进行中标记 */
    @Volatile
    var checkRunning = false

    /** 校验 Job（弹框关闭真取消） */
    private var checkJob: io.legado.app.help.coroutine.Coroutine<Unit>? = null

    /** 校验过程中已通过索引（P2 半程落库） */
    private val passedSoFar = java.util.concurrent.ConcurrentLinkedQueue<Int>()

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
            // 导入校验开启：L1 静态检查（RSS 规则宽松：仅 sourceUrl 空判残缺，webview 型不误杀）
            val checkEnabled = ImportCheck.enabled
            if (checkEnabled) {
                l1Reports.clear()
            }
            allSources.forEachIndexed { index, it ->
                val has = existing[it.sourceUrl]
                checkSources.add(has)
                var selectable = has == null || has.lastUpdateTime < it.lastUpdateTime
                if (checkEnabled) {
                    val l1 = SourceQualityChecker.l1StaticCheckRssSource(it)
                    l1Reports.add(l1)
                    if (l1.deterministicFail) {
                        selectable = false
                    }
                } else {
                    l1Reports.add(null)
                }
                selectStatus.add(selectable)
            }
            successLiveData.postValue(allSources.size)
        }
    }

    /**
     * 带质量校验的订阅源导入（与书源侧 importSelectWithCheck 同构）
     */
    fun importSelectWithCheck(
        onProgress: (Int, Int, Int) -> Unit,
        onComplete: (ImportCheckOutcome?) -> Unit
    ) {
        val options = ImportCheck.toProbeOptions()
        val selectedIndexes = mutableListOf<Int>()
        selectStatus.forEachIndexed { index, b ->
            if (b) selectedIndexes.add(index)
        }
        if (selectedIndexes.isEmpty()) {
            onComplete(null)
            return
        }
        checkRunning = true
        passedSoFar.clear()
        AppLog.putDebugWithTag(
            QualityCheckSession.LOG_TAG,
            "订阅源导入校验启动: selected=${selectedIndexes.size}",
            level = AppLog.Level.INFO
        )
        checkJob = execute {
            if (SourceQualityChecker.isNetworkDown()) {
                importSelected(selectedIndexes, autoDisable = false)
                onComplete(
                    ImportCheckOutcome(
                        imported = selectedIndexes.size,
                        filtered = emptyList(),
                        networkDown = true,
                        unCheckedCount = selectedIndexes.size
                    )
                )
                return@execute
            }
            val session = SourceQualitySession(options)
            val checkedCounter = java.util.concurrent.atomic.AtomicInteger(0)
            val filteredCounter = java.util.concurrent.atomic.AtomicInteger(0)
            // 流式落库（与书源侧同构）：通过源每满 50 条立即落库，防长校验被杀全丢
            val pendingPassed = java.util.Collections.synchronizedList(mutableListOf<Int>())
            fun flushPassed(force: Boolean = false) {
                val batch = synchronized(pendingPassed) {
                    if (pendingPassed.isEmpty() || (!force && pendingPassed.size < BATCH_IMPORT_SIZE)) {
                        emptyList()
                    } else {
                        val b = pendingPassed.toList()
                        pendingPassed.clear()
                        b
                    }
                }
                if (batch.isNotEmpty()) {
                    AppLog.putDebugWithTag(
                        QualityCheckSession.LOG_TAG,
                        "订阅源流式落库: batch=${batch.size} 累计通过=${passedSoFar.size}",
                        level = AppLog.Level.INFO
                    )
                    importSelected(batch, autoDisable = false)
                }
            }
            val outcomes = coroutineScope {
                selectedIndexes.map { index ->
                    async {
                        val source = allSources[index]
                        val cached = if (source.lastUpdateTime > 0) {
                            SourceQualityChecker.getCachedReport(source.sourceUrl, source.lastUpdateTime, options)
                        } else null
                        val report = cached ?: SourceQualityChecker.checkRssSource(source, session)
                        if (cached == null && source.lastUpdateTime > 0) {
                            SourceQualityChecker.putCachedReport(source.sourceUrl, source.lastUpdateTime, options, report)
                        }
                        val (filtered, reason) = SourceQualityScorer.isFiltered(report, options.strictness)
                        val fCount = if (filtered) filteredCounter.incrementAndGet() else {
                            passedSoFar.add(index)
                            pendingPassed.add(index)
                            flushPassed()
                            filteredCounter.get()
                        }
                        checkProgressLiveData.postValue(
                            Triple(checkedCounter.incrementAndGet(), selectedIndexes.size, fCount)
                        )
                        if (filtered) {
                            index to FilteredBookSource(
                                index = index,
                                name = source.sourceName,
                                reason = reason,
                                score = report.score
                            )
                        } else {
                            index to null
                        }
                    }
                }.awaitAll()
            }
            flushPassed(force = true)
            val filteredList = outcomes.mapNotNull { it.second }
            checkRunning = false
            AppLog.putDebugWithTag(
                QualityCheckSession.LOG_TAG,
                "订阅源导入校验完成: 导入=${selectedIndexes.size - filteredList.size} 过滤=${filteredList.size}",
                level = AppLog.Level.INFO
            )
            onComplete(
                ImportCheckOutcome(
                    imported = selectedIndexes.size - filteredList.size,
                    filtered = filteredList,
                    networkDown = false,
                    unCheckedCount = 0
                )
            )
        }.onError {
            checkRunning = false
            AppLog.put("ImportRssCheckError:${it.localizedMessage}", it)
            onComplete(null)
        }
    }

    fun cancelCheck(landPassed: Boolean) {
        checkRunning = false
        AppLog.putDebugWithTag(
            QualityCheckSession.LOG_TAG,
            "订阅源校验取消: landPassed=$landPassed 已通过=${passedSoFar.size}",
            level = AppLog.Level.INFO
        )
        checkJob?.cancel()
        checkJob = null
        if (landPassed && passedSoFar.isNotEmpty()) {
            importSelected(passedSoFar.toList(), autoDisable = false)
        }
        passedSoFar.clear()
    }

    fun restoreFiltered(indexes: List<Int>, autoDisable: Boolean, finally: () -> Unit) {
        execute {
            importSelected(indexes, autoDisable)
        }.onFinally {
            finally.invoke()
        }
    }

    /** 勾选索引集落库（keepName/keepGroup/分组逻辑与 importSelect 一致） */
    private fun importSelected(indexes: List<Int>, autoDisable: Boolean) {
        val group = groupName?.trim()
        val keepName = AppConfig.importKeepName
        val keepGroup = AppConfig.importKeepGroup
        val keepEnable = AppConfig.importKeepEnable
        val selectSource = arrayListOf<RssSource>()
        indexes.forEach { index ->
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
            if (autoDisable) {
                source.enabled = false
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
        if (selectSource.isNotEmpty()) {
            SourceHelp.insertRssSource(*selectSource.toTypedArray())
        }
    }

    companion object {
        const val BATCH_IMPORT_THRESHOLD = 200
        const val BATCH_IMPORT_SIZE = 50
    }

}
