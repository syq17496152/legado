package io.legado.app.ui.config

import android.os.Bundle
import android.view.ViewGroup
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import io.legado.app.R
import io.legado.app.base.BaseActivity
import io.legado.app.databinding.ActivityThemeManageBinding
import io.legado.app.help.config.BookInfoComponentConfig
import io.legado.app.help.config.BookInfoComponentItem
import io.legado.app.help.config.BookInfoPageStyle
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding

class BookInfoManageActivity : BaseActivity<ActivityThemeManageBinding>() {

    override val binding by viewBinding(ActivityThemeManageBinding::inflate)

    private val styleState = mutableStateOf(BookInfoPageStyle.CLASSIC)
    private val componentsState = mutableStateOf<List<BookInfoComponentItem>>(emptyList())

    // ui-theme-governance-polish P6：管理族宿主接入背景透明度（1.5 封闭清单成员）
    override fun manageBackgroundAlphaEnabled(): Boolean = true

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        hideTopBar()

        // Load initial state
        styleState.value = BookInfoComponentConfig.loadStyle()
        componentsState.value = BookInfoComponentConfig.load()

        // Hide legacy XML views (tab_bar, tv_summary, btn_add)
        binding.tabBar.visibility = android.view.View.GONE
        binding.tvSummary.visibility = android.view.View.GONE
        binding.btnAdd.visibility = android.view.View.GONE

        initComposeContent()
    }

    // followup F5：统一管理族壳——View TitleBar 摘除（AppManagementScaffold 接管顶栏，返回由 Screen onBack 提供）
    private fun hideTopBar() {
        binding.titleBar.visibility = android.view.View.GONE
    }

    private fun initComposeContent() {
        val container = binding.recyclerView.parent as? ViewGroup ?: return
        val index = container.indexOfChild(binding.recyclerView)
        container.removeView(binding.recyclerView)

        val composeView = ComposeView(this).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setContent {
                BookInfoManageScreen(
                    style = styleState.value,
                    components = componentsState.value,
                    onBack = { finish() },
                    onStyleChanged = ::onStyleChanged,
                    onComponentToggle = ::onComponentToggle,
                    onReset = ::onReset,
                    onMoveItem = ::onMoveItem
                )
            }
        }
        container.addView(composeView, index)
    }

    private fun onStyleChanged(style: BookInfoPageStyle) {
        BookInfoComponentConfig.saveStyle(style)
        styleState.value = style
    }

    private fun onComponentToggle(index: Int, checked: Boolean) {
        val current = componentsState.value.toMutableList()
        if (index !in current.indices) return
        if (!checked && current.count { it.enabled } <= 1) {
            toastOnUi(R.string.book_info_component_keep_one)
            return
        }
        current[index] = current[index].copy(enabled = checked)
        componentsState.value = current
        BookInfoComponentConfig.save(current)
    }

    private fun onReset() {
        BookInfoComponentConfig.reset()
        componentsState.value = BookInfoComponentConfig.load()
    }

    private fun onMoveItem(fromIndex: Int, toIndex: Int) {
        val current = componentsState.value.toMutableList()
        if (fromIndex !in current.indices || toIndex !in current.indices) return
        val item = current.removeAt(fromIndex)
        current.add(toIndex, item)
        componentsState.value = current
        BookInfoComponentConfig.save(current)
    }
}
