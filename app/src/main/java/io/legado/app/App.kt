package io.legado.app

import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.res.Configuration
import android.os.Build
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.graphics.toColorInt
import com.bumptech.glide.Glide
import com.github.liuyueyi.quick.transfer.constants.TransType
import com.jeremyliao.liveeventbus.LiveEventBus
import com.jeremyliao.liveeventbus.logger.DefaultLogger
import com.script.rhino.ReadOnlyJavaObject
import com.script.rhino.RhinoClassShutter
import com.script.rhino.RhinoScriptEngine
import com.script.rhino.RhinoWrapFactory
import io.legado.app.base.AppContextWrapper
import io.legado.app.constant.AppConst.channelIdDownload
import io.legado.app.constant.AppLog
import io.legado.app.constant.AppConst.channelIdReadAloud
import io.legado.app.constant.AppConst.channelIdWeb
import io.legado.app.constant.AppConst.channelIdAiTask
import io.legado.app.constant.AppConst.channelIdCast
import io.legado.app.constant.EventBus
import io.legado.app.constant.PreferKey
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.HttpTTS
import io.legado.app.data.entities.RssSource
import io.legado.app.data.entities.rule.BookInfoRule
import io.legado.app.data.entities.rule.ContentRule
import io.legado.app.data.entities.rule.ExploreRule
import io.legado.app.data.entities.rule.SearchRule
import io.legado.app.help.AppFreezeMonitor
import io.legado.app.help.AppWebDav
import io.legado.app.help.CacheManager
import io.legado.app.help.CrashHandler
import io.legado.app.help.DefaultData
import io.legado.app.help.DispatchersMonitor
import io.legado.app.help.LifecycleHelp
import io.legado.app.help.MemoryPressure
import io.legado.app.help.RuleBigDataHelp
import io.legado.app.help.book.BookHelp
import io.legado.app.help.config.AppearanceKitManager
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.ReadBookConfig
import io.legado.app.help.config.ThemeConfig
import io.legado.app.help.config.ThemeConfig.applyDayNight
import io.legado.app.help.config.ThemeConfig.applyDayNightInit
import io.legado.app.help.config.ThemePackageManager
import io.legado.app.help.config.TopBarConfig
import io.legado.app.lib.theme.ThemeRuntimeKeys
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.help.webView.WebViewPool
import io.legado.app.model.ImageProvider
import io.legado.app.model.localBook.epubcore.facade.EpubCoreProvider
import io.legado.app.ui.widget.image.CoverImageView
import io.legado.app.utils.buildMainHandler
import io.legado.app.utils.postEvent
import kotlinx.coroutines.Dispatchers.IO
import io.legado.app.help.http.Cronet
import io.legado.app.help.http.ObsoleteUrlFactory
import io.legado.app.help.http.okHttpClient
import io.legado.app.help.rhino.BookScriptObject
import io.legado.app.help.readaloud.TtsCacheSweep
import io.legado.app.help.readaloud.prebuild.TtsPrebuildManager
import io.legado.app.help.rhino.BookSourceGuardLog
import io.legado.app.help.rhino.NativeBaseSource
import io.legado.app.help.source.SourceHelp
import io.legado.app.help.storage.Backup
import io.legado.app.model.AutoTask
import io.legado.app.model.BookCover
import io.legado.app.utils.ChineseUtils
import io.legado.app.utils.LogUtils
import io.legado.app.utils.MD5Utils
import io.legado.app.utils.defaultSharedPreferences
import io.legado.app.utils.getPrefBoolean
import io.legado.app.utils.getPrefInt
import io.legado.app.utils.getPrefString
import io.legado.app.utils.isDebuggable
import io.legado.app.utils.putPrefBoolean
import io.legado.app.utils.putPrefInt
import io.legado.app.utils.putPrefString
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import splitties.init.appCtx
import splitties.init.injectAsAppCtx
import splitties.systemservices.notificationManager
import java.io.File
import java.net.URL
import java.util.concurrent.TimeUnit
import java.util.logging.Level

