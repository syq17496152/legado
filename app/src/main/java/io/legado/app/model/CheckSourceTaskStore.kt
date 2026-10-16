package io.legado.app.model

import io.legado.app.data.entities.BookSourcePart
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.roundToInt

/**
 * 校验任务整体状态
 */
enum class CheckSourceTaskStatus {
    IDLE,
    RUNNING,
    COMPLETED,
    CANCELLED,
}

/**
 * 单个书源的校验状态
 */
enum class CheckSourceItemStatus {
    WAITING,
    RUNNING,
    PASSED,
    FAILED,
    BLOCKED,
    CANCELLED,
}

/** 校验过程七阶段（与 NG `CheckSourceTaskStore` 同口径） */
enum class CheckSourceStage {
    PREPARING,
    DOMAIN,
    SEARCH,
    DISCOVERY,
    INFO,
    CATALOG,
    CONTENT,
}

/** 阶段中文名（UI 展示单源，避免在 Compose 侧散落 when 分支） */
val CheckSourceStage.displayName: String
    get() = when (this) {
        CheckSourceStage.PREPARING -> "准备"
        CheckSourceStage.DOMAIN -> "域名"
        CheckSourceStage.SEARCH -> "搜索"
        CheckSourceStage.DISCOVERY -> "发现"
        CheckSourceStage.INFO -> "详情"
        CheckSourceStage.CATALOG -> "目录"
        CheckSourceStage.CONTENT -> "正文"
    }

data class CheckSourceItemState(
    val origin: String,
    val sourceName: String,
    val sourceType: Int,
    val status: CheckSourceItemStatus = CheckSourceItemStatus.WAITING,
    val stage: CheckSourceStage = CheckSourceStage.PREPARING,
    val message: String = "",
    val durationMillis: Long = 0L,
    /** 结果评分（书源 = 既有权重口径 `BookSource.weight`；0 = 未产出） */
    val score: Int = 0,
    /**
     * 失败归因（NEW-102）：仅 [CheckSourceItemStatus.FAILED] 有实义 ——
     * 由服务侧 `QualityReportApplier.failureKindOf(e)` 从异常推出（超时 / 脚本 / 站点），
     * `NONE` 表示**无异常短路**的维度判定失败（规则层）。
     */
    val failureKind: FailureKind = FailureKind.NONE,
    /** 存疑跳过（不误杀口径）：[CheckSourceItemStatus.BLOCKED] 且因静态存疑原因放行时为 true */
    val suspect: Boolean = false,
    val updatedAt: Long = 0L,
)

