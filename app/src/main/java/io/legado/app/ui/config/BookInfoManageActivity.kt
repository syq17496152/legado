package io.legado.app.ui.config

import android.os.Bundle
import androidx.compose.runtime.mutableStateOf
import androidx.viewbinding.ViewBinding
import io.legado.app.R
import io.legado.app.base.BaseActivity
import io.legado.app.base.attachComposeContent
import io.legado.app.base.composeShell
import io.legado.app.help.config.BookInfoComponentConfig
import io.legado.app.help.config.BookInfoComponentItem
import io.legado.app.help.config.BookInfoPageStyle
import io.legado.app.utils.toastOnUi

class BookInfoManageActivity : BaseActivity<ViewBinding>() {

    // 原 activity_theme_manage.xml 已退役（CE-a #8）：composeShell 合成壳 + attachComposeContent 单源；
    // 本页顶栏由 `BookInfoManageScreen` 内的 `AppManagementScaffold` 自带，无需 installGlassTopBar
    override val binding: ViewBinding by lazy { composeShell(this) }

    private val styleState = mutableStateOf(BookInfoPageStyle.CLASSIC)
    private val componentsState = mutableStateOf<List<BookInfoComponentItem>>(emptyList())

    // ui-theme-governance-polish P6：管理族宿主接入背景透明度（1.5 封闭清单成员）
    override fun manageBackgroundAlphaEnabled(): Boolean = true

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        // Load initial state
        styleState.value = BookInfoComponentConfig.loadStyle()
        componentsState.value = BookInfoComponentConfig.load()

        initComposeContent()
    }

    private fun initComposeContent() {
        binding.root.attachComposeContent {
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
