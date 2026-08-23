package io.legado.app.ui.config

import android.content.Context
import android.os.Bundle
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import androidx.core.content.edit
import androidx.core.view.MenuProvider
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.constant.AppLog
import io.legado.app.constant.PreferKey
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.AppCloudStorage
import io.legado.app.lib.cloud.CloudStorageType
import io.legado.app.lib.cloud.S3CapacityFullException
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.LocalConfig
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.help.storage.Backup
import io.legado.app.help.storage.BackupConfig
import io.legado.app.help.storage.ImportOldData
import io.legado.app.help.storage.Restore
import io.legado.app.lib.permission.Permissions
import io.legado.app.lib.permission.PermissionsCompat
import io.legado.app.ui.about.AppLogDialog
import io.legado.app.ui.config.compose.ComposeSettingFragment
import io.legado.app.ui.config.compose.SettingActionSpec
import io.legado.app.ui.config.compose.SettingChoiceOption
import io.legado.app.ui.config.compose.SettingChoiceSpec
import io.legado.app.ui.config.compose.SettingPageSpec
import io.legado.app.ui.config.compose.SettingSectionSpec
import io.legado.app.ui.config.compose.SettingSwitchSpec
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.ui.widget.dialog.WaitDialog
import io.legado.app.ui.widget.compose.showComposeChoiceListDialog
import io.legado.app.ui.widget.compose.showComposeConfirmDialog
import io.legado.app.ui.widget.compose.showComposeMultiChoiceDialog
import io.legado.app.ui.widget.compose.showComposeTextFormDialog
import io.legado.app.ui.widget.compose.showComposeTextInputDialog
import io.legado.app.utils.FileDoc
import io.legado.app.utils.applyTint
import io.legado.app.utils.checkWrite
import io.legado.app.utils.defaultSharedPreferences
import io.legado.app.utils.getPrefString
import io.legado.app.utils.isContentScheme
import io.legado.app.utils.launch
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.showHelp
import io.legado.app.utils.startActivity
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.Dispatchers.Main
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import splitties.init.appCtx

class BackupConfigFragment : ComposeSettingFragment(), MenuProvider {

    private companion object {
        const val KEY_WEB_DAV_ACCOUNT_MANAGE = "webDavAccountManage"
        const val KEY_S3_CONTAINER_MANAGE = "s3ContainerManage"
        const val KEY_LIBRARY_CONTAINER_MANAGE = "libraryContainerManage"
        const val KEY_WEB_DAV_BACKUP = "web_dav_backup"
        const val KEY_WEB_DAV_RESTORE = "web_dav_restore"
        const val KEY_IMPORT_OLD = "import_old"
    }

    private val viewModel by activityViewModels<ConfigViewModel>()
    private val waitDialog by lazy { WaitDialog(requireContext()) }
    private var backupJob: Job? = null
    private var restoreJob: Job? = null
    private var activeBackupPath: String? = null
    private var pendingS3FullBackupPath: String? = null

    private val selectBackupPath = registerForActivityResult(HandleFileContract()) {
        it.uri?.let { uri ->
            if (uri.isContentScheme()) {
                AppConfig.backupPath = uri.toString()
            } else {
                AppConfig.backupPath = uri.path
            }
        }
    }
    private val backupDir = registerForActivityResult(HandleFileContract()) { result ->
        result.uri?.let { uri ->
            if (uri.isContentScheme()) {
                AppConfig.backupPath = uri.toString()
                backup(uri.toString())
            } else {
                uri.path?.let { path ->
                    AppConfig.backupPath = path
                    backup(path)
                }
            }
        }
    }
    private val restoreDoc = registerForActivityResult(HandleFileContract()) {
        it.uri?.let { uri ->
            waitDialog.setText(R.string.restore)
            waitDialog.show()
            val task = Coroutine.async {
                Restore.restore(appCtx, uri)
            }.onFinally {
                waitDialog.dismiss()
            }
            waitDialog.setOnCancelListener {
                task.cancel()
            }
        }
    }
    private val restoreOld = registerForActivityResult(HandleFileContract()) {
        it.uri?.let { uri ->
            ImportOldData.importUri(appCtx, uri)
        }
    }

