package io.legado.app.ui.book.read.config

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.constant.AppLog
import io.legado.app.data.appDb
import io.legado.app.data.entities.TtsCastingTemplate
import io.legado.app.help.readaloud.casting.TtsCastingStore
import io.legado.app.ui.book.read.config.casting.TtsCastingManageFragment
import io.legado.app.ui.theme.LegadoTheme
import io.legado.app.ui.widget.compose.AppDialogSize
import io.legado.app.ui.widget.compose.ComposeDialogFragment
import io.legado.app.ui.widget.compose.rememberAppDialogStyle
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch

/**
 * 多人听书模板选择列表器（P2-1 主入口，AD-09/S1）：
 * - 全集可见（模板名+builtin 标记+规则摘要+单选激活），替代期1 循环切换的发现性缺口
 * - 顶部常显当前实际生效来源（书级覆盖时选择仅改全局层并即时提示，S1-1/S6-1）
 * - 书级覆盖行（P2-4）：当前书上下文，bookContext 模式选择/清除覆盖，书级优先全局
 * - 循环切换（PlayerPanel Scene 闸位）保留为兼容入口，两入口写同一 setActiveTemplateId 收敛点
 */
class TtsCastingPickerDialog : ComposeDialogFragment() {

    override val dialogSize: AppDialogSize = AppDialogSize.Management

    companion object {
        /** bookUrl 为空=无书上下文（全局设置路径），书级覆盖行不可用（S6-3） */
        fun newInstance(bookUrl: String?, bookName: String?): TtsCastingPickerDialog {
            return TtsCastingPickerDialog().apply {
                arguments = Bundle().apply {
                    putString("bookUrl", bookUrl)
                    putString("bookName", bookName)
                }
            }
        }
    }

    private var bookUrl: String? = null
    private var bookName: String? = null

    private var templates by mutableStateOf<List<TtsCastingTemplate>>(emptyList())
    private var globalActiveId by mutableStateOf<String?>(null)
    private var bookOverrideId by mutableStateOf<String?>(null)
    /** 覆盖选择模式：false=选择全局激活模板；true=选择本书覆盖模板 */
    private var pickingBookOverride by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        bookUrl = arguments?.getString("bookUrl")
        bookName = arguments?.getString("bookName")
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                LegadoTheme {
                    TtsCastingPickerScreen()
                }
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        lifecycleScope.launch {
            appDb.ttsCastingTemplateDao.observeAll()
                .catch { AppLog.put("选角模板列表获取失败：${it.message}") }
                .collect { list ->
                    templates = list
                    refreshActiveState()
                }
        }
    }

    private fun refreshActiveState() {
        globalActiveId = TtsCastingStore.activeTemplateId()
        bookOverrideId = bookUrl?.let { url ->
            runCatching {
                kotlinx.coroutines.runBlocking { TtsCastingStore.resolveActiveTemplateId(url) }
            }.getOrNull()
        }
    }

    @Composable
    private fun TtsCastingPickerScreen() {
        val style = rememberAppDialogStyle()
        Column(
            Modifier
                .fillMaxWidth()
                .background(style.surface)
        ) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { dismissAllowingStateLoss() }) {
                    Icon(
                        painter = painterResource(R.drawable.ic_close_x),
                        contentDescription = stringResource(R.string.close),
                        tint = style.secondaryText
                    )
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        text = stringResource(
                            if (pickingBookOverride) R.string.tts_casting_book_override
                            else R.string.tts_casting_list_title
                        ),
                        style = MaterialTheme.typography.titleMedium,
                        color = style.primaryText,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    // 当前实际生效来源常显（S1-1 前置约束：覆盖时选择仅改全局层）
                    Text(
                        text = effectiveSourceText(),
                        style = MaterialTheme.typography.bodySmall,
                        color = style.accent,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            if (pickingBookOverride) {
                // 覆盖模式首项：清除覆盖（跟随全局，蓝本简报 3.2 titleAction/3.5 INHERIT 语义）
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable {
                            bookUrl?.let { url ->
                                TtsCastingStore.setBookOverrideTemplateId(url, null)
                                refreshActiveState()
                                pickingBookOverride = false
                                context?.toastOnUi(R.string.tts_casting_book_override_clear)
                            }
                        }
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.tts_casting_book_override_clear),
                        style = MaterialTheme.typography.bodyLarge,
                        color = style.accent
                    )
                }
            }
            LazyColumn(Modifier.fillMaxWidth()) {
                items(templates, key = { it.id }) { template ->
                    val checked = if (pickingBookOverride) {
                        bookOverrideId == template.id
                    } else {
                        globalActiveId == template.id
                    }
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable {
                                if (pickingBookOverride) {
                                    bookUrl?.let { url ->
                                        TtsCastingStore.setBookOverrideTemplateId(url, template.id)
                                    }
                                    pickingBookOverride = false
                                } else {
                                    TtsCastingStore.setActiveTemplateId(template.id)
                                    if (!bookOverrideId.isNullOrBlank()) {
                                        // 本书存在覆盖：全局切换不改变实际生效，即时提示（S1-1）
                                        context?.toastOnUi(R.string.tts_casting_active_source_book)
                                    }
                                }
                                refreshActiveState()
                            }
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = template.name,
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = style.primaryText,
                                    modifier = Modifier.weight(1f, fill = false),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                if (template.builtin) {
                                    Text(
                                        text = stringResource(R.string.tts_casting_builtin_tag),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = style.secondaryText,
                                        modifier = Modifier.padding(start = 6.dp)
                                    )
                                }
                            }
                            Text(
                                text = template.id,
                                style = MaterialTheme.typography.bodySmall,
                                color = style.secondaryText,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        if (checked) {
                            Icon(
                                painter = painterResource(R.drawable.ic_check),
                                contentDescription = null,
                                tint = style.accent
                            )
                        }
                    }
                }
            }
            // 底部：管理入口 + 书级覆盖切换
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.End
            ) {
                androidx.compose.material3.TextButton(onClick = {
                    pickingBookOverride = !pickingBookOverride
                }, enabled = !bookUrl.isNullOrBlank()) {
                    Text(
                        text = stringResource(
                            if (pickingBookOverride) R.string.tts_casting_list_title
                            else R.string.tts_casting_book_override
                        ),
                        color = style.accent,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                androidx.compose.material3.TextButton(onClick = {
                    showDialogFragment(TtsCastingManageFragment.newInstance())
                }) {
                    Text(
                        text = stringResource(R.string.tts_casting_manage_entry),
                        color = style.accent,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
    }

    /** 当前实际生效来源描述（全局/本书覆盖，S6-1 标注口径） */
    private fun effectiveSourceText(): String {
        val bookContext = bookName ?: return ""
        val overrideId = bookOverrideId
        val effectiveId = overrideId ?: globalActiveId
        if (effectiveId.isNullOrBlank()) {
            return getString(R.string.tts_casting_active_none)
        }
        val effectiveName = templates.firstOrNull { it.id == effectiveId }?.name ?: effectiveId
        return if (overrideId != null) {
            getString(R.string.tts_casting_active_book_format, bookContext, effectiveName)
        } else {
            getString(R.string.tts_casting_active_global_format, effectiveName)
        }
    }
}