class App : Application() {

    private lateinit var oldConfig: Configuration

    // B13 内存压力监控：定时轮询，小堆 3s / 大堆 10s
    private val memoryTrimHandler by lazy { buildMainHandler() }
    private val memoryTrimRunnable = object : Runnable {
        override fun run() {
            MemoryPressure.throttleTrim(::trimAppMemory)
            memoryTrimHandler.postDelayed(
                this,
                if (MemoryPressure.isSmallHeap) 3_000L else 10_000L
            )
        }
    }

    override fun onCreate() {
        super.onCreate()
        val processName = currentProcessName()
        if (isIsolatedSandboxProcess(processName)) {
            // 隔离进程只承载 quickjs 沙箱（DB/通知/网络/CrashHandler 在该进程均不可用或无意义）
            return
        }
        // `:llm` 推理进程只应承载 llama.cpp：**不得**跑主进程初始化。
        // 2026-11-06 模拟器铁证：修复 appCtx 注入后 `:llm` 会继续执行下面的整套初始化，
        // 其中某处加载了 WebView provider，抢占「同一 data 目录只能有一个进程用 WebView」的锁，
        // 随后主进程创建 WebView 直接 FATAL：
        //   `Using WebView from more than one process at once ... lock owner <pkg>:llm`
        // 此外它还会重复初始化 DB/Cronet/DoH 并写 shared_prefs（多进程整文件回写会丢设置）。
        if (isLlmInferenceProcess(processName)) {
            injectAppCtxIfNeeded(this)
            return
        }
        injectAppCtxIfNeeded(this)
        CrashHandler(this)
        // MCP 内腿服务（8765）· debug 变体专属（release 为空实现）：web-mcp-productization 二期 tasks 5.1
        DebugAppHook.onAppCreate()
        // Cronet 500（cronet-bundled）不再暴露 org.chromium.base.ThreadUtils 的线程断言测试钩子
        // hasSubtleSideEffectsSetThreadAssertsDisabledForTesting（150 时代用于禁用线程断言），此处移除调用
        oldConfig = Configuration(resources.configuration)
        // F-暗夜紫默认主题：首次安装时将暗夜紫设为夜间主题配置
        // 语义：夜间主题名（dNThemeName）未设置时预设暗夜紫配色；themeMode 亦未设置时才强制夜间模式（themeMode="2"）；
        // 已设置过夜间主题名的用户不受影响，保留原配色。
        // A3.5b：改用独立首启标志位判定——原「dNThemeName 为空」会把**只用过日间主题、从不设夜间主题
        // 的存量用户**误判为首装，进而强制切夜间 + 改顶栏样式（灾难性覆盖用户主题）。
        // 标志位由 ThemeRuntimeKeys.migrateThemeFirstInstallFlag 在 attachBaseContext 幂等迁移。
        val firstInstallDarkPurple = getPrefBoolean(PreferKey.themeFirstInstallDone, false)
        if (firstInstallDarkPurple) {
            // T12（theme-arch-gap）：字面量换 DARK_PURPLE_THEME_NAME 常量
            // theme-fontscale-daynight AD-03：改读代码内置配置（历史 themeConfig.json 资产已移除）
            val purple = AppearanceKitManager.darkPurpleNightConfig()
            val presetMode = getPrefString(PreferKey.themeMode).isNullOrBlank()
            if (presetMode) {
                // T11（theme-arch-gap）：首装预设合并单 editor 批量提交（原逐键多次 apply 非原子）
                appCtx.defaultSharedPreferences.edit().apply {
                    purple?.let { c ->
                        putString(PreferKey.dNThemeName, c.themeName)
                        putInt(PreferKey.cNPrimary, c.primaryColor.toColorInt())
                        putInt(PreferKey.cNAccent, c.accentColor.toColorInt())
                        putInt(PreferKey.cNBackground, c.backgroundColor.toColorInt())
                        putInt(PreferKey.cNBBackground, c.bottomBackground.toColorInt())
                    }
                    if (presetMode) {
                        putString(PreferKey.themeMode, "2")
                        // 暗夜紫默认外观：顶栏用 regular（胶囊搜索框+标签条）；底栏保持 preset=default 的 floating+glass 形态
                        putString(PreferKey.defaultTopBarStyle, TopBarConfig.STYLE_REGULAR)
                    }
                }
            }
        }
        applyDayNightInit(this)
        registerActivityLifecycleCallbacks(LifecycleHelp)
        defaultSharedPreferences.registerOnSharedPreferenceChangeListener(AppConfig)
        // P0-S4 类导入策略灰度：注册类访问观察者（modules 层回调解耦 AppLog，D13）
        RhinoClassShutter.classAccessObserver = object : RhinoClassShutter.ClassAccessObserver {
            override fun onObserveClass(className: String, sourceLabel: String?) {
                BookSourceGuardLog.observeClass(sourceLabel, className)
            }

            override fun onBlockClass(className: String, sourceLabel: String?) {
                BookSourceGuardLog.blockedClass(sourceLabel, className)
            }
        }
        // 线程池拆分配置迁移：必须在业务使用 threadCount 前执行
        migrateThreadCountConfig()
        // B13 内存压力监控：注册降级回调 + 启动定时轮询
        MemoryPressure.setTrimCallback(::trimAppMemory)
        startMemoryPressureMonitor()
        Coroutine.async(executeContext = IO) {
            LogUtils.init(this@App)
            LogUtils.d("App", "onCreate")
            LogUtils.logDeviceInfo()
            //预下载Cronet so
            Cronet.preDownload()
            // P0-ANR-fix(2026-07-31): 后台预初始化 cronetEngine，避免主线程 lazy 触发导致 ANR
            // 铁证: cronetEngine lazy 在主线程触发 syncEnsureSoFile+manualLoad+build 耗时>5s 导致 ANR
            // 修复: App启动时在IO线程预触发 cronetEngine lazy，后续主线程访问直接返回已初始化实例
            io.legado.app.lib.cronet.preInitCronetEngine()
            // FR-2: 启动时预热 DoH 服务器（探测延迟+选择更优为主），提前初始化 dohClients
            io.legado.app.help.http.DohDns.preheatDohServers()
            createNotificationChannels()
            LiveEventBus.config()
                .lifecycleObserverAlwaysActive(true)
                .autoClear(false)
                // F9/2.18：enableLogger 开关收编 AppLog.syncEventBusLogger 单源（与其它设置回调同源防覆盖）
                .setLogger(EventLogger())
            AppLog.syncEventBusLogger()
            DefaultData.upVersion()
            AppFreezeMonitor.init(this@App)
            DispatchersMonitor.init()
            URL.setURLStreamHandlerFactory(ObsoleteUrlFactory(okHttpClient))
            launch { installGmsTlsProvider(appCtx) }
            initRhino()
            //初始化封面
            BookCover.toString()
            //清除过期数据
            appDb.cacheDao.clearDeadline(System.currentTimeMillis())
            if (getPrefBoolean(PreferKey.autoClearExpired, true)) {
                val clearTime = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(1)
                appDb.searchBookDao.clearExpired(clearTime)
            }
            RuleBigDataHelp.clearInvalid()
            BookHelp.clearInvalidCache()
            // R17：TTS 混合缓存启动兜底清扫（保留各书持久化当前章 + 预合成保留名单），
            // 与 HttpReadAloudService 换章清扫共用 TtsCacheSweep 单一判据
            sweepTtsCacheOnStartup()
            Backup.clearCache()
            ReadBookConfig.clearBgAndCache()
//            ThemeConfig.clearBg() //每次手动切换主题时清理多余图片
            //初始化简繁转换引擎
            when (AppConfig.chineseConverterType) {
                1 -> {
                    ChineseUtils.fixT2sDict()
                    ChineseUtils.preLoad(true, TransType.TRADITIONAL_TO_SIMPLE)
                }

                2 -> ChineseUtils.preLoad(true, TransType.SIMPLE_TO_TRADITIONAL)
            }
            //调整排序序号
            SourceHelp.adjustSortNumber()
            //同步阅读记录
            if (AppConfig.syncBookProgress) {
                AppWebDav.downloadAllBookProgress()
            }
            //F-P1-1 自动任务调度恢复
            AutoTask.refreshSchedule()
            // F-暗夜紫可回切：把内置暗夜紫主题注册进「主题包」体系，使其在主题列表(夜间)可见可选，避免切走后回不去
            // theme-fontscale-daynight AD-03：改读代码内置配置（历史 themeConfig.json 资产已移除）
            runCatching {
                val darkPurple = AppearanceKitManager.darkPurpleNightConfig()
                if (!ThemePackageManager.localThemeExists(true, AppearanceKitManager.DARK_PURPLE_THEME_NAME)) {
                    ThemePackageManager.addFromConfig(darkPurple)
                }
            }.onFailure {
                AppLog.put("注册暗夜紫主题包失败\n${it.localizedMessage}", it)
            }
            // F-暗夜紫外观套件：确保「主题包+专属紫调顶栏包+外观套件索引」就绪；首装**一次性**自动套用整套。
            // 2026-09-24 主题设置体系失守修复：原判据仅 `firstInstallDarkPurple`，而该值语义是
            // 「本机为全新安装」（迁移后**长期为 true**，并非「已完成首装」）⇒ 每次启动都重复
            // `apply(kit)`，把用户此后选择的主题 / 外观套件 / 主页布局 preset / 顶栏包**静默还原**
            // （实测：注入的面 token 重启后被改回套件值、`mainLayoutPreset` 与 `defaultTopBarStyle`
            // 被改回 regular）。故追加一次性标记 `appearanceKitAutoApplyDone`。
            runCatching {
                AppearanceKitManager.ensureDarkPurpleKit()?.let { kit ->
                    val autoApplied = getPrefBoolean(PreferKey.appearanceKitAutoApplyDone, false)
                    val shouldApply = AppearanceKitManager.shouldAutoApplyDarkPurpleKitOnce(
                        isFreshInstall = firstInstallDarkPurple,
                        alreadyAutoApplied = autoApplied
                    )
                    if (shouldApply) {
                        AppearanceKitManager.apply(appCtx, kit.toAppearanceKit())
                        appCtx.putPrefBoolean(PreferKey.appearanceKitAutoApplyDone, true)
                    }
                }
            }.onFailure {
                AppLog.put("注册暗夜紫外观套件失败\n${it.localizedMessage}", it)
            }
            // theme-fontscale-daynight AD-04：磨砂玻璃晨昏套件首启幂等预置（仅注入列表，不自动套用）
            runCatching {
                AppearanceKitManager.ensureFrostedGlassKitSeeded()
            }.onFailure {
                AppLog.put("预置磨砂玻璃晨昏套件失败\n${it.localizedMessage}", it)
            }
        }
    }

