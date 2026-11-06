package io.legado.app.help.llm

import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Message
import android.os.Messenger
import io.legado.app.constant.AppLog
import io.legado.app.constant.AppLog.TAG_LLM_TRACE
import io.legado.app.help.coroutine.Coroutine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 2.5 端侧推理进程宿主（design AD-11 / AD-25）。
 *
 * 为什么必须独立进程：llama.cpp 是 native 重内存（几百 MB 到 1GB+），OOM / native crash 若发生在
 * 主进程会直接带走正在朗读的 App。放到 `:llm` 进程后，崩溃对主进程只表现为「连接断开」，
 * 主进程按 [LlmRetryPolicy] 退避重连，剧本侧回落规则结果。
 *
 * 协议（全部小消息，大文本走文件）：
 * ```
 * MSG_STATUS   → 回 LlmStatus
 * MSG_LOAD     (modelPath)                          → 回 ok/errorCode
 * MSG_GENERATE (jobId, systemPrompt, predictLength) → 回 ok/errorCode；结果落 files/llm_out/<jobId>.json
 * MSG_CANCEL   (jobId)                              → 回 ok；正在跑的任务回 CANCELLED
 * MSG_RELEASE                                       → 卸载模型
 * ```
 *
 * 单队列（AD-11）：`:llm` 同时只有一个 job；并发请求直接回 `BUSY`，由调用方排队。
 */
class LlmService : Service() {

    /** 自检服务端兜底时限：客户端超时是 160s，这里必须更早，才能给出「超时」而不是「连接超时」 */
    private val BENCH_DEADLINE_MS = 150_000L

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    /**
     * 延迟创建运行时：`AiChat.getInferenceEngine` 会加载 native 库（模拟器实测 20s+）。
     * 若在 `onCreate` 里同步创建，`onServiceConnected` 要等 onCreate 结束 ⇒ 主进程 bind 超时，
     * AMS 还会报「Timeout executing service」。放到首次 load 请求里（后台线程）即可。
     */
    private val runtime: LlmRuntime by lazy { LlmRuntime.forContext(this) }
    private val jobsDir by lazy { LlmJobs.jobsDir(this) }
    private val outDir by lazy { LlmJobs.outDir(this) }

    private var ipcThread: HandlerThread? = null
    private var messenger: Messenger? = null

    /** 当前正在跑的生成任务（用于取消与结果落位防串号） */
    private var pending: Pending? = null
    private var running: Coroutine<LlmRuntime.Result>? = null
    /**
     * 「非生成类长任务」单槽：加载与自检共用。
     * 两者都只动模型加载态、且都不该互相叠加（同时跑会把 native 内存翻倍），
     * 故用通配类型共用一个槽位；生成任务仍走 [running]（互不干扰）。
     */
    private var loading: Coroutine<*>? = null

    private data class Pending(val jobId: String, val replyTo: Messenger?)

    override fun onCreate() {
        super.onCreate()
        // 上一轮遗留的 job 文件先清一遍（10 分钟窗口，AD-25）
        LlmJobs.prune(jobsDir, outDir)
        val thread = HandlerThread("llm-ipc").also { it.start() }
        ipcThread = thread
        messenger = Messenger(Handler(thread.looper, ::onMessage))
        AppLog.put("LlmService 启动（进程隔离推理）")
    }

    override fun onBind(intent: Intent?): IBinder? {
        // 诊断日志（保留）：bind 是否真的回调 + binder 是否为空，是排查 7.2 链路的第一步
        val binder = messenger?.binder
        AppLog.put("LlmService onBind binder=${binder != null}")
        return binder
    }

    override fun onDestroy() {
        runCatching { running?.cancel() }
        runCatching { loading?.cancel() }
        runCatching { runtime.close() }
        serviceScope.cancel()
        ipcThread?.quitSafely()
        ipcThread = null
        messenger = null
        super.onDestroy()
    }

