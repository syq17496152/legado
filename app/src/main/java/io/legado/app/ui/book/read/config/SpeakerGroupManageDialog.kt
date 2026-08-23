package io.legado.app.ui.book.read.config

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.base.BaseActivity
import io.legado.app.base.BaseDialogFragment
import io.legado.app.data.appDb
import io.legado.app.data.entities.HttpTTS
import io.legado.app.data.entities.ReadAloudSpeakerGroup
import io.legado.app.data.entities.ReadAloudSpeakerGroupItem
import io.legado.app.help.config.AppConfig
import io.legado.app.help.readaloud.ReadAloudConfigChangeNotifier
import io.legado.app.help.readaloud.speech.SpeechVoiceCatalogRepository
import io.legado.app.help.readaloud.speech.SpeechVoiceEngineGroup
import io.legado.app.help.readaloud.speech.SpeechVoiceGroupRepository
import io.legado.app.help.readaloud.speech.SpeechVoiceOption
import io.legado.app.help.readaloud.speech.SpeechRoute
import io.legado.app.lib.theme.accentColor
import io.legado.app.lib.theme.composeActionRadius
import io.legado.app.lib.theme.composePanelRadius
import io.legado.app.lib.theme.primaryTextColor
import io.legado.app.lib.theme.secondaryTextColor
import io.legado.app.lib.theme.uiTypeface
import io.legado.app.ui.widget.compose.LegadoMiuixSwitch
import io.legado.app.ui.widget.compose.rememberAppDialogStyle
import io.legado.app.ui.widget.compose.toMiuixPalette
import io.legado.app.utils.ColorUtils
import io.legado.app.utils.setLayout
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import androidx.viewbinding.ViewBinding