    override fun attachBaseContext(base: Context) {
        val processName = currentProcessName()
        if (isIsolatedSandboxProcess(processName) || isLlmInferenceProcess(processName)) {
            // 隔离进程（quickjs_sandbox）只承载 JS 沙箱：
            // 该进程的 ContextImpl 拿不到 UserManager ⇒ defaultSharedPreferences 直接 NPE
            // （2026-10-22 真机实测：进程启动即 FATAL，绑定超时 → BIND_TIMEOUT）。
            // `:llm` 推理进程同理不需要语言包装与主题迁移——**尤其不能在这里写 shared_prefs**：
            // 两个进程各持一份整文件镜像回写，会互相覆盖设置（用户侧表现为"设置莫名丢失"）。
            super.attachBaseContext(base)
            return
        }
        runCatching {
            ThemeRuntimeKeys.migrateLegacyNightValues(base)
            ThemeRuntimeKeys.migrateThemeFirstInstallFlag(base)
            // 2026-09-24：为「已配置过的存量用户」回填套件自动套用的一次性标记，
            // 避免升级后首启被套件再覆盖一次主题/布局（详见该函数 KDoc）
            ThemeRuntimeKeys.migrateAppearanceKitAutoApplyFlag(base)
        }
        super.attachBaseContext(AppContextWrapper.wrap(base))
    }

