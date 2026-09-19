package io.legado.app.ui.config.compose

import android.content.SharedPreferences
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.core.content.edit
import androidx.fragment.app.Fragment
import io.legado.app.R
import io.legado.app.ui.config.ConfigActivity
import io.legado.app.ui.widget.components.MenuAction
import io.legado.app.ui.widget.compose.LegadoComposeTheme
import io.legado.app.ui.widget.compose.showComposeChoiceListDialog
import io.legado.app.utils.defaultSharedPreferences
import io.legado.app.utils.getPrefBoolean
import io.legado.app.utils.getPrefInt
import io.legado.app.utils.getPrefString
import io.legado.app.utils.putPrefBoolean
import io.legado.app.utils.putPrefInt
import io.legado.app.utils.toastOnUi

/**
 * Compose 设置页基类（全部 Compose 设置页的通用渲染框架）。
 *
 * 框架级能力（一次实现、全部子类零成本继承，见
 * `docs/UI/config/compose-setting/OPTIMIZATION.md`）：
 * - **页内检索**（优化 1）：顶栏搜索入口 → [SettingSpecScreen] 过滤渲染；
 *   入口仅在可见设置项 ≥ [searchMinItemCount] 时出现，避免小页面噪声。
 * - **外部定位反馈闭环**（优化 2）：定位成功给目标行 2s 描边脉冲锚点；
 *   失败给「未找到目标设置项」回执，替代原静默吞掉。
 *
 * 顶栏动作由 [ConfigActivity.setConfigMenuActions] 承载（subpage-topbar-unify AD-04）；
 * 子类附加动作一律覆写 [extraMenuActions]，**禁止**再直接调用 `setConfigMenuActions`
 * （否则会覆盖框架的检索入口）。
 */
