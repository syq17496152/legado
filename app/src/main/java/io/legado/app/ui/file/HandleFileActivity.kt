package io.legado.app.ui.file

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.webkit.MimeTypeMap
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.net.toUri
import androidx.lifecycle.lifecycleScope
import androidx.viewbinding.ViewBinding
import io.legado.app.R
import io.legado.app.base.VMBaseActivity
import io.legado.app.base.composeShell
import io.legado.app.constant.AppLog
import io.legado.app.help.IntentData
import io.legado.app.lib.dialogs.SelectItem
import io.legado.app.lib.permission.Permissions
import io.legado.app.lib.permission.PermissionsCompat
import io.legado.app.ui.association.TransparentShellViews
import io.legado.app.ui.widget.compose.showComposeActionListDialog
import io.legado.app.ui.widget.compose.showComposeTextInputDialog
import io.legado.app.utils.SelectImageContract
import io.legado.app.utils.checkWrite
import io.legado.app.utils.externalFiles
import io.legado.app.utils.getJsonArray
import io.legado.app.utils.isContentScheme
import io.legado.app.utils.launch
import io.legado.app.utils.toastOnUi
import splitties.init.appCtx
import java.io.File

class HandleFileActivity :
    VMBaseActivity<ViewBinding, HandleFileViewModel>(),
    FilePickerDialog.CallBack {

    // 原 activity_translucence.xml 已退役（CE-b）：composeShell 合成壳 + 共享装配（5 个宿主共用）
    override val binding: ViewBinding by lazy { composeShell(this) }
    private val shell by lazy { TransparentShellViews(this) }
    override val viewModel by viewModels<HandleFileViewModel>()
    private var mode = 0

    private val selectDocTree =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            uri?.let {
                if (uri.isContentScheme()) {
                    val modeFlags =
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    contentResolver.takePersistableUriPermission(uri, modeFlags)
                }
                onResult(Intent().setData(uri))
            } ?: finish()
        }

    private val selectDoc = registerForActivityResult(ActivityResultContracts.OpenDocument()) {
        it?.let {
            if (it.isContentScheme()) {
                val modeFlags =
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                try {
                    contentResolver.takePersistableUriPermission(it, modeFlags)
                } catch (_: SecurityException) {
                    // 部分设备/ContentProvider不支持持久化URI权限，忽略即可
                }
            }
            onResult(Intent().setData(it))
        } ?: finish()
    }

    private val selectImage = registerForActivityResult(SelectImageContract()) {
        it.uri?.let { uri ->
            onResult(Intent().setData(uri))
        } ?: finish()
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        shell.install(binding.root)
        mode = intent.getIntExtra("mode", 0)
        viewModel.errorLiveData.observe(this) {
            toastOnUi(it)
            finish()
        }
        val allowExtensions = intent.getStringArrayExtra("allowExtensions")
        val selectList: ArrayList<SelectItem<Int>> = when (mode) {
            HandleFileContract.DIR_SYS -> getDirActions(true)
            HandleFileContract.DIR -> getDirActions()
            HandleFileContract.FILE -> getFileActions()
            HandleFileContract.EXPORT -> arrayListOf(
                SelectItem(getString(R.string.upload_url), 111)
            ).apply {
                addAll(getDirActions())
            }

            HandleFileContract.IMAGE -> getImageActions()
            else -> arrayListOf()
        }
        intent.getJsonArray<SelectItem<Int>>("otherActions")?.let {
            selectList.addAll(it)
        }
        val title = intent.getStringExtra("title") ?: let {
            when (mode) {
                HandleFileContract.EXPORT -> return@let getString(R.string.export)
                HandleFileContract.DIR -> return@let getString(R.string.select_folder)
                HandleFileContract.IMAGE -> return@let getString(R.string.select_image)
                else -> return@let getString(R.string.select_file)
            }
        }
        // F178 动作列表分组说明：showComposeActionListDialog 以并行 descriptions 渲染每项右缘 hint，
        // 让用户在选择前就清楚每个入口的来源（系统/应用内/手动输入）与能力（http 直链）。
        // 分组头（云端/系统/应用内/手动输入四类）需组件层支持 group header，超出本页范围 → 用 hint 收敛到"每项右缘说明"。
        val hints = selectList.map { hintOf(it.value) }
        showComposeActionListDialog(
            title = title,
            labels = selectList.map { it.title },
            descriptions = hints
        ) { index ->
            when (selectList[index].value) {
                HandleFileContract.DIR -> kotlin.runCatching {
                    selectDocTree.launch()
                }.onFailure {
                    AppLog.put(getString(R.string.open_sys_dir_picker_error), it, true)
                    checkPermissions {
                        FilePickerDialog.show(
                            supportFragmentManager,
                            mode = HandleFileContract.DIR
                        )
                    }
                }

                HandleFileContract.FILE -> kotlin.runCatching {
                    selectDoc.launch(typesOfExtensions(allowExtensions))
                }.onFailure {
                    AppLog.put(getString(R.string.open_sys_dir_picker_error), it, true)
                    checkPermissions {
                        FilePickerDialog.show(
                            supportFragmentManager,
                            mode = HandleFileContract.FILE,
                            allowExtensions = allowExtensions
                        )
                    }
                }

                HandleFileContract.IMAGE -> {
                    selectImage.launch()
                }

                10 -> checkPermissions {
                    @Suppress("DEPRECATION")
                    lifecycleScope.launchWhenResumed {
                        FilePickerDialog.show(
                            supportFragmentManager,
                            mode = HandleFileContract.DIR
                        )
                    }
                }

                11 -> checkPermissions {
                    @Suppress("DEPRECATION")
                    lifecycleScope.launchWhenResumed {
                        FilePickerDialog.show(
                            supportFragmentManager,
                            mode = HandleFileContract.FILE,
                            allowExtensions = allowExtensions
                        )
                    }
                }

                111 -> getFileData()?.let {
                    viewModel.upload(it.first, it.second, it.third) { url ->
                        val uri = url.toUri()
                        setResult(RESULT_OK, Intent().setData(uri))
                        finish()
                    }
                }

                112 -> checkPermissions { // 手动输入目录路径
                    showInputDirectoryDialog()
                }

                113 -> checkPermissions { // 手动输入图片链接
                    showInputImgSrcDialog()
                }

                else -> {
                    val path = selectList[index].title
                    val uri = if (path.isContentScheme()) {
                        path.toUri()
                    } else {
                        Uri.fromFile(File(path))
                    }
                    onResult(Intent().setData(uri))
                }
            }
        }
    }

    private fun showInputDirectoryDialog() {
        // 弹框托管（ui-theme-governance-polish tasks 9.4 孤岛家族迁移）
        // 行为差异：空值校验走 validateInput，失败时弹框不关闭；原 View alert 点确定后必然关闭
        showComposeTextInputDialog(
            title = getString(R.string.manual_input),
            hint = getString(R.string.enter_directory_path),
            validateInput = { inputPath ->
                if (inputPath.isBlank()) {
                    toastOnUi(getString(R.string.empty_directory_input))
                    false
                } else {
                    true
                }
            },
            onPositive = { inputPath ->
                val file = File(inputPath)
                if (file.exists() &&
                    file.isDirectory &&
                    isExternalStorage(file) &&
                    file.checkWrite()
                ) {
                    onResult(Intent().setData(Uri.fromFile(file)))
                } else {
                    toastOnUi(getString(R.string.invalid_directory))
                }
            },
            onDismissed = {
                finish()
            }
        )
    }

    private fun showInputImgSrcDialog() {
        // 弹框托管（ui-theme-governance-polish tasks 9.4 孤岛家族迁移）
        // 行为差异：空值校验走 validateInput，失败时弹框不关闭；原 View alert 点确定后必然关闭
        showComposeTextInputDialog(
            title = getString(R.string.manual_input),
            hint = getString(R.string.enter_img_src_path),
            validateInput = { inputPath ->
                if (inputPath.isBlank()) {
                    toastOnUi(getString(R.string.empty_img_src_input))
                    false
                } else {
                    true
                }
            },
            onPositive = { inputPath ->
                if (inputPath.startsWith("http", true)) {
                    onResult(Intent().setData(inputPath.toUri()))
                } else {
                    val file = File(inputPath)
                    if (file.exists() &&
                        file.isFile &&
                        isExternalStorage(file) &&
                        file.canRead()
                    ) {
                        onResult(Intent().setData(Uri.fromFile(file)))
                    } else {
                        toastOnUi(getString(R.string.invalid_file_path))
                    }
                }
            },
            onDismissed = {
                finish()
            }
        )
    }

    private fun isExternalStorage(path: File): Boolean {
        if (path.canonicalPath.startsWith(appCtx.externalFiles.parent!!)) {
            return false
        }
        try {
            if (Environment.isExternalStorageEmulated(path)) {
                return true
            }
        } catch (_: IllegalArgumentException) {
            // 部分 ROM 上 isExternalStorageEmulated 抛异常，忽略即可
        }
        try {
            if (Environment.isExternalStorageRemovable(path)) {
                return true
            }
        } catch (_: IllegalArgumentException) {
            // 部分 ROM 上 isExternalStorageRemovable 抛异常，忽略即可
        }
        return false
    }

    private fun getFileData(): Triple<String, Any, String>? {
        val fileName = intent.getStringExtra("fileName")
        val file = intent.getStringExtra("fileKey")?.let {
            IntentData.get<Any>(it)
        }
        val contentType = intent.getStringExtra("contentType")
        if (fileName != null && file != null && contentType != null) {
            return Triple(fileName, file, contentType)
        }
        return null
    }

    private fun getDirActions(onlySys: Boolean = false): ArrayList<SelectItem<Int>> {
        return if (onlySys) {
            arrayListOf(
                SelectItem(getString(R.string.sys_folder_picker), HandleFileContract.DIR),
                SelectItem(getString(R.string.manual_input), 112) // 添加手动输入选项
            )
        } else {
            arrayListOf(
                SelectItem(getString(R.string.sys_folder_picker), HandleFileContract.DIR),
                SelectItem(getString(R.string.app_folder_picker), 10),
                SelectItem(getString(R.string.manual_input), 112) // 添加手动输入选项
            )
        }
    }

    private fun getFileActions(): ArrayList<SelectItem<Int>> {
        return arrayListOf(
            SelectItem(getString(R.string.sys_file_picker), HandleFileContract.FILE),
            SelectItem(getString(R.string.app_file_picker), 11)
        )
    }

    /**
     * F178：动作项右缘能力说明。未知 value（`otherActions` 由调用方注入）返回空串，
     * 由 [LegadoMiuixChoiceRow] 的 `isNotBlank` 守卫跳过渲染，不产生空白行。
     */
    private fun hintOf(value: Int): String = when (value) {
        HandleFileContract.DIR, HandleFileContract.FILE -> getString(R.string.handle_file_hint_sys_picker)
        HandleFileContract.IMAGE -> getString(R.string.handle_file_hint_sys_image)
        10, 11 -> getString(R.string.handle_file_hint_app_picker)
        111 -> getString(R.string.handle_file_hint_upload)
        112, 113 -> getString(R.string.handle_file_hint_manual_input)
        else -> ""
    }

    private fun getImageActions(): ArrayList<SelectItem<Int>> {
        return arrayListOf(
            SelectItem(getString(R.string.sys_image_picker), HandleFileContract.IMAGE)
        ).apply {
            addAll(getFileActions())
            add(SelectItem(getString(R.string.manual_input_img_src), 113)) //手动输入图片链接
        }
    }

    private fun checkPermissions(success: (() -> Unit)? = null) {
        PermissionsCompat.Builder()
            .addPermissions(*Permissions.Group.STORAGE)
            .rationale(R.string.tip_perm_request_storage)
            .onGranted {
                success?.invoke()
            }
            .onDenied {
                finish()
            }
            .onError {
                finish()
            }
            .request()
    }

    private fun typesOfExtensions(allowExtensions: Array<String>?): Array<String> {
        val types = hashSetOf<String>()
        if (allowExtensions.isNullOrEmpty()) {
            types.add("*/*")
        } else {
            allowExtensions.forEach {
                when (it) {
                    "*" -> types.add("*/*")
                    "txt", "xml" -> types.add("text/*")
                    else -> {
                        val mime = MimeTypeMap.getSingleton()
                            .getMimeTypeFromExtension(it)
                            ?: "application/octet-stream"
                        types.add(mime)
                    }
                }
            }
        }
        return types.toTypedArray()
    }

    override fun onResult(data: Intent) {
        val uri = data.data
        uri ?: let {
            finish()
            return
        }
        if (mode == HandleFileContract.EXPORT) {
            getFileData()?.let { fileData ->
                viewModel.saveToLocal(uri, fileData.first, fileData.second) { savedUri ->
                    setResult(RESULT_OK, Intent().setData(savedUri))
                    finish()
                }
            }
        } else {
            data.putExtra("value", intent.getStringExtra("value"))
            setResult(RESULT_OK, data)
            finish()
        }
    }
}