    /** 当前进程名（API 28+ 直接取；更低版本读 /proc/self/cmdline，与沙箱模块同一口径）。 */
    private fun currentProcessName(): String? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            return Application.getProcessName()
        }
        return runCatching {
            java.io.FileInputStream("/proc/self/cmdline").use { input ->
                val bytes = ByteArray(256)
                val length = input.read(bytes)
                if (length <= 0) return@use null
                val end = bytes.indexOf(0).let { if (it in 0 until length) it else length }
                String(bytes, 0, end, Charsets.UTF_8)
            }
        }.getOrNull()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val diff = newConfig.diff(oldConfig)
        // T1（theme-arch-gap）跟随系统链路四件套：
        // ①夜间位掩码收窄——修 CONFIG_UI_MODE 宽掩码（UI_MODE_TYPE 车载/底座/异形屏
        //   等 uiMode 变化误触发全量重建），仅 UI_MODE_NIGHT 位变化才响应
        // ②themeMode=="0"（跟随系统）前置——固定日/夜模式的用户不受系统深浅切换影响
        // ③幂等防抖——目标夜间模式已生效（getDefaultNightMode==target）则跳过，
        //   多屏/多窗口同帧多次回调不再重复全链 applyTheme+RECREATE
        // ④AppearanceKit 先行——套件接管时由 Kit 投影主题（generation 防抖内建），
        //   未接管才走 applyDayNight 全链；目标夜间态以新 config 为准显式传参
        if ((diff and Configuration.UI_MODE_NIGHT_MASK) != 0 && AppConfig.themeMode == "0") {
            val newNight = (newConfig.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                    Configuration.UI_MODE_NIGHT_YES
            val targetMode =
                if (newNight) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO
            if (AppCompatDelegate.getDefaultNightMode() != targetMode) {
                Coroutine.async {
                    runCatching {
                        if (AppearanceKitManager.applyCurrentModeTheme(appCtx, newNight)) {
                            postEvent(EventBus.RECREATE, "")
                        } else {
                            applyDayNight(appCtx, newNight)
                        }
                    }.onFailure {
                        AppLog.put("applyDayNight onConfigChange failed\n${it.localizedMessage}", it)
                    }
                }
            }
        }
        oldConfig = Configuration(newConfig)
    }

    /**
     * R17：启动期 TTS 缓存兜底清扫（在 IO 线程执行，失败静默并记日志）。
     *
     * 保留集必须来自**持久化进度**（`Book.durChapterIndex` 对应章节标题的 md5），
     * 禁止用 `ReadBook` 内存态（启动时它是空的，会把当前章产物一起删掉）。
     * 预合成保留名单（用户主动生成的资产）任何情况不删。
     */
    private suspend fun sweepTtsCacheOnStartup() {
        kotlin.runCatching {
            val keepStems = appDb.bookDao.flowAll().first().mapNotNull { book ->
                val index = book.durChapterIndex
                if (index < 0) return@mapNotNull null
                appDb.bookChapterDao.getChapter(book.bookUrl, index)?.title
                    ?.let { MD5Utils.md5Encode16(it) }
            }.toSet()
            val deleted = TtsCacheSweep.sweep(
                File(cacheDir.absolutePath + File.separator + "httpTTS"),
                keepStems = keepStems,
                reserved = TtsPrebuildManager.reservedKeys.keys
            )
            if (deleted > 0) {
                AppLog.putDebug("TtsCacheSweep 启动清扫: keep=${keepStems.size}, deleted=$deleted")
            }
        }.onFailure {
            AppLog.put("TtsCacheSweep 启动清扫失败: ${it.localizedMessage}")
        }
    }

    // B13 内存压力监控：启动定时轮询（小堆 3s / 大堆 10s）
    private fun startMemoryPressureMonitor() {
        memoryTrimHandler.removeCallbacks(memoryTrimRunnable)
        memoryTrimHandler.postDelayed(memoryTrimRunnable, 3_000L)
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        kotlin.runCatching {
            val used = MemoryPressure.usedMemory() / (1024 * 1024)
            val max = MemoryPressure.maxMemory / (1024 * 1024)
            val avail = MemoryPressure.availableMemory() / (1024 * 1024)
            AppLog.putDebugWithTag(
                AppLog.TAG_MEMORY_PRESSURE,
                "onTrimMemory level=$level avail=${avail}MB used=${used}MB max=${max}MB smallHeap=${MemoryPressure.isSmallHeap}",
                level = AppLog.Level.INFO
            )
        }
        trimAppMemory(level)
    }

    @Suppress("DEPRECATION")
    override fun onLowMemory() {
        super.onLowMemory()
        trimAppMemory(ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL)
        kotlin.runCatching { Glide.get(this).clearMemory() }
    }

    // B13 内存压力降级：联动清空/缩小各内存缓存
    private fun trimAppMemory(level: Int) {
        kotlin.runCatching {
            WebViewPool.trimMemory()
            // S10：阅读面（EPUB core 离屏布局会话的 WebView 是最大内存头）纳入同一压力治理
            EpubCoreProvider.onMemoryPressure(level)
            CacheManager.trimMemory(level)
            ImageProvider.trimMemory(level)
            CoverImageView.trimMemory(level)
            Glide.get(this).trimMemory(level)
        }
        kotlin.runCatching {
            AppLog.putDebugWithTag(
                AppLog.TAG_MEMORY_PRESSURE,
                "trim executed level=$level",
                level = AppLog.Level.WARN
            )
        }
    }

    /**
     * 尝试在安装了GMS的设备上(GMS或者MicroG)使用GMS内置的Conscrypt
     * 作为首选JCE提供程序，而使Okhttp在低版本Android上
     * 能够启用TLSv1.3
     * https://f-droid.org/zh_Hans/2020/05/29/android-updates-and-tls-connections.html
     * https://developer.android.google.cn/reference/javax/net/ssl/SSLSocket
     *
     * @param context
     * @return
     */
    private fun installGmsTlsProvider(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return
        }
        try {
            val gmsPackageName = "com.google.android.gms"
            val appInfo = packageManager.getApplicationInfo(gmsPackageName, 0)
            if ((appInfo.flags and ApplicationInfo.FLAG_SYSTEM) == 0) {
                return
            }
            val gms = context.createPackageContext(
                gmsPackageName,
                CONTEXT_INCLUDE_CODE or CONTEXT_IGNORE_SECURITY
            )
            gms.classLoader
                .loadClass("com.google.android.gms.common.security.ProviderInstallerImpl")
                .getMethod("insertProvider", Context::class.java)
                .invoke(null, gms)
        } catch (e: java.lang.Exception) {
            AppLog.put("App: init", e)
        }
    }

    /**
     * 创建通知渠道（下载/朗读/Web 服务/AI 任务，API 26+）
     */
    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val downloadChannel = NotificationChannel(
            channelIdDownload,
            getString(R.string.action_download),
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            enableLights(false)
            enableVibration(false)
            setSound(null, null)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }

        val readAloudChannel = NotificationChannel(
            channelIdReadAloud,
            getString(R.string.read_aloud),
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            enableLights(false)
            enableVibration(false)
            setSound(null, null)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }

        val webChannel = NotificationChannel(
            channelIdWeb,
            getString(R.string.web_service),
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            enableLights(false)
            enableVibration(false)
            setSound(null, null)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }

        val aiTaskChannel = NotificationChannel(
            channelIdAiTask,
            "AI任务",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            enableLights(false)
            enableVibration(false)
            setSound(null, null)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }

        // add-dlna-cast：投屏会话渠道（低打扰，前台服务保活）
        val castChannel = NotificationChannel(
            channelIdCast,
            getString(R.string.dlna_cast_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            enableLights(false)
            enableVibration(false)
            setSound(null, null)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }

        //向notification manager 提交channel
        notificationManager.createNotificationChannels(
            listOf(
                downloadChannel,
                readAloudChannel,
                webChannel,
                aiTaskChannel,
                castChannel
            )
        )
    }

    /**
     * 线程池拆分配置迁移：老用户首次升级到新版本时，将旧 threadCount 迁移为 searchThreadCount + updateCacheThreadCount
     *
     * 触发条件：migratedThreadCount 标志位不存在（首次升级或备份恢复后重新迁移）
     * 迁移规则：
     * - 旧 threadCount != 32（用户修改过）→ 仅当新配置为默认值时才覆盖（避免覆盖备份恢复的值）
     * - 旧 threadCount == 32（默认值）→ 保持新配置默认值（32/16）
     * - 异常容错：SharedPreferences 读取失败不崩溃
     */
    private fun migrateThreadCountConfig() {
        try {
            if (getPrefBoolean(PreferKey.migratedThreadCount, false)) {
                return // 已迁移过
            }
            @Suppress("DEPRECATION")
            val legacyThreadCount = AppConfig.threadCount
            var migrated = false
            if (legacyThreadCount != 32) {
                // 用户修改过旧配置，仅当新配置为默认值时才迁移（避免覆盖备份恢复的值）
                if (AppConfig.searchThreadCount == 32) {
                    AppConfig.searchThreadCount = legacyThreadCount
                    migrated = true
                }
                if (AppConfig.updateCacheThreadCount == 16) {
                    AppConfig.updateCacheThreadCount = legacyThreadCount
                    migrated = true
                }
            }
            appCtx.putPrefBoolean(PreferKey.migratedThreadCount, true)
            if (migrated) {
                AppConfig.migratedThreadCountJustDone = true
            }
            LogUtils.d("App", "线程池配置迁移完成: legacy=$legacyThreadCount, search=${AppConfig.searchThreadCount}, updateCache=${AppConfig.updateCacheThreadCount}, migrated=$migrated")
        } catch (ex: Exception) {
            AppLog.put("App: 线程池配置迁移失败\n${ex.localizedMessage}", ex)
            // 即使失败也标记已迁移，避免每次启动都尝试
            try {
                appCtx.putPrefBoolean(PreferKey.migratedThreadCount, true)
            } catch (_: Exception) {
            }
        }
    }

    private fun initRhino() {
        RhinoScriptEngine
        RhinoWrapFactory.register(BookSource::class.java, NativeBaseSource.factory)
        RhinoWrapFactory.register(RssSource::class.java, NativeBaseSource.factory)
        RhinoWrapFactory.register(HttpTTS::class.java, NativeBaseSource.factory)
        RhinoWrapFactory.register(ExploreRule::class.java, ReadOnlyJavaObject.factory)
        RhinoWrapFactory.register(SearchRule::class.java, ReadOnlyJavaObject.factory)
        RhinoWrapFactory.register(BookInfoRule::class.java, ReadOnlyJavaObject.factory)
        RhinoWrapFactory.register(ContentRule::class.java, ReadOnlyJavaObject.factory)
        RhinoWrapFactory.register(BookChapter::class.java, ReadOnlyJavaObject.factory)
        RhinoWrapFactory.register(Book::class.java, BookScriptObject.factory)
        RhinoWrapFactory.register(Book.ReadConfig::class.java, ReadOnlyJavaObject.factory)
    }

    class EventLogger : DefaultLogger() {

        override fun log(level: Level, msg: String) {
            super.log(level, msg)
            // F9/2.19：事件总线日志降为 DEBUG 构建专属（真机日志 3039 条/8% 噪音源，事件流非诊断对象）
            if (BuildConfig.BUILD_DEBUG) {
                LogUtils.d(TAG, msg)
            }
        }

        override fun log(level: Level, msg: String, th: Throwable?) {
            super.log(level, msg, th)
            if (BuildConfig.BUILD_DEBUG) {
                LogUtils.d(TAG, "$msg\n${th?.stackTraceToString()}")
            }
        }

        companion object {
            private const val TAG = "[LiveEventBus]"
        }
    }

    companion object {
        init {
            if (BuildConfig.BUILD_DEBUG) {
                System.setProperty("kotlinx.coroutines.debug", "on")
            }
        }
    }

}

