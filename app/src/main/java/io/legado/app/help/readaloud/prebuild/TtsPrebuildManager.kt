package io.legado.app.help.readaloud.prebuild

import android.content.Context
import io.legado.app.constant.AppLog
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.HttpTTS
import io.legado.app.help.book.BookHelp
import io.legado.app.help.book.ContentProcessor
import io.legado.app.help.readaloud.casting.TtsCastingStore
import io.legado.app.help.config.AppConfig
import io.legado.app.model.ReadBook
import io.legado.app.utils.getPrefBoolean
import splitties.init.appCtx

import io.legado.app.ui.book.read.page.provider.ChapterProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 批量预合成状态（StateFlow 载体）
 * generation=任务代际 id（服务实例轮询按代际过滤，防旧实例误渲染）
 */
data class TtsPrebuildState(
    val generation: Long = 0,
    val bookKey: String = "",
    val bookName: String = "",
    val phase: Phase = Phase.IDLE,
    val currentUnit: Int = 0,
    val totalUnit: Int = 0,
    val failedCount: Int = 0,
    val message: String = ""
) {
    enum class Phase { IDLE, SCANNING, RUNNING, DONE, FAILED, CANCELLED }
}

/**
 * 批量预合成队列单例（P2-7，AD-15，§3.7.2）：
 * - 内存态不持久化（Room v110 冻结；任务可重建，音频产物才是资产）
 * - 全局单队列 FIFO：多本书发起=排队追加
 * - 参数快照：入队锁定键参数全集，任务期内切引擎/改模板/调语速不影响进行中任务
 * - 失败语义：网络/引擎错误单单元重试 1 次后跳过计入失败明细；IO/磁盘满类连续 3 单元失败→任务级中止；
 *   AD-08 降级链不适用于批量链（失败即跳过）
 * - 播放优先租约+原子提交+键单源+保留名单=并发防护四件套（§3.7.3）
 * - 单写者状态机：终态仅由 worker 感知 cancelFlag 后落笔，终态展示后定时复位 IDLE
 * - 可测性：租约判定/账目计算抽纯函数（leaseDecision/buildAccount），object 仅装配
 */
object TtsPrebuildManager {

    /** 预合成保留名单：已落盘预合成文件名集合（removeCacheFile/清理跳过；按 rename 成功事实登记）
     *  E2/P1-3：持久化副本 cacheDir/tts_prebuild/reserved_keys.json，进程重启后回装（防产物被清理误删） */
    val reservedKeys = ConcurrentHashMap<String, Long>()

    /** 保留名单落盘文件（独立子目录，避开 removeCacheFile 对 cacheDir/httpTTS 的清扫循环） */
    private val reservedFile: File by lazy {
        val dir = File(appCtx.cacheDir, "tts_prebuild")
        if (!dir.exists()) dir.mkdirs()
        File(dir, "reserved_keys.json")
    }
    private val reservedSaveLock = Any()
    private val gson = com.google.gson.Gson()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow(TtsPrebuildState())
    val state: StateFlow<TtsPrebuildState> = _state

    init {
        // E2：异步回装（首访主线程不读盘；回装完成前内存名单为空=与历史行为一致）
        scope.launch { loadReservedKeys() }
    }

    /** 回装持久化名单：损坏容错（解析失败静默重建空名单）+30 天 prune（过期条目不再保护清理） */
    private fun loadReservedKeys() {
        runCatching {
            if (!reservedFile.exists()) return
            val json = reservedFile.readText()
            if (json.isBlank()) return
            val type = object : com.google.gson.reflect.TypeToken<Map<String, Long>>() {}.type
            val loaded: Map<String, Long> = gson.fromJson(json, type) ?: return
            val pruneBefore = System.currentTimeMillis() - RESERVED_TTL_MS
            loaded.forEach { (k, ts) ->
                if (ts > pruneBefore) reservedKeys[k] = ts
            }
        }.onFailure {
            // 损坏/半成品文件：静默重建空名单（旧完整版由 temp+rename 保障，正常不会走到）
            AppLog.put("TTS 预合成保留名单回装失败，已重建空名单：${it.message}")
            reservedKeys.clear()
        }
    }

