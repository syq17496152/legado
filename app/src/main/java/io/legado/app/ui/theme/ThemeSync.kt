package io.legado.app.ui.theme

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.setValue

/**
 * 主题全局同步信号（主题架构 v2，借鉴 MoRealm/Archive 的全局主题状态思路）。
 *
 * 问题背景：Compose 迁移后 LegadoTheme 只在组合时一次性读 ThemeStore，
 * 主题设置变更（EventBus.RECREATE）仅 MainActivity/ConfigActivity 订阅重建，
 * 其余已组合的 Compose 页面永不刷新 —— 表现为「设置后不起作用」。
 *
 * 机制：ThemeConfig.applyTheme() 末尾调用 [bump]（T12 注释修正：recreateActivities 仅
 * postEvent(RECREATE)，重建页面经 onCreate 全量重读，不直接 bump），
 * 所有在组合中读取了 [version] 的 Composable（LegadoTheme、直接读
 * ThemeStore 的 GlassTopAppBar/PillNavigationBar 等）立即失效重组，
 * 重新读取 ThemeStore 最新值 —— 无需依赖 Activity 重建，栈内后台页面同样生效。
 */
object ThemeSync {

    var version by mutableLongStateOf(0L)
        private set

    fun bump() {
        version += 1
    }
}