/**
 * 是否为隔离沙箱进程（`quickjs-sandbox` 的 `android:isolatedProcess` 服务进程）。
 *
 * 隔离进程没有 UserManager / SharedPreferences（读配置直接 NPE），且只应承载 quickjs-kt，
 * 因此 [App.attachBaseContext]（语言包装）与 [App.onCreate]（DB/通知/网络）都必须短路。
 * 2026-10-22 真机实测：未短路时沙箱进程启动即 FATAL → 宿主绑定等待 5s 超时（BIND_TIMEOUT）。
 */
internal fun isIsolatedSandboxProcess(processName: String?): Boolean =
    processName != null && processName.contains(SANDBOX_PROCESS_SUFFIX)

/** 与 `quickjs-sandbox` 模块的 `QuickJsSandboxProtocol.PROCESS_SUFFIX` 保持一致（模块内为 internal）。 */
internal const val SANDBOX_PROCESS_SUFFIX = ":quickjs_sandbox"

/** 端侧 LLM 推理进程后缀（`AndroidManifest` 里 `LlmService` 的 `android:process`）。 */
internal const val LLM_PROCESS_SUFFIX = ":llm"

/**
 * 是否为端侧 LLM 推理进程（`:llm`）。
 *
 * 该进程只允许承载 llama.cpp 推理（design AD-11）：它**不应**跑 [App.onCreate] 的主进程初始化链
 * （DB / Cronet / DoH / WebView / 主题迁移 / `shared_prefs` 写入）。
 * 2026-11-06 模拟器铁证：`:llm` 跑完整初始化后加载了 WebView provider，抢占
 * 「同一 data 目录只能有一个进程使用 WebView」的锁，主进程随后创建 WebView 直接 FATAL。
 */
