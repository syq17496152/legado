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
import io.legado.app.ui.widget.image.CoverImageView
import io.legado.app.utils.buildMainHandler
import io.legado.app.utils.postEvent
import kotlinx.coroutines.Dispatchers.IO
import io.legado.app.help.http.Cronet
import io.legado.app.help.http.ObsoleteUrlFactory
import io.legado.app.help.http.okHttpClient
import io.legado.app.help.rhino.BookScriptObject
import io.legado.app.help.rhino.BookSourceGuardLog
import io.legado.app.help.rhino.NativeBaseSource
import io.legado.app.help.source.SourceHelp
import io.legado.app.help.storage.Backup
import io.legado.app.model.AutoTask
import io.legado.app.model.BookCover
import io.legado.app.utils.ChineseUtils
import io.legado.app.utils.LogUtils
import io.legado.app.utils.defaultSharedPreferences
import io.legado.app.utils.getPrefBoolean
import io.legado.app.utils.getPrefInt
import io.legado.app.utils.getPrefString
import io.legado.app.utils.isDebuggable
import io.legado.app.utils.putPrefBoolean
import io.legado.app.utils.putPrefInt
import io.legado.app.utils.putPrefString
import kotlinx.coroutines.launch
import splitties.init.appCtx
import splitties.systemservices.notificationManager
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
        CrashHandler(this)
        // Cronet 500（cronet-bundled）不再暴露 org.chromium.base.ThreadUtils 的线程断言测试钩子
        // hasSubtleSideEffectsSetThreadAssertsDisabledForTesting（150 时代用于禁用线程断言），此处移除调用
        oldConfig = Configuration(resources.configuration)
        // F-暗夜紫默认主题：首次安装时将暗夜紫设为夜间主题配置
        // 语义：真·首次安装（夜间主题名与 themeMode 均未设置过）→ 预设暗夜紫配色 + 强制夜间模式（themeMode="2"）；
        // 老用户（已设置过 themeMode 或夜间主题名）不受影响，仍保留原主题模式与配色。
        val firstInstallDarkPurple = getPrefString(PreferKey.dNThemeName).isNullOrBlank()
        if (firstInstallDarkPurple) {
            // T12（theme-arch-gap）：字面量换 DARK_PURPLE_THEME_NAME 常量（单一来源）
            val purple = ThemeConfig.configList.firstOrNull {
                it.themeName == AppearanceKitManager.DARK_PURPLE_THEME_NAME
            }
            val presetMode = getPrefString(PreferKey.themeMode).isNullOrBlank()
            if (purple != null || presetMode) {
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
        // AD-04 内置主题色板重放：色板更新后对未自定义内置主题的用户重放（applyDayNightInit 前，冷启动免广播）
        val paletteVersion = getPrefInt(PreferKey.builtinThemePaletteVersion)
        if (paletteVersion < ThemePackageManager.BUILTIN_THEME_PALETTE_VERSION) {
            ThemePackageManager.replayBuiltinPaletteIfUnmodified()
            putPrefInt(PreferKey.builtinThemePaletteVersion, ThemePackageManager.BUILTIN_THEME_PALETTE_VERSION)
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
                .enableLogger(BuildConfig.DEBUG || AppConfig.recordLog)
                .setLogger(EventLogger())
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
            runCatching {
                val darkPurple = ThemeConfig.configList.firstOrNull {
                    it.themeName == "暗夜紫" && it.isNightTheme
                }
                if (darkPurple != null && !ThemePackageManager.localThemeExists(true, "暗夜紫")) {
                    ThemePackageManager.addFromConfig(darkPurple)
                }
            }.onFailure {
                AppLog.put("注册暗夜紫主题包失败\n${it.localizedMessage}", it)
            }
            // F-暗夜紫外观套件：确保「主题包+专属紫调顶栏包+外观套件索引」就绪；首次安装自动套用整套
            runCatching {
                AppearanceKitManager.ensureDarkPurpleKit()?.let { kit ->
                    if (firstInstallDarkPurple) {
                        AppearanceKitManager.apply(appCtx, kit.toAppearanceKit())
                    }
                }
            }.onFailure {
                AppLog.put("注册暗夜紫外观套件失败\n${it.localizedMessage}", it)
            }
        }
    }

    override fun attachBaseContext(base: Context) {
        runCatching {
            ThemeRuntimeKeys.migrateLegacyNightValues(base)
        }
        super.attachBaseContext(AppContextWrapper.wrap(base))
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
     * 创建通知ID
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

        //向notification manager 提交channel
        notificationManager.createNotificationChannels(
            listOf(
                downloadChannel,
                readAloudChannel,
                webChannel,
                aiTaskChannel
            )
        )
    }

    /**
     * 线程池拆分配置迁移：老用户首次升级到新版本时，将旧 threadCount 迁移为 searchThreadCount + updateCacheThreadCount
     *
     * 触发条件：pref_migrated_thread_count 标志位不存在（首次升级或备份恢复后重新迁移）
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
            LogUtils.d(TAG, msg)
        }

        override fun log(level: Level, msg: String, th: Throwable?) {
            super.log(level, msg, th)
            LogUtils.d(TAG, "$msg\n${th?.stackTraceToString()}")
        }

        companion object {
            private const val TAG = "[LiveEventBus]"
        }
    }

    companion object {
        init {
            if (BuildConfig.DEBUG) {
                System.setProperty("kotlinx.coroutines.debug", "on")
            }
        }
    }

}