    private fun onMessage(msg: Message): Boolean {
        when (msg.what) {
            LlmIpc.MSG_STATUS -> replyStatus(msg.replyTo)
            LlmIpc.MSG_LOAD -> startLoad(msg)
            LlmIpc.MSG_GENERATE -> startGenerate(msg)
            LlmIpc.MSG_BENCH -> startBench(msg)
            LlmIpc.MSG_CANCEL -> cancelJob(msg)
            LlmIpc.MSG_RELEASE -> runtime.unload()
            else -> return false
        }
        return true
    }

    /**
     * 自检（加载 + 固定长度生成）。
     *
     * 放在推理进程执行是 AD-11 的硬要求：`runtime` 一构造就加载 native 与整个 GGUF，
     * 若在主进程跑，native OOM 会直接带走正在朗读的 App。生成任务在跑时直接回 BUSY
     * （与 [MSG_GENERATE] 同一单队列语义，避免自检把用户正在等的剧本挤掉）。
     */
    private fun startBench(msg: Message) {
        val modelPath = msg.data?.getString(LlmIpc.KEY_MODEL_PATH).orEmpty()
        // **必须在这里就把 replyTo 取出来**：Message 是池化复用的，onMessage 返回 true 后它会被回收、
        // replyTo 被清空；若在协程回调里再读 `msg.replyTo` 会拿到 null，回执直接丢失
        // （2026-11-06 实测：日志 `bench 回执 ... target=false`，客户端只能干等 160s 超时）。
        val replyTo = msg.replyTo
        // 诊断日志（保留）：请求是否带着模型路径到达（区分"客户端没发对"与"服务端没回"）
        AppLog.putDebugWithTag(
            TAG_LLM_TRACE,
            "收到 MSG_BENCH pathLen=${modelPath.length} replyTo=${replyTo != null}",
            level = AppLog.Level.INFO
        )
        if (modelPath.isBlank()) {
            replyBench(replyTo, LlmBenchResult.failure(LlmIpc.ERROR_BAD_JOB, "缺少模型路径"))
            return
        }
        if (pending != null) {
            replyBench(replyTo, LlmBenchResult.failure(LlmIpc.ERROR_BAD_JOB, "推理队列忙"))
            return
        }
        loading?.cancel()
        // 服务端必须**总是**回执：native 侧 `generateNextToken`/`loadModel` 是阻塞 JNI，
        // 协程取消打不断它（实测 load 完成后 generate 立刻命中 stop 位、或 native 长时间不返回）。
        // 没有这道兜底时，客户端只能干等到自己的 160s 超时，日志还会误导成「连接超时」。
        val replied = AtomicBoolean(false)
        val guard = Runnable {
            if (replied.compareAndSet(false, true)) {
                replyBench(
                    replyTo,
                    LlmBenchResult.failure(LlmRuntime.LlmErrorCode.TIMEOUT.name, "自检超时（推理进程无响应）")
                )
                loading = null
            }
        }
        ipcThread?.let { Handler(it.looper).postDelayed(guard, BENCH_DEADLINE_MS) }
        loading = Coroutine.async(
            scope = serviceScope,
            context = Dispatchers.Default,
            executeContext = Dispatchers.Default
        ) {
            runtime.benchmark(modelPath)
        }.onSuccess { bench ->
            if (replied.compareAndSet(false, true)) replyBench(replyTo, LlmBenchResult.of(bench))
            loading = null
        }.onError { error ->
            if (replied.compareAndSet(false, true)) {
                replyBench(
                    replyTo,
                    LlmBenchResult.failure(
                        LlmRuntime.LlmErrorCode.UNKNOWN.name,
                        error.localizedMessage ?: error.javaClass.simpleName
                    )
                )
            }
            loading = null
        }
    }

