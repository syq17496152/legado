package io.legado.app.ui.config.theme.compose

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.fragment.app.viewModels
import io.legado.app.help.IntentData
import io.legado.app.help.config.ThemeConfig
import io.legado.app.ui.widget.compose.AppDialogFrame
import io.legado.app.ui.widget.compose.AppDialogSize
import io.legado.app.ui.widget.compose.ComposeDialogFragment
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject

/**
 * AD-05 Compose 主题编辑器壳：ComposeDialogFragment + ThemeEditorViewModel 接线。
 *
 * @param configJson ThemeConfig.Config JSON（IntentData 传递，null = 新建）
 */
class ThemeEditorDialogFragment : ComposeDialogFragment() {

    private val viewModel: ThemeEditorViewModel by viewModels()

    override val dialogSize: AppDialogSize = AppDialogSize.Wide
    override val dialogHeight: Int = ViewGroup.LayoutParams.WRAP_CONTENT

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
                AppDialogFrame(
                    title = getString(io.legado.app.R.string.theme_edit),
                    scrollContent = false,
                    content = {
                        ThemeEditorScreen(
                            viewModel = viewModel,
                            onSelectWallpaper = { selectWallpaper() },
                            onCloseRequest = { dismissAllowingStateLoss() }
                        )
                    },
                    actions = {}
                )
            }
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
