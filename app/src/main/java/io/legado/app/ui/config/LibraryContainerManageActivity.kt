package io.legado.app.ui.config

import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.ViewGroup
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.base.BaseActivity
import io.legado.app.data.appDb
import io.legado.app.databinding.ActivityS3ContainerManageBinding
import io.legado.app.help.book.library.LibraryCloudBackend
import io.legado.app.help.book.library.LibraryContainerConfig
import io.legado.app.help.book.library.LibraryContainerExportCrypto
import io.legado.app.help.book.library.LibraryContainerManager
import io.legado.app.help.http.newCallResponseBody
import io.legado.app.help.http.okHttpClient
import io.legado.app.lib.cloud.S3Config
import io.legado.app.lib.cloud.S3Container
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.ui.widget.compose.AppManagementMenuAction
import io.legado.app.ui.widget.compose.showComposeActionListDialog
import io.legado.app.ui.widget.compose.showComposeConfirmDialog
import io.legado.app.ui.widget.compose.showComposeMultiChoiceDialog
import io.legado.app.ui.widget.compose.showComposeTextInputDialog
import io.legado.app.ui.widget.compose.showComposeTextFormDialogWithChecks
import io.legado.app.ui.widget.dialog.WaitDialog
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonArray
import io.legado.app.utils.fromJsonObject
import io.legado.app.utils.readText
import io.legado.app.utils.sendToClip
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.max

class LibraryContainerManageActivity : BaseActivity<ActivityS3ContainerManageBinding>() {

    override val binding by viewBinding(ActivityS3ContainerManageBinding::inflate)

    private val containersState = mutableStateOf<List<LibraryContainerConfig>>(emptyList())
    private val waitDialog by lazy { WaitDialog(this) }
    private var editingSourceUrls: MutableSet<String> = mutableSetOf()
    private var pendingExportDecryptKey: String? = null
    private val importJson = registerForActivityResult(HandleFileContract()) { result ->
        result.uri?.let { uri ->
            lifecycleScope.launch {
                runCatching { uri.readText(this@LibraryContainerManageActivity) }
                    .onSuccess { handleImportText(it) }
                    .onFailure { toastOnUi(it.localizedMessage ?: getString(R.string.wrong_format)) }
            }
        }
    }
    private val exportJson = registerForActivityResult(HandleFileContract()) { result ->
        val uri = result.uri
        if (uri == null) {
            pendingExportDecryptKey = null
        } else {
            val value = uri.toString()
            showExportResult(value, pendingExportDecryptKey)
            pendingExportDecryptKey = null
        }
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        initComposeContent()
        reload()
    }

    override fun onDestroy() {
        super.onDestroy()
        waitDialog.dismiss()
    }