    private fun replyBench(target: Messenger?, result: LlmBenchResult) {
        val bundle = Bundle().apply {
            putBoolean(LlmIpc.KEY_OK, result.ok)
            putString(LlmIpc.KEY_ERROR_CODE, result.errorCode)
            putString(LlmIpc.KEY_MESSAGE, result.message)
            putInt(LlmIpc.KEY_TOKEN_COUNT, result.tokenCount)
            putLong(LlmIpc.KEY_LOAD_MS, result.loadMs)
            putLong(LlmIpc.KEY_GENERATE_MS, result.generateMs)
            putFloat(LlmIpc.KEY_TOK_PER_SEC, result.tokPerSec)
        }
        // 诊断日志（保留）：自检回执是否真的发出，是「客户端超时 vs 服务端没回」的分水岭
        AppLog.putDebugWithTag(
            TAG_LLM_TRACE,
            "bench 回执 ok=${result.ok} code=${result.errorCode} tok=${result.tokenCount} " +
                    "tokPerSec=${result.tokPerSec} loadMs=${result.loadMs} target=${target != null}",
            level = AppLog.Level.INFO
        )
        send(target, LlmIpc.MSG_BENCH, if (result.ok) LlmIpc.REPLY_OK else LlmIpc.REPLY_FAIL, bundle)
    }

    private fun startLoad(msg: Message) {
        val modelPath = msg.data?.getString(LlmIpc.KEY_MODEL_PATH).orEmpty()
        // 同 [startBench]：Message 会被池化回收，异步回执必须在入口先取 replyTo，
        // 否则 `load` 的回执永远丢失 —— 客户端只能在 65s 后报「连接推理进程超时」并降级规则，
        // 表现为「本地 AI 打开着却从不生效」（E21 的真实根因，2026-11-06 定位）。
        val replyTo = msg.replyTo
        if (modelPath.isBlank()) {
            reply(replyTo, LlmIpc.MSG_LOAD, LlmJobResult.failure(LlmIpc.ERROR_BAD_JOB, "缺少模型路径"))
            return
        }
        loading?.cancel()
        val task = Coroutine.async(
            scope = serviceScope,
            context = Dispatchers.Default,
            executeContext = Dispatchers.Default
        ) {
            runtime.load(modelPath)
        }.onSuccess { result ->
            reply(replyTo, LlmIpc.MSG_LOAD, LlmJobResult.of(result))
            loading = null
        }.onError { error ->
            reply(
                replyTo, LlmIpc.MSG_LOAD,
                LlmJobResult.failure(LlmRuntime.LlmErrorCode.UNKNOWN.name, error.localizedMessage.orEmpty())
            )
            loading = null
        }
        loading = task
    }

    private fun startGenerate(msg: Message) {
        val data = msg.data ?: Bundle()
        val jobId = data.getString(LlmIpc.KEY_JOB_ID).orEmpty()
        if (!LlmJobs.isSafeJobId(jobId)) {
            reply(msg.replyTo, LlmIpc.MSG_GENERATE, LlmJobResult.failure(LlmIpc.ERROR_BAD_JOB, "非法 jobId"))
            return
        }
        if (pending != null) {
            reply(msg.replyTo, LlmIpc.MSG_GENERATE, LlmJobResult.failure(LlmIpc.ERROR_BAD_JOB, "推理队列忙"))
            return
        }
        val text = LlmJobs.readInput(jobsDir, jobId)
        if (text.isNullOrBlank()) {
            reply(msg.replyTo, LlmIpc.MSG_GENERATE, LlmJobResult.failure(LlmIpc.ERROR_BAD_JOB, "缺少请求正文"))
            return
        }
        val systemPrompt = data.getString(LlmIpc.KEY_SYSTEM_PROMPT).orEmpty()
        val predictLength = data.getInt(LlmIpc.KEY_PREDICT_LENGTH, LlmIpc.DEFAULT_PREDICT_LENGTH)
        pending = Pending(jobId, msg.replyTo)
        running = Coroutine.async(
            scope = serviceScope,
            context = Dispatchers.Default,
            executeContext = Dispatchers.Default
        ) {
            // system prompt 必须先**确实生效**再 generate：官方 binding 只允许 load 后设一次，
            // 二次设置会抛异常；若把它吞掉继续 generate，native 侧 chat 模板为空会 SIGSEGV，
            // 整个推理进程直接消失（真机表现为「推理进程无回执」）。ensureSystemPrompt 内部会
            // 重载模型重试一次；仍失败则抛出去 —— 让客户端收到失败回执并降级为规则结果。
            if (systemPrompt.isNotBlank()) {
                val applied = runtime.ensureSystemPrompt(systemPrompt)
                check(applied.ok) {
                    "system prompt 未生效：${applied.errorCode?.name.orEmpty()} ${applied.message}"
                }
            }
            runtime.generate(text, predictLength)
        }.onSuccess { result ->
            finishJob(jobId, LlmJobResult.of(result))
        }.onError { error ->
            finishJob(
                jobId,
                LlmJobResult.failure(
                    LlmRuntime.LlmErrorCode.UNKNOWN.name,
                    error.localizedMessage ?: error.javaClass.simpleName
                )
            )
        }
    }

