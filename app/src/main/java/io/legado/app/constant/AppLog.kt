package io.legado.app.constant

import android.util.Log
import io.legado.app.BuildConfig
import io.legado.app.help.config.AppConfig
import io.legado.app.utils.LogUtils
import io.legado.app.utils.toastOnUi
import splitties.init.appCtx

object AppLog {

    /**
     * F9/2.18：LiveEventBus 事件日志开关单源（DEBUG 构建或 recordLog 开启时启用）。
     * App 启动链与其它设置 recordLog 变更回调统一走本方法，消除双开关互相覆盖。
     */
    fun syncEventBusLogger() {
        runCatching {
            com.jeremyliao.liveeventbus.LiveEventBus.config()
                .enableLogger(BuildConfig.DEBUG || recordLogOrOff())
        }
    }

    // 模块 Tag 常量：统一命名规范，便于 ai_tests 按模块过滤日志（logcat -s WebBook:E）
    const val TAG_WEB_BOOK = "WebBook"
    const val TAG_ANALYZE = "AnalyzeRule"
    const val TAG_HTTP = "HttpHelper"
    const val TAG_WEB_VIEW = "BackstageWebView"
    const val TAG_DATA = "DataLayer"
    const val TAG_RSS = "Rss"
    const val TAG_CONTENT = "ContentProcess"
    // 机制层互补组件 Tag（SourceNetworkClient/SourceConcurrencyController 等共用，对应 source-arch-mutual-borrow spec）
    const val TAG_SOURCE_MECHANISM = "SourceMechanism"
    // V3 新增：图片垂直画布模块 Tag（对应 tasks.md §AOAdapt 日志模板）
    const val TAG_IMAGE_CANVAS = "ImageCanvas"
    const val TAG_IMAGE_DETAIL = "ImageDetail"
    const val TAG_IMAGE_PLAY = "ImagePlay"
    // 图片嗅探模块 Tag（ImageUrlExtractor + ImageSnifferWebView 共用，对应 image-sniffer-optimization spec）
    const val TAG_IMAGE_SNIFF = "ImageSniff"
    // forks-ecosystem-analysis（Borrow 15 项）各功能点 Tag，供真机 adb logcat -s <TAG>:I 采集
    const val TAG_CRYPTO_SCOPE = "CryptoScope"          // B1 内置 CryptoJS
    const val TAG_DECOMPRESS = "Decompress"             // B2 Brotli 解压
    const val TAG_NETWORK_LOG = "HttpLog"               // B4 网络日志（HttpLog 复用）
    const val TAG_SEARCH_STORAGE = "SearchStorage"      // B5 搜索存储上限
    const val TAG_BOOK_ORIGIN_MIGRATE = "BookOriginMigrate" // B6 书源 URL 迁移
    const val TAG_SOURCE_RECYCLE_BIN = "SourceRecycleBin"   // B7 规则回收站
    const val TAG_SPECIAL_CONTENT = "SpecialContent"    // B8 特殊内容保护
    const val TAG_SHELF_PROGRESS = "ShelfProgress"      // B9 书架阅读进度
    const val TAG_MEMORY_PRESSURE = "MemoryPressure"    // B13 内存压力监控
    const val TAG_CACHE_STATS = "CacheStats"            // B11 缓存分项统计
    const val TAG_CACHE_CONCURRENT = "CacheConcurrent"  // B12 缓存并发率
    const val TAG_WEBDAV_BACKUP = "WebDavBackup"        // B14 WebDAV 删除/重命名
    const val TAG_HIGHLIGHT_STYLE = "HighlightStyle"    // B15 高亮捕获组样式
    const val TAG_THOUGHT_EXPORT = "ThoughtExport"      // B16 想法批注导出
    const val TAG_SOURCE_SANDBOX = "SourceSandbox"      // P0-S1 书源文件沙箱（越界/拒绝记录）
    const val TAG_SOURCE_DIALOG = "SourceDialog"        // P0-S3 书源弹窗拦截记录
    const val TAG_SOURCE_CACHE = "SourceCache"          // P0-S2 书源脚本缓存命名空间（清理失败记录）
    const val TAG_SOURCE_GUARD = "SourceGuard"          // P0-S4 类导入策略观察/实拦记录
    // TTS 朗读引擎全链埋点（optimize-tts-engine-phase2 真机联调）：路由切换/init/多角色分段/选角 resolve/预合成/缓存键
    const val TAG_TTS_TRACE = "TtsTrace"

