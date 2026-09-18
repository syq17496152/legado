package io.legado.app.ui.about

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
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.Fragment
import io.legado.app.R
import io.legado.app.help.update.AppUpdateConfig
import io.legado.app.ui.widget.compose.AppDialogFrame
import io.legado.app.ui.widget.compose.AppDialogSize
import io.legado.app.ui.widget.compose.ComposeDialogFragment
import io.legado.app.ui.widget.compose.LegadoMiuixActionButton
import io.legado.app.ui.widget.compose.LegadoMiuixChoiceRow
import io.legado.app.ui.widget.compose.rememberAppDialogStyle
import io.legado.app.ui.widget.compose.showComposeTextInputDialog
import io.legado.app.ui.widget.compose.toMiuixPalette
import io.legado.app.utils.showDialogFragment

/**
 * 更新加速管理（app-update-github-channel §2.5）
 *
 * 通道已收敛为 GitHub 单通道，故此对话框只管理代理：列表增删改 + "不使用代理"。
 * 配置直接写入 [AppUpdateConfig]（与关于页读取同一份 SharedPreferences，
 * 变更经 `onSharedPreferenceChanged` 自动刷新入口摘要，无需回调链）。
 */
object UpdateAcceleratorDialog {

    fun show(fragment: Fragment) {
        fragment.showDialogFragment(
            UpdateAcceleratorComposeDialog.create(
                initialTemplates = AppUpdateConfig.githubProxyTemplates,
                initialIndex = AppUpdateConfig.githubProxyIndex
            )
        )
    }
}

class UpdateAcceleratorComposeDialog : ComposeDialogFragment() {

    override val dialogSize: AppDialogSize = AppDialogSize.Form

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val args = arguments ?: Bundle()
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                val style = rememberAppDialogStyle()
                val palette = style.toMiuixPalette()

                var templates by rememberSaveable {
                    mutableStateOf(ArrayList(args.getStringArrayList(ARG_TEMPLATES).orEmpty()))
                }
                var selectedIndex by rememberSaveable {
                    mutableIntStateOf(args.getInt(ARG_INDEX))
                }

                // 列表变更后统一回写并同步索引（写入侧会处理"索引越界重置为 -1"）
                fun persist(next: List<String>) {
                    AppUpdateConfig.githubProxyTemplates = next
                    templates = ArrayList(AppUpdateConfig.githubProxyTemplates)
                    selectedIndex = AppUpdateConfig.githubProxyIndex
                }

                AppDialogFrame(
                    title = stringResource(R.string.update_acceleration_manage),
                    scrollContent = true,
                    content = {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.update_accel_tip),
                                color = style.secondaryText,
                                fontSize = 13.sp,
                                lineHeight = 18.sp,
                                modifier = Modifier.fillMaxWidth()
                            )
                            Spacer(modifier = Modifier.height(2.dp))

                            // 不使用代理
                            LegadoMiuixChoiceRow(
                                text = stringResource(R.string.update_accel_no_proxy),
                                selected = selectedIndex < 0,
                                palette = palette,
                                onClick = {
                                    selectedIndex = -1
                                    AppUpdateConfig.githubProxyIndex = -1
                                },
                                minHeight = 40.dp
                            )

                            // 代理模板列表（数量为个位数，直接渲染，避免嵌套滚动）
                            templates.forEachIndexed { index, template ->
                                LegadoMiuixChoiceRow(
                                    text = template,
                                    selected = selectedIndex == index,
                                    palette = palette,
                                    onClick = {
                                        selectedIndex = index
                                        AppUpdateConfig.githubProxyIndex = index
                                    },
                                    minHeight = 40.dp
                                )
                            }

                            Spacer(modifier = Modifier.height(4.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                LegadoMiuixActionButton(
                                    text = stringResource(R.string.update_accel_add),
                                    palette = palette,
                                    onClick = {
                                        showProxyEditDialog(oldValue = null) { value ->
                                            val next = templates + value
                                            AppUpdateConfig.githubProxyTemplates = next
                                            templates = ArrayList(AppUpdateConfig.githubProxyTemplates)
                                            // 新增后直接选中它，符合"加完即用"的直觉
                                            AppUpdateConfig.githubProxyIndex =
                                                AppUpdateConfig.githubProxyTemplates.indexOf(value)
                                            selectedIndex = AppUpdateConfig.githubProxyIndex
                                        }
                                    },
                                    cornerRadius = style.actionRadius
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                LegadoMiuixActionButton(
                                    text = stringResource(R.string.update_accel_edit),
                                    palette = palette,
                                    onClick = {
                                        val index = selectedIndex
                                        val oldValue = templates.getOrNull(index)
                                            ?: return@LegadoMiuixActionButton
                                        showProxyEditDialog(oldValue = oldValue) { value ->
                                            val next = templates.toMutableList().apply {
                                                set(index, value)
                                            }
                                            AppUpdateConfig.githubProxyTemplates = next
                                            templates = ArrayList(AppUpdateConfig.githubProxyTemplates)
                                            AppUpdateConfig.githubProxyIndex =
                                                AppUpdateConfig.githubProxyTemplates.indexOf(value)
                                            selectedIndex = AppUpdateConfig.githubProxyIndex
                                        }
                                    },
                                    cornerRadius = style.actionRadius
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                LegadoMiuixActionButton(
                                    text = stringResource(R.string.update_accel_delete),
                                    palette = palette,
                                    onClick = {
                                        val index = selectedIndex
                                        if (index !in templates.indices) return@LegadoMiuixActionButton
                                        val next = templates.toMutableList().apply { removeAt(index) }
                                        persist(next)
                                    },
                                    danger = true,
                                    cornerRadius = style.actionRadius
                                )
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
        }
    }

    private fun showProxyEditDialog(oldValue: String?, onSaved: (String) -> Unit) {
        showComposeTextInputDialog(
            title = context?.getString(
                if (oldValue == null) R.string.update_accel_add_title
                else R.string.update_accel_edit_title
            ).orEmpty(),
            hint = context?.getString(R.string.update_accel_hint).orEmpty(),
            initialValue = oldValue.orEmpty(),
            onPositive = { value ->
                val trimmed = value.trim()
                if (trimmed.isNotBlank()) {
                    onSaved(trimmed)
                }
            }
        )
    }

    companion object {
        private const val ARG_TEMPLATES = "proxyTemplates"
        private const val ARG_INDEX = "proxyIndex"

        fun create(initialTemplates: List<String>, initialIndex: Int): UpdateAcceleratorComposeDialog {
            return UpdateAcceleratorComposeDialog().apply {
                arguments = Bundle().apply {
                    putStringArrayList(ARG_TEMPLATES, ArrayList(initialTemplates))
                    putInt(ARG_INDEX, initialIndex)
                }
            }
        }
    }
}