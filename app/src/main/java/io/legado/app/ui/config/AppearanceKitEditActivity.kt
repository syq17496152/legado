package io.legado.app.ui.config

import android.os.Bundle
import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.base.BaseActivity
import io.legado.app.databinding.ActivityThemeManageBinding
import io.legado.app.help.config.AppearanceKitEditOptions
import io.legado.app.help.config.AppearanceKitManager
import io.legado.app.help.config.ComponentRef
import io.legado.app.help.config.KitBinding
import io.legado.app.help.config.MainLayoutPresetConfig
import io.legado.app.help.config.StoredAppearanceKit
import io.legado.app.ui.widget.compose.AppSettingPalette
import io.legado.app.ui.widget.compose.AppSettingSectionTitle
import io.legado.app.ui.widget.compose.rememberAppSettingPalette
import io.legado.app.ui.widget.compose.showComposeChoiceListDialog
import io.legado.app.ui.widget.compose.showComposeConfirmDialog
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.launch

class AppearanceKitEditActivity : BaseActivity<ActivityThemeManageBinding>() {

    override val binding: ActivityThemeManageBinding by lazy {
        ActivityThemeManageBinding.inflate(layoutInflater)
    }

    private var kitState by mutableStateOf<StoredAppearanceKit?>(null)
    private var dayOptions by mutableStateOf(AppearanceKitEditOptions())
    private var nightOptions by mutableStateOf(AppearanceKitEditOptions())

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        binding.titleBar.title = getString(R.string.edit)
        binding.tabBar.visibility = View.GONE
        binding.tvSummary.visibility = View.GONE
        binding.recyclerView.visibility = View.GONE
        binding.btnAdd.visibility = View.GONE
        val composeView = ComposeView(this).apply {
            layoutParams = binding.recyclerView.layoutParams
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                kitState?.let { kit ->
                    AppearanceKitEditScreen(
                        kit = kit,
                        dayOptions = dayOptions,
                        nightOptions = nightOptions,
                        onKitChange = { kitState = it },
                        onSave = ::saveKit,
                        onDelete = ::confirmDeleteKit,
                        onSelectPreset = ::showPresetSelector,
                        onSelect = ::showSelector
                    )
                }
            }
        }
        binding.root.addView(composeView, binding.root.indexOfChild(binding.recyclerView))
        loadKit()
    }

    private fun loadKit() {
        val kitId = intent.getStringExtra(EXTRA_KIT_ID).orEmpty()
        lifecycleScope.launch {
            val kit = AppearanceKitManager.importedKit(kitId)
            if (kit == null) {
                toastOnUi(R.string.error)
                finish()
                return@launch
            }
            kitState = kit
            dayOptions = AppearanceKitManager.editableOptions(false)
            nightOptions = AppearanceKitManager.editableOptions(true)
        }
    }

    private fun saveKit() {
        val kit = kitState ?: return
        lifecycleScope.launch {
            runCatching {
                AppearanceKitManager.saveImportedKit(kit, this@AppearanceKitEditActivity)
            }.onSuccess {
                toastOnUi(if (it) R.string.success else R.string.error)
                if (it) finish()
            }.onFailure {
                toastOnUi(it.localizedMessage ?: getString(R.string.error))
            }
        }
    }

    private fun confirmDeleteKit() {
        val kit = kitState ?: return
        showComposeConfirmDialog(
            title = getString(R.string.delete),
            message = getString(R.string.appearance_kit_delete_confirm, kit.name),
            positiveText = getString(R.string.delete),
            negativeText = getString(R.string.cancel),
            dangerPositive = true,
            onPositive = { deleteKit(kit) }
        )
    }

    private fun deleteKit(kit: StoredAppearanceKit) {
        lifecycleScope.launch {
            runCatching {
                AppearanceKitManager.deleteImportedTheme(
                    this@AppearanceKitEditActivity,
                    kit.toAppearanceKit()
                )
            }.onSuccess { deleted ->
                toastOnUi(if (deleted) R.string.delete_success else R.string.error)
                if (deleted) finish()
            }.onFailure {
                toastOnUi(it.localizedMessage ?: getString(R.string.error))
            }
        }
    }

    private fun showPresetSelector(current: String?, onSelected: (String?) -> Unit) {
        val values = listOf(
            null,
            MainLayoutPresetConfig.PRESET_DEFAULT,
            MainLayoutPresetConfig.PRESET_REGULAR,
            MainLayoutPresetConfig.PRESET_SIDEBAR
        )
        val labels = listOf("默认缺省: 常规", "悬浮", "常规", "侧栏")
        showComposeChoiceListDialog(
            title = "导航样式",
            labels = labels,
            selectedIndex = values.indexOf(current).takeIf { it >= 0 } ?: 0,
            onSelected = { index -> onSelected(values.getOrNull(index)) }
        )
    }

    private fun showSelector(
        title: String,
        current: ComponentRef?,
        options: List<ComponentRef>,
        defaultLabel: String,
        onSelected: (ComponentRef?) -> Unit
    ) {
        val labels = listOf(defaultLabel) + options.map { it.name.ifBlank { it.dirName } }
        val currentIndex = current
            ?.let { value -> options.indexOfFirst { it.key() == value.key() } }
            ?.takeIf { it >= 0 }
        showComposeChoiceListDialog(
            title = title,
            labels = labels,
            selectedIndex = currentIndex?.plus(1) ?: 0,
            onSelected = { index ->
                onSelected(if (index <= 0) null else options.getOrNull(index - 1))
            }
        )
    }

    companion object {
        const val EXTRA_KIT_ID = "kitId"
    }
}

