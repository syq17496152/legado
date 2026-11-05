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
import io.legado.app.help.coroutine.Coroutine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

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
    private var loading: Coroutine<LlmRuntime.Result>? = null

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

    override fun onBind(intent: Intent?): IBinder? = messenger?.binder

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
            LlmIpc.MSG_CANCEL -> cancelJob(msg)
            LlmIpc.MSG_RELEASE -> runtime.unload()
            else -> return false
        }
        return true
    }

    private fun startLoad(msg: Message) {
        val modelPath = msg.data?.getString(LlmIpc.KEY_MODEL_PATH).orEmpty()
        if (modelPath.isBlank()) {
            reply(msg.replyTo, LlmIpc.MSG_LOAD, LlmJobResult.failure(LlmIpc.ERROR_BAD_JOB, "缺少模型路径"))
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
            reply(msg.replyTo, LlmIpc.MSG_LOAD, LlmJobResult.of(result))
            loading = null
        }.onError { error ->
            reply(
                msg.replyTo, LlmIpc.MSG_LOAD,
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
            if (systemPrompt.isNotBlank()) runtime.setSystemPrompt(systemPrompt)
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