    enum class Level { ERROR, WARN, INFO, DEBUG }

    data class LogEntry(
        val time: Long,
        val message: String,
        val throwable: Throwable? = null,
        val level: Level = Level.ERROR
    )

    private val mLogs = arrayListOf<LogEntry>()

    // log-system-upgrade AD-03: 内存容量 100→500（AI 解析需要更大回溯窗口；单条经 truncateSafely 限 2000 字符，峰值约 1MB）
    private const val MAX_LOG_SIZE = 500

    // log-system-upgrade: 快照 getter 加同步锁（put* 系列同锁 this），避免 UI 侧快照时并发 add/remove 抛 ConcurrentModificationException
    // 注：@Synchronized 不适用于无后备字段的属性，getter 用 @get:Synchronized（video-regression-fix-0906 编译修复）
    @get:Synchronized
    val logs get() = mLogs.toList()

    /**
     * P0 截断保护：防止 data URI(MB级base64图片) / 超长文本 导致 OOM/ANR/TransactionTooLargeException
     * - data URI 专项截断：startsWith("data:image/") 时截断为前80字符 + 长度提示
     * - 通用截断：>maxLen 字符按代码点截断（UTF-8安全，避免切断中文）+ 长度提示
     * 供 Debug.log / AppLog.putEntry / AppLog.putNotSave 复用，单点定义避免重复
     */
    fun truncateSafely(msg: String, maxLen: Int = 2000): String {
        if (msg.length <= maxLen) return msg
        // data URI 专项截断（base64 图片可达数 MB，撑爆 logcat + Binder 崩溃）
        if (msg.startsWith("data:image/")) {
            val prefixLen = minOf(80, msg.length)
            return "${msg.substring(0, prefixLen)}...(data URI 共${msg.length}字符，已截断)"
        }
        // 通用截断：按代码点截断，避免切断多字节中文字符
        val codePointCount = msg.codePointCount(0, msg.length)
        val safeEnd = if (codePointCount <= maxLen) msg.length
        else msg.offsetByCodePoints(0, maxLen)
        return "${msg.substring(0, safeEnd)}...(已截断，总长${msg.length}字符)"
    }

    @Synchronized
    fun put(message: String?, throwable: Throwable? = null, toast: Boolean = false) {
        putEntry(message, throwable, toast, Level.ERROR)
    }

    @Synchronized
    fun putError(message: String?, throwable: Throwable? = null, toast: Boolean = false) {
        putEntry(message, throwable, toast, Level.ERROR)
    }

    @Synchronized
    fun putWarn(message: String?, throwable: Throwable? = null, toast: Boolean = false) {
        putEntry(message, throwable, toast, Level.WARN)
    }

    @Synchronized
    fun putInfo(message: String?, throwable: Throwable? = null, toast: Boolean = false) {
        putEntry(message, throwable, toast, Level.INFO)
    }

    // ---------------- F9/2.17：频控日志（收编各处自建 60s 去重/采样轮子，单点实现） ----------------

    /** 节流窗口时长 */
    private const val THROTTLE_WINDOW_MS = 60_000L

    /** 节流/采样 key 上限（有界防 key 无界增长；全 AppLog 写路径 @Synchronized，LinkedHashMap 线程安全） */
    private const val MAX_FREQ_KEYS = 200

    private data class ThrottleState(val windowStart: Long, val count: Int)
    private val throttleStates = LinkedHashMap<String, ThrottleState>(64)
    private val sampleCounters = LinkedHashMap<String, Long>(64)

    private fun recordFreqKey(map: LinkedHashMap<String, *>, key: String) {
        if (map.size >= MAX_FREQ_KEYS) {
            val it = map.entries.iterator()
            if (it.hasNext()) { it.next(); it.remove() }
        }
    }