    /** 名单落盘（synchronized 快照+temp+rename 原子写；调用方须在 IO 线程） */
    private fun writeReservedKeys() {
        synchronized(reservedSaveLock) {
            runCatching {
                val snapshot = reservedKeys.toMap()
                val json = gson.toJson(snapshot)
                val temp = File(reservedFile.parentFile, reservedFile.name + ".part")
                temp.writeText(json)
                if (!temp.renameTo(reservedFile)) {
                    temp.copyTo(reservedFile, overwrite = true)
                    temp.delete()
                }
            }.onFailure {
                AppLog.put("TTS 预合成保留名单落盘失败：${it.message}")
            }
        }
    }

    /** 名单变更后立即派发 IO 直写（取消防抖：防"内存已更新文件未落盘"脱节面，红队 R3-1） */
    private fun persistReservedKeysAsync() {
        scope.launch { writeReservedKeys() }
    }

    /** 进行中/排队任务簿：bookKey→任务（重复发起去重依据） */
    private val activeTasks = LinkedHashMap<String, PrebuildTask>()
    private val cancelFlags = ConcurrentHashMap<String, AtomicBoolean>()
    private var workerJob: Job? = null
    private var generation = 0L

    /** 单批章数上限（兼顾 Android 15 dataSync 6h 时限与内存） */
    const val MAX_BATCH_CHAPTERS = 200

    /** 内部任务（参数快照全集；engineKey 快照已删除：E5 门面改传 task.httpTts?.id，快照零消费） */
    private data class PrebuildTask(
        val generation: Long,
        val book: Book,
        val bookKey: String,
        val start: Int,
        val end: Int,
        val httpTts: HttpTTS?,
        val voiceKey: String,
        val speechRate: Int,
        val readAloudByPage: Boolean
    )

    /**
     * 入队（发起链）：预检去重+参数快照+FIFO 排队
     * @return null=成功入队；非 null=拒绝原因
     */
    fun enqueue(
        context: Context,
        book: Book,
        start: Int,
        end: Int,
        httpTts: HttpTTS
    ): String? {
        val endC = end.coerceAtMost(start + MAX_BATCH_CHAPTERS - 1)
        if (end - start + 1 > MAX_BATCH_CHAPTERS) {
            return context.getString(io.legado.app.R.string.tts_casting_prebuild_too_many)
        }
        val bookKey = book.bookUrl
        synchronized(activeTasks) {
            // 重复发起去重：同书任务进行中/排队→拒绝
            if (activeTasks.containsKey(bookKey)) {
                return context.getString(io.legado.app.R.string.tts_casting_prebuild_duplicate)
            }
            generation++
            val task = PrebuildTask(
                generation = generation,
                book = book,
                bookKey = bookKey,
                start = start,
                end = endC,
                httpTts = httpTts,
                voiceKey = readVoiceKey(),
                // P0-4 修复：speedKey 单向对齐播放端口径（speechRatePlay+5，含 ttsFlowSys 分支）。
                // 播放端该值同时是发引擎的 speakSpeed 请求参数域（历史口径），禁反向修改播放端
                speechRate = AppConfig.speechRatePlay + 5,
                readAloudByPage = appCtx.getPrefBoolean(io.legado.app.constant.PreferKey.readAloudByPage)
            )
            activeTasks[bookKey] = task
            cancelFlags[bookKey] = AtomicBoolean(false)
            // TtsTrace 真机联调：预合成入队（参数快照证据；synchronized 内捕获快照值）
            AppLog.putDebugWithTag(
                AppLog.TAG_TTS_TRACE,
                "prebuild 入队 book=${book.name.takeLast(16)} ch=$start..$endC engineKey=${httpTts.id} voiceKey=${task.voiceKey.takeLast(12)} rate=${task.speechRate}",
                level = AppLog.Level.INFO
            )
        }
        ensureWorker()
        return null
    }

    /** 当前声源 voiceKey（toneID，哨兵解析后维度；与键单源同口径） */
    private fun readVoiceKey(): String {
        return runCatching {
            io.legado.app.help.readaloud.speech.SpeechRoute
                .resolveSpeechRoute(io.legado.app.help.readaloud.casting.ReadAloudDelegate.currentTtsEngineRaw())
                .toneID
        }.getOrDefault("")
    }

