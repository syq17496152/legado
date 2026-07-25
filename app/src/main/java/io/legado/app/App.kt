package io.legado.app

import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.res.Configuration
import android.os.Build
import androidx.core.graphics.toColorInt
import com.github.liuyueyi.quick.transfer.constants.TransType
import com.jeremyliao.liveeventbus.LiveEventBus
import com.jeremyliao.liveeventbus.logger.DefaultLogger
import com.script.rhino.ReadOnlyJavaObject
import com.script.rhino.RhinoScriptEngine
import com.script.rhino.RhinoWrapFactory
import io.legado.app.base.AppContextWrapper
import io.legado.app.constant.AppConst.channelIdDownload
import io.legado.app.constant.AppLog
import io.legado.app.constant.AppConst.channelIdReadAloud
import io.legado.app.constant.AppConst.channelIdWeb
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
import io.legado.app.help.CrashHandler
import io.legado.app.help.DefaultData
import io.legado.app.help.DispatchersMonitor
import io.legado.app.help.LifecycleHelp
import io.legado.app.help.RuleBigDataHelp
import io.legado.app.help.book.BookHelp
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.ReadBookConfig
import io.legado.app.help.config.ThemeConfig
import io.legado.app.help.config.ThemeConfig.applyDayNight
import io.legado.app.help.config.ThemeConfig.applyDayNightInit
import io.legado.app.help.coroutine.Coroutine
import kotlinx.coroutines.Dispatchers.IO
import io.legado.app.help.http.Cronet
import io.legado.app.help.http.ObsoleteUrlFactory
import io.legado.app.help.http.okHttpClient
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
import org.chromium.base.ThreadUtils
import splitties.init.appCtx
import splitties.systemservices.notificationManager
import java.net.URL
import java.util.concurrent.TimeUnit
import java.util.logging.Level

class App : Application() {

    private lateinit var oldConfig: Configuration

    override fun onCreate() {
        super.onCreate()
        CrashHandler(this)
        if (isDebuggable) {
            // Cronet 149 重命名：setThreadAssertsDisabledForTesting → hasSubtleSideEffectsSetThreadAssertsDisabledForTesting
            ThreadUtils.hasSubtleSideEffectsSetThreadAssertsDisabledForTesting(true)
        }
        oldConfig = Configuration(resources.configuration)
        // F-暗夜紫默认主题：首次安装时写入暗夜紫夜间主题配置（老用户 dNThemeName 已有值，不受影响）
        if (getPrefString(PreferKey.dNThemeName).isNullOrBlank()) {
            ThemeConfig.configList.firstOrNull { it.themeName == "暗夜紫" }?.let { c ->
                appCtx.putPrefString(PreferKey.dNThemeName, c.themeName)
                appCtx.putPrefInt(PreferKey.cNPrimary, c.primaryColor.toColorInt())
                appCtx.putPrefInt(PreferKey.cNAccent, c.accentColor.toColorInt())
                appCtx.putPrefInt(PreferKey.cNBackground, c.backgroundColor.toColorInt())
                appCtx.putPrefInt(PreferKey.cNBBackground, c.bottomBackground.toColorInt())
            }
        }
        applyDayNightInit(this)
        registerActivityLifecycleCallbacks(LifecycleHelp)
        defaultSharedPreferences.registerOnSharedPreferenceChangeListener(AppConfig)
        // 线程池拆分配置迁移：必须在业务使用 threadCount 前执行
        migrateThreadCountConfig()
        Coroutine.async(executeContext = IO) {
            LogUtils.init(this@App)
            LogUtils.d("App", "onCreate")
            LogUtils.logDeviceInfo()
            //预下载Cronet so
            Cronet.preDownload()
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
        }
    }

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(AppContextWrapper.wrap(base))
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val diff = newConfig.diff(oldConfig)
        if ((diff and ActivityInfo.CONFIG_UI_MODE) != 0) {
            applyDayNight(this)
        }
        oldConfig = Configuration(newConfig)
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

        //向notification manager 提交channel
        notificationManager.createNotificationChannels(
            listOf(
                downloadChannel,
                readAloudChannel,
                webChannel
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
