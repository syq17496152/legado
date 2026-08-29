package io.legado.app.ui.association

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.fragment.app.viewModels
import io.legado.app.R
import io.legado.app.constant.AppLog
import io.legado.app.ui.widget.compose.AppDialogFrame
import io.legado.app.ui.widget.compose.ComposeDialogFragment
import io.legado.app.ui.widget.compose.LegadoMiuixActionButton
import io.legado.app.ui.widget.compose.rememberAppDialogStyle
import io.legado.app.ui.widget.compose.showComposeConfirmDialog
import io.legado.app.ui.widget.compose.toMiuixPalette
import io.legado.app.utils.toastOnUi
import splitties.init.appCtx

class OpenUrlConfirmDialog() : ComposeDialogFragment() {

    constructor(
        uri: String,
        mimeType: String?,
        sourceOrigin: String? = null,
        sourceName: String? = null,
        sourceType: Int
    ) : this() {
        arguments = Bundle().apply {
            putString("uri", uri)
            putString("mimeType", mimeType)
            putString("sourceOrigin", sourceOrigin)
            putString("sourceName", sourceName)
            putInt("sourceType", sourceType)
        }
    }

    val viewModel by viewModels<OpenUrlConfirmViewModel>()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                var subtitle by rememberSaveable { mutableStateOf<String?>(null) }
                LaunchedEffect(Unit) {
                    val args = arguments
                    if (args != null) {
                        viewModel.initData(args)
                        if (viewModel.uri.isBlank()) {
                            dismissAllowingStateLoss()
                        } else {
                            subtitle = viewModel.sourceName
                        }
                    }
                }
                val style = rememberAppDialogStyle()
                AppDialogFrame(
                    title = "跳转确认",
                    message = "正在请求跳转链接/应用，是否跳转？",
                    content = {
                        val sourceTitle = subtitle
                        if (!sourceTitle.isNullOrBlank()) {
                            Text(
                                text = sourceTitle,
                                color = style.secondaryText,
                                fontSize = MaterialTheme.typography.bodySmall.fontSize
                            )
                        }
                    },
                    actions = {
                        val palette = style.toMiuixPalette()
                        LegadoMiuixActionButton(
                            text = stringResource(R.string.disable_source),
                            palette = palette,
                            onClick = {
                                viewModel.disableSource {
                                    dismissAllowingStateLoss()
                                }
                            },
                            cornerRadius = style.actionRadius
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        LegadoMiuixActionButton(
                            text = stringResource(R.string.delete_source),
                            palette = palette,
                            onClick = {
                                showComposeConfirmDialog(
                                    title = getString(R.string.draw),
                                    message = getString(R.string.sure_del) + "\n" + viewModel.sourceName,
                                    positiveText = getString(R.string.yes),
                                    negativeText = getString(R.string.no),
                                    dangerPositive = true,
                                    onPositive = {
                                        viewModel.deleteSource {
                                            dismissAllowingStateLoss()
                                        }
                                    }
                                )
                            },
                            danger = true,
                            cornerRadius = style.actionRadius
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        LegadoMiuixActionButton(
                            text = stringResource(R.string.cancel),
                            palette = palette,
                            onClick = { dismissAllowingStateLoss() },
                            cornerRadius = style.actionRadius
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        LegadoMiuixActionButton(
                            text = stringResource(R.string.ok),
                            palette = palette,
                            onClick = {
                                openUrl()
                                dismissAllowingStateLoss()
                            },
                            primary = true,
                            cornerRadius = style.actionRadius
                        )
                    }
                )
            }
        }
    }

    private fun openUrl() {
        try {
            val uri = viewModel.uri.toUri()
            val mimeType = viewModel.mimeType
            // 创建目标 Intent 并设置类型
            val targetIntent = Intent(Intent.ACTION_VIEW).apply {
                // 同时设置 Data 和 Type
                if (!mimeType.isNullOrBlank()) {
                    setDataAndType(uri, mimeType)
                } else {
                    data = uri
                }
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            // 验证是否有应用可以处理
            if (targetIntent.resolveActivity(appCtx.packageManager) != null) {
                startActivity(targetIntent)
            } else {
                toastOnUi(R.string.can_not_open)
            }
        } catch (e: Exception) {
            AppLog.put("打开链接失败", e, true)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        activity?.finish()
    }

}
