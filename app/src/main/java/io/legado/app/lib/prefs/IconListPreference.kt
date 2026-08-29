package io.legado.app.lib.prefs

import android.content.Context
import android.content.ContextWrapper
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.preference.ListPreference
import androidx.preference.PreferenceViewHolder
import io.legado.app.R
import io.legado.app.lib.theme.composeActionRadius
import io.legado.app.ui.theme.LegadoTheme
import io.legado.app.ui.widget.compose.AppDialogFrame
import io.legado.app.ui.widget.compose.AppDialogSize
import io.legado.app.ui.widget.compose.ComposeDialogFragment
import io.legado.app.ui.widget.compose.LegadoMiuixCard
import io.legado.app.ui.widget.compose.LegadoMiuixPalette
import io.legado.app.ui.widget.compose.LegadoResourceIcon
import io.legado.app.ui.widget.compose.rememberAppDialogStyle
import io.legado.app.ui.widget.compose.toMiuixPalette
import io.legado.app.utils.getCompatDrawable


class IconListPreference(context: Context, attrs: AttributeSet) : ListPreference(context, attrs) {
    private var iconNames: Array<CharSequence>
    private val mEntryDrawables = arrayListOf<Drawable?>()

    init {
        layoutResource = R.layout.view_preference
        widgetLayoutResource = R.layout.view_icon

        val a = context.theme.obtainStyledAttributes(attrs, R.styleable.IconListPreference, 0, 0)

        iconNames = try {
            a.getTextArray(R.styleable.IconListPreference_icons)
        } finally {
            a.recycle()
        }

        for (iconName in iconNames) {
            val resId = context.resources
                .getIdentifier(iconName.toString(), "mipmap", context.packageName)
            var d: Drawable? = null
            kotlin.runCatching {
                d = context.getCompatDrawable(resId)
            }
            mEntryDrawables.add(d)
        }
    }

    fun selectedIconDrawable(): Drawable? {
        val selectedIndex = findIndexOfValue(value)
        return if (selectedIndex >= 0) {
            mEntryDrawables.getOrNull(selectedIndex)
                ?.constantState
                ?.newDrawable()
                ?.mutate()
        } else {
            null
        }
    }

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)
        val v = Preference.bindView<ImageView>(
            context,
            holder,
            icon,
            title,
            summary,
            widgetLayoutResource,
            R.id.preview,
            50,
            50
        )
        if (v is ImageView) {
            val selectedIndex = findIndexOfValue(value)
            if (selectedIndex >= 0) {
                val drawable = mEntryDrawables[selectedIndex]
                v.setImageDrawable(drawable)
            }
        }
    }

    override fun onClick() {
        getActivity()?.let {
            val dialog = IconDialog().apply {
                val args = Bundle()
                args.putString("value", value)
                args.putCharSequenceArray("entries", entries)
                args.putCharSequenceArray("entryValues", entryValues)
                args.putCharSequenceArray("iconNames", iconNames)
                arguments = args
                onChanged = { value ->
                    this@IconListPreference.value = value
                }
            }
            it.supportFragmentManager
                .beginTransaction()
                .add(dialog, getFragmentTag())
                .commitAllowingStateLoss()
        }
    }

    override fun onAttached() {
        super.onAttached()
        val fragment =
            getActivity()?.supportFragmentManager?.findFragmentByTag(getFragmentTag()) as IconDialog?
        fragment?.onChanged = { value ->
            this@IconListPreference.value = value
        }
    }

    private fun getActivity(): FragmentActivity? {
        val context = context
        if (context is FragmentActivity) {
            return context
        } else if (context is ContextWrapper) {
            val baseContext = context.baseContext
            if (baseContext is FragmentActivity) {
                return baseContext
            }
        }
        return null
    }

    private fun getFragmentTag(): String {
        return "icon_$key"
    }

    /**
     * 图标选择弹框
     *
     * 迁移说明：原 BaseDialogFragment(R.layout.dialog_recycler_view) + RecyclerView(Adapter)
     * 迁移为 ComposeDialogFragment + AppDialogFrame + LazyVerticalGrid；
     * 对外成员（[onChanged] 及 arguments 键）保持不变，onChanged 在点击时读取最新值，
     * 兼容宿主 Preference 在 onAttached 中重新挂载回调的场景。
     */
    class IconDialog : ComposeDialogFragment() {

        var onChanged: ((value: String) -> Unit)? = null
        var dialogValue: String? = null
        var dialogEntries: Array<CharSequence>? = null
        var dialogEntryValues: Array<CharSequence>? = null
        var dialogIconNames: Array<CharSequence>? = null

        override val dialogSize: AppDialogSize = AppDialogSize.Confirm

        override fun onCreateView(
            inflater: LayoutInflater,
            container: ViewGroup?,
            savedInstanceState: Bundle?
        ): View {
            arguments?.let {
                dialogValue = it.getString("value")
                dialogEntries = it.getCharSequenceArray("entries")
                dialogEntryValues = it.getCharSequenceArray("entryValues")
                dialogIconNames = it.getCharSequenceArray("iconNames")
            }
            return ComposeView(requireContext()).apply {
                setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
                setContent {
                    LegadoTheme {
                        val style = rememberAppDialogStyle()
                        val palette = style.toMiuixPalette()
                        val values = dialogEntryValues.orEmpty()
                        val labels = dialogEntries.orEmpty()
                        val iconNames = dialogIconNames.orEmpty()
                        val selectedValue = dialogValue
                        AppDialogFrame(
                            title = stringResource(R.string.change_icon),
                            scrollContent = false,
                            content = {
                                LazyVerticalGrid(
                                    columns = GridCells.Adaptive(minSize = 88.dp),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(max = 420.dp),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    items(values.size) { index ->
                                        val value = values[index].toString()
                                        IconPreferenceCell(
                                            label = labels.getOrNull(index)?.toString() ?: value,
                                            iconName = iconNames.getOrNull(index)?.toString().orEmpty(),
                                            selected = value == selectedValue,
                                            palette = palette,
                                            onClick = {
                                                this@IconDialog.onChanged?.invoke(value)
                                                this@IconDialog.dismissAllowingStateLoss()
                                            }
                                        )
                                    }
                                }
                            },
                            actions = {}
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun IconPreferenceCell(
    label: String,
    iconName: String,
    selected: Boolean,
    palette: LegadoMiuixPalette,
    onClick: () -> Unit
) {
    val cornerRadius = palette.actionRadius
        ?: androidx.compose.ui.platform.LocalContext.current.composeActionRadius()
    val shape = RoundedCornerShape(cornerRadius)
    LegadoMiuixCard(
        modifier = Modifier
            .clickable(onClick = onClick)
            .border(
                width = 1.dp,
                color = if (selected) palette.accent else Color.Transparent,
                shape = shape
            ),
        color = if (selected) palette.accent.copy(alpha = 0.14f) else palette.surfaceVariant,
        contentColor = palette.primaryText,
        cornerRadius = cornerRadius,
        insidePadding = PaddingValues(horizontal = 6.dp, vertical = 10.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            LegadoResourceIcon(
                iconName = iconName,
                modifier = Modifier.size(40.dp)
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = label,
                color = if (selected) palette.accent else palette.primaryText,
                fontSize = MaterialTheme.typography.bodySmall.fontSize,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center
            )
        }
    }
}
