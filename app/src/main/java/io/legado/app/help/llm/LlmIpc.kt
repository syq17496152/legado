package io.legado.app.help.llm

import androidx.annotation.Keep

/**
 * 2.5 跨进程消息契约（design AD-25）。
 *
 * 只定义**小消息**：一次请求的全部大文本走 `LlmJobs` 落盘，Messenger 只传 jobId/路径/参数。
 */
object LlmIpc {

    const val MSG_STATUS = 1
    const val MSG_LOAD = 2
    const val MSG_GENERATE = 3
    const val MSG_CANCEL = 4
    const val MSG_RELEASE = 5
    /** 自检（加载 + 固定长度生成）：**必须在推理进程内跑**，主进程不得加载 native（AD-11） */
    const val MSG_BENCH = 6

    /** 回复里 `arg1` 的取值 */
    const val REPLY_OK = 0
    const val REPLY_FAIL = 1

    const val KEY_JOB_ID = "jobId"
    const val KEY_MODEL_PATH = "modelPath"
    const val KEY_SYSTEM_PROMPT = "systemPrompt"
    const val KEY_PREDICT_LENGTH = "predictLength"

    /** 回执字段（全部小值；正文与结果正文走文件） */
    const val KEY_OK = "ok"
    const val KEY_ERROR_CODE = "errorCode"
    const val KEY_MESSAGE = "message"
    const val KEY_TOKEN_COUNT = "tokenCount"
    const val KEY_ELAPSED_MS = "elapsedMs"
    const val KEY_PHASE = "phase"
    /** 自检回执额外字段（`MSG_BENCH` 专用） */
    const val KEY_TOK_PER_SEC = "tokPerSec"
    const val KEY_LOAD_MS = "loadMs"
    const val KEY_GENERATE_MS = "generateMs"

    /** 客户端侧错误码（运行时错误码之外的两类） */
    const val ERROR_CANCELLED = "CANCELLED"
    const val ERROR_UNAVAILABLE = "UNAVAILABLE"
    const val ERROR_BAD_JOB = "BAD_JOB"

    /** 默认生成 token 上限（与 [LlmRuntime.DEFAULT_PREDICT_LENGTH] 对齐） */
    const val DEFAULT_PREDICT_LENGTH = 256
}

/**
 * 落盘结果（Gson 序列化 ⇒ 必须 `@Keep`，否则 R8 会剥字段签名）。
 * 文本也随结果落盘而不是走 Binder：剧本 JSON 可能上千字，Binder 单次上限约 1MB。
 */
@Keep
data class LlmJobResult(
    val ok: Boolean = false,
    val text: String = "",
    val errorCode: String = "",
    val message: String = "",
    val tokenCount: Int = 0,
    val elapsedMs: Long = 0
) {

    companion object {

        fun of(result: LlmRuntime.Result): LlmJobResult = LlmJobResult(
            ok = result.ok,
            text = result.text,
            errorCode = result.errorCode?.name.orEmpty(),
            message = result.message,
            tokenCount = result.tokenCount,
            elapsedMs = result.elapsedMs
        )

        fun failure(code: String, message: String = ""): LlmJobResult =
            LlmJobResult(ok = false, errorCode = code, message = message)
    }
}

/**
 * 自检结果（`MSG_BENCH` 回执）。
 *
 * 为什么要在推理进程里跑自检：`LlmRuntime` 一构造就会加载 llama.cpp native 与整个 GGUF
 * （推荐档 396MB）。主进程若直接跑自检，等于把「native 重内存」搬进正在朗读/阅读的进程，
 * 违背 AD-11（隔离的意义就是 native OOM/crash 不传染主进程）。
 *
 * 纯数据 + 基本类型工厂，便于 JVM 单测锁定 `ok` 判据（避免"0 tok/s 却报成功"）。
 */
@Keep
data class LlmBenchResult(
    val ok: Boolean = false,
    val loadMs: Long = 0,
    val generateMs: Long = 0,
    val tokenCount: Int = 0,
    val tokPerSec: Float = 0f,
    val errorCode: String = "",
    val message: String = ""
) {

    companion object {

        /**
         * `ok` 判据：无错误码 **且** 真的测出了速度。
         * 只有错误码为空还不够——加载失败也可能漏填错误码，0 tok/s 一律视为没跑通。
         */
        fun of(
            loadMs: Long,
            generateMs: Long,
            tokenCount: Int,
            tokPerSec: Float,
            errorCode: String,
            message: String
        ): LlmBenchResult = LlmBenchResult(
            ok = errorCode.isBlank() && tokPerSec > 0f,
            loadMs = loadMs,
            generateMs = generateMs,
            tokenCount = tokenCount,
            tokPerSec = tokPerSec,
            errorCode = errorCode,
            message = message
        )

        fun of(bench: LlmRuntime.Bench): LlmBenchResult = of(
            loadMs = bench.loadMs,
            generateMs = bench.generateMs,
            tokenCount = bench.tokenCount,
            tokPerSec = bench.tokPerSec,
            errorCode = bench.errorCode?.name.orEmpty(),
            message = bench.message
        )

        fun failure(code: String, message: String = ""): LlmBenchResult =
            LlmBenchResult(ok = false, errorCode = code, message = message)
    }
}

/** 服务状态回执（主进程据此判断"能不能用"，不读 prefs） */
@Keep
data class LlmStatus(
    val phase: String = LlmRuntime.Phase.UNINITIALIZED.name,
    val modelPath: String = "",
    val errorCode: String = "",
    val message: String = ""
) {

    val ready: Boolean get() = phase == LlmRuntime.Phase.READY.name

    companion object {

        fun of(snapshot: LlmRuntime.Snapshot): LlmStatus = LlmStatus(
            phase = snapshot.phase.name,
            modelPath = snapshot.modelPath,
            errorCode = snapshot.errorCode?.name.orEmpty(),
            message = snapshot.message
        )

        fun unavailable(message: String = "推理进程未连接"): LlmStatus =
            LlmStatus(
                phase = LlmRuntime.Phase.UNINITIALIZED.name,
                errorCode = LlmIpc.ERROR_UNAVAILABLE,
                message = message
            )
    }
}
