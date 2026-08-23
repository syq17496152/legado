package io.legado.app.ui.config.compose

import android.content.SharedPreferences
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.annotation.StringRes
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.core.content.edit
import androidx.fragment.app.Fragment
import io.legado.app.R
import io.legado.app.ui.widget.compose.LegadoComposeTheme
import io.legado.app.ui.widget.compose.showComposeChoiceListDialog
import io.legado.app.utils.defaultSharedPreferences
import io.legado.app.utils.getPrefBoolean
import io.legado.app.utils.getPrefInt
import io.legado.app.utils.getPrefString
import io.legado.app.utils.putPrefBoolean
import io.legado.app.utils.putPrefInt

abstract class ComposeSettingFragment : Fragment(),
    SharedPreferences.OnSharedPreferenceChangeListener {

    @get:StringRes
    protected abstract val titleRes: Int

    protected open val applyActivityTitle: Boolean = true

    protected open val autoOpenTargetItem: Boolean = true

    protected open val drawPanelImage: Boolean = true

    private val refreshTick = mutableIntStateOf(0)
    private val scrollTargetKey = mutableStateOf<String?>(null)
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
    }

    override fun onResume() {
        super.onResume()
        prefs.registerOnSharedPreferenceChangeListener(this)
        if (applyActivityTitle) {
            activity?.setTitle(titleRes)
        }
        consumeTargetKey()
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
        view?.post {
            if (autoOpenTargetItem) {
                handleItemClick(item)
            }
            activity?.intent?.removeExtra("targetKey")
        }
    }

    private fun consumeMissingTarget() {
        if (targetKeyHandled) return
        targetKeyHandled = true
        scrollTargetKey.value = null
        activity?.intent?.removeExtra("targetKey")
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