class SpeakerGroupManageActivity : BaseActivity<ViewBinding>(
    fullScreen = false,
    imageBg = false
), SpeakerGroupManageActions {

    private lateinit var composeView: ComposeView
    override val binding: ViewBinding by lazy {
        composeView = ComposeView(this)
        object : ViewBinding {
            override fun getRoot(): View = composeView
        }
    }

    private var groups by mutableStateOf<List<ReadAloudSpeakerGroup>>(emptyList())
    private var items by mutableStateOf<List<ReadAloudSpeakerGroupItem>>(emptyList())
    private var httpTtsList by mutableStateOf<List<HttpTTS>>(emptyList())
    private var editorTarget by mutableStateOf<ReadAloudSpeakerGroup?>(null)
    private var editingNew by mutableStateOf(false)
    private var pickerTarget by mutableStateOf<ReadAloudSpeakerGroup?>(null)
    private var deleteTarget by mutableStateOf<ReadAloudSpeakerGroup?>(null)

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        composeView.setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        composeView.setContent {
            SpeakerGroupManageScreen(
                groups = groups,
                speakerItems = items,
                httpTtsList = httpTtsList,
                editorTarget = editorTarget,
                editingNew = editingNew,
                pickerTarget = pickerTarget,
                deleteTarget = deleteTarget,
                actions = this@SpeakerGroupManageActivity
            )
        }
        lifecycleScope.launch {
            combine(
                appDb.readAloudSpeakerGroupDao.flowGroups(),
                appDb.readAloudSpeakerGroupDao.flowItems(),
                appDb.httpTTSDao.flowAll()
            ) { groupList, itemList, ttsList ->
                Triple(groupList, itemList, ttsList)
            }
                .catch { toastOnUi(it.localizedMessage ?: "读取发言人分组失败") }
                .flowOn(IO)
                .collect { (groupList, itemList, ttsList) ->
                    groups = groupList
                    items = itemList
                    httpTtsList = ttsList
                }
        }
    }

    override fun openNewGroupEditor() {
        editingNew = true
        editorTarget = null
    }

    override fun openGroupEditor(group: ReadAloudSpeakerGroup) {
        editingNew = false
        editorTarget = group
    }

    override fun closeGroupEditor() {
        editingNew = false
        editorTarget = null
    }

    override fun saveGroup(group: ReadAloudSpeakerGroup?, name: String, enabled: Boolean) {
        val value = name.trim()
        if (value.isBlank()) {
            toastOnUi("分组名称不能为空")
            return
        }
        lifecycleScope.launch(IO) {
            val now = System.currentTimeMillis()
            val saved = (group ?: ReadAloudSpeakerGroup()).copy(
                name = value,
                enabled = if (SpeechVoiceGroupRepository.isInvalidGroupName(value)) false else enabled,
                sortOrder = group?.sortOrder ?: ((appDb.readAloudSpeakerGroupDao.maxGroupOrder() ?: -1) + 1),
                createdAt = group?.createdAt?.takeIf { it > 0L } ?: now,
                updatedAt = now
            )
            if (saved.id > 0L) appDb.readAloudSpeakerGroupDao.updateGroup(saved)
            else appDb.readAloudSpeakerGroupDao.insertGroup(saved)
            notifyConfigChanged()
            launch(kotlinx.coroutines.Dispatchers.Main) { closeGroupEditor() }
        }
    }

    override fun toggleGroup(group: ReadAloudSpeakerGroup) {
        if (SpeechVoiceGroupRepository.isInvalidGroup(group)) return
        lifecycleScope.launch(IO) {
            appDb.readAloudSpeakerGroupDao.updateGroup(
                group.copy(enabled = !group.enabled, updatedAt = System.currentTimeMillis())
            )
            notifyConfigChanged()
        }
    }

    override fun requestDeleteGroup(group: ReadAloudSpeakerGroup) {
        deleteTarget = group
    }

    override fun closeDeleteGroup() {
        deleteTarget = null
    }

    override fun deleteGroup(group: ReadAloudSpeakerGroup) {
        lifecycleScope.launch(IO) {
            appDb.readAloudSpeakerGroupDao.deleteItemsByGroup(group.id)
            appDb.readAloudSpeakerGroupDao.deleteGroup(group.id)
            notifyConfigChanged()
            launch(kotlinx.coroutines.Dispatchers.Main) { closeDeleteGroup() }
        }
    }

    override fun openSpeakerPicker(group: ReadAloudSpeakerGroup) {
        pickerTarget = group
    }

    override fun closeSpeakerPicker() {
        pickerTarget = null
    }

    override fun addSpeakers(group: ReadAloudSpeakerGroup, options: List<SpeechVoiceOption>) {
        if (options.isEmpty()) {
            toastOnUi("请先选择发言人")
            return
        }
        lifecycleScope.launch(IO) {
            val now = System.currentTimeMillis()
            val existingKeys = appDb.readAloudSpeakerGroupDao.itemsByGroup(group.id)
                .map { speakerItemKey(it.engineType, it.engineValue, it.toneID, it.speakerName) }
                .toMutableSet()
            var sortOrder = (appDb.readAloudSpeakerGroupDao.maxItemOrder(group.id) ?: -1) + 1
            val newItems = options.mapNotNull { option ->
                val key = speakerItemKey(option.engineType, option.engineValue, option.toneID, option.speakerName)
                if (!existingKeys.add(key)) null
                else SpeechVoiceGroupRepository.itemFromOption(group.id, option, sortOrder++, now)
            }
            if (newItems.isNotEmpty()) {
                appDb.readAloudSpeakerGroupDao.insertItems(newItems)
                notifyConfigChanged()
            }
            launch(kotlinx.coroutines.Dispatchers.Main) {
                closeSpeakerPicker()
                toastOnUi("已添加 ${newItems.size} 个发言人")
            }
        }
    }

    override fun deleteItem(item: ReadAloudSpeakerGroupItem) {
        lifecycleScope.launch(IO) {
            appDb.readAloudSpeakerGroupDao.deleteItems(listOf(item.id))
            notifyConfigChanged()
        }
    }

    override fun close() {
        finish()
    }

    private fun notifyConfigChanged() {
        ReadAloudConfigChangeNotifier.notifySpeech()
    }
}

class SpeakerGroupManageDialog : BaseDialogFragment(0), SpeakerGroupManageActions {

    private var groups by mutableStateOf<List<ReadAloudSpeakerGroup>>(emptyList())
    private var items by mutableStateOf<List<ReadAloudSpeakerGroupItem>>(emptyList())
    private var httpTtsList by mutableStateOf<List<HttpTTS>>(emptyList())
    private var editorTarget by mutableStateOf<ReadAloudSpeakerGroup?>(null)
    private var editingNew by mutableStateOf(false)
    private var pickerTarget by mutableStateOf<ReadAloudSpeakerGroup?>(null)
    private var deleteTarget by mutableStateOf<ReadAloudSpeakerGroup?>(null)