    override val titleRes: Int = R.string.backup_restore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        migrateCloudStoragePreferenceTypes()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        activity?.addMenuProvider(this, viewLifecycleOwner)
        if (!LocalConfig.backupHelpVersionIsLast) {
            showHelp("webDavHelp")
        }
    }

    override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
        menuInflater.inflate(R.menu.backup_restore, menu)
        menu.applyTint(requireContext())
    }

    override fun onMenuItemSelected(menuItem: MenuItem): Boolean {
        when (menuItem.itemId) {
            R.id.menu_help -> {
                showHelp("webDavHelp")
                return true
            }

            R.id.menu_log -> showDialogFragment<AppLogDialog>()
        }
        return false
    }

    override fun buildPageSpec(): SettingPageSpec {
        val type = CloudStorageType.from(getPrefString(PreferKey.cloudStorageType))
        val webDavVisible = type == CloudStorageType.WEBDAV
        val s3Visible = type == CloudStorageType.S3
        val syncBookProgress = booleanSetting(PreferKey.syncBookProgress, true)
        return SettingPageSpec(
            titleRes = titleRes,
            sections = listOf(
                SettingSectionSpec(
                    title = getString(R.string.web_dav_set),
                    items = listOf(
                        choice(
                            key = PreferKey.cloudStorageType,
                            title = getString(R.string.cloud_storage),
                            entriesRes = R.array.cloud_storage_types,
                            valuesRes = R.array.cloud_storage_type_values,
                            defaultValue = CloudStorageType.WEBDAV.name
                        ),
                        SettingActionSpec(
                            key = KEY_WEB_DAV_ACCOUNT_MANAGE,
                            title = getString(R.string.webdav_account_manage),
                            summary = webDavAccountSummary(),
                            visible = webDavVisible,
                            onClick = ::showWebDavAccountDialog
                        ),
                        SettingActionSpec(
                            key = PreferKey.webDavDir,
                            title = getString(R.string.sub_dir),
                            summary = AppConfig.webDavDir ?: "legado",
                            visible = webDavVisible,
                            onClick = {
                                showTextSettingDialog(
                                    key = PreferKey.webDavDir,
                                    title = getString(R.string.sub_dir),
                                    initialValue = AppConfig.webDavDir ?: "legado"
                                )
                            }
                        ),
                        SettingActionSpec(
                            key = PreferKey.webDavDeviceName,
                            title = getString(R.string.webdav_device_name),
                            summary = getPrefString(PreferKey.webDavDeviceName),
                            visible = webDavVisible,
                            onClick = {
                                showTextSettingDialog(
                                    key = PreferKey.webDavDeviceName,
                                    title = getString(R.string.webdav_device_name),
                                    initialValue = AppConfig.webDavDeviceName ?: ""
                                )
                            }
                        ),
                        SettingActionSpec(
                            key = KEY_S3_CONTAINER_MANAGE,
                            title = getString(R.string.s3_container_manage),
                            summary = getString(R.string.s3_container_manage_summary),
                            visible = s3Visible,
                            onClick = {
                                requireContext().startActivity<S3ContainerManageActivity>()
                            }
                        ),
                        SettingActionSpec(
                            key = KEY_LIBRARY_CONTAINER_MANAGE,
                            title = "书库容器",
                            summary = "管理用于同步阅读章节缓存的独立 S3 容器",
                            onClick = {
                                requireContext().startActivity<LibraryContainerManageActivity>()
                            }
                        ),
                        SettingSwitchSpec(
                            key = PreferKey.autoSwitchS3Container,
                            title = getString(R.string.s3_auto_switch_container),
                            summary = getString(R.string.s3_auto_switch_container_summary),
                            checked = booleanSetting(PreferKey.autoSwitchS3Container, true),
                            visible = s3Visible,
                            onCheckedChange = {
                                updateBooleanSetting(PreferKey.autoSwitchS3Container, it)
                            }
                        ),
                        SettingSwitchSpec(
                            key = PreferKey.syncThemePackages,
                            title = getString(R.string.sync_theme_packages),
                            summary = getString(R.string.sync_theme_packages_summary),
                            checked = booleanSetting(PreferKey.syncThemePackages, false),
                            onCheckedChange = { checked ->
                                if (checked && !hasCloudStorageAccount()) {
                                    toastOnUi(R.string.cloud_storage_config_required)
                                } else {
                                    updateBooleanSetting(PreferKey.syncThemePackages, checked)
                                }
                            }
                        ),
                        switch(
                            key = PreferKey.syncBookProgress,
                            title = getString(R.string.sync_book_progress_t),
                            summary = getString(R.string.sync_book_progress_s),
                            defaultValue = true
                        ),
                        SettingSwitchSpec(
                            key = PreferKey.syncBookProgressPlus,
                            title = getString(R.string.sync_book_progress_plus_t),
                            summary = getString(R.string.sync_book_progress_plus_s),
                            checked = booleanSetting(PreferKey.syncBookProgressPlus, false),
                            enabled = syncBookProgress,
                            onCheckedChange = {
                                updateBooleanSetting(PreferKey.syncBookProgressPlus, it)
                            }
                        )
                    )
                ),
                SettingSectionSpec(
                    title = getString(R.string.backup_restore),
                    items = listOf(
                        SettingActionSpec(
                            key = PreferKey.backupPath,
                            title = getString(R.string.backup_path),
                            summary = AppConfig.backupPath ?: getString(R.string.select_backup_path),
                            onClick = { selectBackupPath.launch() }
                        ),
                        SettingActionSpec(
                            key = KEY_WEB_DAV_BACKUP,
                            title = getString(R.string.backup),
                            summary = getString(R.string.backup_summary),
                            onClick = { backup() }
                        ),
                        SettingActionSpec(
                            key = KEY_WEB_DAV_RESTORE,
                            title = getString(R.string.restore),
                            summary = getString(R.string.restore_summary),
                            onClick = { restore() },
                            onLongClick = { restoreFromLocal() }
                        ),
                        SettingActionSpec(
                            key = PreferKey.restoreIgnore,
                            title = getString(R.string.restore_ignore),
                            summary = getString(R.string.restore_ignore_summary),
                            onClick = ::backupIgnore
                        ),
                        SettingActionSpec(
                            key = KEY_IMPORT_OLD,
                            title = getString(R.string.menu_import_old_version),
                            summary = getString(R.string.import_old_summary),
                            onClick = { restoreOld.launch() }
                        ),
                        switch(
                            key = PreferKey.onlyLatestBackup,
                            title = getString(R.string.only_latest_backup_t),
                            summary = getString(R.string.only_latest_backup_s),
                            defaultValue = true
                        ),
                        switch(
                            key = PreferKey.autoCheckNewBackup,
                            title = getString(R.string.auto_check_new_backup_t),
                            summary = getString(R.string.auto_check_new_backup_s),
                            defaultValue = true
                        )
                    )
                )
            )
        )
    }

    private fun migrateCloudStoragePreferenceTypes() {
        val preferences = appCtx.defaultSharedPreferences
        val booleanDefaults = mapOf(
            PreferKey.s3PathStyle to true,
            PreferKey.autoSwitchS3Container to true,
            PreferKey.s3FullWebDavFallbackNeverRemind to false
        )
        booleanDefaults.forEach { (key, defaultValue) ->
            val raw = preferences.all[key]
            if (raw != null && raw !is Boolean) {
                val value = when (raw) {
                    is String -> raw.toBooleanStrictOrNull() ?: (raw == "1")
                    is Number -> raw.toInt() != 0
                    else -> defaultValue
                }
                preferences.edit().putBoolean(key, value).apply()
            }
        }
    }

    override fun onSettingPreferenceChanged(key: String) {
        when (key) {
            PreferKey.backupPath,
            PreferKey.webDavDeviceName -> refreshSettings()

            PreferKey.cloudStorageType,
            PreferKey.webDavUrl,
            PreferKey.webDavAccount,
            PreferKey.webDavPassword,
            PreferKey.webDavDir -> view?.post {
                refreshSettings()
                viewModel.upCloudStorageConfig()
            }

            PreferKey.s3Containers,
            PreferKey.s3ContainerSelections,
            PreferKey.autoSwitchS3Container -> view?.post {
                refreshSettings()
                viewModel.upCloudStorageConfig()
            }
        }
    }

    private fun switch(
        key: String,
        title: String,
        summary: String,
        defaultValue: Boolean
    ): SettingSwitchSpec {
        return SettingSwitchSpec(
            key = key,
            title = title,
            summary = summary,
            checked = booleanSetting(key, defaultValue),
            onCheckedChange = { updateBooleanSetting(key, it) }
        )
    }

    private fun choice(
        key: String,
        title: String,
        entriesRes: Int,
        valuesRes: Int,
        defaultValue: String
    ): SettingChoiceSpec {
        val options = choiceOptions(entriesRes, valuesRes)
        return SettingChoiceSpec(
            key = key,
            title = title,
            summary = choiceLabel(options, stringSetting(key, defaultValue)),
            options = options,
            selectedValue = stringSetting(key, defaultValue),
            onSelected = { updateStringSetting(key, it) }
        )
    }

    private fun choiceOptions(
        entriesRes: Int,
        valuesRes: Int
    ): List<SettingChoiceOption> {
        val entries = resources.getStringArray(entriesRes)
        val values = resources.getStringArray(valuesRes)
        return values.mapIndexed { index, value ->
            SettingChoiceOption(
                value = value,
                label = entries.getOrElse(index) { value }
            )
        }
    }

    private fun choiceLabel(
        options: List<SettingChoiceOption>,
        selectedValue: String
    ): String {
        return options.firstOrNull { it.value == selectedValue }
            ?.label
            ?.toString()
            ?: selectedValue
    }

    private fun showTextSettingDialog(
        key: String,
        title: String,
        initialValue: String
    ) {
        showComposeTextInputDialog(
            title = title,
            hint = title,
            initialValue = initialValue,
            onPositive = {
                appCtx.defaultSharedPreferences.edit {
                    putString(key, it.trim())
                }
            }
        )
    }

    private fun hasCloudStorageAccount(): Boolean {
        return when (CloudStorageType.from(getPrefString(PreferKey.cloudStorageType))) {
            CloudStorageType.WEBDAV -> hasWebDavAccount()
            CloudStorageType.S3 -> hasS3Account()
        }
    }

    private fun hasWebDavAccount(): Boolean {
        return !getPrefString(PreferKey.webDavAccount).isNullOrBlank()
                && !getPrefString(PreferKey.webDavPassword).isNullOrBlank()
    }

    private fun hasS3Account(): Boolean {
        return AppCloudStorage.listContainers().any {
            it.enabled && it.endpoint.isNotBlank() && it.bucket.isNotBlank()
                    && it.accessKey.isNotBlank() && it.secretKey.isNotBlank()
        }
    }

    private fun webDavAccountSummary(): String {
        val url = getPrefString(PreferKey.webDavUrl).orEmpty()
        val account = getPrefString(PreferKey.webDavAccount).orEmpty()
        val password = getPrefString(PreferKey.webDavPassword).orEmpty()
        return when {
            url.isBlank() && account.isBlank() && password.isBlank() ->
                getString(R.string.webdav_account_manage_summary)
            account.isBlank() ->
                url.ifBlank { getString(R.string.webdav_account_manage_summary) }
            url.isBlank() ->
                account
            else ->
                "$url / $account"
        }
    }

    /**
     * 备份忽略设置
     */
    private fun backupIgnore() {
        val checkedIndices = BackupConfig.ignoreKeys.indices
            .filter { BackupConfig.ignoreConfig[BackupConfig.ignoreKeys[it]] ?: false }
            .toSet()
        showComposeMultiChoiceDialog(
            title = getString(R.string.restore_ignore),
            labels = BackupConfig.ignoreTitle.toList(),
            checkedIndices = checkedIndices,
            negativeText = getString(android.R.string.ok),
            onItemCheckedChange = { which, isChecked ->
                BackupConfig.ignoreKeys.getOrNull(which)?.let { key ->
                    BackupConfig.ignoreConfig[key] = isChecked
                    BackupConfig.saveIgnoreConfig()
                }
            }
        )
    }

    private fun showWebDavAccountDialog() {
        showComposeTextFormDialog(
            title = getString(R.string.webdav_account_manage),
            labels = listOf(
                getString(R.string.web_dav_url),
                getString(R.string.web_dav_account),
                getString(R.string.web_dav_pw)
            ),
            initialValues = listOf(
                getPrefString(PreferKey.webDavUrl).orEmpty(),
                getPrefString(PreferKey.webDavAccount).orEmpty(),
                getPrefString(PreferKey.webDavPassword).orEmpty()
            ),
            passwordFields = setOf(2),
            onPositive = { values ->
                appCtx.defaultSharedPreferences.edit {
                    putString(PreferKey.webDavUrl, values.getOrNull(0).orEmpty().trim())
                    putString(PreferKey.webDavAccount, values.getOrNull(1).orEmpty().trim())
                    putString(PreferKey.webDavPassword, values.getOrNull(2).orEmpty())
                }
                refreshSettings()
                viewModel.upCloudStorageConfig()
            }
        )
    }


    fun backup(ignoreS3FullPrompt: Boolean = false) {
        val backupPath = AppConfig.backupPath
        if (backupPath.isNullOrEmpty()) {
            backupDir.launch()
        } else {
            if (backupPath.isContentScheme()) {
                lifecycleScope.launch {
                    val canWrite = withContext(IO) {
                        FileDoc.fromDir(backupPath).checkWrite()
                    }
                    if (canWrite) {
                        backup(backupPath, uploadCloud = true, checkS3FullPrompt = !ignoreS3FullPrompt)
                    } else {
                        backupDir.launch()
                    }
                }
            } else {
                backupUsePermission(backupPath, checkS3FullPrompt = !ignoreS3FullPrompt)
            }
        }
    }

    private fun backup(
        backupPath: String,
        uploadCloud: Boolean = true,
        uploadWebDavFallback: Boolean = false,
        checkS3FullPrompt: Boolean = true
    ) {
        if (uploadCloud && checkS3FullPrompt && shouldShowS3FullWebDavFallback()) {
            showS3FullWebDavFallbackDialog(backupPath)
            return
        }
        waitDialog.setText(R.string.backup)
        waitDialog.setOnCancelListener {
            backupJob?.cancel()
        }
        waitDialog.show()
        backupJob?.cancel()
        backupJob = lifecycleScope.launch {
            try {
                activeBackupPath = backupPath
                Backup.backupLocked(requireContext(), backupPath, uploadCloud, uploadWebDavFallback)
                appCtx.toastOnUi(R.string.backup_success)
            } catch (e: S3CapacityFullException) {
                ensureActive()
                if (showS3FullFallbackAfterFailure(backupPath)) {
                    return@launch
                }
                AppLog.put("备份出错\n${e.localizedMessage}", e)
                appCtx.toastOnUi(
                    appCtx.getString(
                        R.string.backup_fail,
                        e.localizedMessage
                    )
                )
            } catch (e: Throwable) {
                ensureActive()
                AppLog.put("备份出错\n${e.localizedMessage}", e)
                appCtx.toastOnUi(
                    appCtx.getString(
                        R.string.backup_fail,
                        e.localizedMessage
                    )
                )
            } finally {
                activeBackupPath = null
                ensureActive()
                waitDialog.dismiss()
            }
        }
    }

    private fun shouldShowS3FullWebDavFallback(): Boolean {
        if (CloudStorageType.from(getPrefString(PreferKey.cloudStorageType)) != CloudStorageType.S3) {
            return false
        }
        if (appCtx.defaultSharedPreferences.getBoolean(PreferKey.s3FullWebDavFallbackNeverRemind, false)) {
            return false
        }
        val items = AppCloudStorage.listContainers().filter { it.enabled }
        return items.isNotEmpty() && items.all { it.isFull }
    }

    private fun showS3FullFallbackAfterFailure(backupPath: String): Boolean {
        if (appCtx.defaultSharedPreferences.getBoolean(PreferKey.s3FullWebDavFallbackNeverRemind, false)) {
            return false
        }
        showS3FullWebDavFallbackDialog(backupPath)
        return true
    }

    private fun showS3FullWebDavFallbackDialog(backupPath: String) {
        pendingS3FullBackupPath = backupPath
        val hasWebDav = !getPrefString(PreferKey.webDavAccount).isNullOrBlank()
                && !getPrefString(PreferKey.webDavPassword).isNullOrBlank()
        val messageRes = if (hasWebDav) {
            R.string.s3_full_webdav_fallback_message
        } else {
            R.string.s3_full_no_webdav_fallback_message
        }
        val neverRemind = {
            appCtx.defaultSharedPreferences.edit {
                putBoolean(PreferKey.s3FullWebDavFallbackNeverRemind, true)
            }
            retryActiveBackup(uploadCloud = false, uploadWebDavFallback = false)
        }
        if (hasWebDav) {
            showComposeConfirmDialog(
                title = getString(R.string.s3_full_webdav_fallback_title),
                message = getString(messageRes),
                positiveText = getString(R.string.s3_full_webdav_fallback_upload),
                negativeText = getString(R.string.s3_full_webdav_fallback_ignore),
                neutralText = getString(R.string.s3_full_webdav_fallback_never),
                onPositive = {
                    retryActiveBackup(uploadCloud = true, uploadWebDavFallback = true)
                },
                onNegative = {
                    retryActiveBackup(uploadCloud = false, uploadWebDavFallback = false)
                },
                onNeutral = neverRemind
            )
        } else {
            showComposeConfirmDialog(
                title = getString(R.string.s3_full_webdav_fallback_title),
                message = getString(messageRes),
                positiveText = getString(R.string.s3_full_webdav_fallback_ignore),
                neutralText = getString(R.string.s3_full_webdav_fallback_never),
                showNegative = false,
                onPositive = {
                    retryActiveBackup(uploadCloud = false, uploadWebDavFallback = false)
                },
                onNeutral = neverRemind
            )
        }
    }

    private fun retryActiveBackup(uploadCloud: Boolean, uploadWebDavFallback: Boolean) {
        val path = pendingS3FullBackupPath ?: activeBackupPath
        pendingS3FullBackupPath = null
        if (path.isNullOrBlank()) {
            backup(ignoreS3FullPrompt = true)
        } else {
            backup(
                path,
                uploadCloud = uploadCloud,
                uploadWebDavFallback = uploadWebDavFallback,
                checkS3FullPrompt = false
            )
        }
    }

    private fun backupUsePermission(path: String, checkS3FullPrompt: Boolean = true) {
        PermissionsCompat.Builder()
            .addPermissions(*Permissions.Group.STORAGE)
            .rationale(R.string.tip_perm_request_storage)
            .onGranted {
                backup(path, uploadCloud = true, checkS3FullPrompt = checkS3FullPrompt)
            }
            .request()
    }

    fun restore() {
        waitDialog.setText(R.string.loading)
        waitDialog.setOnCancelListener {
            restoreJob?.cancel()
        }
        waitDialog.show()
        Coroutine.async {
            restoreJob = coroutineContext[Job]
            showRestoreDialog(requireContext())
        }.onError {
            AppLog.put("恢复备份出错\n${it.localizedMessage}", it)
            if (context == null) {
                return@onError
            }
            showComposeConfirmDialog(
                title = getString(R.string.restore),
                message = "Cloud storage error\n${it.localizedMessage}\nRestore from local backup?",
                onPositive = {
                    restoreFromLocal()
                }
            )
        }.onFinally {
            waitDialog.dismiss()
        }
    }

    private suspend fun showRestoreDialog(context: Context) {
        val names = withContext(IO) {
            ensureCloudStorageForRestore()
            AppCloudStorage.getBackupNames()
        }
        if (AppCloudStorage.isJianGuoYun && names.size > 700) {
            context.toastOnUi("由于坚果云限制列出文件数量，部分备份可能未显示，请及时清理旧备份")
        }
        if (names.isNotEmpty()) {
            currentCoroutineContext().ensureActive()
            withContext(Main) {
                context.showComposeChoiceListDialog(
                    title = context.getString(R.string.select_restore_file),
                    labels = names
                ) { index ->
                    if (index in 0 until names.size) {
                        view?.post {
                            restoreWebDav(names[index])
                        }
                    }
                }
            }
        } else {
            throw NoStackTraceException("Cloud storage backup file not found")
        }
    }

    private suspend fun ensureCloudStorageForRestore() {
        val currentType = CloudStorageType.from(getPrefString(PreferKey.cloudStorageType))
        if (currentType == CloudStorageType.S3 && hasS3Account()) {
            AppCloudStorage.upConfig()
            return
        }
        if (currentType == CloudStorageType.WEBDAV && hasWebDavAccount()) {
            AppCloudStorage.upConfig()
            return
        }
        val fallbackType = when {
            hasS3Account() -> CloudStorageType.S3
            hasWebDavAccount() -> CloudStorageType.WEBDAV
            else -> throw NoStackTraceException("Cloud storage is not configured")
        }
        appCtx.defaultSharedPreferences.edit {
            putString(PreferKey.cloudStorageType, fallbackType.name)
        }
        withContext(Main) {
            refreshSettings()
        }
        AppCloudStorage.upConfig()
    }

    private fun restoreWebDav(name: String) {
        waitDialog.setText(R.string.restore)
        waitDialog.show()
        val task = Coroutine.async {
            AppCloudStorage.restore(name)
        }.onError {
            AppLog.put("云端恢复出错\n${it.localizedMessage}", it)
            appCtx.toastOnUi("云端恢复出错\n${it.localizedMessage}")
        }.onFinally {
            waitDialog.dismiss()
        }
        waitDialog.setOnCancelListener {
            task.cancel()
        }
    }

    private fun restoreFromLocal() {
        restoreDoc.launch {
            title = getString(R.string.select_restore_file)
            mode = HandleFileContract.FILE
            allowExtensions = arrayOf("zip")
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        waitDialog.dismiss()
    }

}