    /** 按书取消（缓存清理/删书联动）：置 cancelFlag，worker 感知后落终态 */
    fun cancelByBook(bookKey: String) {
        cancelFlags[bookKey]?.set(true)
    }

    /** 取消当前进行中任务（通知/页面取消按钮） */
    fun cancelCurrent() {
        synchronized(activeTasks) {
            activeTasks.keys.firstOrNull()?.let { cancelFlags[it]?.set(true) }
        }
    }

    /** 取消全部任务（服务 onTaskRemoved/onDestroy 联动；保留名单不动，防预合成资产失去清理保护） */
    fun cancelAll() {
        cancelFlags.values.forEach { it.set(true) }
    }

    /** 清空全部任务与保留名单（缓存管理页整目录清理联动） */
    fun cancelAllAndClearReserved() {
        cancelFlags.values.forEach { it.set(true) }
        reservedKeys.clear()
        persistReservedKeysAsync()
    }

    /** 从保留名单移除指定键（清理后未完成单元可重新合成；现零调用点，防御性接线） */
    fun removeReserved(key: String) {
        reservedKeys.remove(key)
        persistReservedKeysAsync()
    }

    /**
     * 租约判定纯函数（§3.7.3-1，可 JVM 单测）：
     * @param curChapterIndex 当前朗读章索引（null=未在朗读该书/章节未知）
     * @param nextChapterIndex 正在预下载的下一章索引（null=无）
     * @param taskChapterIndex 任务当前推进章索引
     * @param deferredRounds 已延后轮数
     * @return Pair(是否延后, 新延后轮数)：延后 3 轮上限后强制执行（防任务永不完成）
     */
    fun leaseDecision(
        curChapterIndex: Int?,
        nextChapterIndex: Int?,
        taskChapterIndex: Int,
        deferredRounds: Int
    ): Pair<Boolean, Int> {
        val leased = curChapterIndex == taskChapterIndex ||
            nextChapterIndex == taskChapterIndex
        if (!leased) return false to deferredRounds
        return if (deferredRounds < DEFER_MAX_ROUNDS) {
            true to deferredRounds + 1
        } else {
            false to deferredRounds
        }
    }

    /** 账目纯函数：剩余待合成/总单元（入队预扫描口径） */
    fun buildAccount(totalUnit: Int, doneUnit: Int, failedUnit: Int): Pair<Int, Int> {
        val remaining = (totalUnit - doneUnit - failedUnit).coerceAtLeast(0)
        return remaining to totalUnit
    }

    private const val DEFER_MAX_ROUNDS = 3

    /** 串行 worker：FIFO 消费任务簿（单写者：终态仅 worker 落笔）；检查+赋值同步防并发双 worker（P2-18） */
    private fun ensureWorker() {
        synchronized(this) {
            if (workerJob?.isActive == true) return
            workerJob = scope.launch {
                while (true) {
                    val task = synchronized(activeTasks) {
                        activeTasks.entries.firstOrNull()?.value
                    } ?: break
                    runTask(task)
                    synchronized(activeTasks) { activeTasks.remove(task.bookKey) }
                    cancelFlags.remove(task.bookKey)
                }
            }
        }
    }

    /** 待合成单元（fileName 内联随单元走，消除索引暂存错位雷 P1-7/P2-18） */
    private class PendingUnit(
        val chapterIndex: Int,
        val unitText: String,
        val fileName: String
    ) {
        var deferRounds = 0
        var retried = false
    }

