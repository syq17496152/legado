package io.legado.app.ui.debug

import android.os.Bundle
import android.content.res.Configuration
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.Composable
import io.legado.app.constant.EventBus
import io.legado.app.ui.theme.initLegadoComposeTheme
import io.legado.app.ui.theme.setLegadoContent
import io.legado.app.ui.theme.setupLegadoComposeSystemBar
import io.legado.app.utils.observeEvent

/**
 * 调试工具页公共基类（ui-theme-gap-audit G4）
 *
 * 原 7 个调试 Activity 均直接继承 AppCompatActivity，仅调用 initLegadoComposeTheme()
 * + setLegadoContent，未订阅 EventBus.RECREATE，改主题设置时系统栏/窗口背景热切换
 * 存在盲区。本基类统一主题初始化与 RECREATE 订阅（对齐 BaseActivity 主题架构 v2）：
 * - onCreate 时按主题设置 Style（initLegadoComposeTheme）
 * - 收到 RECREATE：系统栏即时刷新 + Compose 侧经 ThemeSync 即时换肤
 * - onConfigurationChanged（日夜自动切换）兜底刷新系统栏
 */
abstract class DebugBaseActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        initLegadoComposeTheme()
        super.onCreate(savedInstanceState)
        observeEvent<String>(EventBus.RECREATE) {
            setupLegadoComposeSystemBar()
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        setupLegadoComposeSystemBar()
    }

    /** 调试页统一入口：设置系统栏 + 背景 + Compose 内容（主题已含背景图） */
    protected fun setDebugContent(content: @Composable () -> Unit) {
        setLegadoContent {
            content()
        }
    }
}