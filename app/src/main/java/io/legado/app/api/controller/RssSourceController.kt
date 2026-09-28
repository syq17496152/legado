package io.legado.app.api.controller


import io.legado.app.api.ReturnData
import io.legado.app.data.entities.RssSource
import io.legado.app.service.kernel.RssSourceKernel
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonArray
import io.legado.app.utils.fromJsonObject
import kotlinx.coroutines.runBlocking

/**
 * 订阅源域 Web 门面（一期 · 2.3.1）：解析 → 调 [RssSourceKernel] → 装信封。
 *
 * 保留非 `suspend` 旧签名与属性形态，理由同 [BookController] 类注释。
 */
object RssSourceController {

    val sources: ReturnData
        get() {
            val source = runBlocking { RssSourceKernel.sources() }
            return if (source.isEmpty()) {
                ReturnData().setErrorMsg("源列表为空")
            } else {
                ReturnData().setData(source)
            }
        }

    fun saveSource(postData: String?): ReturnData {
        postData ?: return ReturnData().setErrorMsg("数据不能为空")
        val source = GSON.fromJsonObject<RssSource>(postData).getOrElse {
            return ReturnData().setErrorMsg("转换源失败${it.localizedMessage}")
        }
        if (!RssSourceKernel.hasValidIdentity(source)) {
            return ReturnData().setErrorMsg("源名称和URL不能为空")
        }
        runBlocking { RssSourceKernel.saveSource(source) }
        return ReturnData().setData("")
    }

    fun saveSources(postData: String?): ReturnData {
        postData ?: return ReturnData().setErrorMsg("数据不能为空")
        val source = GSON.fromJsonArray<RssSource>(postData).getOrNull()
        if (source.isNullOrEmpty()) {
            return ReturnData().setErrorMsg("转换源失败")
        }
        return ReturnData().setData(runBlocking { RssSourceKernel.saveSources(source) })
    }

    fun getSource(parameters: Map<String, List<String>>): ReturnData {
        val url = parameters["url"]?.firstOrNull()
        if (url.isNullOrEmpty()) {
            return ReturnData().setErrorMsg("参数url不能为空，请指定书源地址")
        }
        val source = runBlocking { RssSourceKernel.source(url) }
            ?: return ReturnData().setErrorMsg("未找到源，请检查源地址")
        return ReturnData().setData(source)
    }

    fun deleteSources(postData: String?): ReturnData {
        postData ?: return ReturnData().setErrorMsg("没有传递数据")
        GSON.fromJsonArray<RssSource>(postData).onFailure {
            return ReturnData().setErrorMsg("格式不对")
        }.onSuccess {
            runBlocking { RssSourceKernel.deleteSources(it) }
        }
        return ReturnData().setData("已执行"/*okSources*/)
    }
}