    private fun initComposeContent() {
        val container = binding.root as? ViewGroup ?: return
        container.removeAllViews()
        val cv = ComposeView(this).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setContent {
                LibraryContainerManageScreen(
                    containers = containersState.value,
                    onBack = { finish() },
                    onAdd = { showEditDialog(null) },
                    onItemClick = { showEditDialog(it) },
                    pageMenuActions = ::pageMenuActions,
                    onMoreActions = ::containerActions
                )
            }
        }
        container.addView(cv)
    }

    override fun onCompatCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, MENU_IMPORT, 0, R.string.import_str).setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        menu.add(0, MENU_EXPORT_ALL, 1, "导出全部").setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        return super.onCompatCreateOptionsMenu(menu)
    }

    override fun onCompatOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            MENU_IMPORT -> {
                showImportActions()
                true
            }
            MENU_EXPORT_ALL -> {
                exportContainers(LibraryContainerManager.containers(), "library-containers.json")
                true
            }
            else -> super.onCompatOptionsItemSelected(item)
        }
    }

    private fun reload() {
        containersState.value = LibraryContainerManager.containers()
    }

    private fun showImportActions() {
        showComposeActionListDialog(
            title = getString(R.string.import_str),
            labels = listOf(
                getString(R.string.import_str) as CharSequence,
                getString(R.string.import_on_line) as CharSequence
            ),
            negativeText = getString(R.string.cancel),
            onSelected = { index ->
                when (index) {
                    0 -> importJson.launch {
                        mode = HandleFileContract.FILE
                        title = getString(R.string.import_str)
                        allowExtensions = arrayOf("json")
                    }
                    1 -> showImportUrlDialog()
                }
            }
        )
    }

    private fun pageMenuActions(): List<AppManagementMenuAction> {
        return listOf(
            AppManagementMenuAction(getString(R.string.import_str)) {
                showImportActions()
            },
            AppManagementMenuAction("导出全部") {
                exportContainers(LibraryContainerManager.containers(), "library-containers.json")
            }
        )
    }

    private fun showImportUrlDialog() {
        showComposeTextInputDialog(
            title = getString(R.string.import_on_line),
            hint = "https://...",
            positiveText = getString(android.R.string.ok),
            negativeText = getString(R.string.cancel),
            onPositive = { url ->
                val trimmed = url.trim()
                if (trimmed.isNotEmpty()) importContainersFromUrl(trimmed)
            }
        )
    }

    private fun importContainersFromUrl(url: String) {
        lifecycleScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    okHttpClient.newCallResponseBody { url(url) }.use { it.string() }
                }
            }.onSuccess { text ->
                handleImportText(text)
            }.onFailure {
                toastOnUi(it.localizedMessage ?: getString(R.string.wrong_format))
            }
        }
    }

    private fun handleImportText(text: String) {
        if (LibraryContainerExportCrypto.isEncrypted(text)) {
            showDecryptImportDialog(text)
            return
        }
        runCatching { importContainers(text, lockedImported = false) }
            .onSuccess(::showImportResult)
            .onFailure { toastOnUi(it.localizedMessage ?: getString(R.string.wrong_format)) }
    }

    private fun showDecryptImportDialog(text: String) {
        showComposeTextInputDialog(
            title = "导入加密书库容器",
            hint = "请输入解密密钥",
            minLines = 2,
            positiveText = getString(android.R.string.ok),
            negativeText = getString(R.string.cancel),
            onPositive = { key ->
                val trimmed = key.trim()
                runCatching {
                    val decrypted = LibraryContainerExportCrypto.decrypt(text, trimmed)
                    importContainers(decrypted, lockedImported = true)
                }.onSuccess(::showImportResult)
                    .onFailure { error ->
                        toastOnUi(error.localizedMessage ?: getString(R.string.wrong_format))
                    }
            }
        )
    }

    private fun showImportResult(count: Int) {
        if (count > 0) {
            reload()
            toastOnUi("已导入 $count 个书库容器")
        } else {
            toastOnUi(R.string.wrong_format)
        }
    }

    private fun importContainers(text: String, lockedImported: Boolean): Int {
        val imported = parseImportedContainers(text)
        if (imported.containers.isEmpty()) return 0
        val idMap = mutableMapOf<String, String>()
        var count = 0
        imported.containers
            .map { it.normalized() }
            .filter { it.container.endpoint.isNotBlank() && it.container.bucket.isNotBlank() }
            .forEach { config ->
                val saveConfig = if (lockedImported) {
                    config.copy(
                        container = config.container.copy(id = S3Container.newId()),
                        lockedImported = true
                    )
                } else {
                    config.copy(lockedImported = false)
                }
                val saved = LibraryContainerManager.upsert(saveConfig)
                idMap[config.id] = saved.id
                count++
            }
        imported.selectedId?.let { selectedId ->
            idMap[selectedId]?.let { LibraryContainerManager.select(it) }
        }
        return count
    }

    private fun parseImportedContainers(text: String): LibraryContainerImport {
        val raw = text.trim()
        if (raw.isBlank()) return LibraryContainerImport()
        GSON.fromJsonObject<LibraryContainerExport>(raw).getOrNull()
            ?.takeIf { it.containers.isNotEmpty() }
            ?.let { return LibraryContainerImport(it.containers, it.selectedId) }
        GSON.fromJsonArray<LibraryContainerConfig>(raw).getOrNull()
            ?.takeIf { it.isNotEmpty() }
            ?.let { return LibraryContainerImport(it) }
        GSON.fromJsonObject<LibraryContainerConfig>(raw).getOrNull()
            ?.takeIf { it.container.endpoint.isNotBlank() || it.container.bucket.isNotBlank() }
            ?.let { return LibraryContainerImport(listOf(it)) }
        GSON.fromJsonObject<S3Container>(raw).getOrNull()
            ?.takeIf { it.endpoint.isNotBlank() || it.bucket.isNotBlank() }
            ?.let { return LibraryContainerImport(listOf(LibraryContainerConfig(container = it))) }
        return LibraryContainerImport()
    }

    private fun exportContainers(items: List<LibraryContainerConfig>, fileName: String) {
        val exportableItems = items.filterNot { it.lockedImported }
        if (exportableItems.size < items.size) {
            toastOnUi("已跳过加密导入的书库容器")
        }
        if (exportableItems.isEmpty()) {
            toastOnUi("没有可导出的书库容器")
            return
        }
        val selectedId = LibraryContainerManager.selectedId()
            ?.takeIf { id -> exportableItems.any { it.id == id } }
        val payload = LibraryContainerExport(
            selectedId = selectedId,
            containers = exportableItems.map { it.normalized().copy(lockedImported = false) }
        )
        val encrypted = LibraryContainerExportCrypto.encrypt(GSON.toJson(payload))
        pendingExportDecryptKey = encrypted.decryptKey
        exportJson.launch {
            mode = HandleFileContract.EXPORT
            title = "导出书库容器"
            fileData = HandleFileContract.FileData(
                fileName,
                GSON.toJson(encrypted.payload).toByteArray(),
                "application/json"
            )
        }
    }

    private fun showExportResult(value: String, decryptKey: String?) {
        val isUrl = value.startsWith("http://", true) || value.startsWith("https://", true)
        val keyText = decryptKey.orEmpty()
        val message = buildString {
            if (isUrl) {
                append(value)
                append("\n\n")
            }
            append("解密密钥：\n")
            append(keyText)
        }
        showComposeConfirmDialog(
            title = if (isUrl) getString(R.string.upload_url) else getString(R.string.export_success),
            message = message,
            positiveText = "复制密钥",
            negativeText = getString(R.string.cancel),
            neutralText = if (isUrl) "复制URL" else null,
            onPositive = {
                sendToClip(keyText)
                toastOnUi(R.string.copy_complete)
            },
            onNeutral = if (isUrl) {
                {
                    sendToClip(value)
                    toastOnUi(R.string.copy_complete)
                }
            } else null
        )
    }

    private fun showEditDialog(item: LibraryContainerConfig?) {
        val locked = item?.lockedImported == true
        editingSourceUrls = item?.sourceUrls.orEmpty().toMutableSet()

        val title = when {
            item == null -> "添加书库容器"
            locked -> "编辑同步书源"
            else -> "编辑书库容器"
        }

        if (locked) {
            showLockedSourcePicker(item)
            return
        }

        val container = item?.container
        val password = item?.password.orEmpty()
        val minUploadChars = item?.minUploadChars ?: 1500
        val dailyUploadLimit = item?.dailyUploadLimit ?: 0

        val labels = listOf(
            getString(R.string.s3_container_name) as CharSequence,
            getString(R.string.s3_endpoint) as CharSequence,
            getString(R.string.s3_bucket) as CharSequence,
            getString(R.string.s3_access_key) as CharSequence,
            getString(R.string.s3_secret_key) as CharSequence,
            getString(R.string.s3_container_capacity_gb) as CharSequence,
            getString(R.string.s3_prefix) as CharSequence,
            getString(R.string.s3_region) as CharSequence,
            getString(R.string.s3_session_token) as CharSequence,
            "书库加密密码" as CharSequence,
            "最少自动上传字数" as CharSequence,
            "每日自动上传章节上限" as CharSequence
        )
        val initialValues = listOf(
            container?.name.orEmpty(),
            container?.endpoint.orEmpty(),
            container?.bucket.orEmpty(),
            container?.accessKey.orEmpty(),
            container?.secretKey.orEmpty(),
            capacityMbToGbText(container?.capacityMb ?: DEFAULT_CAPACITY_MB),
            container?.prefix ?: "Library",
            container?.region ?: "auto",
            container?.sessionToken.orEmpty(),
            password,
            minUploadChars.toString(),
            dailyUploadLimit.toString()
        )
        val checkboxLabels = listOf(
            getString(R.string.s3_container_enabled) as CharSequence,
            getString(R.string.s3_path_style) as CharSequence
        )
        val checkedIndices = listOfNotNull(
            if (container?.enabled ?: true) CHECK_ENABLED else null,
            if (container?.pathStyle ?: true) CHECK_PATH_STYLE else null
        ).toSet()

        showComposeTextFormDialogWithChecks(
            title = title,
            labels = labels,
            initialValues = initialValues,
            passwordFields = setOf(FIELD_SECRET_KEY, FIELD_SESSION_TOKEN),
            checkboxLabels = checkboxLabels,
            checkedIndices = checkedIndices,
            positiveText = getString(R.string.dialog_confirm),
            negativeText = getString(R.string.dialog_cancel),
            onPositive = { values, checks ->
                saveDialogItem(item, values, checks)?.let { saved ->
                    if (item == null) refreshCapacity(saved, showWait = false)
                }
            }
        )
    }

    private fun showLockedSourcePicker(item: LibraryContainerConfig) {
        lifecycleScope.launch {
            val sources = withContext(Dispatchers.IO) { appDb.bookSourceDao.allEnabled }
            val labels = sources.map { source ->
                val group = source.bookSourceGroup
                    ?.takeIf { it.isNotBlank() }
                    ?.let { " · $it" }
                    .orEmpty()
                "${source.bookSourceName}$group"
            }
            val checkedIndices = sources.mapIndexedNotNull { index, source ->
                index.takeIf { editingSourceUrls.contains(source.bookSourceUrl) }
            }.toSet()
            showComposeMultiChoiceDialog(
                title = "指定书源",
                labels = labels,
                checkedIndices = checkedIndices,
                positiveText = getString(android.R.string.ok),
                negativeText = getString(R.string.cancel),
                onPositive = { checked ->
                    val selected = sources.mapIndexedNotNull { index, source ->
                        source.bookSourceUrl.takeIf { checked.getOrNull(index) == true }
                    }.toSet()
                    editingSourceUrls = selected.toMutableSet()
                    LibraryContainerManager.upsert(
                        item.copy(sourceUrls = selected, lockedImported = true)
                    )
                    reload()
                    toastOnUi("已保存指定书源")
                }
            )
        }
    }

    private fun saveDialogItem(
        oldItem: LibraryContainerConfig?,
        fields: List<String>,
        checks: BooleanArray
    ): LibraryContainerConfig? {
        val pathStyle = checks.checkedAt(CHECK_PATH_STYLE, default = true)
        val parsed = S3Config.parseAddress(
            fields.fieldAt(FIELD_ENDPOINT),
            fields.fieldAt(FIELD_BUCKET),
            fields.fieldAt(FIELD_REGION),
            pathStyle
        )
        val capacityMb = gbTextToCapacityMb(fields.fieldAt(FIELD_CAPACITY))
        val usedBytes = oldItem?.container?.usedBytes?.coerceAtLeast(0L) ?: 0L
        if (parsed.endpoint.isBlank() || parsed.bucket.isBlank()) {
            toastOnUi(R.string.s3_container_endpoint_bucket_required)
            return null
        }
        if (fields.fieldAt(FIELD_ACCESS_KEY).isBlank()
            || fields.fieldAt(FIELD_SECRET_KEY).isBlank()
        ) {
            toastOnUi(R.string.s3_container_key_required)
            return null
        }
        val container = S3Container(
            id = oldItem?.id ?: S3Container.newId(),
            name = fields.fieldAt(FIELD_NAME).trim().ifBlank { parsed.bucket },
            endpoint = parsed.endpoint,
            bucket = parsed.bucket,
            prefix = fields.fieldAt(FIELD_PREFIX).trim().ifBlank { "Library" },
            region = parsed.region.ifBlank { "auto" },
            accessKey = fields.fieldAt(FIELD_ACCESS_KEY).trim(),
            secretKey = fields.fieldAt(FIELD_SECRET_KEY).trim(),
            sessionToken = fields.fieldAt(FIELD_SESSION_TOKEN).trim().ifBlank { null },
            pathStyle = parsed.pathStyle,
            capacityMb = capacityMb,
            usedBytes = if (capacityMb > 0) usedBytes.coerceAtMost(mbToBytes(capacityMb)) else usedBytes,
            lastRefreshTime = oldItem?.container?.lastRefreshTime ?: 0L,
            isFull = capacityMb > 0 && usedBytes >= mbToBytes(capacityMb),
            enabled = checks.checkedAt(CHECK_ENABLED, default = true)
        )
        val password = fields.fieldAt(FIELD_PASSWORD).takeIf { it.isNotBlank() }
        val minUploadChars = fields.fieldAt(FIELD_MIN_UPLOAD_CHARS).toIntOrNull()?.coerceAtLeast(0) ?: 1500
        val dailyUploadLimit = fields.fieldAt(FIELD_DAILY_UPLOAD_LIMIT).toIntOrNull()?.coerceAtLeast(0) ?: 0
        val saved = LibraryContainerManager.upsert(
            LibraryContainerConfig(
                container = container,
                password = password,
                sourceUrls = editingSourceUrls.toSet(),
                minUploadChars = minUploadChars,
                dailyUploadLimit = dailyUploadLimit,
                lockedImported = oldItem?.lockedImported == true
            )
        )
        editingSourceUrls = mutableSetOf()
        reload()
        return saved
    }

    private fun containerActions(item: LibraryContainerConfig): List<AppManagementMenuAction> {
        val actions = if (item.lockedImported) {
            listOf(Action.EDIT, Action.DELETE)
        } else {
            listOf(
                Action.EDIT,
                Action.EXPORT,
                Action.TEST,
                Action.REFRESH,
                Action.SET_DEFAULT,
                if (item.container.enabled) Action.DISABLE else Action.ENABLE,
                Action.DELETE
            )
        }
        return actions.map { action ->
            AppManagementMenuAction(
                text = action.title,
                danger = action == Action.DELETE
            ) {
                when (action) {
                    Action.EDIT -> showEditDialog(item)
                    Action.EXPORT -> exportContainers(
                        listOf(item),
                        "library-container-${safeExportName(LibraryContainerManager.displayLabel(item))}.json"
                    )
                    Action.TEST -> testConnection(item)
                    Action.REFRESH -> refreshCapacity(item)
                    Action.SET_DEFAULT -> {
                        if (!item.container.enabled) {
                            toastOnUi(R.string.s3_container_disabled)
                        } else {
                            LibraryContainerManager.select(item.id)
                            toastOnUi(R.string.s3_container_set_default_success)
                            reload()
                        }
                    }
                    Action.ENABLE -> updateItem(item.copy(container = item.container.copy(enabled = true, isFull = false)))
                    Action.DISABLE -> updateItem(item.copy(container = item.container.copy(enabled = false)))
                    Action.DELETE -> confirmDelete(item)
                }
            }
        }
    }

    private fun updateItem(item: LibraryContainerConfig) {
        LibraryContainerManager.upsert(item)
        reload()
    }

    private fun confirmDelete(item: LibraryContainerConfig) {
        showComposeConfirmDialog(
            title = "删除书库容器",
            message = "确认删除 ${LibraryContainerManager.displayLabel(item)}？",
            positiveText = getString(R.string.delete),
            negativeText = getString(R.string.cancel),
            dangerPositive = true,
            onPositive = {
                LibraryContainerManager.delete(item.id)
                reload()
            }
        )
    }

    private fun testConnection(item: LibraryContainerConfig) {
        waitDialog.setText(R.string.loading)
        waitDialog.show()
        lifecycleScope.launch {
            runCatching {
                withContext(Dispatchers.IO) { LibraryCloudBackend(item).check() }
            }.onSuccess {
                toastOnUi(R.string.s3_container_test_success)
            }.onFailure {
                toastOnUi(getString(R.string.s3_container_test_failed, it.localizedMessage.orEmpty()))
            }
            waitDialog.dismiss()
        }
    }

    private fun refreshCapacity(item: LibraryContainerConfig, showWait: Boolean = true) {
        if (showWait) {
            waitDialog.setText(R.string.loading)
            waitDialog.show()
        }
        lifecycleScope.launch {
            runCatching {
                withContext(Dispatchers.IO) { LibraryCloudBackend(item).refreshUsage() }
            }.onSuccess {
                LibraryContainerManager.updateUsage(item.id, it)
                toastOnUi(R.string.s3_container_capacity_refreshed)
                reload()
            }.onFailure {
                toastOnUi(it.localizedMessage.orEmpty())
            }
            if (showWait) waitDialog.dismiss()
        }
    }

    private enum class Action(val title: String) {
        EDIT("编辑"),
        EXPORT("导出"),
        TEST("测试连接"),
        REFRESH("刷新容量"),
        SET_DEFAULT("设为默认"),
        ENABLE("启用"),
        DISABLE("禁用"),
        DELETE("删除")
    }

    internal companion object {
        const val DEFAULT_CAPACITY_MB = 5L * 1024L
        private const val FIELD_NAME = 0
        private const val FIELD_ENDPOINT = 1
        private const val FIELD_BUCKET = 2
        private const val FIELD_ACCESS_KEY = 3
        private const val FIELD_SECRET_KEY = 4
        private const val FIELD_CAPACITY = 5
        private const val FIELD_PREFIX = 6
        private const val FIELD_REGION = 7
        private const val FIELD_SESSION_TOKEN = 8
        private const val FIELD_PASSWORD = 9
        private const val FIELD_MIN_UPLOAD_CHARS = 10
        private const val FIELD_DAILY_UPLOAD_LIMIT = 11
        private const val CHECK_ENABLED = 0
        private const val CHECK_PATH_STYLE = 1
        private const val MENU_IMPORT = 1
        private const val MENU_EXPORT_ALL = 2

        private fun List<String>.fieldAt(index: Int): String = getOrNull(index).orEmpty()

        private fun BooleanArray.checkedAt(index: Int, default: Boolean): Boolean {
            return if (index in indices) this[index] else default
        }

        internal fun mbToBytes(value: Long): Long = value.coerceAtLeast(0L) * 1024L * 1024L

        internal fun gbTextToCapacityMb(value: String): Long {
            val gb = value.trim().toDoubleOrNull() ?: return 0L
            if (gb <= 0.0) return 0L
            return max(ceil(gb * 1024.0).toLong(), 1L)
        }

        internal fun capacityMbToGbText(value: Long): String {
            if (value <= 0L) return ""
            val gb = value / 1024.0
            return if (value % 1024L == 0L) {
                (value / 1024L).toString()
            } else {
                String.format(Locale.US, "%.2f", gb).trimEnd('0').trimEnd('.')
            }
        }

        internal fun formatBytes(value: Long): String {
            val bytes = value.coerceAtLeast(0L).toDouble()
            val gb = bytes / 1024.0 / 1024.0 / 1024.0
            return if (gb >= 1.0) {
                "${formatDecimal(gb)} GB"
            } else {
                val mb = bytes / 1024.0 / 1024.0
                "${max(mb.toLong(), 0L)} MB"
            }
        }

        internal fun formatDecimal(value: Double): String {
            return String.format(Locale.US, "%.2f", value).trimEnd('0').trimEnd('.')
        }

        internal fun safeExportName(value: String): String {
            return value.replace(Regex("[\\\\/:*?\"<>|\\s]+"), "-").trim('-').ifBlank { "default" }
        }
    }

    private data class LibraryContainerImport(
        val containers: List<LibraryContainerConfig> = emptyList(),
        val selectedId: String? = null
    )

    private data class LibraryContainerExport(
        val version: Int = 1,
        val selectedId: String? = null,
        val containers: List<LibraryContainerConfig> = emptyList()
    )
}
