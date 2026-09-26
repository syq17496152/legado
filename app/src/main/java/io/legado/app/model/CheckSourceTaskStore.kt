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

/** 校验结果形态（标准源 / 正文已试读） */
enum class CheckSourceResultKind {
    STANDARD,
    CONTENT_PARSED,
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
    val resultKind: CheckSourceResultKind = CheckSourceResultKind.STANDARD,
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
        resultKind: CheckSourceResultKind = CheckSourceResultKind.STANDARD,
    ) {
        completeItem(
            origin = origin,
            status = CheckSourceItemStatus.PASSED,
            message = "",
            durationMillis = durationMillis,
            score = score,
            resultKind = resultKind,
        )
    }

    @Synchronized
    fun markFailed(origin: String, message: String, durationMillis: Long) {
        completeItem(
            origin = origin,
            status = CheckSourceItemStatus.FAILED,
            message = message,
            durationMillis = durationMillis,
        )
    }

    @Synchronized
    fun markBlocked(origin: String, durationMillis: Long) {
        completeItem(
            origin = origin,
            status = CheckSourceItemStatus.BLOCKED,
            message = "",
            durationMillis = durationMillis,
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
        resultKind: CheckSourceResultKind = CheckSourceResultKind.STANDARD,
    ) {
        updateItem(origin) { item ->
            item.copy(
                status = status,
                message = message,
                durationMillis = durationMillis,
                score = score,
                resultKind = resultKind,
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
        }

        CheckSourceTaskStatus.CANCELLED ->
            "已取消 · 已完成 $processedCount/$totalCount · 通过 $passedCount · 失败 $failedCount"
    }
}