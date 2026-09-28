package io.legado.app.api.controller


import io.legado.app.api.ReturnData
import io.legado.app.constant.AppLog
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
        // 校验链（2.3.1 / REQ-1-311）：与书源同口径
        RssSourceKernel.skipReason(source)?.let {
            return ReturnData().setErrorMsg(it)
        }
        runBlocking { RssSourceKernel.saveSource(source) }
        return ReturnData().setData("")
    }

    fun saveSources(postData: String?): ReturnData {
        postData ?: return ReturnData().setErrorMsg("数据不能为空")
        val validated = try {
            runBlocking { RssSourceKernel.parseAndValidate(postData) }
        } catch (e: Exception) {
            return ReturnData().setErrorMsg("转换源失败")
        }
        if (validated.accepted.isEmpty() && validated.skipped.isEmpty()) {
            return ReturnData().setErrorMsg("转换源失败")
        }
        if (validated.skipped.isNotEmpty()) {
            AppLog.put(
                "Web 批量推送订阅源：结构残缺跳过 ${validated.skipped.size} 条（首条原因：${validated.skipped.first().reason}）",
                null,
                toast = false
            )
        }
        // `data` 保持"成功数组"形状（老页 ToolBar.vue 以 总数-成功数 计失败数）
        return ReturnData().setData(runBlocking { RssSourceKernel.saveSources(validated.accepted) })
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