@Composable
private fun AppearanceKitEditScreen(
    kit: StoredAppearanceKit,
    dayOptions: AppearanceKitEditOptions,
    nightOptions: AppearanceKitEditOptions,
    onKitChange: (StoredAppearanceKit) -> Unit,
    onSave: () -> Unit,
    onDelete: () -> Unit,
    onSelectPreset: (String?, (String?) -> Unit) -> Unit,
    onSelect: (
        title: String,
        current: ComponentRef?,
        options: List<ComponentRef>,
        defaultLabel: String,
        onSelected: (ComponentRef?) -> Unit
    ) -> Unit
) {
    val palette = rememberAppSettingPalette()
    var name by remember(kit.id) { mutableStateOf(kit.name) }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp, 14.dp, 16.dp, 28.dp)
    ) {
        item {
            AppSettingSectionTitle(title = "应用主题", palette = palette)
            SettingPanel(palette = palette) {
                OutlinedTextField(
                    value = name,
                    onValueChange = {
                        name = it
                        onKitChange(kit.copy(name = it))
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    label = { Text("名称") },
                    singleLine = true
                )
                PresetRow(kit.binding.preset, palette) {
                    onSelectPreset(kit.binding.preset) { preset ->
                        onKitChange(kit.copy(binding = kit.binding.copy(preset = preset)))
                    }
                }
            }
            Spacer(Modifier.height(14.dp))
            PreviewPanel(kit, palette)
            Spacer(Modifier.height(14.dp))
            BindingSection("日间", kit.binding, false, dayOptions, palette, {
                onKitChange(kit.copy(binding = it))
            }, onSelect)
            Spacer(Modifier.height(14.dp))
            BindingSection("夜间", kit.binding, true, nightOptions, palette, {
                onKitChange(kit.copy(binding = it))
            }, onSelect)
            Spacer(Modifier.height(18.dp))
            SaveButton(palette, onSave)
            Spacer(Modifier.height(10.dp))
            DeleteButton(palette, onDelete)
        }
    }
}