    override fun onStart() {
        super.onStart()
        setLayout(ViewGroup.LayoutParams.MATCH_PARENT, 0.88f)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                SpeakerGroupManageScreen(
                    groups = groups,
                    speakerItems = items,
                    httpTtsList = httpTtsList,
                    editorTarget = editorTarget,
                    editingNew = editingNew,
                    pickerTarget = pickerTarget,
                    deleteTarget = deleteTarget,
                    actions = this@SpeakerGroupManageDialog
                )
            }
        }
    }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        lifecycleScope.launch {
            combine(
                appDb.readAloudSpeakerGroupDao.flowGroups(),
                appDb.readAloudSpeakerGroupDao.flowItems(),
                appDb.httpTTSDao.flowAll()
            ) { groupList, itemList, ttsList ->
                Triple(groupList, itemList, ttsList)
            }
                .catch { requireContext().toastOnUi(it.localizedMessage ?: "读取发言人分组失败") }
                .flowOn(IO)
                .collect { (groupList, itemList, ttsList) ->
                    groups = groupList
                    items = itemList
                    httpTtsList = ttsList
                }
        }
    }

    override fun openNewGroupEditor() {
        editingNew = true
        editorTarget = null
    }

    override fun openGroupEditor(group: ReadAloudSpeakerGroup) {
        editingNew = false
        editorTarget = group
    }

    override fun closeGroupEditor() {
        editingNew = false
        editorTarget = null
    }

    override fun saveGroup(group: ReadAloudSpeakerGroup?, name: String, enabled: Boolean) {
        val value = name.trim()
        if (value.isBlank()) {
            requireContext().toastOnUi("分组名称不能为空")
            return
        }
        lifecycleScope.launch(IO) {
            val now = System.currentTimeMillis()
            val saved = (group ?: ReadAloudSpeakerGroup()).copy(
                name = value,
                enabled = if (SpeechVoiceGroupRepository.isInvalidGroupName(value)) false else enabled,
                sortOrder = group?.sortOrder ?: ((appDb.readAloudSpeakerGroupDao.maxGroupOrder() ?: -1) + 1),
                createdAt = group?.createdAt?.takeIf { it > 0L } ?: now,
                updatedAt = now
            )
            if (saved.id > 0L) {
                appDb.readAloudSpeakerGroupDao.updateGroup(saved)
            } else {
                appDb.readAloudSpeakerGroupDao.insertGroup(saved)
            }
            notifyConfigChanged()
            launch(kotlinx.coroutines.Dispatchers.Main) { closeGroupEditor() }
        }
    }

    override fun toggleGroup(group: ReadAloudSpeakerGroup) {
        if (SpeechVoiceGroupRepository.isInvalidGroup(group)) return
        lifecycleScope.launch(IO) {
            appDb.readAloudSpeakerGroupDao.updateGroup(
                group.copy(enabled = !group.enabled, updatedAt = System.currentTimeMillis())
            )
            notifyConfigChanged()
        }
    }

    override fun requestDeleteGroup(group: ReadAloudSpeakerGroup) {
        deleteTarget = group
    }

    override fun closeDeleteGroup() {
        deleteTarget = null
    }

    override fun deleteGroup(group: ReadAloudSpeakerGroup) {
        lifecycleScope.launch(IO) {
            appDb.readAloudSpeakerGroupDao.deleteItemsByGroup(group.id)
            appDb.readAloudSpeakerGroupDao.deleteGroup(group.id)
            notifyConfigChanged()
            launch(kotlinx.coroutines.Dispatchers.Main) { closeDeleteGroup() }
        }
    }

    override fun openSpeakerPicker(group: ReadAloudSpeakerGroup) {
        pickerTarget = group
    }

    override fun closeSpeakerPicker() {
        pickerTarget = null
    }

    override fun addSpeakers(group: ReadAloudSpeakerGroup, options: List<SpeechVoiceOption>) {
        if (options.isEmpty()) {
            requireContext().toastOnUi("请先选择发言人")
            return
        }
        lifecycleScope.launch(IO) {
            val now = System.currentTimeMillis()
            val existingKeys = appDb.readAloudSpeakerGroupDao.itemsByGroup(group.id)
                .map { speakerItemKey(it.engineType, it.engineValue, it.toneID, it.speakerName) }
                .toMutableSet()
            var sortOrder = (appDb.readAloudSpeakerGroupDao.maxItemOrder(group.id) ?: -1) + 1
            val newItems = options.mapNotNull { option ->
                val key = speakerItemKey(option.engineType, option.engineValue, option.toneID, option.speakerName)
                if (!existingKeys.add(key)) {
                    null
                } else {
                    SpeechVoiceGroupRepository.itemFromOption(group.id, option, sortOrder++, now)
                }
            }
            if (newItems.isNotEmpty()) {
                appDb.readAloudSpeakerGroupDao.insertItems(newItems)
                notifyConfigChanged()
            }
            launch(kotlinx.coroutines.Dispatchers.Main) {
                closeSpeakerPicker()
                requireContext().toastOnUi("已添加 ${newItems.size} 个发言人")
            }
        }
    }

    override fun deleteItem(item: ReadAloudSpeakerGroupItem) {
        lifecycleScope.launch(IO) {
            appDb.readAloudSpeakerGroupDao.deleteItems(listOf(item.id))
            notifyConfigChanged()
        }
    }

    override fun close() {
        dismissAllowingStateLoss()
    }

    private fun notifyConfigChanged() {
        ReadAloudConfigChangeNotifier.notifySpeech()
    }
}

