package io.legado.app.base

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.util.AttributeSet
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import androidx.activity.addCallback
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.viewbinding.ViewBinding
import io.legado.app.R
import io.legado.app.constant.AppConst
import io.legado.app.constant.AppLog
import io.legado.app.constant.EventBus
import io.legado.app.constant.Theme
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.ThemeConfig
import io.legado.app.lib.theme.ThemeStore
import io.legado.app.lib.theme.backgroundColor
import io.legado.app.lib.theme.primaryColor
import io.legado.app.ui.widget.TitleBar
import io.legado.app.utils.ColorUtils
import io.legado.app.utils.applyBackgroundTint
import io.legado.app.utils.applyOpenTint
import io.legado.app.utils.applyTint
import io.legado.app.utils.disableAutoFill
import io.legado.app.utils.fullScreen
import io.legado.app.utils.hideSoftInput
import io.legado.app.utils.observeEvent
import io.legado.app.utils.setLightStatusBar
import io.legado.app.utils.setNavigationBarColorAuto
import io.legado.app.utils.setStatusBarColorAuto
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.windowSize

abstract class BaseActivity<VB : ViewBinding>(
    val fullScreen: Boolean = true,
    private val theme: Theme = Theme.Auto,
    private val toolBarTheme: Theme = Theme.Auto,
    private val transparent: Boolean = false,
    private val imageBg: Boolean = true,
    private val showOpenMenuIcon: Boolean = true
) : AppCompatActivity() {

    protected abstract val binding: VB

    /**
     * 收到 EventBus.RECREATE（主题切换/书架布局变更等）时是否重建本 Activity。
     * 沉浸页（阅读器/视频/音频播放）覆写为 false，避免打断播放；其 Compose
     * 内容经 ThemeSync 版本信号即时换肤，View 侧系统栏由 onConfigurationChanged 兜底。
     */
    open val recreateOnThemeChange: Boolean
        get() = true

    val isInMultiWindow: Boolean
        @SuppressLint("ObsoleteSdkInt")
        get() {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                isInMultiWindowMode
            } else {
                false
            }
        }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppContextWrapper.wrap(newBase))
    }

    override fun onCreateView(
        parent: View?,
        name: String,
        context: Context,
        attrs: AttributeSet
    ): View? {
        if (AppConst.menuViewNames.contains(name) && parent?.parent is FrameLayout) {
            (parent.parent as View).setBackgroundColor(backgroundColor)
        }
        return super.onCreateView(parent, name, context, attrs)
    }

    @SuppressLint("ObsoleteSdkInt")
    override fun onCreate(savedInstanceState: Bundle?) {
        window.decorView.disableAutoFill()
        initTheme()
        super.onCreate(savedInstanceState)
        setupSystemBar()
        setContentView(binding.root)
        upBackgroundImage()
        lastThemeToken = ThemeStore.valuesChanged(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            (findViewById<View>(R.id.title_bar) as? TitleBar)
                ?.onMultiWindowModeChanged(isInMultiWindowMode, fullScreen)
        }
        onBackPressedDispatcher.addCallback(this) {
            finish()
        }
        observeLiveBus()
        // 主题架构 v2：主题/书架布局等 RECREATE 事件统一由基类订阅——
        // 普通页面重建（View+Compose 全刷新）；沉浸页/活预览设置页按
        // recreateOnThemeChange 豁免，改刷系统栏与背景图（Compose 侧经 ThemeSync 即时换肤）
        observeEvent<String>(EventBus.RECREATE) {
            if (recreateOnThemeChange) {
                recreate()
            } else {
                // T2（theme-arch-gap）：豁免页不重建，重刷底色 tint（initTheme 只在
                // onCreate 走）+ 系统栏 + 背景图；Compose 侧经 ThemeSync 即时换肤
                window.decorView.applyBackgroundTint(backgroundColor)
                setupSystemBar()
                upBackgroundImage()
            }
        }
        onActivityCreated(savedInstanceState)
    }

    /** 上次见到的主题令牌（onCreate 初始化，onResume 比对懒刷新） */
    private var lastThemeToken = 0L

    override fun onResume() {
        super.onResume()
        refreshThemeAppearanceIfChanged()
    }

    /**
     * 主题外观懒同步（from legado-archive refreshThemeBackgroundIfChanged 模式）：
     * 本页处于后台期间主题变更（未收到/未处理重建事件）时，onResume 对比
     * ThemeStore VALUES_CHANGED 令牌，确定性刷新系统栏与背景图。
     */
    private fun refreshThemeAppearanceIfChanged() {
        val token = ThemeStore.valuesChanged(this)
        if (token != lastThemeToken) {
            lastThemeToken = token
            setupSystemBar()
            upBackgroundImage()
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    override fun onMultiWindowModeChanged(isInMultiWindowMode: Boolean, newConfig: Configuration) {
        super.onMultiWindowModeChanged(isInMultiWindowMode, newConfig)
        (findViewById<View>(R.id.title_bar) as? TitleBar)
            ?.onMultiWindowModeChanged(isInMultiWindowMode, fullScreen)
        setupSystemBar()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        (findViewById<View>(R.id.title_bar) as? TitleBar)
            ?.onMultiWindowModeChanged(isInMultiWindow, fullScreen)
        setupSystemBar()
    }

    abstract fun onActivityCreated(savedInstanceState: Bundle?)

    final override fun onCreateOptionsMenu(menu: Menu): Boolean {
        val bool = onCompatCreateOptionsMenu(menu)
        menu.applyTint(this, toolBarTheme)
        return bool
    }

    override fun onMenuOpened(featureId: Int, menu: Menu): Boolean {
        menu.applyOpenTint(this, showOpenMenuIcon)
        return super.onMenuOpened(featureId, menu)
    }

    open fun onCompatCreateOptionsMenu(menu: Menu) = super.onCreateOptionsMenu(menu)

    final override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            supportFinishAfterTransition()
            return true
        }
        return onCompatOptionsItemSelected(item)
    }

    open fun onCompatOptionsItemSelected(item: MenuItem) = super.onOptionsItemSelected(item)

    open fun initTheme() {
        when (theme) {
            Theme.Transparent -> setTheme(R.style.AppTheme_Transparent)
            Theme.Dark -> {
                setTheme(R.style.AppTheme_Dark)
               window.decorView.applyBackgroundTint(backgroundColor)
            }

            Theme.Light -> {
                setTheme(R.style.AppTheme_Light)
               window.decorView.applyBackgroundTint(backgroundColor)
            }

            else -> {
                // T4（theme-arch-gap）：Auto 分支基础样式跟随主题模式判定，
                // 修上游 isColorLight(primaryColor) 旧写法——深主色+日间场景误用 Dark 样式
                if (AppConfig.isNightTheme) {
                    setTheme(R.style.AppTheme_Dark)
                } else {
                    setTheme(R.style.AppTheme_Light)
                }
               window.decorView.applyBackgroundTint(backgroundColor)
            }
        }
        if (!recreateOnThemeChange) {
            // T7 补丁（theme-arch-gap）：豁免页锁定 localNightMode 为当前生效值，
            // 阻断 AppCompatDelegate.setDefaultNightMode 变化时对所有 Activity 的
            // 自动 recreate 穿透（真机铁证：阅读菜单开启态切系统深浅被强制关闭）。
            // 豁免页改由 RECREATE 豁免分支原位刷新；锁定值随新实例 onCreate 重读
            // （super.onCreate 前 setLocalNightMode 只存值不触发 recreate，安全）
            delegate.setLocalNightMode(
                if (AppConfig.isNightTheme) {
                    AppCompatDelegate.MODE_NIGHT_YES
                } else {
                    AppCompatDelegate.MODE_NIGHT_NO
                }
            )
        }
    }

    open fun upBackgroundImage() {
        if (!imageBg) return
        // T2（theme-arch-gap）背景图回落四件套：
        // ①getBgImage 返 null（未设置/背景不可用）时回落清 decorView 自定义背景，
        //   恢复主题底色 tint（修旧图残留）②OOM/异常时同样回落不保留半态
        val drawable: Drawable? = try {
            ThemeConfig.getBgImage(this, windowManager.windowSize)
        } catch (_: OutOfMemoryError) {
            toastOnUi("背景图片太大,内存溢出")
            null
        } catch (e: Exception) {
            AppLog.put("加载背景出错\n${e.localizedMessage}", e)
            null
        }
        if (drawable != null) {
            window.decorView.background = drawable
        } else {
            window.decorView.background = null
            window.decorView.applyBackgroundTint(backgroundColor)
        }
    }

    open fun setupSystemBar() {
        if (fullScreen && !isInMultiWindow) {
            fullScreen()
        }
        val isTransparentStatusBar = AppConfig.isTransparentStatusBar
        val statusBarColor = ThemeStore.statusBarColor(this, isTransparentStatusBar)
        setStatusBarColorAuto(statusBarColor, isTransparentStatusBar, fullScreen)
        if (toolBarTheme == Theme.Dark) {
            setLightStatusBar(false)
        } else if (toolBarTheme == Theme.Light) {
            setLightStatusBar(true)
        }
        upNavigationBarColor()
    }

    open fun upNavigationBarColor() {
        if (AppConfig.immNavigationBar) {
            setNavigationBarColorAuto(ThemeStore.navigationBarColor(this))
        } else {
            val nbColor = ColorUtils.darkenColor(ThemeStore.navigationBarColor(this))
            setNavigationBarColorAuto(nbColor)
        }
    }

    open fun observeLiveBus() {
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        return try {
            super.dispatchTouchEvent(ev)
        } catch (e: IllegalArgumentException) {
            AppLog.put("BaseActivity: finish", e)
            false
        }
    }

    override fun finish() {
        currentFocus?.hideSoftInput()
        super.finish()
    }
}