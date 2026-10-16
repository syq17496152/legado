package io.legado.app.ui.book.source.manage

import android.view.View
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.fragment.app.activityViewModels
import io.legado.app.R
import io.legado.app.data.appDb
import io.legado.app.ui.theme.LegadoTheme
import io.legado.app.ui.widget.compose.ComposeDialogFragment
import io.legado.app.ui.widget.compose.ComposeGroupManageDialogContent
import io.legado.app.ui.widget.compose.AppDialogSize
import kotlinx.coroutines.flow.conflate

class GroupManageDialog : ComposeDialogFragment() {

    override val dialogSize: AppDialogSize = AppDialogSize.Management
    override val dialogWindowAnimations: Int = R.style.AnimDialogFade
    override val dialogHeight: Int = android.view.ViewGroup.LayoutParams.WRAP_CONTENT
    private val viewModel: BookSourceViewModel by activityViewModels()

    override fun onCreateView(
        inflater: android.view.LayoutInflater,
        container: android.view.ViewGroup?,
        savedInstanceState: android.os.Bundle?
    ): View {
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                // 宿主主题作用域前置（G-37 / 铁律 S1）：内容根必须包 `LegadoTheme`
                LegadoTheme {
                    val groups by appDb.bookSourceDao.flowGroups()
                        .conflate()
                        .collectAsState(initial = emptyList())
                    ComposeGroupManageDialogContent(
                        groups = groups,
                        onAddGroup = viewModel::addGroup,
                        onRenameGroup = { old, new -> viewModel.upGroup(old, new) },
                        onDeleteGroup = viewModel::delGroup,
                        onDismiss = { dismissAllowingStateLoss() }
                    )
                }
            }
        }
    }
}
