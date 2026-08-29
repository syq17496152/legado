package io.legado.app.ui.book.toc.rule

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Help
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.referentialEqualityPolicy
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.fragment.app.viewModels
import io.legado.app.R
import io.legado.app.constant.AppLog
import io.legado.app.data.appDb
import io.legado.app.data.entities.TxtTocRule
import io.legado.app.model.localBook.TextFile
import io.legado.app.ui.association.ImportTxtTocRuleDialog
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.ui.qrcode.QrCodeResult
import io.legado.app.ui.widget.components.AppDropdownMenu
import io.legado.app.ui.widget.components.MenuAction
import io.legado.app.ui.widget.compose.AppDialogFrame
import io.legado.app.ui.widget.compose.ComposeDialogFragment
import io.legado.app.ui.widget.compose.ComposeSuggestionTextInputDialog
import io.legado.app.ui.widget.compose.LegadoComposeTheme
import io.legado.app.ui.widget.compose.LegadoMiuixActionButton
import io.legado.app.ui.widget.compose.LegadoMiuixCard
import io.legado.app.ui.widget.compose.LegadoMiuixSwitch
import io.legado.app.ui.widget.compose.rememberAppDialogStyle
import io.legado.app.ui.widget.compose.showComposeConfirmDialog
import io.legado.app.ui.widget.compose.toMiuixPalette
import io.legado.app.utils.ACache
import io.legado.app.utils.isAbsUrl
import io.legado.app.utils.setLayout
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.showHelp
import io.legado.app.utils.splitNotBlank
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flowOn
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

/**
 * txt目录规则（容器与列表迁移 ComposeDialogFragment + AppDialogFrame，条目拖拽排序）
 */
class TxtTocRuleDialog() : ComposeDialogFragment(),
    TxtTocRuleEditDialog.Callback {

    constructor(tocRegex: String?) : this() {
        arguments = Bundle().apply {
            putString("tocRegex", tocRegex)
        }
    }

    private val importTocRuleKey = "tocRuleUrl"
    private val viewModel: TxtTocRuleViewModel by viewModels()
    var selectedName by mutableStateOf<String?>(null)
    private val qrCodeResult = registerForActivityResult(QrCodeResult()) {
        it ?: return@registerForActivityResult
        showDialogFragment(ImportTxtTocRuleDialog(it))
    }
    private val importDoc = registerForActivityResult(HandleFileContract()) {
        it.uri?.let { uri ->
            showDialogFragment(ImportTxtTocRuleDialog(uri.toString()))
        }
    }

    override fun onStart() {
        super.onStart()
        setLayout(0.9f, 0.8f)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                LegadoComposeTheme {
                    val tocRules by produceState<List<TxtTocRule>>(
                        initialValue = emptyList()
                    ) {
                        appDb.txtTocRuleDao.observeAll().catch {
                            AppLog.put("TXT目录规则对话框获取数据失败\n${it.localizedMessage}", it)
                        }.flowOn(IO).conflate().collect { rules ->
                            initSelectedName(rules)
                            value = rules
                        }
                    }
                    TxtTocRuleContent(
                        rules = tocRules,
                        selectedName = selectedName,
                        onSelect = { rule -> selectedName = rule.name },
                        onToggleEnable = { rule, checked ->
                            rule.enable = checked
                            viewModel.update(rule)
                        },
                        onEdit = { rule ->
                            showDialogFragment(TxtTocRuleEditDialog(rule?.id))
                        },
                        onDelete = { rule -> confirmDelete(rule) },
                        onAdd = { showDialogFragment(TxtTocRuleEditDialog()) },
                        onImportLocal = {
                            importDoc.launch {
                                mode = HandleFileContract.FILE
                                allowExtensions = arrayOf("txt", "json")
                            }
                        },
                        onImportOnLine = { showImportDialog() },
                        onImportQr = { qrCodeResult.launch(Unit) },
                        onImportDefault = { viewModel.importDefault() },
                        onHelp = { showHelp("txtTocRuleHelp") },
                        onReorder = { ordered -> applyReorder(ordered) },
                        onCancel = { dismissAllowingStateLoss() },
                        onOk = { ordered -> confirmSelection(ordered) }
                    )
                }
            }
        }
    }

    private fun initSelectedName(tocRules: List<TxtTocRule>) {
        val durRegex = arguments?.getString("tocRegex") ?: return
        if (selectedName == null) {
            tocRules.forEach {
                if (durRegex == it.rule + TextFile.spaceChars + it.replacement) {
                    selectedName = it.name
                    return@forEach
                }
            }
            if (selectedName == null) {
                selectedName = ""
            }
        }
    }

    private fun confirmSelection(rules: List<TxtTocRule>) {
        rules.forEach { tocRule ->
            if (selectedName == tocRule.name) {
                val callBack = activity as? CallBack
                callBack?.onTocRegexDialogResult(tocRule.rule + TextFile.spaceChars + tocRule.replacement)
                dismissAllowingStateLoss()
                return
            }
        }
    }

    private fun applyReorder(rules: List<TxtTocRule>) {
        rules.forEachIndexed { index, item ->
            item.serialNumber = index + 1
        }
        viewModel.update(*rules.toTypedArray())
    }

    private fun confirmDelete(item: TxtTocRule) {
        showComposeConfirmDialog(
            title = getString(R.string.draw),
            message = getString(R.string.sure_del) + "\n" + item.name,
            positiveText = getString(R.string.yes),
            negativeText = getString(R.string.no),
            dangerPositive = true,
            onPositive = {
                viewModel.del(item)
            }
        )
    }

    private fun showImportDialog() {
        val aCache = ACache.get(cacheDir = false)
        val defaultUrl = "https://gitee.com/fisher52/YueDuJson/raw/master/myTxtChapterRule.json"
        val cacheUrls: MutableList<String> = aCache
            .getAsString(importTocRuleKey)
            ?.splitNotBlank(",")
            ?.toMutableList()
            ?: mutableListOf()
        if (!cacheUrls.contains(defaultUrl)) {
            cacheUrls.add(0, defaultUrl)
        }
        val dialog = ComposeSuggestionTextInputDialog.create(
            title = getString(R.string.import_on_line),
            hint = "url",
            suggestions = cacheUrls,
            deletable = true,
            positiveText = getString(R.string.ok),
            negativeText = getString(R.string.cancel),
            onSuggestionDeleted = { url ->
                cacheUrls.remove(url)
                aCache.put(importTocRuleKey, cacheUrls.joinToString(","))
            },
            onPositive = { text ->
                if (text.isAbsUrl() && !cacheUrls.contains(text)) {
                    cacheUrls.add(0, text)
                    aCache.put(importTocRuleKey, cacheUrls.joinToString(","))
                }
                showDialogFragment(ImportTxtTocRuleDialog(text))
            }
        )
        showDialogFragment(dialog)
    }

    override fun saveTxtTocRule(txtTocRule: TxtTocRule) {
        viewModel.save(txtTocRule)
    }

    interface CallBack {
        fun onTocRegexDialogResult(tocRegex: String) {}
    }

}

