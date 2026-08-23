package io.legado.app.ui.file

import android.app.Activity.RESULT_OK
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.result.contract.ActivityResultContract
import io.legado.app.help.IntentData
import io.legado.app.lib.dialogs.SelectItem
import io.legado.app.utils.RealPathUtil
import io.legado.app.utils.externalFiles
import io.legado.app.utils.isContentScheme
import io.legado.app.utils.putJson
import io.legado.app.utils.toastOnUi
import splitties.init.appCtx

@Suppress("unused")
class HandleFileContract :
    ActivityResultContract<(HandleFileContract.HandleFileParam.() -> Unit)?, HandleFileContract.Result>() {

    private var requestCode: Int = 0

    override fun createIntent(context: Context, input: (HandleFileParam.() -> Unit)?): Intent {
        val intent = Intent(context, HandleFileActivity::class.java)
        val handleFileParam = HandleFileParam()
        input?.let {
            handleFileParam.apply(input)
        }
        if (handleFileParam.mode == IMAGE) {
            handleFileParam.allowExtensions = arrayOf("jpg", "png", "bmp", "webp")
        }
        handleFileParam.let {
            requestCode = it.requestCode
            intent.putExtra("mode", it.mode)
            intent.putExtra("title", it.title)
            intent.putExtra("allowExtensions", it.allowExtensions)
            intent.putJson("otherActions", it.otherActions)
            it.fileData?.let { fileData ->
                intent.putExtra("fileName", fileData.name)
                intent.putExtra("fileKey", IntentData.put(fileData.data))
                intent.putExtra("contentType", fileData.type)
            }
            intent.putExtra("value", it.value)
        }
        return intent
    }

    override fun parseResult(resultCode: Int, intent: Intent?): Result {
        val uri = if (resultCode != RESULT_OK || intent?.data == null) {
            null
        } else {
            val data = intent.data!!
            // 统一解析真实路径：content tree URI 用 getTreePath，file:// 直接取 path，
            // 两者都判断是否落在应用私有外部目录（Android/data 下，对用户文件系统不可见且无实际意义）
            val realPath = if (data.isContentScheme()) {
                RealPathUtil.getTreePath(data)
            } else {
                data.path
            }
            if (realPath != null && realPath.startsWith(appCtx.externalFiles.parent!!)) {
                // 明确提示而非静默失败，引导用户选择公共目录
                appCtx.toastOnUi("不能选择应用私有目录，请选择公共目录（如 Download/Documents）")
                null
            } else {
                data
            }
        }
        return Result(uri, requestCode, intent?.getStringExtra("value"))
    }

    companion object {
        const val DIR = 0
        const val FILE = 1
        const val DIR_SYS = 2
        const val EXPORT = 3
        const val IMAGE = 4
    }

    @Suppress("ArrayInDataClass")
    data class HandleFileParam(
        var mode: Int = DIR,
        var title: String? = null,
        var allowExtensions: Array<String> = arrayOf(),
        var otherActions: ArrayList<SelectItem<Int>>? = null,
        var fileData: FileData? = null,
        var requestCode: Int = 0,
        var value: String? = null,
        var showUploadUrl: Boolean = true
    )

    data class Result(
        val uri: Uri?,
        val requestCode: Int,
        val value: String?
    )

    data class FileData(
        val name: String,
        val data: Any,
        val type: String
    )

}