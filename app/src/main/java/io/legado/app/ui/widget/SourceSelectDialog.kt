package io.legado.app.ui.widget

import android.content.Context
import android.graphics.drawable.ColorDrawable
import android.view.KeyEvent
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import androidx.activity.ComponentDialog
import androidx.activity.OnBackPressedCallback
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.R
import io.legado.app.ui.widget.compose.LegadoMiuixCard
import io.legado.app.ui.widget.compose.LegadoMiuixChoiceRow
import io.legado.app.ui.widget.compose.rememberAppDialogStyle
import io.legado.app.ui.widget.compose.toMiuixPalette
import io.legado.app.utils.dpToPx
import io.legado.app.utils.windowSize
import splitties.systemservices.windowManager

object SourceSelectDialog {

    fun <T> show(
        context: Context,
        title: CharSequence,
        items: List<T>,
        selectedKey: String?,
        displayName: (T) -> String,
        searchTexts: (T) -> List<String>,
        itemKey: (T) -> String,
        showTitle: Boolean = true,
        onSelect: (T) -> Unit
    ) {
        if (items.isEmpty()) return
        val dialog = ComponentDialog(context).apply {
            requestWindowFeature(Window.FEATURE_NO_TITLE)
            window?.setBackgroundDrawable(ColorDrawable(android.graphics.Color.TRANSPARENT))
            window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            onBackPressedDispatcher.addCallback(
                object : OnBackPressedCallback(true) {
                    override fun handleOnBackPressed() {
                        dismiss()
                    }
                }
            )
            setOnKeyListener { _, keyCode, event ->
                if (keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
                    dismiss()
                    true
                } else {
                    false
                }
            }
        }
        dialog.setContentView(
            ComposeView(context).apply {
                setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
                setContent {
                    SourceSelectContent(
                        title = title.toString(),
                        items = items,
                        selectedKey = selectedKey,
                        displayName = displayName,
                        searchTexts = searchTexts,
                        itemKey = itemKey,
                        showTitle = showTitle,
                        onSelect = {
                            dialog.dismiss()
                            onSelect(it)
                        }
                    )
                }
            }
        )
        dialog.setOnShowListener {
            val width = minOf(
                (context.windowManager.windowSize.widthPixels * 0.94f).toInt(),
                520.dpToPx()
            )
            dialog.window?.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT)
            dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
        dialog.show()
    }
}

@Composable
private fun <T> SourceSelectContent(
    title: String,
    items: List<T>,
    selectedKey: String?,
    displayName: (T) -> String,
    searchTexts: (T) -> List<String>,
    itemKey: (T) -> String,
    showTitle: Boolean,
    onSelect: (T) -> Unit
) {
    val style = rememberAppDialogStyle()
    val palette = style.toMiuixPalette()
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    val imeBottom = with(density) { WindowInsets.ime.getBottom(this).toDp() }
    val availableHeight = (configuration.screenHeightDp.dp - imeBottom).coerceAtLeast(180.dp)
    val maxPanelHeight = (availableHeight * 0.86f).coerceAtMost(420.dp)
    val listMaxHeight = (maxPanelHeight - if (showTitle) 156.dp else 112.dp)
        .coerceIn(72.dp, 260.dp)
    var query by remember { mutableStateOf("") }
    val filteredItems = remember(items, query) {
        val key = query.trim()
        if (key.isBlank()) {
            items
        } else {
            items.filter { item ->
                searchTexts(item).any { it.contains(key, ignoreCase = true) }
            }
        }
    }
    CompositionLocalProvider(
        LocalTextStyle provides LocalTextStyle.current.copy(fontFamily = style.bodyFontFamily)
    ) {
        LegadoMiuixCard(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = maxPanelHeight)
                .padding(horizontal = 18.dp, vertical = 12.dp),
            color = style.surface,
            contentColor = style.primaryText,
            cornerRadius = style.panelRadius,
            insidePadding = PaddingValues(horizontal = 18.dp, vertical = 16.dp)
        ) {
            if (showTitle) {
                Text(
                    text = title,
                    color = style.primaryText,
                    fontSize = 19.sp,
                    fontWeight = FontWeight.SemiBold,
                    fontFamily = style.titleFontFamily,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(12.dp))
            }
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text(stringResource(R.string.screen_find)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { query = "" }) {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_clear),
                                contentDescription = null,
                                tint = style.secondaryText
                            )
                        }
                    }
                },
                textStyle = LocalTextStyle.current.copy(
                    color = style.primaryText,
                    fontSize = 15.sp
                ),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = style.primaryText,
                    unfocusedTextColor = style.primaryText,
                    focusedContainerColor = style.fieldSurface,
                    unfocusedContainerColor = style.fieldSurface,
                    focusedBorderColor = Color.Transparent,
                    unfocusedBorderColor = Color.Transparent,
                    focusedLabelColor = style.accent,
                    unfocusedLabelColor = style.secondaryText,
                    cursorColor = style.accent
                )
            )
            Spacer(modifier = Modifier.height(10.dp))
            if (filteredItems.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(minOf(180.dp, listMaxHeight)),
                    contentAlignment = androidx.compose.ui.Alignment.Center
                ) {
                    Text(
                        text = stringResource(R.string.empty),
                        color = style.secondaryText,
                        fontSize = 14.sp,
                        textAlign = TextAlign.Center
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = listMaxHeight),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    itemsIndexed(
                        items = filteredItems,
                        key = { index, item ->
                            stableSourceSelectKey(itemKey(item), originalSourceSelectIndex(items, item, index))
                        }
                    ) { _, item ->
                        LegadoMiuixChoiceRow(
                            text = displayName(item),
                            selected = itemKey(item) == selectedKey,
                            palette = palette,
                            onClick = { onSelect(item) },
                            minHeight = 46.dp
                        )
                    }
                }
            }
        }
    }
}

private fun stableSourceSelectKey(rawKey: String, index: Int): String {
    return rawKey.takeIf { it.isNotBlank() }
        ?.let { "$it#$index" }
        ?: "source-selector-$index"
}

private fun <T> originalSourceSelectIndex(items: List<T>, item: T, fallback: Int): Int {
    items.forEachIndexed { index, candidate ->
        if (candidate === item) return index
    }
    return items.indexOf(item).takeIf { it >= 0 } ?: fallback
}
