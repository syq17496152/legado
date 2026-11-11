package io.legado.app.ui.config

import androidx.fragment.app.Fragment
import io.legado.app.R
import io.legado.app.help.storage.FullAccessState
import io.legado.app.help.storage.SafeDirEntry
import io.legado.app.help.storage.StorageAccess
import io.legado.app.lib.permission.Permissions
import io.legado.app.lib.permission.PermissionsCompat
import io.legado.app.ui.config.compose.SettingActionSpec
import io.legado.app.ui.config.compose.SettingItemSpec
import io.legado.app.ui.widget.compose.showComposeActionListDialog
import io.legado.app.ui.widget.compose.showComposeConfirmDialog
import io.legado.app.utils.toastOnUi

/**
 * 设置页「存储访问」分组：所有文件访问权限 + 已授权目录管理。
 *
 * 从 `OtherConfigFragment` 拆出以控制单文件行数（NFR-04）；只做展示与交互编排，
 * 权限状态、目录集合、上限与保存位置全部来自 [StorageAccess]。
 */
internal object StorageAccessSettingsUi {

    const val KEY_ALL_FILES_ACCESS = "allFilesAccess"
    const val KEY_SAFE_DIRS = "safeDirsManage"

    /**
     * @param onChanged 状态变化后刷新设置页（授权返回 / 增删目录）
     * @param requestPickDir 拉起 SAF 目录选择（由宿主提供，确保 activity result 注册在 Activity 上）
     */
    fun items(
        fragment: Fragment,
        onChanged: () -> Unit,
        requestPickDir: (title: String) -> Unit
    ): List<SettingItemSpec> = listOf(
        SettingActionSpec(
            key = KEY_ALL_FILES_ACCESS,
            title = fragment.getString(R.string.storage_all_files_access),
            summary = allFilesAccessSummary(fragment),
            searchKeys = listOf("所有文件", "全盘", "存储权限", "目录"),
            onClick = { showAllFilesAccess(fragment, onChanged) }
        ),
        SettingActionSpec(
            key = KEY_SAFE_DIRS,
            title = fragment.getString(R.string.storage_safe_dirs),
            summary = safeDirsSummary(fragment),
            searchKeys = listOf("授权目录", "浏览目录", "SAF", "目录"),
            onClick = { showSafeDirManage(fragment, onChanged, requestPickDir) }
        )
    )

    /** 全盘文件访问权限：状态展示 + 跳系统设置（MANAGE_EXTERNAL_STORAGE 无运行时弹窗） */
    private fun showAllFilesAccess(fragment: Fragment, onChanged: () -> Unit) {
        if (!StorageAccess.supportsAllFilesAccess()) {
            fragment.toastOnUi(R.string.storage_all_files_access_unsupported)
            return
        }
        if (StorageAccess.fullAccessState() == FullAccessState.GRANTED) {
            fragment.toastOnUi(R.string.storage_all_files_access_on)
            return
        }
        if (!StorageAccess.canOpenAllFilesAccessSetting()) {
            // 部分 ROM 移除了该设置页：明确告知并让用户改走目录授权，避免静默失败
            fragment.toastOnUi(R.string.storage_all_files_access_unsupported)
            return
        }
        // 复用既有权限通道：Request 内部会判断 isExternalStorageManager 并跳
        // Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION，rationale 必填（为空会直接 finish）
        PermissionsCompat.Builder()
            .addPermissions(Permissions.MANAGE_EXTERNAL_STORAGE)
            .rationale(R.string.storage_all_files_access_rationale)
            .onGranted(onChanged)
            .onDenied { onChanged() }
            .request()
    }