    /**
     * 节流日志：同 key 60s 窗口内仅输出首条，下一条合并"（前一窗口累计 N 条）"。
     * 适用：周期性/高频失败路径（网络错误、探测循环等）。
     * 级别语义（logging_rules F9 条款）：默认 WARN=降级/兜底发生；诊断 tag 白名单场景勿用本方法。
     * tag 非空时走 putDebugWithTag（模块化 logcat 采集），否则走普通 putEntry。
     */
    @Synchronized
    fun putThrottled(
        key: String,
        message: String?,
        throwable: Throwable? = null,
        level: Level = Level.WARN,
        tag: String? = null
    ) {
        message ?: return
        val now = System.currentTimeMillis()
        val prev = throttleStates[key]
        val inWindow = prev != null && now - prev.windowStart < THROTTLE_WINDOW_MS
        val count = if (inWindow) prev.count + 1 else 1
        if (inWindow) throttleStates[key] = ThrottleState(prev.windowStart, count)
        else {
            recordFreqKey(throttleStates, key)
            throttleStates[key] = ThrottleState(now, count)
        }
        if (inWindow) return // 窗口内非首条抑制（首条已输出）
        val merged = if (prev != null && prev.count > 1) "$message（前一窗口累计${prev.count}条）" else message
        if (tag != null) putDebugWithTag(tag, merged, throwable, level)
        else putEntry(merged, throwable, false, level)
    }

    /**
     * 采样日志：同 key 每 n 条输出 1 条汇总（附累计计数）。
     * 适用：cache hit 等高频成功路径（如 CryptoScope 解密缓存命中 1/50 采样）。
     */
    @Synchronized
    fun putSampled(
        key: String,
        message: String?,
        n: Int = 50,
        level: Level = Level.DEBUG,
        tag: String? = null
    ) {
        message ?: return
        if (n <= 1) {
            if (tag != null) putDebugWithTag(tag, message, null, level)
            else putEntry(message, null, false, level)
            return
        }
        val count = (sampleCounters[key] ?: 0L) + 1L
        recordFreqKey(sampleCounters, key)
        sampleCounters[key] = count
        if (count % n != 0L) return
        val merged = "$message（累计 $count 条，采样 1/$n）"
        if (tag != null) putDebugWithTag(tag, merged, null, level)
        else putEntry(merged, null, false, level)
    }

    /** 测试钩子（F9/2.17 单测）：节流状态快照（key → 窗口内累计条数） */
    internal fun throttleSnapshot(): Map<String, Int> = throttleStates.mapValues { it.value.count }

    /** 测试钩子（F9/2.17 单测）：采样计数快照（key → 累计条数） */
    internal fun sampleSnapshot(): Map<String, Long> = sampleCounters.toMap()

    @Synchronized
    fun putNotSave(message: String?, throwable: Throwable? = null, toast: Boolean = false) {
        message ?: return
        // P0 截断保护：防止 data URI / 超长文本撑爆 logcat + mLogs
        val safeMsg = truncateSafely(message)
        if (toast) {
            appCtx.toastOnUi(safeMsg)
        }
        if (mLogs.size >= MAX_LOG_SIZE) {
            mLogs.removeLastOrNull()
        }
        mLogs.add(0, LogEntry(System.currentTimeMillis(), safeMsg, throwable, Level.ERROR))
        if (BuildConfig.DEBUG) {
            val stackTrace = Thread.currentThread().stackTrace
            Log.e(stackTrace[3].className, safeMsg, throwable)
        }
    }

    @Synchronized
    fun clear() {
        mLogs.clear()
    }

    /**
     * log-system-upgrade：内存日志多选删除（LogActivity 应用日志 Tab 用）
     * LogEntry 是 data class，equals 按值比较——重复内容日志会被误删，必须按对象引用（===）删除，
     * 同时规避 UI 多选期间实时插入导致的索引漂移（AD-04 红队 R2 修复项）
     */
    @Synchronized
    fun removeLogs(entries: Collection<LogEntry>) {
        if (entries.isEmpty()) return
        mLogs.removeAll { entry -> entries.any { it === entry } }
    }

    /**
     * recordLog 读取守卫：AppConfig <clinit> 依赖 splitties appCtx（反射 ActivityThread），
     * 纯 JVM 单测环境无该类会抛 ExceptionInInitializerError/NoClassDefFoundError。
     * 失败时按 recordLog=false 处理；真机 AppConfig 正常初始化，永不抛，行为不变。
     */
    private fun recordLogOrOff(): Boolean = try {
        AppConfig.recordLog
    } catch (t: Throwable) {
        false
    }