data class CheckSourceTaskState(
    val runId: Long = 0L,
    val status: CheckSourceTaskStatus = CheckSourceTaskStatus.IDLE,
    val items: List<CheckSourceItemState> = emptyList(),
    val currentOrigin: String? = null,
    val currentSourceName: String = "",
    val currentStage: CheckSourceStage = CheckSourceStage.PREPARING,
    val startedAtMillis: Long = 0L,
    val finishedAtMillis: Long = 0L,
    val resultsAcknowledged: Boolean = false,
) {
    val totalCount: Int get() = items.size
    val passedCount: Int get() = items.count { it.status == CheckSourceItemStatus.PASSED }
    val failedCount: Int get() = items.count { it.status == CheckSourceItemStatus.FAILED }
    val blockedCount: Int get() = items.count { it.status == CheckSourceItemStatus.BLOCKED }
    val processedCount: Int
        get() = items.count {
            it.status == CheckSourceItemStatus.PASSED ||
                it.status == CheckSourceItemStatus.FAILED ||
                it.status == CheckSourceItemStatus.BLOCKED ||
                it.status == CheckSourceItemStatus.CANCELLED
        }
    val remainingCount: Int get() = (totalCount - processedCount).coerceAtLeast(0)
    val progressFraction: Float
        get() = if (totalCount <= 0) 0f else processedCount.toFloat() / totalCount

    /** 结果评分：已完成项中已产出评分的均分（无评分数据时为 null） */
    val averageScore: Int?
        get() {
            val scored = items.filter {
                it.status == CheckSourceItemStatus.PASSED && it.score > 0
            }
            if (scored.isEmpty()) return null
            return (scored.sumOf { it.score }.toFloat() / scored.size).roundToInt()
        }

    // ---- NEW-102 失败归因计数（超时 / 脚本 / 站点 / 规则） ----
    // 口径：**只统计 FAILED 项**（PASSED/BLOCKED/CANCELLED 即使携带 failureKind 也不计入），
    // 否则「探测中已被写过归因、最终又判存疑跳过」的项会被重复计入失败。
    val timeoutCount: Int get() = failedCountOf(FailureKind.TIMEOUT)
    val scriptFailCount: Int get() = failedCountOf(FailureKind.SCRIPT)
    val siteFailCount: Int get() = failedCountOf(FailureKind.NETWORK)

    /** 规则层失败：无异常短路的**维度判定失败**（如目录取样空 / 规则为空），不属于站点/脚本/超时三类 */
    val ruleFailCount: Int get() = failedCountOf(FailureKind.NONE)

    /** 存疑跳过计数（不误杀口径：静态存疑源不计入失败与归因） */
    val suspectCount: Int get() = items.count { it.suspect }

    private fun failedCountOf(kind: FailureKind): Int =
        items.count { it.status == CheckSourceItemStatus.FAILED && it.failureKind == kind }
}

/**
 * 进程内保存最近一次书源校验任务（Q-N5，移植 NG `CheckSourceTaskStore`）。
 *
 * 服务与页面共享同一份**结构化**状态：Activity 重建 / 前后台切换不再依赖一次性 EventBus 文本，
 * 页内横幅与列表行都能读到「当前阶段 + 通过/失败计数 + 评分」。
 *
 * 与既有 `SourceQualityChecker` 的关系（本仓叠加口径）：探测与判定仍由 `SourceQualityChecker` /
 * `CheckSourceService` 既有链路负责，本对象**只做过程与结果的观测**（零写库副作用、不改判定结论）；
 * 评分沿用既有权重口径（`BookSource.weight`），不新造分数体系。
 *
 * 已知上限（如实披露）：搜索/发现两阶段并发执行，[currentStage] 是「最近一次事件」的展示提示，
 * 不以它为判定依据；单源自身的阶段看 [CheckSourceItemState.stage]。
 */
object CheckSourceTaskStore {
    private val mutableState = MutableStateFlow(CheckSourceTaskState())
    val state: StateFlow<CheckSourceTaskState> = mutableState.asStateFlow()

    @Synchronized
    fun begin(sources: List<BookSourcePart>) {
        val now = System.currentTimeMillis()
        mutableState.value = CheckSourceTaskState(
            runId = now,
            status = CheckSourceTaskStatus.RUNNING,
            startedAtMillis = now,
            items = sources.distinctBy(BookSourcePart::bookSourceUrl).map { source ->
                CheckSourceItemState(
                    origin = source.bookSourceUrl,
                    sourceName = source.bookSourceName,
                    sourceType = source.bookSourceType,
                )
            },
        )
    }

    @Synchronized
    fun markRunning(origin: String, sourceName: String, sourceType: Int) {
        updateItem(origin) { item ->
            item.copy(
                sourceName = sourceName,
                sourceType = sourceType,
                status = CheckSourceItemStatus.RUNNING,
                stage = CheckSourceStage.PREPARING,
                message = "",
                durationMillis = 0L,
                score = 0,
                updatedAt = System.currentTimeMillis(),
            )
        }
        updateCurrent(origin, sourceName, CheckSourceStage.PREPARING)
    }

    @Synchronized
    fun markStage(origin: String, sourceName: String, stage: CheckSourceStage) {
        updateItem(origin) { item ->
            item.copy(
                status = CheckSourceItemStatus.RUNNING,
                stage = stage,
                updatedAt = System.currentTimeMillis(),
            )
        }
        updateCurrent(origin, sourceName, stage)
    }

