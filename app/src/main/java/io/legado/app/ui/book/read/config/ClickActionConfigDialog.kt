package io.legado.app.ui.book.read.config

import android.content.DialogInterface
import android.os.Bundle
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.OnBackPressedCallback
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.R
import io.legado.app.constant.PreferKey
import io.legado.app.help.config.AppConfig
import io.legado.app.ui.book.read.ReadBookActivity
import io.legado.app.ui.widget.compose.AppDialogStyle
import io.legado.app.ui.widget.compose.ComposeDialogFragment
import io.legado.app.ui.widget.compose.LegadoMiuixCard
import io.legado.app.ui.widget.compose.LegadoMiuixChoiceRow
import io.legado.app.ui.widget.compose.LegadoMiuixFloatingPanel
import io.legado.app.ui.widget.compose.rememberAppDialogStyle
import io.legado.app.ui.widget.compose.toMiuixPalette
import io.legado.app.utils.putPrefInt

/**
 * Click area configuration.
 */
class ClickActionConfigDialog : ComposeDialogFragment() {

    override val dialogWidth: Int = ViewGroup.LayoutParams.MATCH_PARENT
    override val dialogHeight: Int = ViewGroup.LayoutParams.MATCH_PARENT

    private var bottomDialogRegistered = false
    private var closeActionPicker: (() -> Boolean)? = null
    private var actionPickerBackCallback: OnBackPressedCallback? = null

    override fun onStart() {
        super.onStart()
        dialog?.window?.run {
            setBackgroundDrawableResource(R.color.transparent)
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        dialog?.setOnKeyListener { _, keyCode, event ->
            keyCode == KeyEvent.KEYCODE_BACK &&
                event.action == KeyEvent.ACTION_UP &&
                closeActionPicker?.invoke() == true
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        actionPickerBackCallback = object : OnBackPressedCallback(false) {
            override fun handleOnBackPressed() {
                closeActionPicker?.invoke()
            }
        }.also { callback ->
            activity?.onBackPressedDispatcher?.addCallback(viewLifecycleOwner, callback)
        }
    }

    override fun onDismiss(dialog: DialogInterface) {
        super.onDismiss(dialog)
        if (bottomDialogRegistered) {
            (activity as? ReadBookActivity)?.let { readActivity ->
                readActivity.bottomDialog--
            }
            bottomDialogRegistered = false
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        if (!bottomDialogRegistered) {
            (activity as? ReadBookActivity)?.let { readActivity ->
                readActivity.bottomDialog++
                bottomDialogRegistered = true
            }
        }
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                val style = rememberAppDialogStyle()
                CompositionLocalProvider(
                    LocalTextStyle provides LocalTextStyle.current.copy(fontFamily = style.bodyFontFamily)
                ) {
                    ClickActionConfigContent(style = style)
                }
            }
        }
    }

    override fun onDestroyView() {
        dialog?.setOnKeyListener(null)
        actionPickerBackCallback?.remove()
        actionPickerBackCallback = null
        closeActionPicker = null
        super.onDestroyView()
    }

    override fun onDestroy() {
        super.onDestroy()
        AppConfig.detectClickArea()
    }

    @Composable
    private fun ClickActionConfigContent(style: AppDialogStyle) {
        val optionLabels = actionOptions.associate { it.id to stringResource(it.titleRes) }
        var values by rememberSaveable {
            mutableStateOf(clickSlots.map { it.currentValue() })
        }
        var activeSlotIndex by remember { mutableStateOf<Int?>(null) }
        SideEffect {
            closeActionPicker = {
                if (activeSlotIndex == null) {
                    false
                } else {
                    activeSlotIndex = null
                    true
                }
            }
            actionPickerBackCallback?.isEnabled = activeSlotIndex != null
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.54f))
                .padding(6.dp)
        ) {
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                ClickActionHeader(
                    style = style,
                    onClose = { dismissAllowingStateLoss() }
                )
                ClickActionGrid(
                    values = values,
                    optionLabels = optionLabels,
                    style = style,
                    onSlotClick = { activeSlotIndex = it },
                    modifier = Modifier.weight(1f)
                )
            }