    fun putDebug(message: String?, throwable: Throwable? = null) {
        if (recordLogOrOff()) {
            putEntry(message, throwable, false, Level.DEBUG)
        }
    }

    /**
     * 带模块 Tag 的调试日志（recordLog 守卫）
     * - recordLog 关闭时：ERROR/WARN 级别仍输出到 logcat（确保关键日志可采集），其他级别直接 return
     * - recordLog 开启时：写入文件（带 tag）+ 内存 mLogs + logcat（仅 DEBUG）
     * - tag 透传给 LogUtils.d 和 Log.e，ai_tests 可通过 adb logcat -s <Tag>:E 过滤
     * 用于：catch 块异常补全 + 关键操作成功/失败日志 + 关键参数日志
     *
     * V-004-P0-ImageLog: 修复图片播放器日志缺失（铁证：004 日志 19:01:14 ImageGalleryActivity 启动后 0 日志）
     * 根因：recordLog 关闭时 putDebugWithTag 直接 return，所有图片日志消失，无法定位"图片不显示"根因
     * 方案：recordLog 关闭时 ERROR/WARN 级别仍输出到 logcat（不写文件，不存 mLogs），其他级别 return
     */
    @Synchronized
    fun putDebugWithTag(
        tag: String,
        message: String?,
        throwable: Throwable? = null,
        level: Level = Level.ERROR
    ) {
        message ?: return
        val safeMsg = truncateSafely(message)
        // V-004-P0-ImageLog: recordLog 关闭时，ERROR/WARN/INFO 级别仍输出到 logcat（确保关键日志可采集）
        // R3-P0: 新增 INFO 级别输出（视频预缓冲埋点需要 INFO 级别日志在 release 包可采集）
        if (!recordLogOrOff()) {
            if (level == Level.ERROR || level == Level.WARN || level == Level.INFO) {
                Log.e(tag, safeMsg, throwable)
            }
            return
        }
        val fileMsg = if (throwable == null) safeMsg
        else "$safeMsg\n${throwable.stackTraceToString()}"
        LogUtils.d(tag, fileMsg)
        if (mLogs.size >= MAX_LOG_SIZE) mLogs.removeLastOrNull()
        mLogs.add(0, LogEntry(System.currentTimeMillis(), safeMsg, throwable, level))
        // V-004-P0-ImageLog: ERROR/WARN/INFO 级别在 release 包也输出到 logcat（DEBUG 保留守卫避免噪音）
        if (BuildConfig.DEBUG || level == Level.ERROR || level == Level.WARN || level == Level.INFO) {
            Log.e(tag, safeMsg, throwable)
        }
    }

    @Synchronized
    private fun putEntry(
        message: String?,
        throwable: Throwable?,
        toast: Boolean,
        level: Level
    ) {
        message ?: return
        // P0 截断保护：防止 data URI / 超长文本撑爆 logcat + mLogs
        val safeMsg = truncateSafely(message)
        if (toast) {
            appCtx.toastOnUi(safeMsg)
        }
        if (mLogs.size >= MAX_LOG_SIZE) {
            mLogs.removeLastOrNull()
        }
        if (throwable == null) {
            LogUtils.d("AppLog", safeMsg)
        } else {
            LogUtils.d("AppLog", "$safeMsg\n${throwable.stackTraceToString()}")
        }
        mLogs.add(0, LogEntry(System.currentTimeMillis(), safeMsg, throwable, level))
        // R3-P0: ERROR/WARN/INFO 级别日志在 release 包也输出到 logcat（确保关键日志可采集）
        // 根因：原 BuildConfig.DEBUG 守卫导致 release 包 putWarn/putInfo 不输出 logcat，
        //       视频预缓冲埋点日志在正式包丢失，无法定位线上问题
        // 方案：ERROR/WARN/INFO 级别无条件 Log.e 输出，DEBUG 保留 DEBUG 守卫（避免 release 包 logcat 噪音）
        if (BuildConfig.DEBUG || level == Level.ERROR || level == Level.WARN || level == Level.INFO) {
            val stackTrace = Thread.currentThread().stackTrace
            Log.e(stackTrace[3].className, safeMsg, throwable)
        }
    }

}
