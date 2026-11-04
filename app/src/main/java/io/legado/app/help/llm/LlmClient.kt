package io.legado.app.help.llm

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import io.legado.app.constant.AppLog
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 2.5 主进程侧推理客户端（design AD-11 / AD-25）。
 *
 * 职责边界：
 * - **可取消**：`cancel(jobId)` 走单向消息，不占队列；
 * - **不可用即降级**：连接失败/推理进程被 `am kill` 返回 [LlmIpc.ERROR_UNAVAILABLE]，绝不抛异常；
 * - **重连有上限**：按 [LlmRetryPolicy] 指数退避，每会话最多 3 次（防反复拉起 native 进程）；
 * - **请求串行**：内部 `Mutex`，与 `:llm` 的单队列语义一致，避免把回执串号。
 *
 * 大文本一律落盘（`files/llm_jobs`），Binder 只传 jobId/参数 —— 见 [LlmJobs]。
 */
class LlmClient private constructor(private val context: Context) {

    private val gate = Mutex()
    private var messenger: Messenger? = null
    private var retryUsed = 0
    private var replyDeferred: CompletableDeferred<Bundle>? = null
    private var connectDeferred: CompletableDeferred<Boolean>? = null

    private val jobsDir by lazy { LlmJobs.jobsDir(context) }
    private val outDir by lazy { LlmJobs.outDir(context) }