    private fun cancelJob(msg: Message) {
        val jobId = msg.data?.getString(LlmIpc.KEY_JOB_ID).orEmpty()
        val current = pending
        if (current == null || current.jobId != jobId) {
            // 已结束或不是当前任务：幂等返回成功，让客户端不必区分「取消晚了」
            reply(msg.replyTo, LlmIpc.MSG_CANCEL, LlmJobResult())
            return
        }
        pending = null
        running?.cancel()
        running = null
        val cancelled = LlmJobResult.failure(LlmIpc.ERROR_CANCELLED, "已取消")
        LlmJobs.writeResult(outDir, jobId, cancelled)
        reply(current.replyTo, LlmIpc.MSG_GENERATE, cancelled)
        reply(msg.replyTo, LlmIpc.MSG_CANCEL, LlmJobResult())
        AppLog.put("LlmService 取消 job=$jobId")
    }

    /** 结果落盘 + 回执；`jobId` 不匹配说明任务已被取消（迟到的完成结果直接丢弃） */
    private fun finishJob(jobId: String, result: LlmJobResult) {
        val current = pending
        if (current == null || current.jobId != jobId) return
        pending = null
        running = null
        LlmJobs.writeResult(outDir, jobId, result)
        reply(current.replyTo, LlmIpc.MSG_GENERATE, result)
        if (!result.ok) {
            AppLog.putWarn("LlmService 生成失败：${result.errorCode} ${result.message}")
        }
    }

    private fun replyStatus(target: Messenger?) {
        val status = LlmStatus.of(runtime.snapshot.value)
        val bundle = Bundle().apply {
            putString(LlmIpc.KEY_PHASE, status.phase)
            putString(LlmIpc.KEY_MODEL_PATH, status.modelPath)
            putString(LlmIpc.KEY_ERROR_CODE, status.errorCode)
            putString(LlmIpc.KEY_MESSAGE, status.message)
        }
        send(target, LlmIpc.MSG_STATUS, LlmIpc.REPLY_OK, bundle)
    }

    private fun reply(target: Messenger?, what: Int, result: LlmJobResult) {
        val bundle = Bundle().apply {
            putBoolean(LlmIpc.KEY_OK, result.ok)
            putString(LlmIpc.KEY_ERROR_CODE, result.errorCode)
            putString(LlmIpc.KEY_MESSAGE, result.message)
            putInt(LlmIpc.KEY_TOKEN_COUNT, result.tokenCount)
            putLong(LlmIpc.KEY_ELAPSED_MS, result.elapsedMs)
        }
        send(target, what, if (result.ok) LlmIpc.REPLY_OK else LlmIpc.REPLY_FAIL, bundle)
    }

    private fun send(target: Messenger?, what: Int, code: Int, bundle: Bundle) {
        val to = target ?: return
        val msg = Message.obtain()
        msg.what = what
        msg.arg1 = code
        msg.data = bundle
        runCatching { to.send(msg) }
    }
}