            activeSlotIndex?.let { slotIndex ->
                ActionPickerOverlay(
                    selectedAction = values.getOrNull(slotIndex) ?: -1,
                    style = style,
                    optionLabels = optionLabels,
                    onDismiss = { activeSlotIndex = null },
                    onSelected = { action ->
                        val slot = clickSlots.getOrNull(slotIndex) ?: return@ActionPickerOverlay
                        putPrefInt(slot.prefKey, action)
                        values = values.toMutableList().apply {
                            if (slotIndex in indices) {
                                this[slotIndex] = action
                            }
                        }
                        activeSlotIndex = null
                    }
                )
            }
        }
    }

    @Composable
    private fun ClickActionHeader(
        style: AppDialogStyle,
        onClose: () -> Unit
    ) {
        LegadoMiuixCard(
            modifier = Modifier.fillMaxWidth(),
            color = Color.White.copy(alpha = 0.16f),
            contentColor = Color.White,
            cornerRadius = style.actionRadius,
            insidePadding = PaddingValues(start = 16.dp, top = 5.dp, end = 6.dp, bottom = 5.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.click_regional_config),
                    color = Color.White,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    fontFamily = style.titleFontFamily,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = onClose) {
                    Icon(
                        painter = painterResource(R.drawable.ic_baseline_close),
                        contentDescription = stringResource(R.string.close),
                        tint = Color.White,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }
        }
    }

    @Composable
    private fun ClickActionGrid(
        values: List<Int>,
        optionLabels: Map<Int, String>,
        style: AppDialogStyle,
        onSlotClick: (Int) -> Unit,
        modifier: Modifier = Modifier
    ) {
        Column(
            modifier = modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            repeat(3) { rowIndex ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    repeat(3) { columnIndex ->
                        val slotIndex = rowIndex * 3 + columnIndex
                        ClickActionCell(
                            actionTitle = optionLabels[values.getOrNull(slotIndex)] ?: "",
                            style = style,
                            onClick = { onSlotClick(slotIndex) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }
    }

    @Composable
    private fun ClickActionCell(
        actionTitle: String,
        style: AppDialogStyle,
        onClick: () -> Unit,
        modifier: Modifier = Modifier
    ) {
        LegadoMiuixCard(
            modifier = modifier
                .fillMaxSize()
                .clickable(onClick = onClick),
            color = Color.White.copy(alpha = 0.14f),
            contentColor = Color.White,
            cornerRadius = style.actionRadius,
            insidePadding = PaddingValues(horizontal = 10.dp, vertical = 10.dp)
        ) {
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = actionTitle,
                    color = Color.White,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    lineHeight = 20.sp,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center
                )
            }
        }
    }

    @Composable
    private fun ActionPickerOverlay(
        selectedAction: Int,
        style: AppDialogStyle,
        optionLabels: Map<Int, String>,
        onDismiss: () -> Unit,
        onSelected: (Int) -> Unit
    ) {
        val palette = style.toMiuixPalette()
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.26f))
                .clickable(onClick = onDismiss),
            contentAlignment = Alignment.Center
        ) {
            val panelWidth = minOf(maxWidth * 0.9f, 322.dp)
            val listMaxHeight = minOf(maxHeight * 0.7f, 430.dp)
            LegadoMiuixFloatingPanel(
                visible = true,
                palette = palette,
                width = panelWidth,
                cornerRadius = style.panelRadius,
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 14.dp),
                modifier = Modifier.clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {}
                )
            ) {
                Text(
                    text = stringResource(R.string.select_action),
                    color = style.primaryText,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                    fontFamily = style.titleFontFamily,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(10.dp))
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = listMaxHeight)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    actionOptions.forEach { option ->
                        LegadoMiuixChoiceRow(
                            text = optionLabels[option.id].orEmpty(),
                            selected = option.id == selectedAction,
                            palette = palette,
                            onClick = { onSelected(option.id) },
                            minHeight = 40.dp,
                            compact = true
                        )
                    }
                }
            }
        }
    }

    private data class ActionOption(
        val id: Int,
        val titleRes: Int
    )

    private data class ClickSlot(
        val prefKey: String,
        val currentValue: () -> Int
    )

    companion object {
        private val actionOptions = listOf(
            ActionOption(-1, R.string.non_action),
            ActionOption(0, R.string.menu),
            ActionOption(1, R.string.next_page),
            ActionOption(2, R.string.prev_page),
            ActionOption(3, R.string.next_chapter),
            ActionOption(4, R.string.previous_chapter),
            ActionOption(5, R.string.read_aloud_prev_paragraph),
            ActionOption(6, R.string.read_aloud_next_paragraph),
            ActionOption(7, R.string.bookmark_add),
            ActionOption(8, R.string.edit_content),
            ActionOption(9, R.string.replace_state_change),
            ActionOption(10, R.string.chapter_list),
            ActionOption(11, R.string.search_content),
            ActionOption(12, R.string.sync_book_progress_t),
            ActionOption(13, R.string.read_aloud_pause_resume)
        )

        private val clickSlots = listOf(
            ClickSlot(PreferKey.clickActionTL) { AppConfig.clickActionTL },
            ClickSlot(PreferKey.clickActionTC) { AppConfig.clickActionTC },
            ClickSlot(PreferKey.clickActionTR) { AppConfig.clickActionTR },
            ClickSlot(PreferKey.clickActionML) { AppConfig.clickActionML },
            ClickSlot(PreferKey.clickActionMC) { AppConfig.clickActionMC },
            ClickSlot(PreferKey.clickActionMR) { AppConfig.clickActionMR },
            ClickSlot(PreferKey.clickActionBL) { AppConfig.clickActionBL },
            ClickSlot(PreferKey.clickActionBC) { AppConfig.clickActionBC },
            ClickSlot(PreferKey.clickActionBR) { AppConfig.clickActionBR }
        )
    }
}