    @Synchronized
    fun markPassed(
        origin: String,
        durationMillis: Long,
        score: Int = 0,
    ) {
        completeItem(
            origin = origin,
            status = CheckSourceItemStatus.PASSED,
            message = "",
            durationMillis = durationMillis,
            score = score,
        )
    }

    /**
     * 标记失败项。NEW-102：[failureKind] 由调用方从异常推出（`QualityReportApplier.failureKindOf`），
     * 缺省 `NONE` = 维度判定失败（规则层）——横幅 / CSV / REST-MCP 出参的归因计数都读这一个字段。
     */
    @Synchronized
    fun markFailed(
        origin: String,
        message: String,
        durationMillis: Long,
        failureKind: FailureKind = FailureKind.NONE,
    ) {
        completeItem(
            origin = origin,
            status = CheckSourceItemStatus.FAILED,
            message = message,
            durationMillis = durationMillis,
            failureKind = failureKind,
        )
    }

    /**
     * 标记「跳过」（不适用 / 存疑）项。
     *
     * [message] 传面向用户的原因（如 `存疑（登录依赖）`）——横幅「跳过 N」之外还需**逐项可见**
     * 为什么被跳过，否则用户在列表里只看到一条空消息。
     *
     * NEW-102：[suspect] 缺省 true —— 本入口的现有调用点（`CheckSourceService.checkSource`）只在
     * 静态存疑源放行时进入，故默认即「存疑跳过」，该计数与失败归因**互斥**（不误杀口径）。
     */
    @Synchronized
    fun markBlocked(
        origin: String,
        durationMillis: Long,
        message: String = "",
        suspect: Boolean = true,
    ) {
        completeItem(
            origin = origin,
            status = CheckSourceItemStatus.BLOCKED,
            message = message,
            durationMillis = durationMillis,
            suspect = suspect,
        )
    }

    @Synchronized
    fun finish(cancelled: Boolean) {
        val current = mutableState.value
        if (current.status != CheckSourceTaskStatus.RUNNING) return
        val now = System.currentTimeMillis()
        val items = if (cancelled) {
            current.items.map { item ->
                if (item.status == CheckSourceItemStatus.RUNNING) {
                    item.copy(
                        status = CheckSourceItemStatus.CANCELLED,
                        message = "",
                        updatedAt = now,
                    )
                } else {
                    item
                }
            }
        } else {
            current.items
        }
        mutableState.value = current.copy(
            status = if (cancelled) {
                CheckSourceTaskStatus.CANCELLED
            } else {
                CheckSourceTaskStatus.COMPLETED
            },
            items = items,
            currentOrigin = null,
            currentSourceName = "",
            finishedAtMillis = now,
            resultsAcknowledged = false,
        )
    }

    @Synchronized
    fun markResultsAcknowledged() {
        val current = mutableState.value
        if (current.status == CheckSourceTaskStatus.RUNNING || current.resultsAcknowledged) return
        mutableState.value = current.copy(resultsAcknowledged = true)
    }

    private fun completeItem(
        origin: String,
        status: CheckSourceItemStatus,
        message: String,
        durationMillis: Long,
        score: Int = 0,
        failureKind: FailureKind = FailureKind.NONE,
        suspect: Boolean = false,
    ) {
        updateItem(origin) { item ->
            item.copy(
                status = status,
                message = message,
                durationMillis = durationMillis,
                score = score,
                failureKind = failureKind,
                suspect = suspect,
                updatedAt = System.currentTimeMillis(),
            )
        }
    }

    private fun updateCurrent(origin: String, sourceName: String, stage: CheckSourceStage) {
        val current = mutableState.value
        if (current.status != CheckSourceTaskStatus.RUNNING) return
        mutableState.value = current.copy(
            currentOrigin = origin,
            currentSourceName = sourceName,
            currentStage = stage,
        )
    }

