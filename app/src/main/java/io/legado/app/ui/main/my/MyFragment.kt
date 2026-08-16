package io.legado.app.ui.main.my

import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Help
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import io.legado.app.R
import io.legado.app.base.BaseFragment
import io.legado.app.databinding.FragmentMyConfigBinding
import io.legado.app.ui.main.MainFragmentInterface
import io.legado.app.ui.theme.LegadoTheme
import io.legado.app.ui.widget.components.AppDropdownMenu
import io.legado.app.ui.widget.components.GlassTopAppBar
import io.legado.app.ui.widget.components.MenuAction
import io.legado.app.utils.showHelp
import io.legado.app.utils.viewbindingdelegate.viewBinding

class MyFragment() : BaseFragment(R.layout.fragment_my_config), MainFragmentInterface {

    constructor(position: Int) : this() {
        val bundle = Bundle()
        bundle.putInt("position", position)
        arguments = bundle
    }

    override val position: Int? get() = arguments?.getInt("position")

    private val binding by viewBinding(FragmentMyConfigBinding::bind)
    private var menuExpanded by mutableStateOf(false)

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        initComposeTopBar()
        // 我的页 Compose 化：pre_fragment 容器替换为 ComposeView（保留 View 壳/position）
        binding.preFragment.removeAllViews()
        binding.preFragment.addView(
            ComposeView(requireContext()).apply {
                setContent {
                    LegadoTheme {
                        ProfileScreen3Level()
                    }
                }
            },
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            )
        )
    }

    // 顶栏 Compose 化：GlassTopAppBar 用 colorScheme.surface（跟随昼夜主题），替代 View TitleBar 的固定 primaryColor
    private fun initComposeTopBar() {
        binding.composeTopBar.setContent {
            LegadoTheme {
                Column(modifier = Modifier.statusBarsPadding()) {
                    GlassTopAppBar(
                        title = getString(R.string.my),
                        actions = {
                            Box {
                                IconButton(onClick = { menuExpanded = true }) {
                                    Icon(Icons.Default.MoreVert, contentDescription = null)
                                }
                                AppDropdownMenu(
                                    expanded = menuExpanded,
                                    onDismiss = { menuExpanded = false },
                                    actions = listOf(
                                        MenuAction(
                                            Icons.Default.Help,
                                            getString(R.string.help),
                                            onClick = { showHelp("appHelp") }
                                        )
                                    )
                                )
                            }
                        }
                    )
                }
            }
        }
    }
}