private interface SpeakerGroupManageActions {
    fun openNewGroupEditor()
    fun openGroupEditor(group: ReadAloudSpeakerGroup)
    fun closeGroupEditor()
    fun saveGroup(group: ReadAloudSpeakerGroup?, name: String, enabled: Boolean)
    fun toggleGroup(group: ReadAloudSpeakerGroup)
    fun requestDeleteGroup(group: ReadAloudSpeakerGroup)
    fun closeDeleteGroup()
    fun deleteGroup(group: ReadAloudSpeakerGroup)
    fun openSpeakerPicker(group: ReadAloudSpeakerGroup)
    fun closeSpeakerPicker()
    fun addSpeakers(group: ReadAloudSpeakerGroup, options: List<SpeechVoiceOption>)
    fun deleteItem(item: ReadAloudSpeakerGroupItem)
    fun close()
}

@Composable
private fun SpeakerGroupManageScreen(
    groups: List<ReadAloudSpeakerGroup>,
    speakerItems: List<ReadAloudSpeakerGroupItem>,
    httpTtsList: List<HttpTTS>,
    editorTarget: ReadAloudSpeakerGroup?,
    editingNew: Boolean,
    pickerTarget: ReadAloudSpeakerGroup?,
    deleteTarget: ReadAloudSpeakerGroup?,
    actions: SpeakerGroupManageActions
) {
    val colors = rememberSpeakerManageColors()
    val context = LocalContext.current
    val engineGroups = remember(httpTtsList) {
        SpeechVoiceCatalogRepository.allGroups(context, httpTtsList, includeSystem = true)
    }
    CompositionLocalProvider(
        LocalTextStyle provides LocalTextStyle.current.copy(fontFamily = FontFamily(context.uiTypeface()))
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = colors.page,
            shape = RoundedCornerShape(0.dp)
        ) {
            Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("发言人管理", color = colors.text, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        "多角色自动分配会优先使用这里启用的发言人",
                        color = colors.subText,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
                TextButton(onClick = actions::close) { Text("关闭", color = colors.subText) }
            }
            Row(
                modifier = Modifier.padding(top = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                SpeakerActionButton("新建分组", colors, modifier = Modifier.weight(1f), onClick = actions::openNewGroupEditor)
            }
            if (groups.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("暂无发言人分组", color = colors.subText, fontSize = 14.sp)
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(top = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(groups, key = { it.id }) { group ->
                        SpeakerGroupCard(
                            group = group,
                            items = speakerItems.filter { it.groupId == group.id },
                            colors = colors,
                            actions = actions
                        )
                    }
                }
            }
        }
        if (editingNew || editorTarget != null) {
            GroupEditorDialog(
                group = editorTarget,
                colors = colors,
                onDismiss = actions::closeGroupEditor,
                onSave = { name, enabled -> actions.saveGroup(editorTarget, name, enabled) }
            )
        }
        pickerTarget?.let { group ->
            SpeakerMultiSelectDialog(
                targetGroup = group,
                engineGroups = engineGroups,
                existingItems = speakerItems.filter { it.groupId == group.id },
                colors = colors,
                onDismiss = actions::closeSpeakerPicker,
                onConfirm = { actions.addSpeakers(group, it) }
            )
        }
        deleteTarget?.let { group ->
            ConfirmDeleteGroupDialog(
                group = group,
                colors = colors,
                onDismiss = actions::closeDeleteGroup,
                onConfirm = { actions.deleteGroup(group) }
            )
            }
        }
    }
}