    private inline fun updateItem(
        origin: String,
        transform: (CheckSourceItemState) -> CheckSourceItemState,
    ) {
        val current = mutableState.value
        if (current.status != CheckSourceTaskStatus.RUNNING) return
        val index = current.items.indexOfFirst { it.origin == origin }
        if (index < 0) return
        val updated = current.items.toMutableList()
        updated[index] = transform(updated[index])
        mutableState.value = current.copy(items = updated)
    }
}

/**
 * 列表行过程文案：仅「进行中」的源有值（如 `校验中·搜索`），其余返回空串
 * ⇒ 调用方回落到既有副标题口径（校验消息 / 引用书籍数），未校验的源不产生噪声。
 */
fun CheckSourceItemState.runningStageText(): String =
    if (status == CheckSourceItemStatus.RUNNING) "校验中·${stage.displayName}" else ""

/**
 * 页内横幅文案（过程态）：`校验中 3/20 · 通过 2 · 失败 1 · 正在校验：源名（搜索）`
 * 返回 null ⇒ 无横幅（IDLE 或结果已被确认）。
 */
fun CheckSourceTaskState.bannerText(): String? {
    if (resultsAcknowledged) return null
    return when (status) {
        CheckSourceTaskStatus.IDLE -> null
        CheckSourceTaskStatus.RUNNING -> buildString {
            append("校验中 $processedCount/$totalCount")
            append(" · 通过 $passedCount · 失败 $failedCount")
            if (currentSourceName.isNotBlank()) {
                append(" · 正在校验：$currentSourceName（${currentStage.displayName}）")
            }
        }

        CheckSourceTaskStatus.COMPLETED -> buildString {
            append("校验完成 $totalCount 项 · 通过 $passedCount · 失败 $failedCount")
            if (blockedCount > 0) append(" · 跳过 $blockedCount")
            averageScore?.let { append(" · 平均分 $it") }
            // NEW-102：失败归因摘要（超时 / 脚本 / 站点 / 规则）——用户可直接区分
            // 「源站不可达」与「探测超时」「规则缺陷」，不必逐条点开源看消息
            failureBreakdownText().takeIf { it.isNotBlank() }?.let { append(" · 归因 $it") }
        }

        CheckSourceTaskStatus.CANCELLED ->
            "已取消 · 已完成 $processedCount/$totalCount · 通过 $passedCount · 失败 $failedCount"
    }
}

/**
 * 归因摘要文案（NEW-102，**单源口径**）：`超时 3 · 脚本 1 · 站点 5 · 规则 2`；无失败则空串。
 *
 * 三端共用同一份文案：页内横幅（[bannerText]）/ 导出 CSV（`formatCheckSourceReportCsv`）/
 * REST-MCP 出参（`SourceDebugKernel.checkSourceReport`）——避免各处各自拼串导致口径漂移。
 */
fun CheckSourceTaskState.failureBreakdownText(): String =
    listOfNotNull(
        "超时 $timeoutCount".takeIf { timeoutCount > 0 },
        "脚本 $scriptFailCount".takeIf { scriptFailCount > 0 },
        "站点 $siteFailCount".takeIf { siteFailCount > 0 },
        "规则 $ruleFailCount".takeIf { ruleFailCount > 0 },
    ).joinToString(" · ")

/**
 * 单源归因短文案（NEW-102）：仅 [CheckSourceItemStatus.FAILED] 有值（`超时` / `脚本` / `站点` / `规则`），
 * 其余状态一律空串 ⇒ 通过/跳过行不产生噪声。供 CSV 逐行导出使用。
 */
fun CheckSourceItemState.failureKindText(): String {
    if (status != CheckSourceItemStatus.FAILED) return ""
    return when (failureKind) {
        FailureKind.TIMEOUT -> "超时"
        FailureKind.SCRIPT -> "脚本"
        FailureKind.NETWORK -> "站点"
        FailureKind.NONE -> "规则"
    }
}