@Composable
private fun TxtTocRuleContent(
    rules: List<TxtTocRule>,
    selectedName: String?,
    onSelect: (TxtTocRule) -> Unit,
    onToggleEnable: (TxtTocRule, Boolean) -> Unit,
    onEdit: (TxtTocRule?) -> Unit,
    onDelete: (TxtTocRule) -> Unit,
    onAdd: () -> Unit,
    onImportLocal: () -> Unit,
    onImportOnLine: () -> Unit,
    onImportQr: () -> Unit,
    onImportDefault: () -> Unit,
    onHelp: () -> Unit,
    onReorder: (List<TxtTocRule>) -> Unit,
    onCancel: () -> Unit,
    onOk: (List<TxtTocRule>) -> Unit
) {
    val style = rememberAppDialogStyle()
    val palette = style.toMiuixPalette()
    val lazyListState = rememberLazyListState()
    val rulesSnapshot = rules.toList()
    val rulesSignature = rulesSnapshot.joinToString(separator = "\u001F") { it.toString() }
    var orderedRules by remember {
        mutableStateOf(rulesSnapshot, referentialEqualityPolicy())
    }
    LaunchedEffect(rulesSignature) {
        orderedRules = rulesSnapshot
    }
    var menuExpanded by remember { mutableStateOf(false) }
    val reorderState = rememberReorderableLazyListState(lazyListState) { from, to ->
        orderedRules = orderedRules.toMutableList().apply {
            add(to.index, removeAt(from.index))
        }
    }
    AppDialogFrame(
        title = stringResource(R.string.txt_toc_rule),
        scrollContent = false,
        content = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    LegadoMiuixActionButton(
                        text = stringResource(R.string.add),
                        palette = palette,
                        onClick = onAdd,
                        cornerRadius = style.actionRadius
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Box {
                        IconButton(onClick = { menuExpanded = true }) {
                            Icon(
                                imageVector = Icons.Filled.MoreVert,
                                contentDescription = stringResource(R.string.more)
                            )
                        }
                        AppDropdownMenu(
                            expanded = menuExpanded,
                            onDismiss = { menuExpanded = false },
                            actions = listOf(
                                MenuAction(
                                    icon = Icons.Filled.Download,
                                    title = stringResource(R.string.import_local),
                                    onClick = onImportLocal
                                ),
                                MenuAction(
                                    icon = Icons.Filled.CloudDownload,
                                    title = stringResource(R.string.import_on_line),
                                    onClick = onImportOnLine
                                ),
                                MenuAction(
                                    icon = Icons.Filled.QrCodeScanner,
                                    title = stringResource(R.string.import_by_qr_code),
                                    onClick = onImportQr
                                ),
                                MenuAction(
                                    icon = Icons.Filled.Refresh,
                                    title = stringResource(R.string.import_default_rule),
                                    onClick = onImportDefault
                                ),
                                MenuAction(
                                    icon = Icons.AutoMirrored.Filled.Help,
                                    title = stringResource(R.string.help),
                                    onClick = onHelp
                                )
                            )
                        )
                    }
                }
                Spacer(modifier = Modifier.padding(top = 8.dp))
                if (orderedRules.isEmpty()) {
                    Text(
                        text = stringResource(R.string.content_empty),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        color = style.secondaryText
                    )
                } else {
                    LazyColumn(
                        state = lazyListState,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 460.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        items(orderedRules, key = { it.id }) { rule ->
                            ReorderableItem(reorderState, key = rule.id) {
                                TocRuleRow(
                                    rule = rule,
                                    selected = selectedName != null && selectedName == rule.name,
                                    onSelect = { onSelect(rule) },
                                    onToggleEnable = { checked -> onToggleEnable(rule, checked) },
                                    onEdit = { onEdit(rule) },
                                    onDelete = { onDelete(rule) },
                                    style = style,
                                    dragHandle = {
                                        Icon(
                                            painter = painterResource(R.drawable.ic_drag_handle),
                                            contentDescription = stringResource(R.string.sort),
                                            tint = style.secondaryText,
                                            modifier = Modifier
                                                .padding(end = 4.dp)
                                                .size(22.dp)
                                                .draggableHandle(
                                                    onDragStopped = { onReorder(orderedRules) }
                                                )
                                        )
                                    }
                                )
                            }
                        }
                    }
                }
            }
        },
        actions = {
            LegadoMiuixActionButton(
                text = stringResource(R.string.cancel),
                palette = palette,
                onClick = onCancel,
                cornerRadius = style.actionRadius
            )
            Spacer(modifier = Modifier.width(8.dp))
            LegadoMiuixActionButton(
                text = stringResource(R.string.ok),
                palette = palette,
                onClick = { onOk(orderedRules) },
                primary = true,
                cornerRadius = style.actionRadius
            )
        }
    )
}

