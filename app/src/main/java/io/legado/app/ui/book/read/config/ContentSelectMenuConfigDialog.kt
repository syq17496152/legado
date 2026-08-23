package io.legado.app.ui.book.read.config

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.R
import io.legado.app.constant.EventBus
import io.legado.app.constant.PreferKey
import io.legado.app.ui.book.read.ContentSelectConfig
import io.legado.app.ui.widget.compose.AppDialogFrame
import io.legado.app.ui.widget.compose.AppDialogStyle
import io.legado.app.ui.widget.compose.ComposeDialogFragment
import io.legado.app.ui.widget.compose.AppDialogSize
import io.legado.app.ui.widget.compose.LegadoMiuixActionButton
import io.legado.app.ui.widget.compose.rememberAppDialogStyle
import io.legado.app.ui.widget.compose.toMiuixPalette
import io.legado.app.utils.getPrefString
import io.legado.app.utils.postEvent
import io.legado.app.utils.putPrefString
import io.legado.app.utils.putPrefStringSet

class ContentSelectMenuConfigDialog : ComposeDialogFragment() {

    override val dialogSize: AppDialogSize = AppDialogSize.Form

    private data class ActionItem(
        val id: String,
        val titleRes: Int
    )

    private data class DefaultOpenItem(
        val id: String,
        val titleRes: Int
    )

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                val initialSelected = remember {
                    sanitizeActionIds(ContentSelectConfig.selectedActionIds(requireContext()))
                        .toList()
                }
                var selectedActions by rememberSaveable {
                    mutableStateOf(initialSelected)
                }
                val initialDefaultOpen = remember {
                    requireContext()
                        .getPrefString(PreferKey.contentSelectDefaultOpen, "")
                        .orEmpty()
                        .takeIf { it in defaultOpenValues }
                        .orEmpty()
                }
                var defaultOpen by rememberSaveable {
                    mutableStateOf(initialDefaultOpen)
                }
                val style = rememberAppDialogStyle()
                CompositionLocalProvider(
                    LocalTextStyle provides LocalTextStyle.current.copy(fontFamily = style.bodyFontFamily)
                ) {
                    AppDialogFrame(
                        title = stringResource(R.string.content_select_menu_config),
                        content = {
                            ContentSelectMenuContent(
                                selectedActions = selectedActions.toSet(),
                                defaultOpen = defaultOpen,
                                style = style,
                                onActionToggle = { actionId ->
                                    selectedActions = selectedActions
                                        .toMutableSet()
                                        .apply {
                                            if (!add(actionId)) {
                                                remove(actionId)
                                            }
                                        }
                                        .toList()
                                },
                                onDefaultOpenChange = { defaultOpen = it }
                            )
                        },
                        actions = {
                            val palette = style.toMiuixPalette()
                            LegadoMiuixActionButton(
                                text = stringResource(R.string.cancel),
                                palette = palette,
                                onClick = { dismissAllowingStateLoss() },
                                cornerRadius = style.actionRadius
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            LegadoMiuixActionButton(
                                text = stringResource(R.string.ok),
                                palette = palette,
                                primary = true,
                                onClick = {
                                    saveConfig(selectedActions.toSet(), defaultOpen)
                                },
                                cornerRadius = style.actionRadius
                            )
                        }
                    )
                }
            }
        }
    }

    private fun saveConfig(
        actions: Set<String>,
        defaultOpen: String
    ) {
        val selected = sanitizeActionIds(actions).toMutableSet()
        if (defaultOpen.isNotEmpty()) {
            selected += defaultOpen
        }
        if (selected.isEmpty()) {
            selected += ContentSelectConfig.ACTION_COPY
        }
        requireContext().putPrefStringSet(PreferKey.contentSelectActions, selected)
        requireContext().putPrefString(PreferKey.contentSelectDefaultOpen, defaultOpen)
        postEvent(EventBus.CONTENT_SELECT_MENU_CONFIG_CHANGED, true)
        dismissAllowingStateLoss()
    }

    @Composable
    private fun ContentSelectMenuContent(
        selectedActions: Set<String>,
        defaultOpen: String,
        style: AppDialogStyle,
        onActionToggle: (String) -> Unit,
        onDefaultOpenChange: (String) -> Unit
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            SectionTitle(
                title = stringResource(R.string.content_select_actions),
                style = style
            )
            actionItems.forEach { item ->
                CheckRow(
                    title = stringResource(item.titleRes),
                    checked = item.id in selectedActions,
                    style = style,
                    onClick = { onActionToggle(item.id) }
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            SectionTitle(
                title = stringResource(R.string.content_select_default_open),
                style = style
            )
            defaultOpenItems.forEach { item ->
                RadioRow(
                    title = stringResource(item.titleRes),
                    selected = defaultOpen == item.id,
                    style = style,
                    onClick = { onDefaultOpenChange(item.id) }
                )
            }
        }
    }

    @Composable
    private fun SectionTitle(
        title: String,
        style: AppDialogStyle
    ) {
        Text(
            text = title,
            color = style.accent,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            fontFamily = style.titleFontFamily,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 2.dp, bottom = 2.dp)
        )
    }

    @Composable
    private fun CheckRow(
        title: String,
        checked: Boolean,
        style: AppDialogStyle,
        onClick: () -> Unit
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .toggleable(
                    value = checked,
                    role = Role.Checkbox,
                    onValueChange = { onClick() }
                )
                .padding(vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = title,
                color = style.primaryText,
                fontSize = 15.sp,
                modifier = Modifier.weight(1f)
            )
            Checkbox(
                checked = checked,
                onCheckedChange = null,
                colors = CheckboxDefaults.colors(
                    checkedColor = style.accent,
                    uncheckedColor = style.secondaryText,
                    checkmarkColor = style.surface
                )
            )
        }
    }

    @Composable
    private fun RadioRow(
        title: String,
        selected: Boolean,
        style: AppDialogStyle,
        onClick: () -> Unit
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .selectable(
                    selected = selected,
                    role = Role.RadioButton,
                    onClick = onClick
                )
                .padding(vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = title,
                color = style.primaryText,
                fontSize = 15.sp,
                modifier = Modifier.weight(1f)
            )
            RadioButton(
                selected = selected,
                onClick = null,
                colors = RadioButtonDefaults.colors(
                    selectedColor = style.accent,
                    unselectedColor = style.secondaryText
                )
            )
        }
    }

    companion object {
        private val defaultOpenValues = ContentSelectConfig.defaultOpenValues

        private val actionItems = listOf(
            ActionItem(ContentSelectConfig.ACTION_REPLACE, R.string.replace),
            ActionItem(ContentSelectConfig.ACTION_COPY, R.string.copy_text),
            ActionItem(ContentSelectConfig.ACTION_WEB_SEARCH, R.string.search),
            ActionItem(ContentSelectConfig.ACTION_BOOKMARK, R.string.bookmark),
            ActionItem(ContentSelectConfig.ACTION_ALOUD, R.string.read_aloud),
            ActionItem(ContentSelectConfig.ACTION_DICT, R.string.dict),
            ActionItem(ContentSelectConfig.ACTION_ASK_AI, R.string.ask_ai),
            ActionItem(ContentSelectConfig.ACTION_GENERATE_IMAGE, R.string.ai_image_generate),
            ActionItem(ContentSelectConfig.ACTION_SHARE_IMAGE, R.string.share)
        )
        private val knownActionIds = actionItems.map { it.id }.toSet()

        private val defaultOpenItems = listOf(
            DefaultOpenItem("", R.string.default_none),
            DefaultOpenItem(ContentSelectConfig.ACTION_WEB_SEARCH, R.string.default_search),
            DefaultOpenItem(ContentSelectConfig.ACTION_DICT, R.string.default_dict),
            DefaultOpenItem(ContentSelectConfig.ACTION_ASK_AI, R.string.default_ask_ai)
        )

        private fun sanitizeActionIds(ids: Iterable<String>): Set<String> {
            return ids.filterTo(linkedSetOf()) { it in knownActionIds }
        }
    }
}
