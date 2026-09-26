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
import io.legado.app.help.source.isEmptyConfiguration
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

/**
 * 导入校验：被过滤源（复核窗口展示项）
 */
data class FilteredBookSource(
    val index: Int,
    val name: String,
    val reason: String,
    val score: Int
)

/**
 * 导入校验结果汇总
 */
class ImportCheckOutcome(
    val imported: Int,
    val filtered: List<FilteredBookSource>,
    val networkDown: Boolean,
    val unCheckedCount: Int
)


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

    /** 导入校验（import-source-quality-filter）：L1 静态报告，与 allSources 平行；关闭时为 null 占位 */
    val l1Reports = arrayListOf<SourceQualityReport?>()

    /** 校验进度（已校验/总数/已过滤数），驱动导入弹框校验进度展示 */
    val checkProgressLiveData = MutableLiveData<Triple<Int, Int, Int>>()

    /**
     * 3.3.1（R 批，J10 硬阻断口径）：被忽略的**全空书源**数量
     *
     * 语义：解析阶段即从 `allSources` 剔除（不展示、不可勾选、不导入、不参与校验），
     * 仅以数量回执给弹框标题，避免用户以为「导入的源少了」。
     */
    val emptyConfigCount = MutableLiveData(0)

    /** 校验进行中标记（弹框关闭取消 + 返回键拦截判断） */
    @Volatile
    var checkRunning = false

    /** 校验 Job（弹框关闭真取消） */
    private var checkJob: io.legado.app.help.coroutine.Coroutine<Unit>? = null

    /** 校验过程中已通过的源索引（P2 半程落库：取消时导入已通过部分） */
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
            // 3.3.1（J10 硬阻断）：全空配置源（无任何抓取入口/规则）在此**一次性剔除**——
            // 不进列表（无法勾选）、不进校验集、不进导入集；仅留数量回执给弹框标题。
            // 剔除放在本函数（allSources 定型的唯一收口点）⇒ 两条导入链路（importSelect /
            // importSelectWithCheck）与「选择新增/选择更新」批量勾选天然都拿不到它。
            val keptSources = allSources.filterNot { it.isEmptyConfiguration() }
            val ignoredCount = allSources.size - keptSources.size
            if (ignoredCount > 0) {
                allSources.clear()
                allSources.addAll(keptSources)
            }
            emptyConfigCount.postValue(ignoredCount)
            // spinner-fix delta 2026-09-05：批量 IN 查询替代逐条——4MB 合集数千条逐条 Room
            // 事务查询数十秒，是"导入卡住不显示"的真凶；分批 500 规避 SQLite 变量上限
            val existing = allSources.map { it.bookSourceUrl }
                .chunked(500)
                .flatMap { appDb.bookSourceDao.getBookSourceParts(it) }
                .associateBy { it.bookSourceUrl }
            // 导入校验开启：L1 静态结构检查（0 网络成本，解析后即时标记 FILTERED 态）
            val checkEnabled = ImportCheck.enabled
            if (checkEnabled) {
                l1Reports.clear()
            }
            allSources.forEachIndexed { index, it ->
                val source = existing[it.bookSourceUrl]
                checkSources.add(source)
                var selectable = source == null || source.lastUpdateTime < it.lastUpdateTime
                if (checkEnabled) {
                    val l1 = SourceQualityChecker.l1StaticCheckBookSource(it)
                    l1Reports.add(l1)
                    // L1 结构残缺 = 确定性失败，默认不勾选（FILTERED 态展示，用户可手动勾选恢复）
                    if (l1.deterministicFail) {
                        selectable = false
                    }
                } else {
                    l1Reports.add(null)
                }
                selectStatus.add(selectable)
                newSourceStatus.add(source == null)
                updateSourceStatus.add(source != null && source.lastUpdateTime < it.lastUpdateTime)
            }
            if (checkEnabled) {
                val l1Fail = l1Reports.count { it?.deterministicFail == true }
                AppLog.putDebugWithTag(
                    QualityCheckSession.LOG_TAG,
                    "L1 静态检查: total=${allSources.size} 结构残缺=$l1Fail",
                    level = AppLog.Level.INFO
                )
            }
            successLiveData.postValue(allSources.size)
        }
    }

    /**
     * 带质量校验的导入（ImportCheck.enabled 且深度>L1 时走此路径）：
     * 断网预检短路 → 缓存命中复用 → 并发联网校验 → 档位判定 → 通过集落库 + 过滤集复核
     *
     * @param onProgress 校验进度回调（已校验/总数/已过滤）
     * @param onComplete 完成回调（校验终止/复核窗口弹出均走此回调，主线程）
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
            "导入校验启动: selected=${selectedIndexes.size}",
            level = AppLog.Level.INFO
        )
        checkJob = execute {
            // 断网预检（AD 判定规则表断网豁免）：确认断网 → 全部"未测+存疑"放行
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
            // 流式落库（社区合集 E2E 实证修复）：通过源每满 50 条立即落库，
            // 防万条集合校验 25 分钟后取消/被杀导致全部丢失（913 条 E2E 铁证：force-stop 时整批未落库=0）
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
                        "流式落库: batch=${batch.size} 累计通过=${passedSoFar.size}",
                        level = AppLog.Level.INFO
                    )
                    importSelected(batch, autoDisable = false)
                }
            }
            val outcomes = coroutineScope {
                selectedIndexes.map { index ->
                    async {
                        // 协程取消传播：弹框关闭即终止；并发由 session.semaphore 限流
                        val source = allSources[index]
                        val cached = if (source.lastUpdateTime > 0) {
                            SourceQualityChecker.getCachedReport(source.bookSourceUrl, source.lastUpdateTime, options)
                        } else null
                        val report = cached ?: SourceQualityChecker.checkBookSource(source, session)
                        if (cached == null && source.lastUpdateTime > 0) {
                            SourceQualityChecker.putCachedReport(source.bookSourceUrl, source.lastUpdateTime, options, report)
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
                                name = source.bookSourceName,
                                reason = reason,
                                score = report.score
                            )
                        } else {
                            index to null
                        }
                    }
                }.awaitAll()
            }
            flushPassed(force = true)  // 收尾：落库剩余不足 50 的通过源
            val filteredList = outcomes.mapNotNull { it.second }
            checkRunning = false
            AppLog.putDebugWithTag(
                QualityCheckSession.LOG_TAG,
                "导入校验完成: 导入=${selectedIndexes.size - filteredList.size} 过滤=${filteredList.size}",
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
            AppLog.put("ImportCheckError:${it.localizedMessage}", it)
            onComplete(null)
        }
    }

    /**
     * 取消校验（弹框关闭确认框调用）：
     * @param landPassed true=导入已通过校验的源（半程落库）；false=直接取消（无源落库）
     */
    fun cancelCheck(landPassed: Boolean) {
        checkRunning = false
        AppLog.putDebugWithTag(
            QualityCheckSession.LOG_TAG,
            "校验取消: landPassed=$landPassed 已通过=${passedSoFar.size}",
            level = AppLog.Level.INFO
        )
        checkJob?.cancel()
        checkJob = null
        if (landPassed && passedSoFar.isNotEmpty()) {
            importSelected(passedSoFar.toList(), autoDisable = false)
        }
        passedSoFar.clear()
    }

    /**
     * 恢复导入（复核窗口"仍要导入"）：
     * @param autoDisable true=导入后自动禁用（P8 置灰期望）
     */
    fun restoreFiltered(indexes: List<Int>, autoDisable: Boolean, finally: () -> Unit) {
        execute {
            importSelected(indexes, autoDisable)
        }.onFinally {
            finally.invoke()
        }
    }

    /**
     * 勾选索引集落库（公共路径：keepName/keepGroup/分组/customOrder 逻辑与 importSelect 一致）
     */
    private fun importSelected(indexes: List<Int>, autoDisable: Boolean) {
        val group = groupName?.trim()
        val keepName = AppConfig.importKeepName
        val keepGroup = AppConfig.importKeepGroup
        val keepEnable = AppConfig.importKeepEnable
        val selectSource = arrayListOf<BookSource>()
        indexes.forEach { index ->
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
            if (autoDisable) {
                source.enabled = false
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
        if (selectSource.isNotEmpty()) {
            SourceHelp.insertBookSource(*selectSource.toTypedArray())
            ContentProcessor.upReplaceRules()
        }
    }

    companion object {
        const val BATCH_IMPORT_THRESHOLD = 200
        const val BATCH_IMPORT_SIZE = 50
    }

}
