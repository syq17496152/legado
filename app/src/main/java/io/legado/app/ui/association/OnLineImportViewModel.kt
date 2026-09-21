package io.legado.app.ui.association

import android.app.Application
import androidx.core.net.toUri
import io.legado.app.R
import io.legado.app.constant.AppConst
import io.legado.app.help.config.ReadBookConfig
import io.legado.app.help.http.decompressed
import io.legado.app.help.http.newCallResponseBody
import io.legado.app.help.http.okHttpClient
import io.legado.app.help.http.text
import io.legado.app.utils.FileUtils
import io.legado.app.utils.externalCache
import okhttp3.MediaType.Companion.toMediaType
import splitties.init.appCtx

class OnLineImportViewModel(app: Application) : BaseAssociationViewModel(app) {

    // C6 审计修复（2026-09-22）：原 `getText(url, success)` 全仓**零调用点**（`viewModel.getText(` 0 命中，
    // 姊妹方法 getBytes 有真实调用形成对照）⇒ 删除死件，避免误导后续维护。

    fun getBytes(url: String, success: (bytes: ByteArray) -> Unit) {
        execute {
            okHttpClient.newCallResponseBody {
                if (url.endsWith("#requestWithoutUA")) {
                    url(url.substringBeforeLast("#requestWithoutUA"))
                    header(AppConst.UA_NAME, "null")
                } else {
                    url(url)
                }
            }.bytes()
        }.onSuccess {
            success.invoke(it)
        }.onError {
            errorLive.postValue(
                it.localizedMessage ?: context.getString(R.string.unknown_error)
            )
        }
    }

    /**
     * 导入阅读排版配置。
     *
     * 既有缺陷修复（2026-09-21 复核）：
     * ①原实现把「同名覆盖 / 追加」写在 `forEachIndexed` 首轮分支里并在首轮就 `return@execute`，
     *   于是**空列表时一条都不会追加**（循环体不执行）却照样回调成功；列表非空时也**只比对第 0 条**，
     *   同名项在更后面时会生成重复配置；
     * ②原实现从不调用 [ReadBookConfig.save]（落盘由调用方显式触发）⇒ 导入的排版**重启即丢**。
     * 现改为「按名查找：命中覆盖、未命中追加」+ 立即落盘，并回调真实配置名（导入的是哪个一目了然）。
     */
    fun importReadConfig(
        bytes: ByteArray,
        onSuccess: (configName: String) -> Unit,
        onError: (message: String) -> Unit
    ) {
        execute {
            val config = ReadBookConfig.import(bytes)
            val existIndex = ReadBookConfig.configList.indexOfFirst { it.name == config.name }
            if (existIndex >= 0) {
                ReadBookConfig.configList[existIndex] = config
            } else {
                ReadBookConfig.configList.add(config)
            }
            ReadBookConfig.save()
            config.name
        }.onSuccess {
            onSuccess.invoke(it)
        }.onError {
            onError.invoke(it.localizedMessage ?: context.getString(R.string.unknown_error))
        }
    }

    fun determineType(
        url: String,
        onReadConfigSuccess: (configName: String) -> Unit,
        onError: (message: String) -> Unit
    ) {
        execute {
            val rs = okHttpClient.newCallResponseBody {
                if (url.endsWith("#requestWithoutUA")) {
                    url(url.substringBeforeLast("#requestWithoutUA"))
                    header(AppConst.UA_NAME, "null")
                } else {
                    url(url)
                }
            }
            when (rs.contentType()) {
                "application/zip".toMediaType(),
                "application/octet-stream".toMediaType() -> {
                    importReadConfig(rs.bytes(), onReadConfigSuccess, onError)
                }
                else -> {
                    val inputStream = rs.byteStream()
                    val file = FileUtils.createFileIfNotExist(
                        appCtx.externalCache,
                        "download",
                        "scheme_import_cache.json"
                    )
                    file.outputStream().use { out ->
                        inputStream.use {
                            it.copyTo(out)
                        }
                    }
                    importJson(file.toUri())
                }
            }
        }
    }

}