@Composable
private fun SpeakerGroupCard(
    group: ReadAloudSpeakerGroup,
    items: List<ReadAloudSpeakerGroupItem>,
    colors: SpeakerManageColors,
    actions: SpeakerGroupManageActions
) {
    val invalidGroup = SpeechVoiceGroupRepository.isInvalidGroup(group)
    val switchPalette = rememberAppDialogStyle().toMiuixPalette()
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = colors.card,
        shape = RoundedCornerShape(LocalContext.current.composePanelRadius()),
        border = BorderStroke(1.dp, colors.stroke)
    ) {
        Column(modifier = Modifier.padding(13.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(group.displayName(), color = colors.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        if (invalidGroup) {
                            "${items.size} 个失效发言人 · 不参与自动分配"
                        } else {
                            "${items.size} 个发言人 · ${if (group.enabled) "已启用" else "已停用"}"
                        },
                        color = colors.subText,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(top = 3.dp)
                    )
                }
                if (!invalidGroup) {
                    LegadoMiuixSwitch(
                        checked = group.enabled,
                        onCheckedChange = { actions.toggleGroup(group) },
                        palette = switchPalette
                    )
                }
            }
            if (items.isEmpty()) {
                Text(
                    if (invalidGroup) "没有被标记失效的发言人" else "这个分组还没有发言人",
                    color = colors.subText,
                    fontSize = 12.sp
                )
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items.take(8).forEach { item ->
                        SpeakerItemRow(item = item, colors = colors, onDelete = { actions.deleteItem(item) })
                    }
                    if (items.size > 8) {
                        Text("还有 ${items.size - 8} 个发言人未展开", color = colors.subText, fontSize = 11.sp)
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!invalidGroup) {
                    SpeakerActionButton("添加发言人", colors, Modifier.weight(1f)) { actions.openSpeakerPicker(group) }
                    SpeakerSubActionButton("重命名", colors, Modifier.weight(1f)) { actions.openGroupEditor(group) }
                }
                SpeakerSubActionButton("删除", colors, Modifier.weight(1f), danger = true) {
                    actions.requestDeleteGroup(group)
                }
            }
        }
    }
}

@Composable
private fun SpeakerItemRow(
    item: ReadAloudSpeakerGroupItem,
    colors: SpeakerManageColors,
    onDelete: () -> Unit
) {
    Surface(
        color = colors.page,
        shape = RoundedCornerShape(LocalContext.current.composeActionRadius()),
        border = BorderStroke(1.dp, colors.stroke)
    ) {
        val blocked = SpeechVoiceGroupRepository.isBlockedItem(item)
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(item.displayName(), color = colors.text, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    listOf(
                        "已失效".takeIf { blocked },
                        item.engineName,
                        item.sourceGroupName,
                        item.toneID
                    ).filterNotNull().filter { it.isNotBlank() }.joinToString(" · "),
                    color = colors.subText,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            TextButton(onClick = onDelete) { Text("移除", color = colors.danger, fontSize = 12.sp) }
        }
    }
}

@Composable
private fun GroupEditorDialog(
    group: ReadAloudSpeakerGroup?,
    colors: SpeakerManageColors,
    onDismiss: () -> Unit,
    onSave: (String, Boolean) -> Unit
) {
    var name by remember(group?.id) { mutableStateOf(group?.name.orEmpty()) }
    var enabled by remember(group?.id) { mutableStateOf(group?.enabled ?: true) }
    val switchPalette = rememberAppDialogStyle().toMiuixPalette()
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier.fillMaxWidth().widthIn(max = 420.dp),
            color = colors.page,
            shape = RoundedCornerShape(LocalContext.current.composePanelRadius()),
            border = BorderStroke(1.dp, colors.stroke)
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(if (group == null) "新建分组" else "编辑分组", color = colors.text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("分组名称") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("启用分组", color = colors.text, fontSize = 14.sp, modifier = Modifier.weight(1f))
                    LegadoMiuixSwitch(
                        checked = enabled,
                        onCheckedChange = { enabled = it },
                        palette = switchPalette
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    SpeakerSubActionButton("取消", colors, Modifier.weight(1f), onClick = onDismiss)
                    SpeakerActionButton("保存", colors, Modifier.weight(1f)) { onSave(name, enabled) }
                }
            }
        }
    }
}

