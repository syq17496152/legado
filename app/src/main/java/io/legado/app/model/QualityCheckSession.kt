package io.legado.app.model

import android.content.Context
import io.legado.app.constant.AppLog
import io.legado.app.data.appDb
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.RssSource
import io.legado.app.utils.GSON
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File

/**
 * 体检会话（QualityCheckSession，AD 设计：应用级单例，按源类型书源/订阅源分会话）
 *
 * - 体检过程零写库（只读探测）；删除/禁用由结果页显式触发
 * - 覆盖上一轮前先 cancel 旧 Job 再清结果（A5）
 * - 页面退出不中断；进程被杀即中断（接受，重跑）
 */
class QualityCheckSession<T> private constructor(
    val typeTag: String,
    private val probe: suspend (T, SourceQualitySession) -> SourceQualityReport
) {
    data class State<T>(
        val running: Boolean = false,
        val checked: Int = 0,
        val total: Int = 0,
        val failedCount: Int = 0,
        val results: List<Pair<T, SourceQualityReport>> = emptyList()
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow(State<T>())
    val state: StateFlow<State<T>> = _state
    private var job: Job? = null
    private var probeSession: SourceQualitySession? = null

    // 暂停续跑：记录剩余未检源（真机反馈：万条体检需暂停能力）
    private var remainingSources: List<T> = emptyList()
    private var lastOptions: ProbeOptions? = null
    private var lastSources: List<T> = emptyList()

    /**
     * 暂停：取消当前 Job，记录剩余源（可 resume 续跑）
     */
    fun pause() {
        val checked = _state.value.checked
        val total = _state.value.total
        remainingSources = lastSources.drop(checked)
        job?.cancel()
        job = null
        _state.value = _state.value.copy(running = false)
        AppLog.putDebugWithTag(
            LOG_TAG,
            "体检暂停: 已检=$checked/$total 剩余=${remainingSources.size}",
            level = AppLog.Level.INFO
        )
    }

    /**
     * 恢复：从断点续跑剩余源
     */
    fun resume() {
        if (remainingSources.isEmpty() || _state.value.running) return
        AppLog.putDebugWithTag(
            LOG_TAG,
            "体检续跑: 剩余=${remainingSources.size}",
            level = AppLog.Level.INFO
        )
        start(remainingSources, lastOptions ?: ProbeOptions())
        remainingSources = emptyList()
    }

    fun hasPaused(): Boolean = remainingSources.isNotEmpty()

    /** 本轮体检的域名校验开关（"应用结果"桥接回填分组/weight 用）；未体检过默认 true */
    fun lastCheckDomain(): Boolean = lastOptions?.checkDomain ?: true

    /**
     * 启动体检（覆盖上一轮：先 cancel 旧 Job 再清结果，A5）
     * @param options 探测参数；sources 有序源列表
     */
    fun start(sources: List<T>, options: ProbeOptions) {
        job?.cancel()
        lastOptions = options
        lastSources = sources
        remainingSources = emptyList()
        val session = SourceQualitySession(options)
        probeSession = session
        _state.value = State(running = true, total = sources.size)
        val startTs = System.currentTimeMillis()
        AppLog.putDebugWithTag(
            LOG_TAG,
            "体检启动: type=$typeTag total=${sources.size} options=$options",
            level = AppLog.Level.INFO
        )
        job = scope.launch {
            val results = mutableListOf<Pair<T, SourceQualityReport>>()
            kotlinx.coroutines.coroutineScope {
                sources.map { source ->
                    async {
                        val report = probe(source, session)
                        results.add(source to report)
                        val failed = SourceQualityScorer.toUserState(report) !=
                            SourceQualityScorer.UserState.USABLE
                        val newChecked = _state.value.checked + 1
                        val newFailed = _state.value.failedCount + if (failed) 1 else 0
                        // 每 50 条输出里程碑进度（测试反馈用，避免逐条刷屏）
                        if (newChecked % 50 == 0) {
                            AppLog.putDebugWithTag(
                                LOG_TAG,
                                "体检进度: $newChecked/${sources.size} 异常=$newFailed",
                                level = AppLog.Level.INFO
                            )
                        }
                        // 异常源逐条记录（URL 键+得分+失败维度+存疑原因，测试排查"为什么被判失效"的生命线）
                        if (failed) {
                            val failDims = report.dimensions
                                .filterValues { it.state == DimState.FAIL }.keys
                            AppLog.putDebugWithTag(
                                LOG_TAG,
                                "源异常: key=${keyOf(source)} score=${report.score} " +
                                    "失败维度=$failDims 存疑=${report.suspectReasons.map { it.name }}",
                                level = AppLog.Level.INFO
                            )
                        }
                        _state.value = _state.value.copy(
                            checked = newChecked,
                            failedCount = newFailed,
                            results = results.toList()
                        )
                        report
                    }
                }.map { it.await() }
            }
            _state.value = _state.value.copy(running = false)
            AppLog.putDebugWithTag(
                LOG_TAG,
                "体检完成: checked=${_state.value.checked} 异常=${_state.value.failedCount} " +
                    "耗时=${System.currentTimeMillis() - startTs}ms",
                level = AppLog.Level.INFO
            )
        }
    }

    fun cancel() {
        job?.cancel()
        job = null
        _state.value = _state.value.copy(running = false)
        AppLog.putDebugWithTag(
            LOG_TAG,
            "体检取消: 已检=${_state.value.checked}/${_state.value.total}",
            level = AppLog.Level.INFO
        )
    }

    /**
     * 删除选中源（S6：先自动 JSON 备份到备份目录，再删库）
     * @param urls 选中源 URL 键集合
     * @return 备份文件路径
     */
    suspend fun deleteSelected(context: Context, urls: Set<String>): String {
        val toDelete = _state.value.results.filter { (s, _) ->
            keyOf(s) in urls
        }
        val backupFile = backup(context, toDelete)
        when (typeTag) {
            TAG_BOOK -> {
                val sources = toDelete.map { it.first as BookSource }
                appDb.bookSourceDao.delete(*sources.toTypedArray())
            }
            else -> {
                val sources = toDelete.map { it.first as RssSource }
                appDb.rssSourceDao.delete(*sources.toTypedArray())
            }
        }
        // 从结果中移除已删除项（列表即时刷新）
        _state.value = _state.value.copy(
            results = _state.value.results.filter { (s, _) -> keyOf(s) !in urls }
        )
        AppLog.putDebugWithTag(
            LOG_TAG,
            "删除完成: count=${toDelete.size} 备份=$backupFile",
            level = AppLog.Level.INFO
        )
        return backupFile.absolutePath
    }

    /**
     * 禁用选中源（P4 主按钮）
     */
    suspend fun disableSelected(urls: Set<String>) {
        val toDisable = _state.value.results.filter { (s, _) -> keyOf(s) in urls }
        when (typeTag) {
            TAG_BOOK -> toDisable.forEach { appDb.bookSourceDao.enable((it.first as BookSource).bookSourceUrl, false) }
            else -> toDisable.forEach { appDb.rssSourceDao.enable((it.first as RssSource).sourceUrl, false) }
        }
        AppLog.putDebugWithTag(
            LOG_TAG,
            "禁用完成: count=${toDisable.size}",
            level = AppLog.Level.INFO
        )
    }

    private fun keyOf(s: T): String {
        @Suppress("UNCHECKED_CAST")
        return when (s) {
            is BookSource -> s.bookSourceUrl
            is RssSource -> s.sourceUrl
            else -> s.toString()
        }
    }

    /**
     * S6 误删兜底：删除前自动导出 JSON 备份
     * 真机反馈：备份放用户备份目录（Backup.backupPath 与整体备份同域），不再用私有外部目录
     */
    private fun backup(context: Context, items: List<Pair<T, SourceQualityReport>>): File {
        val dir = File(io.legado.app.help.storage.Backup.backupPath, "quality_backup")
        if (!dir.exists()) dir.mkdirs()
        val file = File(dir, "${typeTag}_${System.currentTimeMillis()}.json")
        val json = GSON.toJson(items.map { it.first })
        file.writeText(json)
        return file
    }

    companion object {
        const val TAG_BOOK = "book_source"
        const val TAG_RSS = "rss_source"

        /** 诊断日志统一 tag（AI 真机测试通过 adb logcat -s QualityCheck 过滤采集） */
        const val LOG_TAG = "QualityCheck"

        /** 书源体检会话（按源类型分会话，A5：双管理页同时体检互不覆盖） */
        val bookSession = QualityCheckSession<BookSource>(TAG_BOOK) { source, session ->
            SourceQualityChecker.checkBookSource(source, session)
        }

        /** 订阅源体检会话 */
        val rssSession = QualityCheckSession<RssSource>(TAG_RSS) { source, session ->
            SourceQualityChecker.checkRssSource(source, session)
        }

        /**
         * 体检入口（管理页"质量体检"菜单）：范围源收集后启动会话
         */
        fun startBookCheck(context: Context, sources: List<BookSource>, options: ProbeOptions) {
            if (bookSession.state.value.running) {
                context.toastOnUi(context.getString(io.legado.app.R.string.quality_report_progress,
                    bookSession.state.value.checked, bookSession.state.value.total, bookSession.state.value.failedCount))
                return
            }
            bookSession.start(sources, options)
        }

        fun startRssCheck(context: Context, sources: List<RssSource>, options: ProbeOptions) {
            if (rssSession.state.value.running) {
                context.toastOnUi(context.getString(io.legado.app.R.string.quality_report_progress,
                    rssSession.state.value.checked, rssSession.state.value.total, rssSession.state.value.failedCount))
                return
            }
            rssSession.start(sources, options)
        }
    }
}