@Composable
private fun PreviewPanel(kit: StoredAppearanceKit, palette: AppSettingPalette) {
    AppSettingSectionTitle(title = "预览", palette = palette)
    SettingPanel(palette = palette) {
        Text(
            text = kit.name.ifBlank { "应用主题" },
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            color = palette.primaryText
        )
        Text(
            text = kit.toAppearanceKit().summary,
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .padding(bottom = 12.dp),
            color = palette.secondaryText,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun SettingPanel(
    palette: AppSettingPalette,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(androidx.compose.ui.graphics.Color(palette.row)),
        content = content
    )
}

@Composable
private fun SaveButton(palette: AppSettingPalette, onClick: () -> Unit) {
    Text(
        text = "保存",
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(palette.accent)
            .clickable(onClick = onClick)
            .padding(vertical = 13.dp),
        color = palette.onAccent,
        textAlign = TextAlign.Center
    )
}

@Composable
private fun DeleteButton(palette: AppSettingPalette, onClick: () -> Unit) {
    Text(
        text = "删除",
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(androidx.compose.ui.graphics.Color(palette.row))
            .clickable(onClick = onClick)
            .padding(vertical = 13.dp),
        color = palette.danger,
        textAlign = TextAlign.Center
    )
}

@Composable
private fun PresetRow(current: String?, palette: AppSettingPalette, onClick: () -> Unit) {
    val label = when (current) {
        MainLayoutPresetConfig.PRESET_DEFAULT -> "悬浮"
        MainLayoutPresetConfig.PRESET_REGULAR -> "常规"
        MainLayoutPresetConfig.PRESET_SIDEBAR -> "侧栏"
        else -> "默认缺省: 常规"
    }
    SettingChoiceRow("导航样式", label, palette, onClick)
}

@Composable
private fun BindingSection(
    title: String,
    binding: KitBinding,
    isNight: Boolean,
    options: AppearanceKitEditOptions,
    palette: AppSettingPalette,
    onBindingChange: (KitBinding) -> Unit,
    onSelect: (
        title: String,
        current: ComponentRef?,
        options: List<ComponentRef>,
        defaultLabel: String,
        onSelected: (ComponentRef?) -> Unit
    ) -> Unit
) {
    AppSettingSectionTitle(title = title, palette = palette)
    SettingPanel(palette = palette) {
        BindingRow("界面主题", if (isNight) binding.nightTheme else binding.dayTheme, options.themes, "使用内置界面主题", palette, {
            onBindingChange(binding.copy().apply { if (isNight) nightTheme = it else dayTheme = it })
        }, onSelect)
        BindingRow("顶栏", if (isNight) binding.nightTopBar else binding.dayTopBar, options.topBars, "缺省: 常规顶栏", palette, {
            onBindingChange(binding.copy().apply { if (isNight) nightTopBar = it else dayTopBar = it })
        }, onSelect)
        BindingRow("底栏", if (isNight) binding.nightNavigationBar else binding.dayNavigationBar, options.navigationBars, "使用默认 MD3 常规底栏", palette, {
            onBindingChange(binding.copy().apply { if (isNight) nightNavigationBar = it else dayNavigationBar = it })
        }, onSelect)
        BindingRow("封面图集", if (isNight) binding.nightCoverCollection else binding.dayCoverCollection, options.coverCollections, "使用默认封面图集", palette, {
            onBindingChange(binding.copy().apply { if (isNight) nightCoverCollection = it else dayCoverCollection = it })
        }, onSelect)
    }
}

@Composable
private fun BindingRow(
    title: String,
    current: ComponentRef?,
    options: List<ComponentRef>,
    defaultLabel: String,
    palette: AppSettingPalette,
    onChange: (ComponentRef?) -> Unit,
    onSelect: (
        title: String,
        current: ComponentRef?,
        options: List<ComponentRef>,
        defaultLabel: String,
        onSelected: (ComponentRef?) -> Unit
    ) -> Unit
) {
    SettingChoiceRow(
        title = title,
        value = current?.name?.ifBlank { current.dirName } ?: defaultLabel,
        palette = palette,
        onClick = { onSelect(title, current, options, defaultLabel, onChange) }
    )
}

@Composable
private fun SettingChoiceRow(
    title: String,
    value: String,
    palette: AppSettingPalette,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Text(text = title, color = palette.primaryText)
        Text(
            text = value,
            color = palette.secondaryText,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