    /** 执行单个预合成任务（S9-1 主链） */
    private suspend fun runTask(task: PrebuildTask) {
        val flag = cancelFlags[task.bookKey] ?: return
        val gen = task.generation
        fun setState(phase: TtsPrebuildState.Phase, current: Int = 0, total: Int = 0, failed: Int = 0, msg: String = "") {
            _state.value = TtsPrebuildState(gen, task.bookKey, task.book.name, phase, current, total, failed, msg)
        }
        setState(TtsPrebuildState.Phase.SCANNING)
        val processor = ContentProcessor.get(task.book.name, task.book.origin)
        var totalUnit = 0
        val unitList = mutableListOf<PendingUnit>()
        // 排版随 worker 协程树（P2-19：任务取消时 LAZY 排版 job 级联取消，不再空耗 CPU）
        val workerScope = CoroutineScope(currentCoroutineContext())
        try {
            // 懒切分+预扫描（worker 内异步执行，发起链零阻塞；扫描期进度="统计中"）
            for (index in task.start..task.end) {
                currentCoroutineContext().ensureActive()
                if (flag.get()) {
                    finishTask(task, TtsPrebuildState.Phase.CANCELLED, totalUnit, totalUnit, 0, "已取消")
                    return
                }
                val bookChapter = appDb.bookChapterDao.getChapter(task.book.bookUrl, index) ?: continue
                val content = BookHelp.getContent(task.book, bookChapter) ?: continue
                val bookContent = processor.getContent(task.book, bookChapter, content, reSegment = false)
                // P1-2 修复：chapterTitle 键因子与播放端同源解析（getDisplayTitle：去换行/简繁转换/替换规则），
                // 播放端 textChapter.title 即此口径（ReadBook.kt:1331），标题原始值直接入键会失配
                val displayTitle = resolveChapterTitle(task.book, bookChapter, processor)
                val textChapter = ChapterProvider.getTextChapterAsync(
                    workerScope, task.book, bookChapter, displayTitle, bookContent,
                    appDb.bookChapterDao.getChapterCount(task.book.bookUrl)
                )
                // P0-3 修复：排版是 LAZY 异步，必须等待完成再取分段（播放端有 isCompleted 守卫，批量端原缺失
                // → 每章几乎必然读到空分页）。60s 超时防异常章挂死；空页=排版异常章，判失败跳过
                try {
                    withTimeout(LAYOUT_TIMEOUT_MS) {
                        while (!textChapter.isCompleted) {
                            currentCoroutineContext().ensureActive()
                            delay(50)
                        }
                    }
                } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                    AppLog.put("TTS 预合成：章节排版超时（${LAYOUT_TIMEOUT_MS / 1000}s），跳过章 $index")
                    continue
                }
                if (textChapter.pages.isEmpty()) {
                    AppLog.put("TTS 预合成：章节排版异常（空页），跳过章 $index")
                    continue
                }
                // P1-1 修复：切分口径与播放端逐字一致（split("\n")+isNotEmpty，不 trim——
                // 排版注入的段首缩进参与播放端键，批量端 trim 掉即失配；反向修改播放端会错位进度账，禁选）
                val contentList = textChapter.getNeedReadAloud(0, task.readAloudByPage, 0)
                    .split("\n")
                    .filter { it.isNotEmpty() }
                for (text in contentList) {
                    val fileName = TtsCacheKeys.speakFileName(
                        engineId = task.httpTts?.id,
                        speechRate = task.speechRate,
                        voiceId = task.voiceKey,
                        chapterIndex = index,
                        chapterTitle = displayTitle,
                        unitText = text
                    )
                    unitList.add(PendingUnit(index, text, fileName))
                }
            }
            totalUnit = unitList.size
            if (unitList.isEmpty()) {
                finishTask(task, TtsPrebuildState.Phase.DONE, 0, 0, 0, "无可合成内容（正文未缓存或全部已命中）")
                return
            }
            // 逐单元合成（while 队列：租约延后单元重排队尾不谎报完成（P1-7）；失败可重试 1 次（P1-6））
            var done = 0
            var failed = 0
            var consecutiveIoFail = 0
            val queue = ArrayDeque(unitList)
            setState(TtsPrebuildState.Phase.RUNNING, 0, unitList.size)
            while (queue.isNotEmpty()) {
                currentCoroutineContext().ensureActive()
                if (flag.get()) {
                    finishTask(task, TtsPrebuildState.Phase.CANCELLED, done, unitList.size, failed, "已取消")
                    return
                }
                val unit = queue.removeFirst()
                // 播放优先租约（当前章+下一章；3 轮延后上限后强制执行）
                val (defer, rounds) = leaseDecision(
                    currentChapterIndex(), nextChapterIndex(), unit.chapterIndex, unit.deferRounds
                )
                if (defer) {
                    unit.deferRounds = rounds
                    queue.addLast(unit)
                    // TtsTrace 真机联调：播放优先租约延后（3 轮上限强制执行）
                    AppLog.putDebugWithTag(
                        AppLog.TAG_TTS_TRACE,
                        "prebuild 租约延后 ch=${unit.chapterIndex} rounds=$rounds cur=${currentChapterIndex()} next=${nextChapterIndex()}",
                        level = AppLog.Level.INFO
                    )
                    // 延后不计 done（P1-7 修复：防终态谎报整本完成）
                    continue
                }
                if (hasTargetFile(task, unit.fileName)) {
                    // E2/P1-3：以文件存在为唯一幂等判据（0 字节不算，见 hasTargetFile）——
                    // 修复名单在但文件被外部删除时 containsKey 单独判定的假阳性 DONE 虚报（红队 R3-1/R5-1）；
                    // 文件存在即跳过同时保留"播放端实时产物互认"（名单未命中的既有产物不重复合成，
                    // 比 design v1.1 的 contains&&has 联合判定更正确：后者会丢失互认导致重复合成）
                    // TtsTrace 真机联调：幂等跳过（命中复用证据）
                    AppLog.putDebugWithTag(
                        AppLog.TAG_TTS_TRACE,
                        "prebuild 幂等跳过 ch=${unit.chapterIndex} file=${unit.fileName.takeLast(16)} unit=${done + failed + 1}/${unitList.size}",
                        level = AppLog.Level.INFO
                    )
                    done++
                    updateProgress(task, gen, done + failed, unitList.size, failed)
                    continue
                }
                val target = targetFile(task, unit.fileName)
                val httpTts = task.httpTts
                if (httpTts == null) {
                    failed++
                    updateProgress(task, gen, done + failed, unitList.size, failed)
                    continue
                }
                val result = TtsSynthesizer.synthesizeToFile(
                    httpTts, unit.unitText, task.voiceKey, task.speechRate, target
                )
                when (result) {
                    is TtsSynthesizer.Result.Success -> {
                        reservedKeys[unit.fileName] = System.currentTimeMillis()
                        persistReservedKeysAsync()
                        consecutiveIoFail = 0
                        done++
                        // TtsTrace 真机联调：单元合成成功（保留名单登记证据）
                        AppLog.putDebugWithTag(
                            AppLog.TAG_TTS_TRACE,
                            "prebuild 合成完成 ch=${unit.chapterIndex} file=${unit.fileName.takeLast(16)} unit=${done + failed}/${unitList.size}",
                            level = AppLog.Level.INFO
                        )
                    }
                    is TtsSynthesizer.Result.Failure -> {
                        if (result.retryable && !unit.retried) {
                            // P1-6 修复：网络/引擎类失败原地重试 1 次（设计 §3.7.2 失败语义）
                            unit.retried = true
                            queue.addLast(unit)
                            AppLog.put("TTS 预合成重试（章 ${unit.chapterIndex}）：${result.reason}")
                        } else {
                            failed++
                            AppLog.put("TTS 预合成失败（章 ${unit.chapterIndex}）：${result.reason}")
                            if (!result.retryable && ++consecutiveIoFail >= 3) {
                                // IO/磁盘满类系统性故障：任务级中止（防逐单元空转）
                                finishTask(task, TtsPrebuildState.Phase.FAILED, done + failed, unitList.size, failed, "连续 IO 失败：${result.reason}")
                                return
                            }
                        }
                    }
                }
                updateProgress(task, gen, done + failed, unitList.size, failed)
            }
            finishTask(task, TtsPrebuildState.Phase.DONE, unitList.size, unitList.size, failed, "")
        } catch (e: kotlinx.coroutines.CancellationException) {
            finishTask(task, TtsPrebuildState.Phase.CANCELLED, totalUnit, totalUnit, 0, "已取消")
            throw e
        } catch (e: Exception) {
            AppLog.put("TTS 预合成任务异常：${e.message}")
            finishTask(task, TtsPrebuildState.Phase.FAILED, totalUnit, totalUnit, 0, e.message ?: "未知异常")
        }
    }

    /** 排版完成等待超时（P0-3）：防异常章无限挂起 */
    private const val LAYOUT_TIMEOUT_MS = 60_000L

    /** 保留名单条目 TTL（E2：超过 30 天不再保护清理，防名单无限膨胀） */
    private const val RESERVED_TTL_MS = 30L * 24 * 60 * 60 * 1000

    /**
     * E5：chapterTitle 键因子同源解析帮助函数（与播放端 textChapter.title 口径一致：
     * ReadBook.kt:1331 同款 getDisplayTitle 调用——去换行/简繁转换/替换规则）
     */
    private fun resolveChapterTitle(
        book: Book,
        bookChapter: io.legado.app.data.entities.BookChapter,
        processor: ContentProcessor
    ): String {
        return bookChapter.getDisplayTitle(
            processor.getTitleReplaceRules(),
            book.getUseReplaceRule(),
            replaceBook = book.toReplaceBook()
        )
    }

    private fun updateProgress(task: PrebuildTask, gen: Long, current: Int, total: Int, failed: Int) {
        _state.value = TtsPrebuildState(gen, task.bookKey, task.book.name, TtsPrebuildState.Phase.RUNNING, current, total, failed)
    }

    /** 终态落笔（单写者）+定时复位 IDLE；failedCount 透传真实失败数（P2-16：原硬编码 0 终态丢失统计） */
    private fun finishTask(task: PrebuildTask, phase: TtsPrebuildState.Phase, current: Int, total: Int, failedCount: Int, msg: String) {
        // TtsTrace 真机联调：任务终态（取消/完成/失败判定证据）
        AppLog.putDebugWithTag(
            AppLog.TAG_TTS_TRACE,
            "prebuild 终态 phase=$phase book=${task.book.name.takeLast(16)} unit=$current/$total failed=$failedCount msg=$msg",
            level = AppLog.Level.INFO
        )
        _state.value = TtsPrebuildState(task.generation, task.bookKey, task.book.name, phase, current, total, failedCount, msg)
        scope.launch {
            delay(10_000L)
            if (_state.value.generation == task.generation &&
                _state.value.phase != TtsPrebuildState.Phase.IDLE &&
                _state.value.phase != TtsPrebuildState.Phase.RUNNING
            ) {
                _state.value = TtsPrebuildState()
            }
        }
    }

    /** 租约数据源：当前朗读章/预下载下一章（跨书任务无租约） */
    private fun currentChapterIndex(): Int? {
        val bookUrl = ReadBook.book?.bookUrl ?: return null
        val taskBookKey = _state.value.bookKey
        if (bookUrl != taskBookKey) return null
        return ReadBook.curTextChapter?.chapter?.index
    }

    private fun nextChapterIndex(): Int? {
        val bookUrl = ReadBook.book?.bookUrl ?: return null
        val taskBookKey = _state.value.bookKey
        if (bookUrl != taskBookKey) return null
        return ReadBook.nextTextChapter?.chapter?.index
    }

    private fun hasTargetFile(task: PrebuildTask, fileName: String): Boolean {
        // P1-5：0 字节文件（播放端写流失败残留）不算命中，防幂等误跳过计 done
        val f = File(ttsFolderPath(task), "$fileName.mp3")
        return f.exists() && f.length() > 0
    }

    private fun targetFile(task: PrebuildTask, fileName: String): File {
        return File(ttsFolderPath(task), "$fileName.mp3")
    }

    /** TTS 缓存目录（与 HttpReadAloudService.ttsFolderPath 同口径：cacheDir/httpTTS） */
    private fun ttsFolderPath(task: PrebuildTask): String {
        val path = "${appCtx.cacheDir.absolutePath}/httpTTS/"
        val dir = File(path)
        if (!dir.exists()) dir.mkdirs()
        return path
    }

    /** 任务是否运行中（外部查询：能力门控/管理页） */
    fun isRunning(bookKey: String? = null): Boolean {
        val s = _state.value
        val phaseMatched = s.phase == TtsPrebuildState.Phase.RUNNING || s.phase == TtsPrebuildState.Phase.SCANNING
        return phaseMatched && (bookKey == null || s.bookKey == bookKey)
    }

    private const val HTTP_TTS_DIR = "httpTTS"
}