    /** 已授权目录管理：空列表直接加目录；非空则展示「目录列表 + 添加新目录」 */
    private fun showSafeDirManage(
        fragment: Fragment,
        onChanged: () -> Unit,
        requestPickDir: (String) -> Unit
    ) {
        val entries = StorageAccess.safeDirEntries()
        if (entries.isEmpty()) {
            if (StorageAccess.fullAccessState() == FullAccessState.GRANTED) {
                fragment.toastOnUi(R.string.storage_all_files_access_on)
                return
            }
            launchAddSafeDir(fragment, requestPickDir)
            return
        }
        val labels = entries.map { entry ->
            if (entry.available) {
                entry.name
            } else {
                fragment.getString(R.string.storage_dir_unavailable_name, entry.name)
            }
        } + fragment.getString(R.string.storage_dir_add)
        val descriptions = entries.map { entry ->
            when {
                !entry.available -> fragment.getString(R.string.storage_dir_unavailable)
                entry.isDefaultSaveDir -> fragment.getString(R.string.storage_dir_default)
                else -> ""
            }
        } + ""
        fragment.showComposeActionListDialog(
            title = fragment.getString(R.string.storage_safe_dirs),
            labels = labels,
            descriptions = descriptions,
            onSelected = { index ->
                if (index == entries.size) {
                    launchAddSafeDir(fragment, requestPickDir)
                } else {
                    entries.getOrNull(index)?.let { showSafeDirActions(fragment, it, onChanged) }
                }
            }
        )
    }

    /** 单个目录的动作：设为默认保存 / 移除（移除为危险操作，二次确认） */
    private fun showSafeDirActions(
        fragment: Fragment,
        entry: SafeDirEntry,
        onChanged: () -> Unit
    ) {
        fragment.showComposeActionListDialog(
            title = entry.name,
            labels = listOf(
                fragment.getString(R.string.storage_dir_set_default),
                fragment.getString(R.string.storage_dir_remove)
            ),
            descriptions = listOf(
                if (entry.isDefaultSaveDir) fragment.getString(R.string.storage_dir_default) else "",
                ""
            ),
            dangerIndices = setOf(1),
            onSelected = { index ->
                when (index) {
                    0 -> {
                        StorageAccess.setDefaultSaveDir(entry.uri)
                        onChanged()
                    }
                    1 -> confirmRemoveSafeDir(fragment, entry, onChanged)
                }
            }
        )
    }

    private fun confirmRemoveSafeDir(
        fragment: Fragment,
        entry: SafeDirEntry,
        onChanged: () -> Unit
    ) {
        fragment.showComposeConfirmDialog(
            title = fragment.getString(R.string.storage_dir_remove),
            message = fragment.getString(R.string.storage_dir_remove_confirm),
            positiveText = fragment.getString(R.string.storage_dir_remove),
            dangerPositive = true,
            onPositive = {
                StorageAccess.removeSafeDir(entry.uri)
                onChanged()
            }
        )
    }

    /** 拉起 SAF 目录选择（达上限时给出可操作提示，不静默忽略） */
    private fun launchAddSafeDir(fragment: Fragment, requestPickDir: (String) -> Unit) {
        if (!StorageAccess.canAddSafeDir()) {
            fragment.toastOnUi(
                fragment.getString(R.string.storage_dir_limit, StorageAccess.MAX_DIRS)
            )
            return
        }
        requestPickDir(fragment.getString(R.string.storage_dir_add))
    }

    private fun allFilesAccessSummary(fragment: Fragment): String = when {
        !StorageAccess.supportsAllFilesAccess() ->
            fragment.getString(R.string.storage_all_files_access_unsupported)
        StorageAccess.fullAccessState() == FullAccessState.GRANTED ->
            fragment.getString(R.string.storage_all_files_access_on)
        else -> fragment.getString(R.string.storage_all_files_access_off)
    }

    /** 目录数量摘要：只读偏好键（轻量），不在此处做 DocumentFile 有效性探测（避免重组期主线程 IO） */
    private fun safeDirsSummary(fragment: Fragment): String {
        val count = StorageAccess.safeDirs().size
        return if (count == 0) {
            fragment.getString(R.string.storage_safe_dirs_empty)
        } else {
            fragment.getString(R.string.storage_safe_dirs_summary, count)
        }
    }
}
