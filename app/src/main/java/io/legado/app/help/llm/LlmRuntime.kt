package io.legado.app.help.llm

import android.content.Context
import com.arm.aichat.AiChat
import com.arm.aichat.InferenceEngine
import com.arm.aichat.UnsupportedArchitectureException
import com.arm.aichat.isModelLoaded
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.catch

/**
 * 2.2 端侧 LLM 运行时业务门面（只封装官方 llama.cpp Kotlin binding，**不写 native/JNI**）。
 *
 * 三条硬约束（design AD-04 / I-1 / I-5）：
 * - **单队列串行**：一次只允许一个加载/生成在跑（`Mutex`），避免多任务抢同一 context；
 * - **失败必带可执行原因**：错误码映射成 [LlmErrorCode]，调用方据此决定回落规则还是提示用户；
 * - **起播路径禁止引用**：本类只被后台剧本/规则助手使用（Grep 门禁 I-1）。
 *
 * 已知上限：官方 binding 的 `sendUserPrompt` 是流式 token，本门面按"整段收集"暴露给业务
 * （剧本仲裁只需要完整 JSON）；需要边生成边消费时再补流式接口。
 */
class LlmRuntime(
    private val binding: LlmBinding,
    private val clock: () -> Long = { System.currentTimeMillis() }
) {

    enum class Phase { UNINITIALIZED, LOADING, READY, GENERATING, ERROR }

    data class Snapshot(
        val phase: Phase,
        val modelPath: String = "",
        val errorCode: LlmErrorCode? = null,
        val message: String = ""
    )

    enum class LlmErrorCode {
        UNSUPPORTED_ARCH,
        NOT_LOADED,
        TIMEOUT,
        EMPTY_OUTPUT,
        IO,
        UNKNOWN
    }

    data class Result(
        val text: String = "",
        val tokenCount: Int = 0,
        val elapsedMs: Long = 0,
        val errorCode: LlmErrorCode? = null,
        val message: String = ""
    ) {
        /** 只看错误码：加载成功没有文本、生成空输出已单独映射 EMPTY_OUTPUT */
        val ok: Boolean get() = errorCode == null
    }

    data class Bench(
        val loadMs: Long,
        val generateMs: Long,
        val tokenCount: Int,
        val tokPerSec: Float,
        val errorCode: LlmErrorCode? = null,
        val message: String = ""
    ) {
        val ok: Boolean get() = errorCode == null && tokPerSec > 0f
    }

    private val gate = Mutex()
    private val _snapshot = MutableStateFlow(Snapshot(Phase.UNINITIALIZED))

    /** 状态机快照（UI/自检共用；只读） */
    val snapshot: StateFlow<Snapshot> = _snapshot

    val loadedModelPath: String get() = _snapshot.value.modelPath

    val isReady: Boolean
        get() = _snapshot.value.phase.let { it == Phase.READY || it == Phase.GENERATING } &&
                binding.currentState.isModelLoaded

    /**
     * 加载模型（幂等：同路径已就绪直接返回）。
     * 失败会把状态推进到 [Phase.ERROR] 并给出错误码，不抛给调用方（后台任务不能因一次失败崩掉）。
     */
    suspend fun load(modelPath: String, timeoutMs: Long = LOAD_TIMEOUT_MS): Result =
        gate.withLock {
            if (_snapshot.value.phase == Phase.READY && _snapshot.value.modelPath == modelPath) {
                return@withLock Result(elapsedMs = 0)
            }
            _snapshot.value = Snapshot(Phase.LOADING, modelPath)
            val started = clock()
            try {
                withTimeout(timeoutMs) { binding.loadModel(modelPath) }
                _snapshot.value = Snapshot(Phase.READY, modelPath)
                Result(elapsedMs = clock() - started)
            } catch (e: TimeoutCancellationException) {
                fail(LlmErrorCode.TIMEOUT, "加载模型超时（${timeoutMs}ms）")
                Result(elapsedMs = clock() - started, errorCode = LlmErrorCode.TIMEOUT, message = "加载模型超时")
            } catch (e: UnsupportedArchitectureException) {
                fail(LlmErrorCode.UNSUPPORTED_ARCH, "模型架构不受支持：${modelPath}")
                Result(
                    elapsedMs = clock() - started,
                    errorCode = LlmErrorCode.UNSUPPORTED_ARCH,
                    message = "模型架构不受支持"
                )
            } catch (e: Exception) {
                fail(LlmErrorCode.UNKNOWN, e.localizedMessage ?: e.javaClass.simpleName)
                Result(
                    elapsedMs = clock() - started,
                    errorCode = LlmErrorCode.UNKNOWN,
                    message = e.localizedMessage ?: e.javaClass.simpleName
                )
            }
        }

    /** 设置 system prompt（加载后调用；失败不改变 READY 状态） */
    suspend fun setSystemPrompt(prompt: String): Result = gate.withLock {
        if (!binding.currentState.isModelLoaded) {
            return@withLock Result(errorCode = LlmErrorCode.NOT_LOADED, message = "模型未加载")
        }
        runCatching { binding.setSystemPrompt(prompt) }
            .onFailure { return@withLock Result(errorCode = LlmErrorCode.UNKNOWN, message = it.message.orEmpty()) }
        Result()
    }

    /**
     * 生成一段完整文本（内部收集官方流式 token）。
     * 超时/空输出/未加载都返回错误码，调用方据此回落规则结果。
     */
    suspend fun generate(
        prompt: String,
        predictLength: Int = DEFAULT_PREDICT_LENGTH,
        timeoutMs: Long = GENERATE_TIMEOUT_MS
    ): Result = gate.withLock {
        if (!binding.currentState.isModelLoaded) {
            return@withLock Result(errorCode = LlmErrorCode.NOT_LOADED, message = "模型未加载")
        }
        _snapshot.value = Snapshot(Phase.GENERATING, _snapshot.value.modelPath)
        val started = clock()
        val sb = StringBuilder()
        var tokens = 0
        try {
            withTimeout(timeoutMs) {
                binding.sendUserPrompt(prompt, predictLength)
                    .catch { throw it }
                    .collect { piece ->
                        if (piece.isNotEmpty()) {
                            sb.append(piece)
                            tokens += 1
                        }
                    }
            }
            val text = sb.toString().trim()
            _snapshot.value = Snapshot(Phase.READY, _snapshot.value.modelPath)
            if (text.isEmpty()) {
                Result(
                    elapsedMs = clock() - started,
                    tokenCount = tokens,
                    errorCode = LlmErrorCode.EMPTY_OUTPUT,
                    message = "模型没有返回内容"
                )
            } else {
                Result(text = text, elapsedMs = clock() - started, tokenCount = tokens)
            }
        } catch (e: TimeoutCancellationException) {
            fail(LlmErrorCode.TIMEOUT, "生成超时（${timeoutMs}ms）")
            Result(
                elapsedMs = clock() - started,
                tokenCount = tokens,
                errorCode = LlmErrorCode.TIMEOUT,
                message = "生成超时"
            )
        } catch (e: Exception) {
            fail(LlmErrorCode.UNKNOWN, e.localizedMessage ?: e.javaClass.simpleName)
            Result(
                elapsedMs = clock() - started,
                tokenCount = tokens,
                errorCode = LlmErrorCode.UNKNOWN,
                message = e.localizedMessage ?: e.javaClass.simpleName
            )
        }
    }

    /**
     * 自检基准（2.4 设置页 / 1.6 POC 用）：加载耗时 + 固定长度生成的 tok/s。
     * 不做"标准答案"校验，只回答"这台机器跑不跑得动"。
     */
    suspend fun benchmark(
        modelPath: String,
        prompt: String = BENCH_PROMPT,
        predictLength: Int = BENCH_TOKENS
    ): Bench {
        val load = load(modelPath)
        if (!load.ok && load.errorCode != null) {
            return Bench(loadMs = load.elapsedMs, generateMs = 0, tokenCount = 0, tokPerSec = 0f,
                errorCode = load.errorCode, message = load.message)
        }
        val gen = generate(prompt, predictLength)
        val tokPerSec = if (gen.elapsedMs > 0) {
            gen.tokenCount * 1000f / gen.elapsedMs
        } else {
            0f
        }
        return Bench(
            loadMs = load.elapsedMs,
            generateMs = gen.elapsedMs,
            tokenCount = gen.tokenCount,
            tokPerSec = tokPerSec,
            errorCode = gen.errorCode,
            message = gen.message
        )
    }

    /** 卸载模型（保留 binding 实例，可再次 load） */
    fun unload() {
        runCatching { binding.cleanUp() }
        _snapshot.value = Snapshot(Phase.UNINITIALIZED)
    }

    /** 释放 binding（进程退出/`:llm` 服务销毁） */
    fun close() {
        runCatching { binding.destroy() }
        _snapshot.value = Snapshot(Phase.UNINITIALIZED)
    }

    private fun fail(code: LlmErrorCode, message: String) {
        _snapshot.value = Snapshot(Phase.ERROR, _snapshot.value.modelPath, code, message)
    }

    companion object {
        const val DEFAULT_PREDICT_LENGTH = 256
        const val LOAD_TIMEOUT_MS = 60_000L
        const val GENERATE_TIMEOUT_MS = 90_000L
        private const val BENCH_TOKENS = 64
        private const val BENCH_PROMPT = "请用一句话说明你在做什么。"

        /** 生产绑定：官方 llama.cpp Android 模块（`com.arm.aichat`，MIT） */
        fun forContext(context: Context): LlmRuntime =
            LlmRuntime(OfficialLlmBinding(AiChat.getInferenceEngine(context)))
    }

    /** 可注入测试替身：业务门面只依赖这层最小接口，单测不需要 native */
    interface LlmBinding {
        val currentState: InferenceEngine.State
        val state: StateFlow<InferenceEngine.State>
        suspend fun loadModel(pathToModel: String)
        suspend fun setSystemPrompt(systemPrompt: String)
        fun sendUserPrompt(message: String, predictLength: Int): Flow<String>
        suspend fun bench(pp: Int, tg: Int, pl: Int, nr: Int): String
        fun cleanUp()
        fun destroy()
    }

    private class OfficialLlmBinding(
        private val engine: InferenceEngine
    ) : LlmBinding {
        override val currentState: InferenceEngine.State
            get() = engine.state.value
        override val state: StateFlow<InferenceEngine.State>
            get() = engine.state
        override suspend fun loadModel(pathToModel: String) = engine.loadModel(pathToModel)
        override suspend fun setSystemPrompt(systemPrompt: String) =
            engine.setSystemPrompt(systemPrompt)
        override fun sendUserPrompt(message: String, predictLength: Int): Flow<String> =
            engine.sendUserPrompt(message, predictLength)
        override suspend fun bench(pp: Int, tg: Int, pl: Int, nr: Int): String =
            engine.bench(pp, tg, pl, nr)
        override fun cleanUp() = engine.cleanUp()
        override fun destroy() = engine.destroy()
    }
}
