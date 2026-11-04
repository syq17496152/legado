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