@Composable
private fun TocRuleRow(
    rule: TxtTocRule,
    selected: Boolean,
    onSelect: () -> Unit,
    onToggleEnable: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    style: io.legado.app.ui.widget.compose.AppDialogStyle,
    dragHandle: @Composable () -> Unit
) {
    val palette = style.toMiuixPalette()
    LegadoMiuixCard(
        modifier = Modifier.fillMaxWidth(),
        color = style.fieldSurface,
        contentColor = style.primaryText,
        cornerRadius = style.actionRadius,
        insidePadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp)
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                dragHandle()
                RadioButton(
                    selected = selected,
                    onClick = onSelect,
                    colors = RadioButtonDefaults.colors(
                        selectedColor = style.accent,
                        unselectedColor = style.secondaryText
                    ),
                    modifier = Modifier.size(32.dp)
                )
                Text(
                    text = rule.name,
                    color = style.primaryText,
                    fontFamily = style.bodyFontFamily,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .weight(1f)
                        .clickable(onClick = onSelect)
                )
                LegadoMiuixSwitch(
                    checked = rule.enable,
                    onCheckedChange = onToggleEnable,
                    palette = palette
                )
                Spacer(modifier = Modifier.width(4.dp))
                IconButton(onClick = onEdit, modifier = Modifier.size(36.dp)) {
                    Icon(
                        painter = painterResource(R.drawable.ic_edit),
                        contentDescription = stringResource(R.string.edit),
                        tint = style.accent
                    )
                }
                IconButton(onClick = onDelete, modifier = Modifier.size(36.dp)) {
                    Icon(
                        painter = painterResource(R.drawable.ic_clear_all),
                        contentDescription = stringResource(R.string.delete),
                        tint = style.danger
                    )
                }
            }
            Text(
                text = rule.example.orEmpty(),
                color = style.secondaryText,
                fontSize = MaterialTheme.typography.labelSmall.fontSize,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 32.dp)
            )
        }
    }
}