abstract class ComposeSettingFragment : Fragment(),
    SharedPreferences.OnSharedPreferenceChangeListener {

    @get:StringRes
    protected abstract val titleRes: Int

    protected open val applyActivityTitle: Boolean = true

    protected open val autoOpenTargetItem: Boolean = true

    protected open val drawPanelImage: Boolean = true

    /** 显示页内检索入口所需的最少可见设置项数（低于该值不显示搜索图标）。 */
    protected open val searchMinItemCount: Int = 8

    private val refreshTick = mutableIntStateOf(0)
    private val scrollTargetKey = mutableStateOf<String?>(null)
    private val highlightTargetKey = mutableStateOf<String?>(null)
    private val searchActive = mutableStateOf(false)
    private val searchQuery = mutableStateOf("")
    private var targetKeyHandled = false

    protected val prefs: SharedPreferences
        get() = requireContext().defaultSharedPreferences

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return ComposeView(requireContext()).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                refreshTick.intValue
                LegadoComposeTheme {
                    SettingSpecScreen(
                        page = buildPageSpec(),
                        scrollTargetKey = scrollTargetKey.value,
                        drawPanelImage = drawPanelImage,
                        searchActive = searchActive.value,
                        searchQuery = searchQuery.value,
                        onSearchQueryChange = { searchQuery.value = it },
                        onSearchClose = {
                            searchActive.value = false
                            searchQuery.value = ""
                        },
                        highlightTargetKey = highlightTargetKey.value,
                        onTargetReady = ::handleTargetReady,
                        onTargetMissing = ::consumeMissingTarget,
                        onItemClick = ::handleItemClick
                    )
                }
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        if (applyActivityTitle) {
            activity?.setTitle(titleRes)
        }
        applyMenuActions()
    }

    override fun onResume() {
        super.onResume()
        prefs.registerOnSharedPreferenceChangeListener(this)
        if (applyActivityTitle) {
            activity?.setTitle(titleRes)
        }
        consumeTargetKey()
        applyMenuActions()
        refreshSettings()
    }

    override fun onPause() {
        prefs.unregisterOnSharedPreferenceChangeListener(this)
        super.onPause()
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
        refreshSettings()
        if (key != null) {
            onSettingPreferenceChanged(key)
        }
    }

    protected abstract fun buildPageSpec(): SettingPageSpec

    protected open fun normalizeTargetKey(rawKey: String): String = rawKey

    protected open fun onSettingPreferenceChanged(key: String) = Unit

    /** 子类附加顶栏动作（框架会自动在最前追加页内检索入口）。 */
    protected open fun extraMenuActions(): List<MenuAction> = emptyList()

    protected fun refreshSettings() {
        refreshTick.intValue += 1
    }

    protected fun booleanSetting(
        key: String,
        defaultValue: Boolean
    ): Boolean {
        return requireContext().getPrefBoolean(key, defaultValue)
    }

    protected fun stringSetting(
        key: String,
        defaultValue: String
    ): String {
        return requireContext().getPrefString(key, defaultValue) ?: defaultValue
    }

    protected fun intSetting(
        key: String,
        defaultValue: Int
    ): Int {
        return requireContext().getPrefInt(key, defaultValue)
    }

    protected fun updateBooleanSetting(
        key: String,
        value: Boolean
    ) {
        requireContext().putPrefBoolean(key, value)
    }

    protected fun updateStringSetting(
        key: String,
        value: String
    ) {
        prefs.edit { putString(key, value) }
    }

    protected fun updateIntSetting(
        key: String,
        value: Int
    ) {
        requireContext().putPrefInt(key, value)
    }

    private fun visibleItemCount(): Int {
        return buildPageSpec().sections.sumOf { section -> section.items.count { it.visible } }
    }

    private fun applyMenuActions() {
        val host = activity as? ConfigActivity ?: return
        val actions = buildList {
            if (visibleItemCount() >= searchMinItemCount) {
                add(
                    MenuAction(Icons.Default.Search, getString(R.string.search), alwaysShow = true) {
                        searchActive.value = true
                    }
                )
            }
            addAll(extraMenuActions())
        }
        host.setConfigMenuActions(actions)
    }

    private fun consumeTargetKey() {
        if (targetKeyHandled) return
        val rawTargetKey = activity?.intent?.getStringExtra("targetKey")?.trim().orEmpty()
        if (rawTargetKey.isBlank()) return
        scrollTargetKey.value = normalizeTargetKey(rawTargetKey)
    }

    private fun handleTargetReady(targetKey: String) {
        if (targetKeyHandled) return
        val item = findItem(targetKey) ?: return consumeMissingTarget()
        targetKeyHandled = true
        scrollTargetKey.value = null
        highlightTargetKey.value = targetKey
        view?.post {
            if (autoOpenTargetItem) {
                handleItemClick(item)
            }
            activity?.intent?.removeExtra("targetKey")
        }
        view?.postDelayed({
            if (highlightTargetKey.value == targetKey) {
                highlightTargetKey.value = null
            }
        }, TargetHighlightDurationMs + 200L)
    }

    private fun consumeMissingTarget() {
        if (targetKeyHandled) return
        targetKeyHandled = true
        scrollTargetKey.value = null
        activity?.intent?.removeExtra("targetKey")
        activity?.toastOnUi(R.string.settings_target_not_found)
    }

    private fun findItem(targetKey: String): SettingItemSpec? {
        return buildPageSpec().sections
            .asSequence()
            .flatMap { it.items.asSequence() }
            .firstOrNull { it.visible && (it.key == targetKey || targetKey in it.searchKeys) }
    }

    private fun handleItemClick(item: SettingItemSpec) {
        if (!item.enabled) return
        when (item) {
            is SettingActionSpec -> item.onClick()
            is SettingSwitchSpec -> item.onCheckedChange(!item.checked)
            is SettingChoiceSpec -> showChoiceDialog(item)
            is SettingSliderSpec -> Unit
        }
    }

    private fun showChoiceDialog(item: SettingChoiceSpec) {
        showComposeChoiceListDialog(
            title = item.title,
            labels = item.options.map { it.label },
            selectedIndex = item.options.indexOfFirst { it.value == item.selectedValue },
            descriptions = item.options.map { it.description?.toString().orEmpty() },
            iconNames = item.options.map { it.iconName.orEmpty() },
            negativeText = getString(R.string.cancel),
            onSelected = { index ->
                item.options.getOrNull(index)?.value?.let(item.onSelected)
            }
        )
    }
}
