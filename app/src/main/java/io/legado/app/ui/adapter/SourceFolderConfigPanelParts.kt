package io.legado.app.ui.adapter

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.legado.app.ui.widget.compose.AppDialogStyle
import io.legado.app.ui.widget.compose.LegadoMiuixCard
import androidx.compose.material3.MaterialTheme
import io.legado.app.ui.theme.bodyTertiary
import io.legado.app.ui.theme.subtitleLarge

@Composable
internal fun SourceFolderConfigPanel(
    texts: SourceFolderConfigTexts,
    style: AppDialogStyle,
    content: @Composable () -> Unit,
    actions: @Composable () -> Unit
) {
    val spec = SourceFolderConfigSpec()
    CompositionLocalProvider(
        LocalTextStyle provides LocalTextStyle.current.copy(fontFamily = style.bodyFontFamily)
    ) {
        LegadoMiuixCard(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 14.dp, vertical = 18.dp),
            color = style.surface,
            contentColor = style.primaryText,
            cornerRadius = style.panelRadius,
            insidePadding = PaddingValues(
                horizontal = spec.panelHorizontalPadding,
                vertical = spec.panelVerticalPadding
            )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .imePadding()
            ) {
                Text(
                    text = texts.title,
                    color = style.primaryText,
                    fontSize = MaterialTheme.typography.subtitleLarge.fontSize,
                    fontWeight = FontWeight.SemiBold,
                    fontFamily = style.titleFontFamily,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(10.dp))
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = spec.contentMaxHeight)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(spec.sectionGap)
                ) {
                    content()
                }
                Spacer(modifier = Modifier.height(10.dp))
                actions()
            }
        }
    }
}

@Composable
internal fun SourceFolderConfigSection(
    title: String,
    style: AppDialogStyle,
    spec: SourceFolderConfigSpec,
    content: @Composable () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(style.actionRadius),
        color = style.fieldSurface,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp
    ) {
        Column(modifier = Modifier.padding(spec.sectionPadding)) {
            Text(
                text = title,
                color = style.accent,
                fontSize = MaterialTheme.typography.bodyTertiary.fontSize,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(bottom = spec.compactGap),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            content()
        }
    }
}
