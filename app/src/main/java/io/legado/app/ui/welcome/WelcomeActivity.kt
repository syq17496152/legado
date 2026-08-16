package io.legado.app.ui.welcome

import android.content.Intent
import android.os.Bundle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.graphics.drawable.toDrawable
import androidx.core.view.postDelayed
import androidx.lifecycle.lifecycleScope
import io.legado.app.base.BaseActivity
import io.legado.app.constant.PreferKey
import io.legado.app.constant.Theme
import io.legado.app.data.appDb
import io.legado.app.databinding.ActivityWelcomeBinding
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.ThemeConfig
import io.legado.app.lib.theme.backgroundColor
import io.legado.app.ui.book.read.ReadBookActivity
import io.legado.app.ui.main.MainActivity
import io.legado.app.ui.theme.LegadoTheme
import io.legado.app.utils.BitmapUtils
import io.legado.app.utils.fullScreen
import io.legado.app.utils.getPrefBoolean
import io.legado.app.utils.getPrefInt
import io.legado.app.utils.getPrefString
import io.legado.app.utils.setStatusBarColorAuto
import io.legado.app.utils.startActivity
import io.legado.app.utils.viewbindingdelegate.viewBinding
import io.legado.app.utils.windowSize
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 欢迎/启动页（S6 弹窗/展示页，L-C17）
 * Compose 化：S6 展示族壳层 WelcomeScreen，欢迎图背景/导航（startMainActivity+defaultToRead）/
 * FLAG_ACTIVITY_BROUGHT_TO_FRONT 防重复/文字图标显隐（日/夜）逻辑保留 Activity
 */
open class WelcomeActivity : BaseActivity<ActivityWelcomeBinding>() {

    override val binding by viewBinding(ActivityWelcomeBinding::inflate)

    // Compose 桥接状态：文字/图标显隐（日/夜两套）
    private var showTitle by mutableStateOf(true)
    private var showSubtitle by mutableStateOf(true)
    private var showIcon by mutableStateOf(true)
    private var showSlogan by mutableStateOf(true)

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        initComposeHost()
        if (intent.flags and Intent.FLAG_ACTIVITY_BROUGHT_TO_FRONT != 0) {
            // 避免从桌面启动程序后，会重新实例化入口类的activity
            finish()
        } else {
            val welcomeShowTime = getPrefInt(PreferKey.welcomeShowTime, 500)
            if (welcomeShowTime == 0) {
                startMainActivity()
            } else {
                binding.root.postDelayed(welcomeShowTime.toLong()) { startMainActivity() }
            }
        }
    }

    private fun initComposeHost() {
        binding.composeHost.setContent {
            LegadoTheme {
                WelcomeScreen(
                    showTitle = showTitle,
                    showSubtitle = showSubtitle,
                    showIcon = showIcon,
                    showSlogan = showSlogan
                )
            }
        }
    }

    override fun setupSystemBar() {
        fullScreen()
        setStatusBarColorAuto(backgroundColor, true, fullScreen)
        upNavigationBarColor()
    }

    override fun upBackgroundImage() {
        if (getPrefBoolean(PreferKey.customWelcome)) {
            kotlin.runCatching {
                when (ThemeConfig.getTheme()) {
                    Theme.Dark -> {
                        getPrefString(PreferKey.welcomeImageDark)?.let { path ->
                            decodeWelcomeImage(path)
                        }
                        showTitle = AppConfig.welcomeShowTextDark
                        showSubtitle = AppConfig.welcomeShowTextDark
                        showIcon = AppConfig.welcomeShowIconDark
                        showSlogan = AppConfig.welcomeShowTextDark
                        return
                    }
                    else -> {
                        getPrefString(PreferKey.welcomeImage)?.let { path ->
                            decodeWelcomeImage(path)
                        }
                        showTitle = AppConfig.welcomeShowText
                        showSubtitle = AppConfig.welcomeShowText
                        showIcon = AppConfig.welcomeShowIcon
                        showSlogan = AppConfig.welcomeShowText
                        return
                    }
                }
            }
        }
        super.upBackgroundImage()
    }

    private fun decodeWelcomeImage(path: String) {
        if (path.endsWith(".9.png")) {
            BitmapUtils.decodeNinePatchDrawable(path)?.let {
                window.decorView.background = it
            }
        } else {
            val size = windowManager.windowSize
            BitmapUtils.decodeBitmap(path, size.widthPixels, size.heightPixels)?.let {
                window.decorView.background = it.toDrawable(resources)
            }
        }
    }

    private fun startMainActivity() {
        startActivity<MainActivity>()
        lifecycleScope.launch(IO) {
            val lastReadBook = appDb.bookDao.lastReadBook
            withContext(kotlinx.coroutines.Dispatchers.Main) {
                if (getPrefBoolean(PreferKey.defaultToRead) && lastReadBook != null) {
                    startActivity<ReadBookActivity>()
                }
            }
        }
        finish()
    }

}

class Launcher1 : WelcomeActivity()
class Launcher2 : WelcomeActivity()
class Launcher3 : WelcomeActivity()
class Launcher4 : WelcomeActivity()
class Launcher5 : WelcomeActivity()
class Launcher6 : WelcomeActivity()
class Launcher7 : WelcomeActivity()