@Composable
private fun SpeakerMultiSelectDialog(
    targetGroup: ReadAloudSpeakerGroup,
    engineGroups: List<SpeechVoiceEngineGroup>,
    existingItems: List<ReadAloudSpeakerGroupItem>,
    colors: SpeakerManageColors,
    onDismiss: () -> Unit,
    onConfirm: (List<SpeechVoiceOption>) -> Unit
) {
    val initialGroup = engineGroups.firstOrNull()
    var selectedEngineKey by remember(engineGroups) { mutableStateOf(initialGroup?.key.orEmpty()) }
    var selectedKeys by remember(targetGroup.id) { mutableStateOf<Set<String>>(emptySet()) }
    var searchText by remember(targetGroup.id) { mutableStateOf("") }
    var expandedGroupKeys by remember(selectedEngineKey) { mutableStateOf<Set<String>>(emptySet()) }
    val selectedEngine = engineGroups.firstOrNull { it.key == selectedEngineKey } ?: initialGroup
    val existingKeys = remember(existingItems) {
        existingItems.map { speakerItemKey(it.engineType, it.engineValue, it.toneID, it.speakerName) }.toSet()
    }
    val sourceGroups = remember(selectedEngine, searchText) {
        buildSpeakerSourceGroups(selectedEngine, searchText)
    }
    LaunchedEffect(selectedEngineKey, sourceGroups.firstOrNull()?.key) {
        if (expandedGroupKeys.isEmpty()) {
            expandedGroupKeys = sourceGroups.firstOrNull()?.let { setOf(it.key) }.orEmpty()
        }
    }
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier.fillMaxWidth().widthIn(max = 560.dp),
            color = colors.page,
            shape = RoundedCornerShape(LocalContext.current.composePanelRadius()),
            border = BorderStroke(1.dp, colors.stroke)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("添加发言人", color = colors.text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                        Text(targetGroup.displayName(), color = colors.subText, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    TextButton(onClick = onDismiss) { Text("关闭", color = colors.subText) }
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(top = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    engineGroups.forEach { group ->
                        EngineChip(
                            title = group.title,
                            selected = selectedEngineKey == group.key,
                            colors = colors,
                            onClick = { selectedEngineKey = group.key }
                        )
                    }
                }
                OutlinedTextField(
                    value = searchText,
                    onValueChange = { searchText = it },
                    label = { Text("搜索发言人 / 分组 / toneID") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp)
                )
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 220.dp, max = 430.dp)
                        .padding(top = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (sourceGroups.isEmpty()) {
                        item {
                            Box(
                                modifier = Modifier.fillMaxWidth().heightIn(min = 160.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text("没有匹配的发言人", color = colors.subText, fontSize = 14.sp)
                            }
                        }
                    } else {
                        sourceGroups.forEach { sourceGroup ->
                            val visibleKeys = sourceGroup.options.map {
                                speakerItemKey(it.engineType, it.engineValue, it.toneID, it.speakerName)
                            }
                            val selectableKeys = visibleKeys.filterNot { it in existingKeys }
                            val allSelected = selectableKeys.isNotEmpty() && selectableKeys.all { it in selectedKeys }
                            val expanded = searchText.isNotBlank() || sourceGroup.key in expandedGroupKeys
                            item(key = "group:${sourceGroup.key}") {
                                SpeakerSourceGroupHeaderRow(
                                    sourceGroup = sourceGroup,
                                    expanded = expanded,
                                    allSelected = allSelected,
                                    selectableCount = selectableKeys.size,
                                    selectedCount = selectableKeys.count { it in selectedKeys },
                                    colors = colors,
                                    onExpandToggle = {
                                        expandedGroupKeys = if (expanded) {
                                            expandedGroupKeys - sourceGroup.key
                                        } else {
                                            expandedGroupKeys + sourceGroup.key
                                        }
                                    },
                                    onSelectToggle = {
                                        selectedKeys = if (allSelected) {
                                            selectedKeys - selectableKeys.toSet()
                                        } else {
                                            selectedKeys + selectableKeys
                                        }
                                    }
                                )
                            }
                            if (expanded) {
                                items(
                                    sourceGroup.options,
                                    key = { option ->
                                        "speaker:${sourceGroup.key}:${speakerItemKey(option.engineType, option.engineValue, option.toneID, option.speakerName)}"
                                    }
                                ) { option ->
                                    val key = speakerItemKey(option.engineType, option.engineValue, option.toneID, option.speakerName)
                                    val exists = key in existingKeys
                                    val selected = key in selectedKeys
                                    SpeakerOptionSelectRow(
                                        option = option,
                                        selected = selected,
                                        exists = exists,
                                        colors = colors
                                    ) {
                                        if (!exists) {
                                            selectedKeys = if (selected) selectedKeys - key else selectedKeys + key
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                Row(modifier = Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    SpeakerSubActionButton("取消", colors, Modifier.weight(1f), onClick = onDismiss)
                    SpeakerActionButton("添加 ${selectedKeys.size}", colors, Modifier.weight(1f)) {
                        val picked = engineGroups
                            .flatMap { it.options }
                            .filter { option ->
                                speakerItemKey(option.engineType, option.engineValue, option.toneID, option.speakerName) in selectedKeys
                            }
                        onConfirm(picked)
                    }
                }
            }
        }
    }
}

private data class SpeakerSourceGroupUi(
    val key: String,
    val title: String,
    val subtitle: String,
    val options: List<SpeechVoiceOption>
)

@Composable
private fun SpeakerSourceGroupHeaderRow(
    sourceGroup: SpeakerSourceGroupUi,
    expanded: Boolean,
    allSelected: Boolean,
    selectableCount: Int,
    selectedCount: Int,
    colors: SpeakerManageColors,
    onExpandToggle: () -> Unit,
    onSelectToggle: () -> Unit
) {
    Surface(
        onClick = onExpandToggle,
        modifier = Modifier.fillMaxWidth(),
        color = colors.card,
        shape = RoundedCornerShape(LocalContext.current.composeActionRadius()),
        border = BorderStroke(1.dp, colors.stroke)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    sourceGroup.title,
                    color = colors.text,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    buildList {
                        add(if (expanded) "已展开" else "已收起")
                        add(sourceGroup.subtitle)
                        if (selectedCount > 0) add("已选 $selectedCount")
                    }.filter { it.isNotBlank() }.joinToString(" · "),
                    color = colors.subText,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
            TextButton(
                enabled = selectableCount > 0,
                onClick = onSelectToggle
            ) {
                Text(
                    if (allSelected) "取消全选" else "全选",
                    color = if (selectableCount > 0) colors.accent else colors.subText,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

@Composable
private fun SpeakerOptionSelectRow(
    option: SpeechVoiceOption,
    selected: Boolean,
    exists: Boolean,
    colors: SpeakerManageColors,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        color = when {
            exists -> colors.card.copy(alpha = 0.55f)
            selected -> colors.accent.copy(alpha = 0.14f)
            else -> colors.card
        },
        shape = RoundedCornerShape(LocalContext.current.composeActionRadius()),
        border = BorderStroke(1.dp, if (selected) colors.accent else colors.stroke)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Checkbox(checked = selected || exists, onCheckedChange = { onClick() }, enabled = !exists)
            Column(modifier = Modifier.weight(1f).padding(start = 4.dp)) {
                Text(option.speakerName, color = colors.text, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    listOf(option.engineName, option.groupName, option.toneID).filter { it.isNotBlank() }.joinToString(" · ")
                        .ifBlank { option.engineName },
                    color = colors.subText,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (exists) {
                Text("已在组内", color = colors.subText, fontSize = 11.sp)
            }
        }
    }
}

@Composable
private fun ConfirmDeleteGroupDialog(
    group: ReadAloudSpeakerGroup,
    colors: SpeakerManageColors,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier.fillMaxWidth().widthIn(max = 380.dp),
            color = colors.page,
            shape = RoundedCornerShape(LocalContext.current.composePanelRadius()),
            border = BorderStroke(1.dp, colors.stroke)
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("删除分组", color = colors.text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                Text("确定删除“${group.displayName()}”？不会删除原始 TTS 引擎。", color = colors.subText, fontSize = 13.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    SpeakerSubActionButton("取消", colors, Modifier.weight(1f), onClick = onDismiss)
                    SpeakerSubActionButton("删除", colors, Modifier.weight(1f), danger = true, onClick = onConfirm)
                }
            }
        }
    }
}

@Composable
private fun EngineChip(
    title: String,
    selected: Boolean,
    colors: SpeakerManageColors,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        color = if (selected) colors.accent.copy(alpha = 0.14f) else colors.card,
        shape = RoundedCornerShape(LocalContext.current.composeActionRadius()),
        border = BorderStroke(1.dp, if (selected) colors.accent else colors.stroke)
    ) {
        Text(
            title,
            color = if (selected) colors.accent else colors.text,
            fontSize = 12.sp,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun SpeakerActionButton(
    text: String,
    colors: SpeakerManageColors,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        modifier = modifier.height(42.dp),
        color = colors.accent,
        shape = RoundedCornerShape(LocalContext.current.composeActionRadius())
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(text, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun SpeakerSubActionButton(
    text: String,
    colors: SpeakerManageColors,
    modifier: Modifier = Modifier,
    danger: Boolean = false,
    onClick: () -> Unit
) {
    val tint = if (danger) colors.danger else colors.text
    Surface(
        onClick = onClick,
        modifier = modifier.height(42.dp),
        color = if (danger) colors.danger.copy(alpha = 0.10f) else colors.card,
        shape = RoundedCornerShape(LocalContext.current.composeActionRadius()),
        border = BorderStroke(1.dp, if (danger) colors.danger.copy(alpha = 0.28f) else colors.stroke)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(text, color = tint, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

private data class SpeakerManageColors(
    val page: Color,
    val card: Color,
    val text: Color,
    val subText: Color,
    val stroke: Color,
    val accent: Color,
    val danger: Color
)

@Composable
private fun rememberSpeakerManageColors(): SpeakerManageColors {
    val context = LocalContext.current
    val night = AppConfig.isNightTheme
    val accent = context.accentColor
    return SpeakerManageColors(
        page = Color(if (night) 0xff15171b.toInt() else 0xffffffff.toInt()),
        card = Color(if (night) 0xff20242a.toInt() else 0xfff6f7fa.toInt()),
        text = Color(context.primaryTextColor),
        subText = Color(context.secondaryTextColor),
        stroke = Color.Transparent,
        accent = Color(accent),
        danger = Color(ColorUtils.blendColors(0xffff4444.toInt(), accent, 0.08f))
    )
}

private fun speakerItemKey(
    engineType: String,
    engineValue: String,
    toneID: String,
    speakerName: String
): String {
    return "$engineType|$engineValue|$toneID|$speakerName"
}

private fun buildSpeakerSourceGroups(
    engine: SpeechVoiceEngineGroup?,
    query: String
): List<SpeakerSourceGroupUi> {
    if (engine == null) return emptyList()
    val normalizedQuery = query.trim()
    return engine.options
        .asSequence()
        .filter { option -> option.matchesSpeakerQuery(normalizedQuery) }
        .groupBy { option ->
            option.groupId.ifBlank { option.groupName.ifBlank { "default" } }
        }
        .map { (sourceGroupId, options) ->
            val sourceName = options.firstOrNull()?.groupName?.takeIf { it.isNotBlank() }
                ?: if (engine.engineType == SpeechRoute.ENGINE_SYSTEM) "系统默认" else "默认分组"
            SpeakerSourceGroupUi(
                key = "${engine.key}:$sourceGroupId",
                title = sourceName,
                subtitle = buildList {
                    add("${options.size} 个发言人")
                    engine.title.takeIf { it.isNotBlank() && it != sourceName }?.let(::add)
                }.joinToString(" · "),
                options = options.sortedWith(
                    compareBy<SpeechVoiceOption> { it.speakerName }
                        .thenBy { it.toneID }
                )
            )
        }
        .sortedWith(compareBy<SpeakerSourceGroupUi> { it.title == "默认分组" }.thenBy { it.title })
}

private fun SpeechVoiceOption.matchesSpeakerQuery(query: String): Boolean {
    if (query.isBlank()) return true
    return listOf(speakerName, groupName, groupId, toneID, engineName)
        .any { it.contains(query, ignoreCase = true) }
}
