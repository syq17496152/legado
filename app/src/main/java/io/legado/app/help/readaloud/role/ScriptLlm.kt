package io.legado.app.help.readaloud.role

import io.legado.app.help.llm.LlmClient
import io.legado.app.help.llm.LlmRuntime

/**
 * 3.5 剧本生成的推理缝（design AD-11 + AD-25）：
 * 仲裁只依赖这三个动作，不关心模型跑在主进程还是 `:llm` 独立进程。
 *
 * - [RuntimeScriptLlm]：进程内运行时（历史路径 / 单测替身用）；
 * - [ClientScriptLlm]：跨进程客户端（生产路径，native 崩溃不传染朗读）。
 */
interface ScriptLlm {

    /** 模型是否可立即用于仲裁（不可用 ⇒ 只产出规则剧本，不标降级） */
    suspend fun ready(): Boolean

    suspend fun setSystemPrompt(prompt: String): LlmRuntime.Result

    suspend fun generate(prompt: String, predictLength: Int = LlmRuntime.DEFAULT_PREDICT_LENGTH): LlmRuntime.Result
}

/** 进程内适配（`LlmRuntime` 本身不实现接口，避免默认参数签名耦合） */
class RuntimeScriptLlm(private val runtime: LlmRuntime) : ScriptLlm {

    override suspend fun ready(): Boolean = runtime.isReady

    override suspend fun setSystemPrompt(prompt: String): LlmRuntime.Result =
        runtime.setSystemPrompt(prompt)

    override suspend fun generate(prompt: String, predictLength: Int): LlmRuntime.Result =
        runtime.generate(prompt, predictLength)
}

/**
 * 跨进程适配：`systemPrompt` 在客户端侧记住，每次 generate 随请求带给 `:llm`
 * （`:llm` 不读 prefs，AD-25；服务侧在同一 KV 上重设 system prompt 成本很低）。
 */
class ClientScriptLlm(private val client: LlmClient) : ScriptLlm {

    private var systemPrompt: String = ""

    override suspend fun ready(): Boolean =
        runCatching { client.ensureConnected() && client.status().ready }.getOrDefault(false)

    override suspend fun setSystemPrompt(prompt: String): LlmRuntime.Result {
        systemPrompt = prompt
        return LlmRuntime.Result()
    }

    override suspend fun generate(prompt: String, predictLength: Int): LlmRuntime.Result {
        val job = client.generate(
            prompt = prompt,
            systemPrompt = systemPrompt,
            predictLength = predictLength
        )
        return LlmRuntime.Result(
            text = job.text,
            tokenCount = job.tokenCount,
            elapsedMs = job.elapsedMs,
            errorCode = errorCodeOf(job.errorCode),
            message = job.message
        )
    }

    /** 跨进程错误码是字符串 ⇒ 必须映射回运行时枚举，未知一律 UNKNOWN（不吞成"成功"） */
    private fun errorCodeOf(code: String): LlmRuntime.LlmErrorCode? {
        if (code.isBlank()) return null
        return runCatching { LlmRuntime.LlmErrorCode.valueOf(code) }
            .getOrElse { LlmRuntime.LlmErrorCode.UNKNOWN }
    }
}
