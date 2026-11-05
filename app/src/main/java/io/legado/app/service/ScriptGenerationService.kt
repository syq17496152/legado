package io.legado.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import io.legado.app.R
import io.legado.app.constant.AppConst
import io.legado.app.constant.AppLog
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.help.LifecycleHelp
import io.legado.app.help.book.BookHelp
import io.legado.app.help.book.characterBookKey
import io.legado.app.help.config.AppConfig
import io.legado.app.help.llm.LlmClient
import io.legado.app.help.llm.LlmModelManager
import io.legado.app.help.readaloud.casting.AiTaskRouter
import io.legado.app.help.readaloud.casting.CharacterVoiceAssignService
import io.legado.app.help.readaloud.role.OfflineCharacterDiscovery
import io.legado.app.help.readaloud.role.BookAliasStore
import io.legado.app.help.readaloud.role.ChapterScriptStore
import io.legado.app.help.readaloud.role.ClientScriptLlm
import io.legado.app.help.readaloud.role.OfflineRoleResolver
import io.legado.app.help.readaloud.role.ScriptGenerationEngine
import io.legado.app.help.readaloud.role.ScriptPriority
import io.legado.app.help.readaloud.role.ScriptSchedulerPolicy
import io.legado.app.help.readaloud.role.ScriptStatusBus
import io.legado.app.help.readaloud.role.ScriptTaskQueue
import io.legado.app.help.tts.builtin.BuiltinTtsRuntime
import io.legado.app.ui.book.read.config.CastSceneActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 3.5 剧本生成执行器（design AD-26 的「前台服务」分支）。
 *
 * 为什么必须真有执行器：`ScriptArbiterRunner` / `ScriptGenerationEngine` / `ChapterScriptStore`
 * 早就齐全，但**没有任何生产者**，结果是听书中心永远显示「剧本未生成」，端侧模型一次也没被调用。
 *
 * 行为：
 * - 只做「队列 + 策略 + 数据装载 + 调引擎 + 通知」，业务判断都在纯函数/引擎里（可单测）；
 * - 队列空或策略判 RELEASE 就 `stopSelf`，不常驻耗电；
 * - 朗读中且用户刚滑动 ⇒ YIELD（让位一拍再继续，不抢 CPU）；
 * - `onDestroy` / 通知栏「停止」⇒ 取消队列，避免后台裸跑。
 *
 * 模型调用一律走 `:llm` 独立进程的 [LlmClient]（AD-11），主进程只做编排。
 */
