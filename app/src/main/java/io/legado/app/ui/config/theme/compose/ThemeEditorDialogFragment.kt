package io.legado.app.ui.config.theme.compose

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.fragment.app.viewModels
import io.legado.app.R
import io.legado.app.help.IntentData
import io.legado.app.help.config.ThemeConfig
import io.legado.app.ui.theme.LegadoTheme
import io.legado.app.ui.widget.compose.AppDialogFrame
import io.legado.app.ui.widget.compose.AppDialogSize
import io.legado.app.ui.widget.compose.ComposeDialogFragment
import io.legado.app.ui.widget.compose.rememberAppDialogStyle
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject

/**
 * AD-05 Compose 主题编辑器壳：ComposeDialogFragment + ThemeEditorViewModel 接线。
 *
 * 关闭通道收敛（ui-theme-governance-polish P3，红队 R1-P0-2/R2-P1-2 整改）：
 * isCancelable=false 禁点外部直接关闭，退出只走返回键/取消按钮/保存成功三条
 * dirty 感知路径；dirty 时弹"放弃修改？"确认（AppDialogFrame 托管，不再引入 View AlertDialog）。
 *
 * @param configJson ThemeConfig.Config JSON（IntentData 传递，null = 新建）
 */
class ThemeEditorDialogFragment : ComposeDialogFragment() {

    private val viewModel: ThemeEditorViewModel by viewModels()

    override val dialogSize: AppDialogSize = AppDialogSize.Wide
    override val dialogHeight: Int = ViewGroup.LayoutParams.WRAP_CONTENT

    /** 放弃修改确认弹框显隐（Compose 状态供返回键/取消按钮双入口共享） */
    private var showDiscardConfirm by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        isCancelable = false
    }

    // 未保存拦截（返回键）：dirty 时弹确认（onBackIntercepted 钩子，红队 R1-P0-2 整改）
    override fun onBackIntercepted(): Boolean {
        if (viewModel.isDirty(viewModel.state.value)) {
            showDiscardConfirm = true
            return true
        }
        return false
    }

    private var onDismissListener: android.content.DialogInterface.OnDismissListener? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val configJson = arguments?.getString(ARG_CONFIG)?.let { key -> IntentData.get<String>(key) }
        val config = configJson?.let { json ->
            GSON.fromJsonObject<ThemeConfig.Config>(json).getOrNull()
        }
        viewModel.init(
            config = config,
            initialNight = arguments?.getBoolean(ARG_NIGHT) ?: false
        )
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                LegadoTheme {
                    Box(modifier = Modifier.fillMaxSize()) {
                        // 取消/保存按钮在壳层实现（可访问 viewModel/dirty，红队 R1-P1-12 落点裁决）
                        AppDialogFrame(
                            title = getString(R.string.theme_edit),
                            scrollContent = false,
                            content = {
                                ThemeEditorScreen(
                                    viewModel = viewModel,
                                    onSelectWallpaper = { selectWallpaper() },
                                    onCloseRequest = { requestClose() }
                                )
                            },
                            actions = {
                                EditorActionButtons(
                                    onCancel = { requestClose() },
                                    onSave = { viewModel.apply() }
                                )
                            }
                        )
                        if (showDiscardConfirm) {
                            DiscardConfirmOverlay(
                                onDiscard = {
                                    showDiscardConfirm = false
                                    dismissAllowingStateLoss()
                                },
                                onStay = { showDiscardConfirm = false }
                            )
                        }
                    }
                }
            }
        }
    }

    /** 取消/返回入口统一 dirty 检查：未脏直接关，脏则弹"放弃修改？" */
    private fun requestClose() {
        if (viewModel.isDirty(viewModel.state.value)) {
            showDiscardConfirm = true
        } else {
            dismissAllowingStateLoss()
        }
    }

    /** 编辑器底部标准双按钮（actions 槽）：取消（dirty 检查）/保存（primary） */
    @Composable
    private fun EditorActionButtons(onCancel: () -> Unit, onSave: () -> Unit) {
        val style = rememberAppDialogStyle()
        Text(
            text = stringResource(R.string.cancel),
            color = style.secondaryText,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier
                .clip(RoundedCornerShape(10.dp))
                .clickable(onClick = onCancel)
                .padding(horizontal = 18.dp, vertical = 10.dp)
        )
        Spacer(modifier = Modifier.padding(horizontal = 6.dp))
        Text(
            text = stringResource(R.string.theme_apply),
            color = style.onAccent,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier
                .clip(RoundedCornerShape(10.dp))
                .background(style.accent)
                .clickable(onClick = onSave)
                .padding(horizontal = 22.dp, vertical = 10.dp)
        )
    }

    /** "放弃修改？"确认覆盖层（AppDialogFrame 托管取色，避免再引入 View AlertDialog 孤岛） */
    @Composable
    private fun DiscardConfirmOverlay(onDiscard: () -> Unit, onStay: () -> Unit) {
        val style = rememberAppDialogStyle()
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.45f))
                .clickable(
                    interactionSource = androidx.compose.foundation.interaction.MutableInteractionSource(),
                    indication = null,
                    onClick = onStay
                ),
            contentAlignment = Alignment.Center
        ) {
            AppDialogFrame(
                title = stringResource(R.string.draw),
                content = {
                    Text(
                        text = stringResource(R.string.exit_no_save),
                        color = style.primaryText
                    )
                },
                actions = {
                    Text(
                        text = stringResource(R.string.no),
                        color = style.secondaryText,
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .clickable(onClick = onStay)
                            .padding(horizontal = 18.dp, vertical = 10.dp)
                    )
                    Spacer(modifier = Modifier.padding(horizontal = 6.dp))
                    Text(
                        text = stringResource(R.string.yes),
                        color = style.onAccent,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .background(style.accent)
                            .clickable(onClick = onDiscard)
                            .padding(horizontal = 22.dp, vertical = 10.dp)
                    )
                }
            )
        }
    }

    private fun selectWallpaper() {
        selectWallpaperLauncher.launch {
            mode = io.legado.app.ui.file.HandleFileContract.IMAGE
            title = getString(io.legado.app.R.string.theme_image_select)
        }
    }

    fun setOnDismissListener(listener: android.content.DialogInterface.OnDismissListener?) {
        this.onDismissListener = listener
    }

    override fun onDismiss(dialog: android.content.DialogInterface) {
        super.onDismiss(dialog)
        onDismissListener?.onDismiss(dialog)
    }

    private val selectWallpaperLauncher =
        registerForActivityResult(io.legado.app.ui.file.HandleFileContract()) { result ->
            result?.uri?.let { viewModel.loadFromImage(it) }
        }

    companion object {
        private const val ARG_CONFIG = "config"
        private const val ARG_NIGHT = "night"

        /**
         * @param config 编辑已有主题（null = 新建，从当前应用主题加载）
         * @param initialNight 新建时的初始编辑模式
         */
        fun create(config: ThemeConfig.Config?, initialNight: Boolean = false): ThemeEditorDialogFragment {
            return ThemeEditorDialogFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_CONFIG, config?.let { GSON.toJson(it) }?.let { IntentData.put(it) })
                    putBoolean(ARG_NIGHT, initialNight)
                }
            }
        }
    }
}
