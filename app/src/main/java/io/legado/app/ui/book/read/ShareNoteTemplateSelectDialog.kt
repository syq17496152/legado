package io.legado.app.ui.book.read

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.R
import io.legado.app.constant.AppLog
import io.legado.app.help.config.ShareNoteTemplateManager
import io.legado.app.ui.config.ShareNoteTemplatePreview
import io.legado.app.ui.widget.compose.AppDialogFrame
import io.legado.app.ui.widget.compose.AppManagementCard
import io.legado.app.ui.widget.compose.ComposeDialogFragment
import io.legado.app.ui.widget.compose.AppDialogSize
import io.legado.app.ui.widget.compose.LegadoMiuixActionButton
import io.legado.app.ui.widget.compose.rememberAppManagementPalette
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class ShareNoteTemplateSelectDialog : ComposeDialogFragment() {

    override val dialogSize: AppDialogSize = AppDialogSize.Form

    private var onSelected: ((ShareNoteTemplateManager.Entry) -> Unit)? = null
    private var onManage: (() -> Unit)? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val appContext = requireContext().applicationContext
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                val palette = rememberAppManagementPalette()
                var entries by remember { mutableStateOf<List<ShareNoteTemplateManager.Entry>>(emptyList()) }
                var previews by remember { mutableStateOf<Map<String, File>>(emptyMap()) }
                LaunchedEffect(Unit) {
                    val loaded = try {
                        withContext(Dispatchers.IO) { ShareNoteTemplateManager.loadEntries() }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        AppLog.put("Share note template select load failed\n${e.localizedMessage}", e)
                        return@LaunchedEffect
                    }
                    val last = ShareNoteTemplateManager.lastDirName()
                    entries = loaded.sortedBy { if (it.dirName == last) 0 else 1 }
                    loaded.forEach { entry ->
                        val file = try {
                            ShareNoteImageRenderer.renderPreview(appContext, entry)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            AppLog.put(
                                "Share note template select preview failed: ${entry.dirName}\n${e.localizedMessage}",
                                e
                            )
                            null
                        }
                        file?.let {
                            previews = previews + (entry.dirName to it)
                        }
                    }
                }
                AppDialogFrame(
                    title = "选择分享模板",
                    message = null,
                    scrollContent = false,
                    content = {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 460.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            items(entries, key = { it.dirName }) { entry ->
                                AppManagementCard(
                                    palette = palette,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            dismissAllowingStateLoss()
                                            ShareNoteTemplateManager.rememberLast(entry)
                                            onSelected?.invoke(entry)
                                        },
                                    insidePadding = PaddingValues(10.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        ShareNoteTemplatePreview(
                                            previewFile = previews[entry.dirName],
                                            palette = palette
                                        )
                                        Spacer(modifier = Modifier.width(12.dp))
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = entry.meta.name,
                                                color = palette.settings.primaryText,
                                                fontSize = 16.sp,
                                                fontWeight = FontWeight.SemiBold,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            Text(
                                                text = "${entry.meta.canvasLabel()} · ${entry.meta.sizeLabel()}",
                                                color = palette.settings.secondaryText,
                                                fontSize = 12.sp,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                modifier = Modifier.padding(top = 4.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    },
                    actions = {
                        LegadoMiuixActionButton(
                            text = "管理模板",
                            palette = palette.miuix,
                            onClick = {
                                dismissAllowingStateLoss()
                                onManage?.invoke()
                            }
                        )
                        LegadoMiuixActionButton(
                            text = getString(R.string.cancel),
                            palette = palette.miuix,
                            onClick = { dismissAllowingStateLoss() }
                        )
                    }
                )
            }
        }
    }

    companion object {
        fun create(
            onSelected: (ShareNoteTemplateManager.Entry) -> Unit,
            onManage: () -> Unit
        ): ShareNoteTemplateSelectDialog {
            return ShareNoteTemplateSelectDialog().apply {
                this.onSelected = onSelected
                this.onManage = onManage
            }
        }
    }
}