    /** 回执入口：所有请求都经 `gate` 串行，单槽位够用 */
    private val replyMessenger by lazy {
        Messenger(Handler(Looper.getMainLooper()) { msg ->
            replyDeferred?.complete(msg.data ?: Bundle())
            true
        })
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            messenger = service?.let { Messenger(it) }
            connectDeferred?.complete(messenger != null)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            // 推理进程崩溃：只表现为连接断开，由上层按策略重连/降级
            messenger = null
            AppLog.putWarn("LlmClient 与推理进程断开")
        }
    }

    /**
     * 建立连接（幂等）。失败时按 [LlmRetryPolicy] 用掉一次额度。
     * @return 是否连上
     */
    suspend fun ensureConnected(timeoutMs: Long = CONNECT_TIMEOUT_MS): Boolean {
        if (messenger != null) return true
        if (LlmRetryPolicy.exhausted(retryUsed)) return false
        val connected = bind(timeoutMs)
        if (connected) {
            retryUsed = 0
            return true
        }
        val decision = LlmRetryPolicy.next(retryUsed)
        retryUsed = decision.attempt
        AppLog.putWarn("LlmClient 连接推理进程失败（第 ${decision.attempt} 次，额度 ${LlmRetryPolicy.MAX_ATTEMPTS}）")
        return false
    }

    /** 本次会话已用掉的重连次数（上层可据此决定是否彻底关闭本地 AI） */
    val retryCount: Int get() = retryUsed

    suspend fun status(): LlmStatus {
        val bundle = request(LlmIpc.MSG_STATUS, Bundle(), STATUS_TIMEOUT_MS) ?: return LlmStatus.unavailable()
        return LlmStatus(
            phase = bundle.getString(LlmIpc.KEY_PHASE).orEmpty(),
            modelPath = bundle.getString(LlmIpc.KEY_MODEL_PATH).orEmpty(),
            errorCode = bundle.getString(LlmIpc.KEY_ERROR_CODE).orEmpty(),
            message = bundle.getString(LlmIpc.KEY_MESSAGE).orEmpty()
        )
    }

    /** 加载模型（服务内幂等：同路径已就绪立即返回） */
    suspend fun load(modelPath: String, timeoutMs: Long = LOAD_TIMEOUT_MS): LlmJobResult {
        val bundle = request(
            LlmIpc.MSG_LOAD,
            Bundle().apply { putString(LlmIpc.KEY_MODEL_PATH, modelPath) },
            timeoutMs
        ) ?: return LlmJobResult.failure(LlmRuntime.LlmErrorCode.TIMEOUT.name, "连接推理进程超时")
        return resultOf(bundle)
    }

    /**
     * 生成一段完整文本。
     * @param systemPrompt 每次调用前设置（服务侧在同一 KV 上复用，成本很低）
     * @return 结果文本从 `files/llm_out/<jobId>.json` 读取（不回传正文，避免 Binder 超限）
     */
    suspend fun generate(
        prompt: String,
        systemPrompt: String = "",
        predictLength: Int = LlmIpc.DEFAULT_PREDICT_LENGTH,
        timeoutMs: Long = GENERATE_TIMEOUT_MS
    ): LlmJobResult {
        if (prompt.isBlank()) return LlmJobResult.failure(LlmIpc.ERROR_BAD_JOB, "请求正文为空")
        val jobId = LlmJobs.newJobId()
        val written = runCatching { LlmJobs.writeInput(jobsDir, jobId, prompt) }.isSuccess
        if (!written) return LlmJobResult.failure(LlmRuntime.LlmErrorCode.IO.name, "请求正文落盘失败")

        val bundle = request(
            LlmIpc.MSG_GENERATE,
            Bundle().apply {
                putString(LlmIpc.KEY_JOB_ID, jobId)
                putString(LlmIpc.KEY_SYSTEM_PROMPT, systemPrompt)
                putInt(LlmIpc.KEY_PREDICT_LENGTH, predictLength)
            },
            timeoutMs
        )
        val result = if (bundle == null) {
            LlmJobResult.failure(LlmRuntime.LlmErrorCode.TIMEOUT.name, "推理进程无回执")
        } else {
            val base = resultOf(bundle)
            // 正文只在落盘文件里：回执成功必须回读，读不到就按原错误返回
            if (base.ok) LlmJobs.readResult(outDir, jobId) ?: base else base
        }
        // 用完即删请求正文（结果文件留给调用方按需读取，过期由 prune 清理）
        LlmJobs.inputFile(jobsDir, jobId).delete()
        LlmJobs.prune(jobsDir, outDir)
        return result
    }

    /** 取消一个 job（单向消息：不等回执，避免取消本身被队列挡住） */
    fun cancel(jobId: String) {
        val to = messenger ?: return
        runCatching {
            to.send(Message.obtain(null, LlmIpc.MSG_CANCEL).apply {
                data = Bundle().apply { putString(LlmIpc.KEY_JOB_ID, jobId) }
            })
        }
    }

    fun release() {
        val to = messenger ?: return
        runCatching { to.send(Message.obtain(null, LlmIpc.MSG_RELEASE)) }
    }

    fun close() {
        runCatching { context.unbindService(connection) }
        messenger = null
    }

    private suspend fun bind(timeoutMs: Long): Boolean = withContext(Dispatchers.Main) {
        val deferred = CompletableDeferred<Boolean>()
        connectDeferred = deferred
        val bound = runCatching {
            context.bindService(
                Intent(context, LlmService::class.java),
                connection,
                Context.BIND_AUTO_CREATE
            )
        }.getOrDefault(false)
        if (!bound) {
            connectDeferred = null
            return@withContext false
        }
        // bindService 是异步的：以 onServiceConnected 回调完成为准，超时即失败并解绑
        val ok = withTimeoutOrNull(timeoutMs) { deferred.await() } ?: false
        connectDeferred = null
        if (!ok) {
            runCatching { context.unbindService(connection) }
        }
        ok
    }

    private suspend fun request(what: Int, data: Bundle, timeoutMs: Long): Bundle? = gate.withLock {
        if (!ensureConnected()) return@withLock null
        val to = messenger ?: return@withLock null
        val deferred = CompletableDeferred<Bundle>()
        replyDeferred = deferred
        val msg = Message.obtain(null, what).apply {
            this.data = data
            replyTo = replyMessenger
        }
        val sent = runCatching { to.send(msg) }.isSuccess
        if (!sent) {
            replyDeferred = null
            messenger = null
            return@withLock null
        }
        val reply = withTimeoutOrNull(timeoutMs) { deferred.await() }
        replyDeferred = null
        reply
    }

    private fun resultOf(bundle: Bundle): LlmJobResult = LlmJobResult(
        ok = bundle.getBoolean(LlmIpc.KEY_OK, false),
        errorCode = bundle.getString(LlmIpc.KEY_ERROR_CODE).orEmpty(),
        message = bundle.getString(LlmIpc.KEY_MESSAGE).orEmpty(),
        tokenCount = bundle.getInt(LlmIpc.KEY_TOKEN_COUNT, 0),
        elapsedMs = bundle.getLong(LlmIpc.KEY_ELAPSED_MS, 0L)
    )

    companion object {

        const val CONNECT_TIMEOUT_MS = 1_500L
        const val STATUS_TIMEOUT_MS = 2_000L
        const val LOAD_TIMEOUT_MS = LlmRuntime.LOAD_TIMEOUT_MS + 5_000L
        const val GENERATE_TIMEOUT_MS = LlmRuntime.GENERATE_TIMEOUT_MS + 5_000L

        fun forContext(context: Context): LlmClient = LlmClient(context.applicationContext)
    }
}
