package io.legado.app.ui.code.config

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.legado.app.R
import io.legado.app.constant.PreferKey
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.ThemeConfig
import io.legado.app.ui.theme.LegadoTheme
import io.legado.app.ui.widget.compose.AppDialogFrame
import io.legado.app.ui.widget.compose.AppDialogSize
import io.legado.app.ui.widget.compose.AppDialogSwitchRow
import io.legado.app.ui.widget.compose.ComposeDialogFragment
import io.legado.app.ui.widget.compose.LegadoMiuixActionButton
import io.legado.app.ui.widget.compose.LegadoMiuixChoiceRow
import io.legado.app.ui.widget.compose.rememberAppDialogStyle
import io.legado.app.ui.widget.compose.toMiuixPalette
import io.legado.app.utils.putPrefBoolean
import io.legado.app.utils.putPrefInt


class ChangeThemeDialog() : ComposeDialogFragment() {

    override val dialogSize: AppDialogSize = AppDialogSize.Form

    private var callBack: CallBack? = null

    override fun onAttach(context: Context) {
        super.onAttach(context)
        if (context is CallBack) {
            callBack = context
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                LegadoTheme {
                    ChangeThemePanel()
                }
            }
        }
    }

    @Composable
    private fun ChangeThemePanel() {
        val style = rememberAppDialogStyle()
        val palette = style.toMiuixPalette()
        var autoSwitch by remember { mutableStateOf(AppConfig.editTemeAuto) }
        var selectedIndex by remember {
            mutableStateOf(
                if (autoSwitch && ThemeConfig.isDarkTheme()) {
                    AppConfig.editThemeDark
                } else {
                    AppConfig.editTheme
                }
            )
        }
        val themeNames = remember {
            listOf(
                R.string.edit_theme_monokai_dimmed,
                R.string.edit_theme_monokai,
                R.string.edit_theme_modern_dark,
                R.string.edit_theme_modern_light,
                R.string.edit_theme_solarized_dark,
                R.string.edit_theme_solarized_light,
                R.string.edit_theme_abyss,
                R.string.edit_theme_quiet_light
            )
        }

        // 对应原 initData() 的 callBack?.upTheme(themeIndex)
        LaunchedEffect(Unit) {
            callBack?.upTheme(selectedIndex)
        }

        fun selectTheme(index: Int) {
            if (index == selectedIndex) {
                return
            }
            selectedIndex = index
            if (autoSwitch && ThemeConfig.isDarkTheme()) {
                putPrefInt(PreferKey.editThemeDark, index)
            } else {
                putPrefInt(PreferKey.editTheme, index)
            }
            callBack?.upTheme(index)
        }

        AppDialogFrame(
            title = stringResource(R.string.change_theme),
            content = {
                AppDialogSwitchRow(
                    text = stringResource(R.string.system_auto),
                    checked = autoSwitch,
                    onCheckedChange = { checked ->
                        putPrefBoolean(PreferKey.editTemeAuto, checked)
                        autoSwitch = checked
                        // 对应原 switch 回调中重跑 initData()：按是否深色重读主题索引并上报
                        val newIndex = if (checked && ThemeConfig.isDarkTheme()) {
                            AppConfig.editThemeDark
                        } else {
                            AppConfig.editTheme
                        }
                        selectedIndex = newIndex
                        callBack?.upTheme(newIndex)
                    }
                )
                Spacer(modifier = Modifier.height(10.dp))
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (row in 0 until 4) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            LegadoMiuixChoiceRow(
                                text = stringResource(themeNames[row * 2]),
                                selected = selectedIndex == row * 2,
                                palette = palette,
                                onClick = { selectTheme(row * 2) },
                                modifier = Modifier.weight(1f),
                                minHeight = 40.dp
                            )
                            LegadoMiuixChoiceRow(
                                text = stringResource(themeNames[row * 2 + 1]),
                                selected = selectedIndex == row * 2 + 1,
                                palette = palette,
                                onClick = { selectTheme(row * 2 + 1) },
                                modifier = Modifier.weight(1f),
                                minHeight = 40.dp
                            )
                        }
                    }
                }
            },
            actions = {
                LegadoMiuixActionButton(
                    text = stringResource(R.string.ok),
                    palette = palette,
                    onClick = { dismissAllowingStateLoss() },
                    primary = true,
                    cornerRadius = style.actionRadius
                )
            }
        )
    }

    interface CallBack {
        fun upTheme(index: Int)
    }

}
