package io.legado.app.ui.rss.favorites

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.legado.app.R
import io.legado.app.data.entities.RssArticle
import io.legado.app.data.entities.RssStar
import io.legado.app.ui.theme.LegadoTheme
import io.legado.app.ui.widget.compose.AppDialogFrame
import io.legado.app.ui.widget.compose.AppDialogSize
import io.legado.app.ui.widget.compose.AppDialogStyle
import io.legado.app.ui.widget.compose.ComposeDialogFragment
import io.legado.app.ui.widget.compose.LegadoMiuixActionButton
import io.legado.app.ui.widget.compose.rememberAppDialogStyle
import io.legado.app.ui.widget.compose.toMiuixPalette

/**
 * 订阅收藏配置（迁移：原 BaseDialogFragment(R.layout.dialog_rss_favorite_config) 的
 * Toolbar + 双 EditText + 删除/取消/确定 迁移为 AppDialogFrame + OutlinedTextField + 操作按钮；
 * 类名/构造器/callback/Callback 接口保持不变，调用点零改动。）
 */
class RssFavoritesDialog() : ComposeDialogFragment() {

    override val dialogSize: AppDialogSize = AppDialogSize.Form

    constructor(rssArticle: RssArticle) : this() {
        arguments = Bundle().apply {
            putString("title", rssArticle.title)
            putString("group", rssArticle.group)
        }
    }

    constructor(rssStar: RssStar) : this() {
        arguments = Bundle().apply {
            putString("title", rssStar.title)
            putString("group", rssStar.group)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val hasArguments = arguments != null
        val initialTitle = arguments?.getString("title")
        val initialGroup = arguments?.getString("group")
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                LegadoTheme {
                    if (!hasArguments) {
                        // 原逻辑：无参数直接关闭
                        LaunchedEffect(Unit) { dismissAllowingStateLoss() }
                    }
                    val style = rememberAppDialogStyle()
                    var titleText by rememberSaveable { mutableStateOf(initialTitle.orEmpty()) }
                    var groupText by rememberSaveable { mutableStateOf(initialGroup.orEmpty()) }
                    AppDialogFrame(
                        title = stringResource(R.string.favorite),
                        content = {
                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                DialogTextField(
                                    value = titleText,
                                    onValueChange = { titleText = it },
                                    label = stringResource(R.string.title),
                                    style = style
                                )
                                DialogTextField(
                                    value = groupText,
                                    onValueChange = { groupText = it },
                                    label = stringResource(R.string.group_name),
                                    style = style
                                )
                            }
                        },
                        actions = {
                            val palette = style.toMiuixPalette()
                            LegadoMiuixActionButton(
                                text = stringResource(R.string.delete),
                                palette = palette,
                                onClick = {
                                    callback?.deleteFavorite()
                                    dismissAllowingStateLoss()
                                },
                                danger = true,
                                cornerRadius = style.actionRadius
                            )
                            Spacer(modifier = Modifier.width(8.dp))
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
                                onClick = {
                                    callback?.updateFavorite(
                                        if (titleText.isNotBlank()) titleText else initialTitle,
                                        if (groupText.isNotBlank()) groupText else initialGroup
                                    )
                                    dismissAllowingStateLoss()
                                },
                                primary = true,
                                cornerRadius = style.actionRadius
                            )
                        }
                    )
                }
            }
        }
    }

    val callback get() = (parentFragment as? Callback) ?: (activity as? Callback)

    interface Callback {

        fun updateFavorite(title: String?, group: String?)

        fun deleteFavorite()

    }

}

@Composable
private fun DialogTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    style: AppDialogStyle
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        shape = RoundedCornerShape(style.actionRadius),
        label = {
            Text(
                text = label,
                color = style.secondaryText,
                fontSize = MaterialTheme.typography.bodySmall.fontSize
            )
        },
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = style.primaryText,
            unfocusedTextColor = style.primaryText,
            focusedContainerColor = style.fieldSurface,
            unfocusedContainerColor = style.fieldSurface,
            cursorColor = style.accent,
            focusedBorderColor = style.accent.copy(alpha = 0.55f),
            unfocusedBorderColor = style.stroke,
            focusedLabelColor = style.accent.copy(alpha = 0.75f),
            unfocusedLabelColor = style.secondaryText
        ),
        textStyle = MaterialTheme.typography.bodyMedium.copy(
            color = style.primaryText,
            fontFamily = style.bodyFontFamily
        )
    )
}
