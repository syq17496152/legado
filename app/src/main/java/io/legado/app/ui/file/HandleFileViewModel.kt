package io.legado.app.ui.file

import android.app.Application
import android.media.MediaScannerConnection
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.MutableLiveData
import io.legado.app.base.BaseViewModel
import io.legado.app.constant.AppLog
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.DirectLinkUpload
import io.legado.app.utils.*

import java.io.File

class HandleFileViewModel(application: Application) : BaseViewModel(application) {

    val errorLiveData = MutableLiveData<String>()

    fun upload(
        fileName: String,
        file: Any,
        contentType: String,
        success: (url: String) -> Unit
    ) {
        execute {
            DirectLinkUpload.upLoad(fileName, file, contentType)
        }.onSuccess {
            success.invoke(it)
        }.onError {
            AppLog.put("上传文件失败\n${it.localizedMessage}", it)
            it.printOnDebug()
            errorLiveData.postValue(it.localizedMessage)
        }
    }

    fun saveToLocal(uri: Uri, fileName: String, data: Any, success: (uri: Uri) -> Unit) {
        execute {
            val bytes = when (data) {
                is File -> data.readBytes()
                is ByteArray -> data
                is String -> data.toByteArray()
                else -> GSON.toJson(data).toByteArray()
            }
            return@execute if (uri.isContentScheme()) {
                val doc = DocumentFile.fromTreeUri(context, uri)!!
                doc.findFile(fileName)?.delete()
                val newDoc = doc.createFile("", fileName)
                newDoc!!.writeBytes(context, bytes)
                // SAF 写入公共目录后，系统文件选择器依赖 MediaStore 索引，需主动刷新；
                // 通过 getTreePath 解析目录真实路径后精确扫描该文件（非 primary 存储返回 null 时跳过）
                RealPathUtil.getTreePath(uri)?.let { dirPath ->
                    MediaScannerConnection.scanFile(
                        context, arrayOf("$dirPath/$fileName"), null, null
                    )
                }
                newDoc.uri
            } else {
                val file = File(uri.path ?: uri.toString())
                val newFile = FileUtils.createFileIfNotExist(file, fileName)
                newFile.writeBytes(bytes)
                // 检查导出文件是否真的写入成功（Android 11+ 公共目录写失败会静默，需显式校验）
                if (!newFile.exists() || newFile.length() == 0L) {
                    throw NoStackTraceException("文件写入失败，请检查目录权限\n路径: ${newFile.absolutePath}")
                }
                // 通知 MediaStore 扫描，确保导出文件在系统文件管理器/文件选择器中立即可见
                MediaScannerConnection.scanFile(context, arrayOf(newFile.absolutePath), null, null)
                Uri.fromFile(newFile)
            }
        }.onError {
            it.printOnDebug()
            errorLiveData.postValue(it.localizedMessage)
        }.onSuccess {
            success.invoke(it)
        }
    }

}