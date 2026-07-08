package io.legado.app.ui.theme

import android.graphics.drawable.Drawable
import android.os.Build
import android.util.DisplayMetrics
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.core.graphics.drawable.toBitmap
import io.legado.app.R
import io.legado.app.constant.Theme
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.ThemeConfig
import io.legado.app.lib.theme.ThemeStore
import io.legado.app.lib.theme.primaryColor
import io.legado.app.utils.ColorUtils
import io.legado.app.utils.fullScreen
import io.legado.app.utils.setNavigationBarColorAuto
import io.legado.app.utils.setStatusBarColorAuto

/**
 * Compose Activity 支持工具（F-P0-1 调试工具集，借鉴蛋蛋Max 精简版）
 *
 * 简化说明：蛋蛋Max 原版含 installComposeGlobalUi（朗读悬浮球/调试日志面板）等
 * 蛋蛋Max 特有功能，本精简版仅保留主题初始化、系统栏设置、背景图加载、setContent
 * 包装等调试工具集所需的最小能力。 | 已知上限：不含朗读悬浮球/调试日志面板 |
 * 升级路径：如需这些功能可后续完整移植 ComposeGlobalUiController 及相关组件
 */

fun ComponentActivity.initLegadoComposeTheme() {
    when (ThemeConfig.getTheme()) {
        Theme.Dark -> setTheme(R.style.AppTheme_Dark)
        Theme.Light -> setTheme(R.style.AppTheme_Light)
        else -> {
            if (ColorUtils.isColorLight(primaryColor)) {
                setTheme(R.style.AppTheme_Light)
            } else {
                setTheme(R.style.AppTheme_Dark)
            }
        }
    }
}

fun ComponentActivity.setupLegadoComposeSystemBar() {
    fullScreen()
    val isTransparentStatusBar = AppConfig.isTransparentStatusBar
    val statusBarColor = ThemeStore.statusBarColor(this, isTransparentStatusBar)
    setStatusBarColorAuto(statusBarColor, isTransparentStatusBar, true)
    if (AppConfig.immNavigationBar) {
        setNavigationBarColorAuto(ThemeStore.navigationBarColor(this))
    } else {
        setNavigationBarColorAuto(ColorUtils.darkenColor(ThemeStore.navigationBarColor(this)))
    }
}

fun ComponentActivity.loadLegadoBackgroundDrawable(): Drawable? {
    return try {
        val metrics = DisplayMetrics()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = windowManager.currentWindowMetrics.bounds
            metrics.widthPixels = bounds.width()
            metrics.heightPixels = bounds.height()
        } else {
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getMetrics(metrics)
        }
        ThemeConfig.getBgImage(this, metrics)
    } catch (_: Exception) {
        null
    }
}

@Composable
fun LegadoBackgroundBox(
    backgroundDrawable: Drawable?,
    modifier: Modifier = Modifier,
    backgroundColor: Color = MaterialTheme.colorScheme.background,
    overlayAlpha: Float? = null,
    content: @Composable () -> Unit
) {
    Box(modifier = modifier.fillMaxSize()) {
        if (backgroundDrawable != null) {
            val resolvedOverlayAlpha = overlayAlpha
                ?: if (backgroundColor.luminance() > 0.5f) 0.10f else 0.18f
            Image(
                bitmap = backgroundDrawable.toBitmap().asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(backgroundColor.copy(alpha = resolvedOverlayAlpha))
            )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(backgroundColor)
            )
        }
        content()
    }
}

@Composable
fun LegadoThemeWithBackground(
    backgroundDrawable: Drawable?,
    overlayAlpha: Float? = null,
    content: @Composable () -> Unit
) {
    LegadoTheme {
        LegadoBackgroundBox(
            backgroundDrawable = backgroundDrawable,
            overlayAlpha = overlayAlpha
        ) {
            content()
        }
    }
}

fun ComponentActivity.setLegadoContent(
    overlayAlpha: Float? = null,
    content: @Composable () -> Unit
) {
    setupLegadoComposeSystemBar()
    val backgroundDrawable = loadLegadoBackgroundDrawable()
    enableEdgeToEdge()
    setContent {
        LegadoThemeWithBackground(
            backgroundDrawable = backgroundDrawable,
            overlayAlpha = overlayAlpha
        ) {
            content()
        }
    }
}