internal fun isLlmInferenceProcess(processName: String?): Boolean =
    processName != null && processName.endsWith(LLM_PROCESS_SUFFIX)

/**
 * 是否需要在本进程内兜底注入 `appCtx`（E14：本地 AI 推理进程启动即崩）。
 *
 * 判定方式：**只做一次探测读取**，抛异常（`appCtx has not been initialized!`）才返回 true。
 * 因此主进程（App Startup 已注入）零副作用，重复调用幂等；判断依据不是进程名，
 * 避免新增次级进程时再逐个补白名单。
 *
 * 提取为纯函数是为了让 JVM 单测能直接覆盖「已初始化不注入 / 未初始化注入」两条分支。
 */
internal fun shouldInjectAppCtx(probe: () -> Unit): Boolean = runCatching(probe).isFailure

/**
 * 幂等兜底注入 `appCtx`：只在本进程确实没有注入时执行（主进程恒为 no-op）。
 *
 * 适用场景：`androidx.startup` 的 `AppCtxInitializer` 在次级进程（`:llm`）实测未生效，
 * 首个 `appCtx` 访问即抛 `IllegalStateException` ⇒ 进程起不来。
 */
private fun injectAppCtxIfNeeded(context: Context) {
    if (shouldInjectAppCtx { appCtx }) {
        context.injectAsAppCtx()
    }
}
