package io.legado.app.help.llm

import android.content.Context
import com.arm.aichat.AiChat
import com.arm.aichat.InferenceEngine
import com.arm.aichat.TemplateUnavailableException
import com.arm.aichat.UnsupportedArchitectureException
import com.arm.aichat.isModelLoaded
import kotlinx.coroutines.CancellationException
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
        /** crash-hardening AD-05：native chat 模板不可用（旧实现会 SIGSEGV 打崩 :llm 进程） */
        TEMPLATE_UNAVAILABLE,
        NOT_LOADED,
        TIMEOUT,
        EMPTY_OUTPUT,
        /** 调用方显式取消（:llm 侧 MSG_CANCEL / 服务销毁）：不代表引擎故障 */
        CANCELLED,
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
            // 引擎是**进程级单例**（`AiChat.getInferenceEngine`），而本类是 Service 级：
            // Service 重建后新实例的 phase 是 UNINITIALIZED，但底层引擎仍在 ModelReady ⇒
            // 直接再 load 会被官方实现 `check(state is Initialized)` 拒绝
            // （2026-11-06 实测报错：`Cannot load model in ModelReady!`）。
            // 这里按「引擎已加载」对齐状态，避免用户看到莫名其妙的自检失败。
            if (binding.currentState.isModelLoaded) {
                _snapshot.value = Snapshot(Phase.READY, modelPath)
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
            } catch (e: TemplateUnavailableException) {
                // crash-hardening AD-05：chat 模板不可用属于"该模型/该引擎状态不可用"，
                // 与架构不支持分开报，便于用户换模型或重新导入
                fail(LlmErrorCode.TEMPLATE_UNAVAILABLE, "chat 模板不可用：$modelPath")
                Result(
                    elapsedMs = clock() - started,
                    errorCode = LlmErrorCode.TEMPLATE_UNAVAILABLE,
                    message = "chat 模板不可用"
                )
            } catch (e: CancellationException) {
                // 显式取消（非超时）：回到未加载态，不把状态打成 ERROR（否则下次自检会误报引擎坏了）
                _snapshot.value = Snapshot(Phase.UNINITIALIZED, modelPath)
                Result(elapsedMs = clock() - started, errorCode = LlmErrorCode.CANCELLED, message = "已取消")
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
     * 保证 system prompt 已生效（官方 binding 只允许在 load 之后设置一次）。
     *
     * 为什么必须做：官方实现用 `_readyForSystemPrompt` 拒绝第二次设置（抛 IllegalStateException），
     * 而 native 侧 chat 模板只在首次设置成功时建好。若吞掉失败继续 generate，
     * `processUserPrompt` 会拿空模板指针 ⇒ **SIGSEGV 打崩整个 `:llm` 进程**
     * （2026-11-06 真机铁证：tombstone 落在 libllama-common.so 的
     * `common_chat_templates_was_explicit`，调用方表现为「推理进程无回执」）。
     *
     * 处置：设置失败 ⇒ 卸载后重载一次再设（自检与剧本任务会各自设置不同 system prompt，
     * 这是常态而不是异常）；重载后仍失败则返回错误，调用方**必须放弃本次生成**并回落规则。
     */
    suspend fun ensureSystemPrompt(prompt: String, timeoutMs: Long = LOAD_TIMEOUT_MS): Result =
        gate.withLock {
            if (!binding.currentState.isModelLoaded) {
                return@withLock Result(errorCode = LlmErrorCode.NOT_LOADED, message = "模型未加载")
            }
            val first = runCatching { binding.setSystemPrompt(prompt) }
            if (first.isSuccess) return@withLock Result()

            val path = _snapshot.value.modelPath
            if (path.isBlank()) {
                return@withLock Result(
                    errorCode = LlmErrorCode.UNKNOWN,
                    message = first.exceptionOrNull()?.localizedMessage.orEmpty()
                )
            }
            runCatching { binding.cleanUp() }
            _snapshot.value = Snapshot(Phase.LOADING, path)
            val reloadError: LlmErrorCode? = try {
                withTimeout(timeoutMs) { binding.loadModel(path) }
                null
            } catch (e: TimeoutCancellationException) {
                LlmErrorCode.TIMEOUT
            } catch (e: CancellationException) {
                // 保留取消语义：调用方主动取消（服务销毁）不能当成一次失败
                _snapshot.value = Snapshot(Phase.UNINITIALIZED, path)
                throw e
            } catch (e: Exception) {
                LlmErrorCode.UNKNOWN
            }
            if (reloadError != null) {
                fail(reloadError, "重载模型失败（system prompt 需要重新设置）")
                return@withLock Result(errorCode = reloadError, message = "重载模型失败")
            }
            _snapshot.value = Snapshot(Phase.READY, path)
            val second = runCatching { binding.setSystemPrompt(prompt) }
            _snapshot.value = Snapshot(Phase.READY, path)
            second.fold(
                onSuccess = { Result() },
                onFailure = {
                    Result(
                        errorCode = LlmErrorCode.UNKNOWN,
                        message = "system prompt 设置失败：${it.localizedMessage.orEmpty()}"
                    )
                }
            )
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
        } catch (e: CancellationException) {
            // 显式取消：引擎仍可用，状态回到 READY（超时已在上一个分支单独处理）
            _snapshot.value = Snapshot(Phase.READY, _snapshot.value.modelPath)
            Result(
                elapsedMs = clock() - started,
                tokenCount = tokens,
                errorCode = LlmErrorCode.CANCELLED,
                message = "已取消"
            )
        } catch (e: TemplateUnavailableException) {
            // crash-hardening AD-05：native 模板为空时立刻失败（旧实现会 SIGSEGV 整进程）
            fail(LlmErrorCode.TEMPLATE_UNAVAILABLE, "chat 模板不可用，已放弃本次生成")
            Result(
                elapsedMs = clock() - started,
                tokenCount = tokens,
                errorCode = LlmErrorCode.TEMPLATE_UNAVAILABLE,
                message = "chat 模板不可用"
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
        // 必须在 generate 前**确保** system prompt 生效：官方引擎用 chat 模板拼回合，
        // 不设 system 时 assistant 回合没被正确打开，实测 Qwen3 首个 token 即命中停止位 ⇒ 一直 0 token；
        // 更致命的是设置失败后继续 generate 会在 native 侧对空模板取指针 ⇒ SIGSEGV（见 ensureSystemPrompt）。
        // 因此这里失败直接返回错误，不再"按原样生成"。
        val applied = ensureSystemPrompt(BENCH_SYSTEM_PROMPT)
        if (!applied.ok) {
            return Bench(
                loadMs = load.elapsedMs, generateMs = 0, tokenCount = 0, tokPerSec = 0f,
                errorCode = applied.errorCode, message = applied.message
            )
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
        /** 自检用的最小 system prompt：官方引擎要求 setSystemPrompt 在 load 之后立刻调用，
         *  否则 chat 模板的 assistant 回合不会被正确打开（实测 Qwen3 首 token 即 EOS） */
        private const val BENCH_SYSTEM_PROMPT = "你是一个简洁的助手，回答尽量短。"

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
