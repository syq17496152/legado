package io.legado.app.ui.association

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.fragment.app.viewModels
import io.legado.app.R
import io.legado.app.constant.AppLog
import io.legado.app.ui.widget.compose.AppDialogFrame
import io.legado.app.ui.widget.compose.AppDialogStyle
import io.legado.app.ui.widget.compose.ComposeDialogFragment
import io.legado.app.ui.widget.compose.LegadoMiuixActionButton
import io.legado.app.ui.widget.compose.rememberAppDialogStyle
import io.legado.app.ui.widget.compose.showComposeConfirmDialog
import io.legado.app.ui.widget.compose.toMiuixPalette
import io.legado.app.utils.sendToClip
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
                // 优化 5：打开失败态（弹窗保留 + 错误行 + 复制链接出口），成功路径仍按原语义直接关闭
                var openFailed by rememberSaveable { mutableStateOf(false) }
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
                    title = stringResource(R.string.open_url_confirm_title),
                    message = stringResource(R.string.open_url_confirm_message),
                    content = {
                        val sourceTitle = subtitle
                        if (!sourceTitle.isNullOrBlank()) {
                            Text(
                                text = sourceTitle,
                                color = style.secondaryText,
                                fontSize = MaterialTheme.typography.bodySmall.fontSize
                            )
                        }
                        // 优化 4（F168）风险分级：目标 URI 直出（原实现完全不展示链接，用户只能盲点"确定"），
                        // 并按协议/mimeType 三态着色——http 明文 danger 描边、文件/应用类给 mimeType 提示、https 中性
                        UriRiskBox(
                            uri = viewModel.uri,
                            mimeType = viewModel.mimeType,
                            style = style
                        )
                        // 优化 5：打开失败不再"toast 后弹窗已消失"，改为弹窗内保留错误行 + 复制链接出口
                        if (openFailed) {
                            Text(
                                text = stringResource(R.string.open_url_failed_hint),
                                color = style.danger,
                                fontSize = MaterialTheme.typography.bodySmall.fontSize,
                                modifier = Modifier.padding(top = 8.dp)
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
                        // 失败态下主按钮让位给「复制链接」：原「确定」已证明无应用可处理，重复点击无意义
                        if (openFailed) {
                            LegadoMiuixActionButton(
                                text = stringResource(R.string.copy_link),
                                palette = palette,
                                onClick = {
                                    appCtx.sendToClip(viewModel.uri)
                                    toastOnUi(R.string.copy_link_done)
                                },
                                primary = true,
                                cornerRadius = style.actionRadius
                            )
                        } else {
                            LegadoMiuixActionButton(
                                text = stringResource(R.string.ok),
                                palette = palette,
                                onClick = { openUrl { openFailed = true } },
                                primary = true,
                                cornerRadius = style.actionRadius
                            )
                        }
                    }
                )
            }
        }
    }

    /**
     * 尝试打开目标链接。
     *
     * 优化 5（F168 伴生）：原实现在"无应用可处理"时只 toast 且弹窗已被关闭，
     * 用户既看不到失败原因也没有补救出口；现改为**不关闭弹窗**、回调宿主置失败态，
     * 由弹窗渲染错误行并提供「复制链接」。仅在真正成功启动时关闭。
     */
    private fun openUrl(onFailed: () -> Unit) {
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
                dismissAllowingStateLoss()
            } else {
                onFailed()
            }
        } catch (e: Exception) {
            AppLog.put("打开链接失败", e, true)
            onFailed()
        }
    }

    /**
     * 优化 4（F168）：目标 URI 风险分级展示。
     * 三态——`http://` 明文链接 danger 描边警示；文件/应用类（file scheme 或带 mimeType）
     * 给中性描边 + 类型说明；其余（https 等）中性描边无附加说明。
     */
    @Composable
    private fun UriRiskBox(uri: String, mimeType: String?, style: AppDialogStyle) {
        val plainHttp = uri.startsWith("http://", ignoreCase = true)
        val fileLike = !plainHttp && (
            uri.startsWith("file://", ignoreCase = true) || !mimeType.isNullOrBlank()
            )
        val shape = RoundedCornerShape(style.actionRadius)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp)
                .clip(shape)
                .border(1.dp, if (plainHttp) style.danger else style.stroke, shape)
                .padding(horizontal = 10.dp, vertical = 8.dp)
        ) {
            Text(
                text = uri,
                color = style.secondaryText,
                fontSize = MaterialTheme.typography.bodySmall.fontSize,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
            if (plainHttp) {
                Text(
                    text = stringResource(R.string.open_url_risk_plain_http),
                    color = style.danger,
                    fontSize = MaterialTheme.typography.bodySmall.fontSize,
                    modifier = Modifier.padding(top = 4.dp)
                )
            } else if (fileLike) {
                Text(
                    text = mimeType?.takeIf { it.isNotBlank() }
                        ?: stringResource(R.string.open_url_risk_file),
                    color = style.secondaryText,
                    fontSize = MaterialTheme.typography.bodySmall.fontSize,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        activity?.finish()
    }

}