class ScriptGenerationService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val queue = ScriptTaskQueue()
    private lateinit var client: LlmClient

    private var worker: Job? = null
    private var currentJob: Job? = null
    private var backgroundStartedAt: Long = 0L
    private var lastText: String = ""

    /** 用户点「重新生成」时跳过复用判定（只对本次排队首条生效，避免长期污染） */
    private var forceOnce = false

    override fun onBind(intent: Intent?): android.os.IBinder? = null

    override fun onCreate() {
        super.onCreate()
        client = LlmClient.forContext(this)
        createChannel()
        startForeground(NOTIFICATION_ID, buildNotification(getString(R.string.script_generation_title)))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CANCEL -> cancelAll("用户停止")
            ACTION_REQUEST -> enqueueFromIntent(intent)
            else -> Unit
        }
        return START_NOT_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // 划掉最近任务：停止生成（回到应用后重新排队即可，剧本落盘是原子的）
        cancelAll("任务被移除")
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        cancelAll("服务销毁")
        runCatching { client.close() }
        scope.cancel()
        super.onDestroy()
    }

    private fun enqueueFromIntent(intent: Intent) {
        val bookUrl = intent.getStringExtra(EXTRA_BOOK_URL).orEmpty()
        val chapters = intent.getIntArrayExtra(EXTRA_CHAPTERS)?.toList()
            ?: listOf(intent.getIntExtra(EXTRA_CHAPTER, -1))
        val force = intent.getBooleanExtra(EXTRA_FORCE, false)
        if (bookUrl.isBlank()) return
        chapters.filter { it >= 0 }.forEachIndexed { index, chapterIndex ->
            // 首个 = 当前章（P0），其余 = 预取（P1）
            queue.enqueue(bookUrl, chapterIndex, if (index == 0) ScriptPriority.P0 else ScriptPriority.P1)
        }
        forceOnce = force
        startLoopIfNeeded()
    }

    private fun startLoopIfNeeded() {
        if (worker?.isActive == true) return
        backgroundStartedAt = 0L
        worker = scope.launch {
            while (isActive) {
                when (ScriptSchedulerPolicy.decide(policyFacts())) {
                    ScriptSchedulerPolicy.Decision.RELEASE -> {
                        queue.cancelAll()
                        render("后台过久，已释放")
                        break
                    }
                    ScriptSchedulerPolicy.Decision.PAUSE -> {
                        render("电量或温度过高，暂停生成")
                        delay(PAUSE_RETRY_MS)
                        continue
                    }
                    ScriptSchedulerPolicy.Decision.YIELD -> {
                        delay(YIELD_RETRY_MS)
                        continue
                    }
                    ScriptSchedulerPolicy.Decision.RUN -> Unit
                }
                val task = queue.poll() ?: break
                currentJob = launch { executeTask(task) }
                currentJob?.join()
                currentJob = null
            }
            // 队列空 / 释放：收回前台通知，让服务退出
            ServiceCompat.stopForeground(this@ScriptGenerationService, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private suspend fun executeTask(task: ScriptTaskQueue.Task) {
        val result = withContext(Dispatchers.IO) { runTask(task.bookKey, task.chapterIndex) }
        if (result.message.isNotBlank()) render(result.message)
    }

    private data class TaskResult(val message: String = "")

    /** 单章流水线：装载正文/角色 → 规则归因 → 模型仲裁 → 落盘 + 状态推送 */
    private suspend fun runTask(bookUrl: String, chapterIndex: Int): TaskResult {
        val book = withContext(Dispatchers.IO) { appDb.bookDao.getBook(bookUrl) } ?: return TaskResult()
        val chapter = withContext(Dispatchers.IO) {
            appDb.bookChapterDao.getChapter(bookUrl, chapterIndex)
        } ?: return TaskResult()
        val paragraphs = paragraphsOf(book, chapter)
        if (paragraphs.isEmpty()) return TaskResult()

        val bookKey = book.characterBookKey()
        var characters = CharacterVoiceAssignService.roleCharacters(bookKey)
        if (characters.isEmpty()) {
            // 首次生成时角色库可能为空：先做一次幂等发现，否则白名单为空、模型一律被拒
            runCatching { OfflineCharacterDiscovery.ensureCharacters(bookKey, paragraphs) }
            characters = CharacterVoiceAssignService.roleCharacters(bookKey)
        }
        if (characters.isEmpty()) {
            return TaskResult("本章未识别出角色，跳过剧本生成")
        }
        val segments = OfflineRoleResolver.ensure(
            bookKey = bookKey,
            chapterIndex = chapterIndex,
            chapterTitle = chapter.title,
            paragraphs = paragraphs,
            characters = characters,
            aliasStore = BookAliasStore(BuiltinTtsRuntime.paths().rulesRoot)
        )
        if (segments.isEmpty()) return TaskResult()

        val route = AiTaskRouter.routeFor(onlineConfigured = false)
        if (route.executor != AiTaskRouter.Executor.LOCAL) {
            return TaskResult("本地 AI 未启用：${route.reason}")
        }
        // 先把激活模型装进 `:llm` 进程（幂等）；装不上不阻断，引擎会产出规则剧本并标降级
        ensureModelLoaded()

        val store = ChapterScriptStore(BuiltinTtsRuntime.paths().scriptsDir)
        val engine = ScriptGenerationEngine(store, ClientScriptLlm(client))
        ScriptStatusBus.begin(bookKey, chapterIndex)
        val outcome = engine.generate(
            bookKey = bookKey,
            chapterIndex = chapterIndex,
            paragraphs = paragraphs,
            segments = segments,
            candidates = characters.map { it.name }.distinct().take(MAX_CANDIDATES),
            force = forceOnce,
            aiRequested = true
        )
        forceOnce = false
        if (outcome.saved) {
            ScriptStatusBus.done(
                bookKey = bookKey,
                chapterIndex = chapterIndex,
                segments = outcome.script?.segments?.size ?: 0,
                source = outcome.script?.source.orEmpty(),
                degraded = outcome.script?.degraded == true,
                message = outcome.script?.degradeReason.orEmpty()
            )
        } else {
            ScriptStatusBus.clear(bookKey, chapterIndex)
        }
        AppLog.put(
            "剧本生成 ch=$chapterIndex 结果=${outcome.status} ai=${outcome.aiApplied} " +
                "段=${outcome.script?.segments?.size ?: 0} 说明=${outcome.message}"
        )
        return TaskResult(
            when (outcome.status) {
                ScriptGenerationEngine.Status.SAVED ->
                    "第 $chapterIndex 章剧本已生成（AI ${outcome.aiApplied} 段）"
                ScriptGenerationEngine.Status.SKIPPED_REUSABLE -> "第 $chapterIndex 章剧本可复用"
                ScriptGenerationEngine.Status.EMPTY -> "第 $chapterIndex 章无可生成内容"
                ScriptGenerationEngine.Status.FAILED -> "第 $chapterIndex 章生成失败：${outcome.message}"
            }
        )
    }

    /** 正文来源：当前章优先用内存（避免为已渲染的章再读盘/联网），其余走缓存/本地/书源 */
    private fun paragraphsOf(book: Book, chapter: BookChapter): List<String> {
        runCatching { ReadBookBridge.currentParagraphs(book.bookUrl, chapter.index) }
            .getOrNull()?.takeIf { it.isNotEmpty() }?.let { return it }
        val content = runCatching { BookHelp.getContent(book, chapter) }.getOrNull()
        return ScriptGenerationPlan.paragraphsOf(content)
    }

    /** 把激活模型装进 `:llm` 进程（幂等）；失败只记录，由引擎按降级处理 */
    private suspend fun ensureModelLoaded() {
        val id = AppConfig.localLlmProfileId
        if (id.isBlank()) return
        val path = runCatching { LlmModelManager.forApp(this).find(id)?.modelFile?.absolutePath }
            .getOrNull() ?: return
        runCatching { client.ensureConnected() }
        val load = runCatching { client.load(path) }.getOrNull()
        if (load != null && !load.ok) {
            AppLog.put("剧本生成：模型加载失败 ${load.errorCode} ${load.message}")
        }
    }

    private fun policyFacts(): ScriptSchedulerPolicy.Facts {
        val battery = batteryFacts()
        val backgroundMinutes = backgroundMinutes()
        return ScriptSchedulerPolicy.Facts(
            batteryPercent = battery.first,
            charging = battery.second,
            thermalLevel = thermalLevel(),
            backgroundMinutes = backgroundMinutes,
            readAloudPlaying = io.legado.app.service.BaseReadAloudService.isRun,
            // 滑动让位需要页面帧信号；执行器侧拿不到，交给 P0 队列语义兜底（不误判为 RUN 抢占）
            userScrollingRecently = false
        )
    }

    private fun batteryFacts(): Pair<Int, Boolean> {
        val manager = getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        val percent = runCatching {
            manager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: 100
        }.getOrDefault(100).let { if (it in 0..100) it else 100 }
        // 充电状态用 API 23 起的 BatteryManager.isCharging（不注册粘性广播，免 API 33 的 receiver flag 限制）
        val charging = runCatching { manager?.isCharging == true }.getOrDefault(false)
        return percent to charging
    }

    private fun thermalLevel(): Int {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return 0
        val power = getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return 0
        return runCatching { power.currentThermalStatus }.getOrDefault(0)
    }

    /** 后台分钟数：用 Activity 计数近似前台/后台（无新增生命周期依赖，纯旁路观测） */
    private fun backgroundMinutes(): Int {
        val foreground = LifecycleHelp.activitySize() > 0
        if (foreground) {
            backgroundStartedAt = 0L
            return 0
        }
        if (backgroundStartedAt == 0L) backgroundStartedAt = SystemClock.elapsedRealtime()
        return ((SystemClock.elapsedRealtime() - backgroundStartedAt) / 60_000L).toInt()
    }

    private fun cancelAll(reason: String) {
        queue.cancelAll()
        currentJob?.cancel()
        currentJob = null
        worker?.cancel()
        worker = null
        AppLog.put("剧本生成停止：$reason")
    }

    private fun render(text: String) {
        if (text.isBlank() || text == lastText) return
        lastText = text
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        runCatching { manager.notify(NOTIFICATION_ID, buildNotification(text)) }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        manager.createNotificationChannel(
            NotificationChannel(
                AppConst.channelIdDownload,
                getString(R.string.script_generation_title),
                NotificationManager.IMPORTANCE_LOW
            )
        )
    }

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, CastSceneActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, ScriptGenerationService::class.java).apply { action = ACTION_CANCEL },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, AppConst.channelIdDownload)
            .setSmallIcon(R.drawable.ic_volume_up)
            .setContentTitle(getString(R.string.script_generation_title))
            .setContentText(text)
            .setContentIntent(open)
            .addAction(0, getString(R.string.script_generation_stop), stop)
            .setOngoing(true)
            .build()
    }

    companion object {

        private const val NOTIFICATION_ID = 10087
        private const val ACTION_REQUEST = "io.legado.app.script.generate.REQUEST"
        private const val ACTION_CANCEL = "io.legado.app.script.generate.CANCEL"
        private const val EXTRA_BOOK_URL = "bookUrl"
        private const val EXTRA_CHAPTER = "chapter"
        private const val EXTRA_CHAPTERS = "chapters"
        private const val EXTRA_FORCE = "force"

        /** 暂停/让位后的重试间隔：长任务不需要毫秒级响应，10s 足够且省电 */
        private const val PAUSE_RETRY_MS = 10_000L
        private const val YIELD_RETRY_MS = 2_000L

        /** 候选角色上限（与 `ScriptPromptTemplates` 的 ≤40 约束一致） */
        private const val MAX_CANDIDATES = 40

        fun request(context: Context, bookUrl: String, chapters: List<Int>, force: Boolean = false) {
            if (bookUrl.isBlank() || chapters.isEmpty()) return
            val intent = Intent(context, ScriptGenerationService::class.java).apply {
                action = ACTION_REQUEST
                putExtra(EXTRA_BOOK_URL, bookUrl)
                putExtra(EXTRA_CHAPTERS, chapters.filter { it >= 0 }.toIntArray())
                putExtra(EXTRA_FORCE, force)
            }
            runCatching { context.startForegroundService(intent) }
        }

        /** 兼容单章调用（章节号单独传，语义与批量一致） */
        fun request(context: Context, bookUrl: String, chapterIndex: Int, force: Boolean = false) {
            request(context, bookUrl, listOf(chapterIndex), force)
        }

        fun cancel(context: Context) {
            val intent = Intent(context, ScriptGenerationService::class.java).apply {
                action = ACTION_CANCEL
            }
            runCatching { context.startService(intent) }
        }
    }
}

/**
 * 与 `ReadBook` 的只读桥：执行器不直接持有全局单例，避免在服务里误改阅读状态。
 */
internal object ReadBookBridge {

    fun currentParagraphs(bookUrl: String, chapterIndex: Int): List<String>? {
        val book = io.legado.app.model.ReadBook.book ?: return null
        if (book.bookUrl != bookUrl) return null
        val chapter = io.legado.app.model.ReadBook.curTextChapter ?: return null
        if (chapter.chapter.index != chapterIndex || !chapter.isCompleted) return null
        return chapter.paragraphs.map { it.text }.filter { it.isNotBlank() }
    }
}
